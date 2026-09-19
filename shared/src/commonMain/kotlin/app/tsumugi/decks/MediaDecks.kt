package app.tsumugi.decks

import app.tsumugi.coverage.CoverageMath
import app.tsumugi.coverage.CoverageService
import app.tsumugi.coverage.DifficultyScorer
import app.tsumugi.coverage.KnowledgeSnapshot
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.ProfileWord
import app.tsumugi.coverage.SubtitleText
import app.tsumugi.coverage.TextCoverage
import app.tsumugi.coverage.TextProfile
import app.tsumugi.coverage.TextProfiler
import app.tsumugi.coverage.WordState
import app.tsumugi.db.Media_deck
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.reader.EpubImporter
import app.tsumugi.reader.ReaderDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Where a media deck's text came from. */
enum class DeckSourceKind { DOCUMENT, EPUB, SUBTITLES, TRANSCRIPT, TEXT }

/** Per-media statistics (BRIEF_V2 §6.1), stored with the deck as JSON so every device shows the same numbers. */
@Serializable
data class MediaDeckStats(
    val uniqueWords: Int,
    val wordTokens: Int,
    /** Words (most frequent first) needed to understand 80/90/95/98% of the word occurrences. */
    val words80: Int,
    val words90: Int,
    val words95: Int,
    val words98: Int,
    /** 5..1, 0 = above N1. */
    val jlpt: Int,
    val ilr: String,
    val textScore: Int,
    val kanjiCount: Int,
    val grammarCount: Int,
    val sampled: Boolean = false,
) {
    val levelLabel: String get() = "${if (jlpt == 0) "above N1" else "N$jlpt"} · ILR $ilr"
}

/** A kanji of the media with its occurrences. */
@Serializable
data class DeckKanji(val kanji: String, val count: Int)

/** One word of a deck with the learner's current state. */
data class DeckWord(
    val entryId: Long,
    val ord: Int,
    val text: String,
    val reading: String?,
    /** Occurrences in the media (frequency decks: Tatoeba sentences). */
    val count: Int,
    val score: Double,
    val context: String?,
    val state: WordState,
)

/** A deck built from a text but not saved yet ("One tap creates a deck"). */
data class MediaVocabulary(
    val title: String,
    val kind: DeckSourceKind,
    val sourceRef: String?,
    val words: List<DeckWord>,
    val kanji: List<DeckKanji>,
    val grammarPointIds: List<String>,
    val stats: MediaDeckStats,
    /** The learner's coverage of this media right now. */
    val coverage: TextCoverage,
)

data class MediaDeckSummary(
    val id: String,
    val title: String,
    val kind: DeckSourceKind,
    val sourceRef: String?,
    val wordCount: Int,
    val stats: MediaDeckStats,
    val createdAt: Long,
    /** The learner's coverage of the media, from the deck's word counts and kanji. */
    val coverage: TextCoverage,
    /** Share of the word occurrences in all of the learner's profiled media that this deck's words cover. */
    val libraryCoverage: Double,
)

data class MediaDeckDetail(
    val summary: MediaDeckSummary,
    val words: List<DeckWord>,
    val kanji: List<DeckKanji>,
    val grammarPointIds: List<String>,
)

/** The prebuilt frequency decks (BRIEF_V2 §6.1), prefixes of the pack's frequency list (D-152). */
enum class CoreDeck(val id: String, val size: Int, val title: String) {
    CORE_2K("core2k", 2_000, "Core 2k"),
    CORE_6K("core6k", 6_000, "Core 6k"),
    CORE_10K("core10k", 10_000, "Core 10k");

    companion object {
        fun byId(id: String): CoreDeck? = entries.firstOrNull { it.id == id }
    }
}

data class FrequencyDeckSummary(
    val deck: CoreDeck,
    /** Words actually available in the installed pack (≤ [CoreDeck.size]). */
    val size: Int,
    val known: Int,
    val learning: Int,
    /** Share of the word occurrences in the learner's own media covered by this deck (0 without profiled media). */
    val libraryCoverage: Double,
)

/**
 * Media decks (BRIEF_V2 §6.1, jpdb.io): any text → a per-media vocabulary list in study order, with its kanji,
 * grammar points and statistics; saved decks sync (`media_deck`, `media_deck_word`, D-150). Also the prebuilt Core
 * frequency decks. Lessons from a deck: [DeckLessons].
 */
