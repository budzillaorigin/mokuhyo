package app.tsumugi.coverage

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.media.Subtitles
import app.tsumugi.reader.ReaderDocument
import app.tsumugi.reader.ReaderDocumentSummary
import app.tsumugi.reader.ReaderSentence
import app.tsumugi.study.ImmersionDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Coverage and difficulty of one document or media item, for the overlay. */
data class DocumentCoverage(
    val coverage: TextCoverage,
    val difficulty: DifficultyScore,
    /** Content words in the text, distinct. */
    val uniqueWords: Int,
    /** True when a very long text was profiled from its start only. */
    val sampled: Boolean,
)

/** One library row with its coverage; [coverage] is null until the document has been profiled. */
data class LibraryCoverage(
    val document: ReaderDocumentSummary,
    val coverage: TextCoverage?,
    val difficulty: DifficultyScore?,
)

/**
 * Device-local store of [TextProfile]s (the `text_profile` table), with decoded profiles kept in memory until their
 * fingerprint changes. Keys: `doc:<id>`, `media:<hash>`, `text:<fingerprint>`.
 */
class TextProfileStore(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val lock = Mutex()
    private val decoded = HashMap<String, Pair<String, TextProfile>>()

    /** The stored profile for [key] when it was built from text with [fingerprint] (any fingerprint when null). */
    @Throws(Exception::class)
    suspend fun get(key: String, fingerprint: String? = null): TextProfile? = lock.withLock {
        decoded[key]?.let { (fp, p) -> if (fingerprint == null || fp == fingerprint) return@withLock p }
        val row = withContext(Dispatchers.IO) { db.decksQueries.profileFor(key).executeAsOneOrNull() } ?: return@withLock null
        if (fingerprint != null && row.fingerprint != fingerprint) return@withLock null
        TextProfile.decode(row.profile)?.also { decoded[key] = row.fingerprint to it }
    }

    @Throws(Exception::class)
    suspend fun put(key: String, fingerprint: String, profile: TextProfile) = lock.withLock {
        withContext(Dispatchers.IO) { db.decksQueries.putProfile(key, fingerprint, profile.encode(), clock.now().toEpochMilliseconds()) }
        decoded[key] = fingerprint to profile
    }

    /** Every stored profile whose key starts with [prefix] ("doc:", "media:"), decoded once. */
    @Throws(Exception::class)
    suspend fun withPrefix(prefix: String): Map<String, TextProfile> = lock.withLock {
        val rows = withContext(Dispatchers.IO) { db.decksQueries.profilesWithPrefix(prefix).executeAsList() }
        rows.mapNotNull { row ->
            val cached = decoded[row.source_key]?.takeIf { it.first == row.fingerprint }?.second
            (cached ?: TextProfile.decode(row.profile)?.also { decoded[row.source_key] = row.fingerprint to it })
                ?.let { row.source_key to it }
        }.toMap()
    }

    @Throws(Exception::class)
    suspend fun delete(key: String) = lock.withLock {
        decoded.remove(key)
        withContext(Dispatchers.IO) { db.decksQueries.deleteProfile(key) }
    }
}

/**
 * The coverage overlay, library sort, difficulty score and 1T mining (BRIEF_V2 §6.1, §6.4, §6.11) over reader
 * documents, subtitle files and plain text. Tokenizing is the expensive part and happens once per text
 * ([TextProfileStore]); the learner side is a cached [KnowledgeSnapshot], so coverage after a review is a cheap
 * recombination. Results are cached per text and knowledge version (D-153).
 */