class MediaDeckService(
    private val db: TsumugiDatabase,
    private val knowledge: LearnerKnowledge,
    private val profiler: TextProfiler,
    private val coverage: CoverageService,
    private val dictionary: suspend () -> DictionaryRepository?,
    private val document: suspend (String) -> ReaderDocument?,
    private val readFile: suspend (String) -> ByteArray,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.decksQueries

    // --- Building -----------------------------------------------------------------------------------------------

    /** The vocabulary of a reader document (not saved); null without the dictionary pack or the document. */
    @Throws(Exception::class)
    suspend fun vocabularyForDocument(documentId: String, onProgress: (Double) -> Unit = {}): MediaVocabulary? {
        val doc = document(documentId) ?: return null
        val fp = TextProfiler.fingerprint(doc.body)
        val key = CoverageService.docKey(documentId)
        val profile = coverage.profiles.get(key, fp)
            ?: profiler.profile(doc.body, doc.ruby, onProgress = onProgress)?.also { coverage.profiles.put(key, fp, it) }
            ?: return null
        return vocabulary(doc.title, DeckSourceKind.DOCUMENT, documentId, profile)
    }

    /** The vocabulary of pasted text or a media transcript. */
    @Throws(Exception::class)
    suspend fun vocabularyForText(title: String, text: String, kind: DeckSourceKind = DeckSourceKind.TEXT, mediaKey: String? = null, onProgress: (Double) -> Unit = {}): MediaVocabulary? {
        val profile = profiler.profile(text, onProgress = onProgress) ?: return null
        if (mediaKey != null) coverage.profiles.put(CoverageService.mediaProfileKey(mediaKey), TextProfiler.fingerprint(text), profile)
        return vocabulary(title, kind, mediaKey, profile)
    }

    /** The vocabulary of a subtitle file (SRT/VTT; a plain transcript works too), keyed by the media's hash. */
    @Throws(Exception::class)
    suspend fun vocabularyForSubtitles(title: String, subtitles: String, mediaKey: String?, onProgress: (Double) -> Unit = {}): MediaVocabulary? =
        vocabularyForText(title, SubtitleText.of(subtitles).text, DeckSourceKind.SUBTITLES, mediaKey, onProgress)

    /** The vocabulary of an EPUB file at [path]. */
    @Throws(Exception::class)
    suspend fun vocabularyForEpub(path: String, onProgress: (Double) -> Unit = {}): MediaVocabulary? {
        val bytes = readFile(path)
        val book = withContext(Dispatchers.Default) { EpubImporter.import(bytes, path.substringAfterLast('/')) }
        val profile = profiler.profile(book.body, book.ruby, onProgress = onProgress) ?: return null
        return vocabulary(book.title, DeckSourceKind.EPUB, null, profile)
    }

    /** Saves [vocabulary] as a deck (a document or media item already having a deck gets it replaced). */
    @Throws(Exception::class)
    suspend fun save(vocabulary: MediaVocabulary): MediaDeckSummary {
        val now = clock.now().toEpochMilliseconds()
        val id = withContext(Dispatchers.IO) {
            db.transactionWithResult<String> {
                val existing = vocabulary.sourceRef?.let { q.deckBySource(vocabulary.kind.name, it).executeAsOneOrNull() }
                existing?.let { q.deleteDeck(now, it.id); q.deleteDeckWords(now, it.id) }
                val id = Uuid.random().toString()
                q.insertDeck(
                    id, vocabulary.title, vocabulary.kind.name, vocabulary.sourceRef, vocabulary.words.size.toLong(),
                    vocabulary.stats.wordTokens.toLong(), JSON.encodeToString(KANJI_LIST, vocabulary.kanji),
                    JSON.encodeToString(STRINGS, vocabulary.grammarPointIds), JSON.encodeToString(MediaDeckStats.serializer(), vocabulary.stats),
                    now, now,
                )
                for (w in vocabulary.words) {
                    q.insertDeckWordIfAbsent(id, w.entryId, w.ord.toLong(), w.text, w.reading, w.count.toLong(), w.score, w.context, now)
                    q.updateDeckWord(w.ord.toLong(), w.text, w.reading, w.count.toLong(), w.score, w.context, now, id, w.entryId)
                }
                id
            }
        }
        return summaries(listOf(withContext(Dispatchers.IO) { q.deckById(id).executeAsOne() })).single()
    }

    // --- Saved decks --------------------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun decks(): List<MediaDeckSummary> = summaries(withContext(Dispatchers.IO) { q.decks().executeAsList() })

    @Throws(Exception::class)
    suspend fun deck(id: String): MediaDeckDetail? {
        val row = withContext(Dispatchers.IO) { q.deckById(id).executeAsOneOrNull() } ?: return null
        val snapshot = knowledge.snapshot()
        return MediaDeckDetail(summaries(listOf(row)).single(), words(id, snapshot), kanjiOf(row), grammarOf(row))
    }

    /** The deck's words in study order with the learner's state. */
    @Throws(Exception::class)
    suspend fun deckWords(id: String): List<DeckWord> = words(id, knowledge.snapshot())

    @Throws(Exception::class)
    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        q.renameDeck(title.trim().ifEmpty { "Deck" }, clock.now().toEpochMilliseconds(), id)
    }

    @Throws(Exception::class)
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val now = clock.now().toEpochMilliseconds()
        db.transaction { q.deleteDeck(now, id); q.deleteDeckWords(now, id) }
    }

    // --- Frequency decks ----------------------------------------------------------------------------------------

    /** Core 2k/6k/10k with the learner's progress and their coverage of the learner's media; empty without the list. */
    @Throws(Exception::class)
    suspend fun frequencyDecks(): List<FrequencyDeckSummary> {
        val dict = dictionary() ?: return emptyList()
        val all = dict.frequencyWordsUpTo(CoreDeck.CORE_10K.size)
        if (all.isEmpty()) return emptyList()
        val snapshot = knowledge.snapshot()
        val media = coverage.libraryProfiles()
        return CoreDeck.entries.map { deck ->
            val words = all.filter { it.ord <= deck.size }
            FrequencyDeckSummary(
                deck, words.size,
                known = words.count { snapshot.word(it.entryId) == WordState.KNOWN },
                learning = words.count { snapshot.word(it.entryId) == WordState.LEARNING },
                libraryCoverage = CoverageMath.deckCoverageOf(words.map { it.entryId }.toSet(), media),
            )
        }
    }

    /** Words [offset, offset + limit) of a Core deck with the learner's state. */
    @Throws(Exception::class)
    suspend fun frequencyDeckWords(deckId: String, offset: Int = 0, limit: Int = 100): List<DeckWord> {
        val deck = CoreDeck.byId(deckId) ?: return emptyList()
        val dict = dictionary() ?: return emptyList()
        val page = dict.frequencyWords(offset, limit.coerceAtMost(deck.size - offset).coerceAtLeast(0))
        val snapshot = knowledge.snapshot()
        val summaries = dict.summaries(page.map { it.entryId }).associateBy { it.id }
        return page.mapNotNull { f ->
            val s = summaries[f.entryId] ?: return@mapNotNull null
            DeckWord(f.entryId, f.ord, s.headword, s.reading, f.count, f.count.toDouble(), null, snapshot.word(f.entryId, s.headword))
        }
    }

    // --- Internals ----------------------------------------------------------------------------------------------

    private suspend fun vocabulary(title: String, kind: DeckSourceKind, sourceRef: String?, profile: TextProfile): MediaVocabulary {
        val snapshot = knowledge.snapshot()
        val words = order(profile).mapIndexed { i, (w, score) ->
            DeckWord(w.entryId, i, w.lemma, w.reading, w.count, score, w.context, snapshot.word(w.entryId, w.lemma))
        }
        val kanji = profile.kanjiByFrequency.map { DeckKanji(it, profile.kanji[it] ?: 0) }
        val grammar = profile.grammar.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
        val thresholds = CoverageMath.thresholds(profile)
        val difficulty = DifficultyScorer.score(profile)
        val stats = MediaDeckStats(
            uniqueWords = profile.uniqueWords, wordTokens = profile.wordTokens,
            words80 = thresholds.words80, words90 = thresholds.words90, words95 = thresholds.words95, words98 = thresholds.words98,
            jlpt = difficulty.jlpt, ilr = difficulty.ilr, textScore = difficulty.textScore,
            kanjiCount = kanji.size, grammarCount = grammar.size, sampled = profile.sampled,
        )
        return MediaVocabulary(title.trim().ifEmpty { "Deck" }, kind, sourceRef, words, kanji, grammar, stats, CoverageMath.coverage(profile, snapshot))
    }

    private suspend fun summaries(rows: List<Media_deck>): List<MediaDeckSummary> {
        if (rows.isEmpty()) return emptyList()
        val snapshot = knowledge.snapshot()
        val media = coverage.libraryProfiles()
        return rows.map { row ->
            val profile = deckProfile(row)
            MediaDeckSummary(
                id = row.id, title = row.title, kind = runCatching { DeckSourceKind.valueOf(row.source_kind) }.getOrDefault(DeckSourceKind.TEXT),
                sourceRef = row.source_ref, wordCount = row.word_count.toInt(),
                stats = runCatching { JSON.decodeFromString(MediaDeckStats.serializer(), row.stats) }.getOrElse { EMPTY_STATS },
                createdAt = row.created_at,
                coverage = CoverageMath.coverage(profile, snapshot),
                libraryCoverage = CoverageMath.deckCoverageOf(profile.words.map { it.entryId }.toSet(), media),
            )
        }
    }

    /** A profile rebuilt from the deck's stored words and kanji, so coverage works on devices that never saw the text. */
    private suspend fun deckProfile(row: Media_deck): TextProfile {
        val words = withContext(Dispatchers.IO) { q.deckWords(row.id).executeAsList() }
            .map { ProfileWord(it.entry_id, it.text, it.reading, it.count.toInt(), context = it.context) }
        return TextProfile(
            words = words, kanji = kanjiOf(row).associate { it.kanji to it.count }, grammar = emptyMap(), sentences = 0,
            chars = 0, japaneseChars = 0, kanjiChars = 0, sentenceJapaneseChars = 0, abstractHits = 0, approxTokens = 0,
        )
    }

    private suspend fun words(deckId: String, snapshot: KnowledgeSnapshot): List<DeckWord> =
        withContext(Dispatchers.IO) { q.deckWords(deckId).executeAsList() }.map {
            DeckWord(it.entry_id, it.ord.toInt(), it.text, it.reading, it.count.toInt(), it.score, it.context, snapshot.word(it.entry_id, it.text))
        }

    private fun kanjiOf(row: Media_deck): List<DeckKanji> = runCatching { JSON.decodeFromString(KANJI_LIST, row.kanji) }.getOrDefault(emptyList())

    private fun grammarOf(row: Media_deck): List<String> = runCatching { JSON.decodeFromString(STRINGS, row.grammar) }.getOrDefault(emptyList())

    companion object {
        /** Content words kept per deck (a light novel has ~8k distinct words; the long tail is names and one-offs). */
        const val MAX_DECK_WORDS = 5_000

        private val JSON = Json { ignoreUnknownKeys = true }
        private val KANJI_LIST = kotlinx.serialization.builtins.ListSerializer(DeckKanji.serializer())
        private val STRINGS = kotlinx.serialization.builtins.ListSerializer(String.serializer())
        private val EMPTY_STATS = MediaDeckStats(0, 0, 0, 0, 0, 0, 5, "0+", 0, 0, 0)

        /**
         * Study order (D-150): score = occurrences in the media × global weight, where the global weight is
         * 1 + log10(1 + Tatoeba count) (recovered from the pack rank), halved for words JMdict doesn't mark common.
         * Function words are left out. Ties: more occurrences, then the more common word, then the entry id.
         */
        fun order(profile: TextProfile): List<Pair<ProfileWord, Double>> =
            profile.words.filterNot { it.function }
                .map { it to it.count * globalWeight(it.rank) }
                .sortedWith(compareByDescending<Pair<ProfileWord, Double>> { it.second }.thenByDescending { it.first.count }.thenBy { it.first.rank }.thenBy { it.first.entryId })
                .take(MAX_DECK_WORDS)

        fun globalWeight(rank: Long): Double {
            val tatoeba = ProfileWord.tatoebaCount(rank)
            return (1.0 + ln(1.0 + tatoeba) / ln(10.0)) * (if (ProfileWord.isCommonRank(rank)) 1.0 else 0.5)
        }
    }
}