class CoverageService(
    private val knowledge: LearnerKnowledge,
    private val profiler: TextProfiler,
    val profiles: TextProfileStore,
    private val documents: suspend () -> List<ReaderDocumentSummary>,
    private val document: suspend (String) -> ReaderDocument?,
    /** Adds a word to reviews with its sentence (CollectionService.addToReviews); returns the item id. */
    private val mine: suspend (entryId: Long, sentence: String) -> String? = { _, _ -> null },
) {
    private val lock = Mutex()
    private val results = HashMap<String, Pair<String, DocumentCoverage>>()

    // --- Overlay ------------------------------------------------------------------------------------------------

    /**
     * "You know X% of the words · Y% of the kanji · N new words to reach 95%" for a reader document, with its
     * difficulty. Tokenizes the document the first time (with [onProgress]); null without the dictionary pack.
     */
    @Throws(Exception::class)
    suspend fun documentCoverage(documentId: String, onProgress: (Double) -> Unit = {}): DocumentCoverage? {
        val profile = documentProfile(documentId, onProgress) ?: return null
        return coverageOf(docKey(documentId), profile)
    }

    /** Coverage of a subtitle file (SRT/VTT) or a media transcript, stored under [mediaKey] (the media hash). */
    @Throws(Exception::class)
    suspend fun subtitleCoverage(mediaKey: String, subtitles: String, onProgress: (Double) -> Unit = {}): DocumentCoverage? {
        val text = SubtitleText.of(subtitles).text
        val profile = storedProfile(mediaProfileKey(mediaKey), text, onProgress) ?: return null
        return coverageOf(mediaProfileKey(mediaKey), profile)
    }

    /** Coverage of any text (pasted text, a transcript, a lyric sheet). */
    @Throws(Exception::class)
    suspend fun textCoverage(text: String, onProgress: (Double) -> Unit = {}): DocumentCoverage? {
        val key = "text:${TextProfiler.fingerprint(text)}"
        val profile = storedProfile(key, text, onProgress) ?: return null
        return coverageOf(key, profile)
    }

    /** The §6.4 difficulty of any text, for the learner (known-word ratio included). */
    @Throws(Exception::class)
    suspend fun difficultyOf(text: String, onProgress: (Double) -> Unit = {}): DifficultyScore? = textCoverage(text, onProgress)?.difficulty

    /** Coverage and difficulty from an already built profile (media decks, tests). */
    @Throws(Exception::class)
    suspend fun coverageOf(key: String, profile: TextProfile): DocumentCoverage {
        val snapshot = knowledge.snapshot()
        val version = "${snapshot.version}|${profile.hashCode()}"
        lock.withLock { results[key]?.takeIf { it.first == version }?.let { return it.second } }
        val coverage = CoverageMath.coverage(profile, snapshot)
        val result = DocumentCoverage(coverage, DifficultyScorer.score(profile, coverage.knownRatio), profile.uniqueWords, profile.sampled)
        lock.withLock { results[key] = version to result }
        return result
    }

    // --- Library ------------------------------------------------------------------------------------------------

    /**
     * Reader documents with their coverage, best covered first (the "sort by coverage" library order). Documents not
     * profiled yet come last, newest first; [profileLibrary] profiles them in the background.
     */
    @Throws(Exception::class)
    suspend fun librarySortedByCoverage(): List<LibraryCoverage> {
        val docs = documents()
        val stored = profiles.withPrefix(DOC_PREFIX)
        val rows = docs.map { d ->
            val profile = stored[docKey(d.id)]
            val c = profile?.let { coverageOf(docKey(d.id), it) }
            LibraryCoverage(d, c?.coverage, c?.difficulty)
        }
        return rows.sortedWith(
            compareByDescending<LibraryCoverage> { it.coverage != null }
                .thenByDescending { it.coverage?.knownRatio ?: 0.0 }
                .thenByDescending { it.document.importedAt },
        )
    }

    /** Profiles every library document that has no profile yet; reports (done, total). Returns how many were built. */
    @Throws(Exception::class)
    suspend fun profileLibrary(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val stored = profiles.withPrefix(DOC_PREFIX).keys
        val missing = documents().filter { docKey(it.id) !in stored }
        var built = 0
        onProgress(0, missing.size)
        for ((i, d) in missing.withIndex()) {
            if (documentProfile(d.id) != null) built++
            onProgress(i + 1, missing.size)
        }
        return built
    }

    /** Profiles of the learner's own media (documents and subtitle files), for "coverage of my media" on decks. */
    @Throws(Exception::class)
    suspend fun libraryProfiles(): List<TextProfile> = profiles.withPrefix(DOC_PREFIX).values + profiles.withPrefix(MEDIA_PREFIX).values

    /** Today's immersion matching on the §6.4 score for every profiled document (D-155). */
    @Throws(Exception::class)
    suspend fun immersionDifficulty(): ImmersionDifficulty {
        val stored = profiles.withPrefix(DOC_PREFIX)
        val scores = stored.mapNotNull { (key, p) -> key.removePrefix(DOC_PREFIX) to coverageOf(key, p).difficulty }.toMap()
        return ScoredImmersionDifficulty(scores)
    }

    // --- 1T mining (§6.11) ----------------------------------------------------------------------------------------

    /** Sentences of a reader document with exactly one unknown word, best first (one per target word). */
    @Throws(Exception::class)
    suspend fun oneTargetSentences(documentId: String, limit: Int = 30, onProgress: (Double) -> Unit = {}): List<OneTargetSentence> {
        val doc = document(documentId) ?: return emptyList()
        val sentences = ArrayList<ReaderSentence>()
        val profile = profiler.profile(doc.body, doc.ruby, onSentence = { sentences += it }, onProgress = onProgress) ?: return emptyList()
        profiles.put(docKey(documentId), TextProfiler.fingerprint(doc.body), profile)
        return OneTargetFinder.find(sentences, profile, knowledge.snapshot(), limit)
    }

    /** Subtitle cues with exactly one unknown word, best first, with the cue index and times for the player. */
    @Throws(Exception::class)
    suspend fun oneTargetCues(subtitles: String, limit: Int = 30, onProgress: (Double) -> Unit = {}): List<OneTargetSentence> {
        val joined = SubtitleText.of(subtitles)
        val sentences = ArrayList<ReaderSentence>()
        val profile = profiler.profile(joined.text, onSentence = { sentences += it }, onProgress = onProgress) ?: return emptyList()
        return OneTargetFinder.find(sentences, profile, knowledge.snapshot(), limit).map { s ->
            val cue = joined.cueAt(s.start)
            if (cue == null) s else s.copy(cueIndex = cue, startMs = joined.cues[cue].startMs, endMs = joined.cues[cue].endMs)
        }
    }

    /** Mines a 1T sentence: its target word goes to reviews with the sentence as context. Returns the item id. */
    @Throws(Exception::class)
    suspend fun mineOneTarget(sentence: OneTargetSentence): String? = mine(sentence.entryId, sentence.text)

    // --- Profiles -----------------------------------------------------------------------------------------------

    private suspend fun documentProfile(documentId: String, onProgress: (Double) -> Unit = {}): TextProfile? {
        val doc = document(documentId) ?: return null
        val fp = TextProfiler.fingerprint(doc.body)
        profiles.get(docKey(documentId), fp)?.let { onProgress(1.0); return it }
        val profile = profiler.profile(doc.body, doc.ruby, onProgress = onProgress) ?: return null
        profiles.put(docKey(documentId), fp, profile)
        return profile
    }

    private suspend fun storedProfile(key: String, text: String, onProgress: (Double) -> Unit): TextProfile? {
        val fp = TextProfiler.fingerprint(text)
        profiles.get(key, fp)?.let { onProgress(1.0); return it }
        val profile = profiler.profile(text, onProgress = onProgress) ?: return null
        profiles.put(key, fp, profile)
        return profile
    }

    companion object {
        const val DOC_PREFIX = "doc:"
        const val MEDIA_PREFIX = "media:"

        fun docKey(documentId: String) = "$DOC_PREFIX$documentId"

        fun mediaProfileKey(mediaKey: String) = "$MEDIA_PREFIX$mediaKey"
    }
}

/**
 * Subtitle cues as one text for the tokenizer: each cue on its own line (a paragraph), its internal line breaks
 * joined without a space (Japanese), with the offset of every cue so sentences map back to cues and times.
 */
class SubtitleText private constructor(val text: String, val cues: List<app.tsumugi.media.Cue>, private val starts: IntArray) {
    /** Index of the cue containing text offset [offset], or null. */
    fun cueAt(offset: Int): Int? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        fun of(subtitles: String): SubtitleText {
            val cues = Subtitles.parse(subtitles)
            if (cues.isEmpty()) return SubtitleText(subtitles, emptyList(), IntArray(0)) // a plain transcript
            val sb = StringBuilder()
            val starts = IntArray(cues.size)
            for ((i, c) in cues.withIndex()) {
                starts[i] = sb.length
                sb.append(c.text.lines().joinToString("") { it.trim() })
                sb.append('\n')
            }
            return SubtitleText(sb.toString(), cues, starts)
        }
    }
}
