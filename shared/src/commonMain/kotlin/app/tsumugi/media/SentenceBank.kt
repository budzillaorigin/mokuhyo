package app.tsumugi.media

import app.tsumugi.db.Media_cue
import app.tsumugi.db.Media_index
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.Token
import app.tsumugi.jp.Kana
import app.tsumugi.platform.normalizeNfc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

enum class MediaKind { VIDEO, AUDIO }

/** Where a media item's cues came from: a loaded .srt/.vtt, or Whisper ([SubtitleGenerator]). */
enum class CueSource { FILE, GENERATED }

/** Where a sentence-search line comes from. The UI labels each group. */
enum class SentenceSource { LIBRARY, TATOEBA, IMMERSION_KIT }

/** A word of an indexed cue: offsets in the cue text, lemma (dictionary form), JMdict id when known. */
@Serializable
data class CueToken(val s: Int, val e: Int, val l: String, val id: Long? = null)

data class IndexedMedia(
    val mediaId: String,
    val title: String,
    val kind: MediaKind,
    /** The platform's handle for the file (path, content URI, bookmark), for playing and clipping a hit. */
    val locator: String?,
    val cueSource: CueSource,
    val cueCount: Int,
    val indexedAt: Long,
)

/**
 * Clip playback metadata for a library line (BRIEF_V2 §6.2): the platform extracts [startMs, endMs) on demand
 * (reusing the ClipService cut, AVAssetExportSession / Media3 Transformer) and grabs the frame at [thumbnailMs]
 * for video (AVAssetImageGenerator / MediaMetadataRetriever). [thumbnailMs] is null for audio-only media.
 */
data class ClipSpan(val startMs: Long, val endMs: Long, val thumbnailMs: Long?)

/**
 * One line in sentence search. [highlightStart]/[highlightEnd] mark the matched word in [japanese] (both -1 when
 * nothing could be located). Library lines carry the media and clip; Tatoeba lines the sentence id; Immersion Kit
 * lines remote media URLs that are played live and never stored (D-162).
 */
data class SentenceHit(
    val source: SentenceSource,
    val japanese: String,
    val english: String?,
    val highlightStart: Int,
    val highlightEnd: Int,
    val mediaId: String? = null,
    val mediaTitle: String? = null,
    val mediaKind: MediaKind? = null,
    val locator: String? = null,
    val cueIndex: Int? = null,
    val clip: ClipSpan? = null,
    val tatoebaId: Long? = null,
    val remoteId: String? = null,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
) {
    val hasHighlight: Boolean get() = highlightStart in 0 until highlightEnd && highlightEnd <= japanese.length
}

/** Index progress: [done] of [total] cues tokenized. */
data class IndexProgress(val done: Int, val total: Int)

/**
 * The sentence bank from the learner's own media (BRIEF_V2 §6.2, DECISIONS D-160, D-161). Every cue of a media
 * item with subtitles is stored with its lattice-tokenizer words and indexed by lemma ("w:食べる") and JMdict id
 * ("e:1358280") in `media_cue_token`, so "sentences with this word" finds inflected forms (食べなかった) too. A
 * query the tokenizer can't split falls back to a substring match over the cue text. Everything is device-local:
 * the media files are on this device only.
 *
 * [tokenize] is the reader's tokenizer (lattice analyzer + dictionary ids), or null when neither pack is installed;
 * cues are then indexed by text only and can be re-indexed once the packs arrive.
 */
class SentenceBank(
    private val db: TsumugiDatabase,
    private val tokenize: suspend (String) -> List<Token>?,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.immersionQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenList = ListSerializer(CueToken.serializer())

    /**
     * Indexes (or re-indexes) every cue of a media item. Call after loading an .srt/.vtt or after
     * [SubtitleGenerator.generate]. Runs on [Dispatchers.IO] with progress; cancelling leaves the previous index.
     */
    @Throws(Exception::class)
    suspend fun index(
        mediaId: String,
        title: String,
        kind: MediaKind,
        locator: String?,
        cues: List<Cue>,
        source: CueSource,
        onProgress: (IndexProgress) -> Unit = {},
    ): IndexedMedia = withContext(Dispatchers.IO) {
        val analyzed = ArrayList<Pair<Cue, List<CueToken>>>(cues.size)
        onProgress(IndexProgress(0, cues.size))
        cues.forEachIndexed { i, cue ->
            coroutineContext.ensureActive()
            analyzed += cue to cueTokens(cue.text)
            if (i % 25 == 24 || i == cues.lastIndex) onProgress(IndexProgress(i + 1, cues.size))
        }
        val now = clock.now().toEpochMilliseconds()
        db.transaction {
            q.deleteCueTokens(mediaId)
            q.deleteMediaCues(mediaId)
            analyzed.forEachIndexed { i, (cue, tokens) ->
                q.insertMediaCue(mediaId, i.toLong(), cue.startMs, cue.endMs, cue.text, json.encodeToString(tokenList, tokens))
                for (key in indexKeys(tokens)) q.insertCueToken(key, mediaId, i.toLong())
            }
            q.putMediaIndex(mediaId, title, kind.name, locator, source.name, analyzed.size.toLong(), now)
        }
        q.mediaIndexById(mediaId).executeAsOne().toIndexed()
    }

    /** Removes a media item from the bank (its file was deleted, or the learner asked). */
    @Throws(Exception::class)
    suspend fun remove(mediaId: String) = withContext(Dispatchers.IO) {
        db.transaction {
            q.deleteCueTokens(mediaId)
            q.deleteMediaCues(mediaId)
            q.deleteMediaIndex(mediaId)
        }
    }

    @Throws(Exception::class)
    suspend fun indexed(): List<IndexedMedia> = withContext(Dispatchers.IO) { q.mediaIndexAll().executeAsList().map { it.toIndexed() } }

    @Throws(Exception::class)
    suspend fun media(mediaId: String): IndexedMedia? = withContext(Dispatchers.IO) { q.mediaIndexById(mediaId).executeAsOneOrNull()?.toIndexed() }

    /** Lines of the learner's media that contain the dictionary word [entryId] (any inflection). */
    @Throws(Exception::class)
    suspend fun linesForEntry(entryId: Long, limit: Int = 20): List<SentenceHit> = withContext(Dispatchers.IO) {
        hits(q.cueRefsForToken("e:$entryId").executeAsList().map { it.media_id to it.idx }, limit) { tokens, _ ->
            tokens.firstOrNull { it.id == entryId }?.let { it.s to it.e }
        }
    }

    /**
     * Lines matching free text: every word of the query (by JMdict id, else lemma) must occur in the line; lines
     * whose text contains the query verbatim follow. Results are shortest first within each group.
     */
    @Throws(Exception::class)
    suspend fun search(query: String, limit: Int = 20): List<SentenceHit> {
        val text = normalizeNfc(query.trim())
        if (text.isEmpty()) return emptyList()
        val words = runCatching { tokenize(text) }.getOrNull().orEmpty().filter { isWord(it) }
        return withContext(Dispatchers.IO) {
            val out = ArrayList<SentenceHit>()
            if (words.isNotEmpty()) {
                val keys = words.map { w -> w.entryId?.let { "e:$it" } ?: "w:${lemmaOf(w)}" }.distinct()
                var refs: Set<Pair<String, Long>>? = null
                for (k in keys) {
                    val found = q.cueRefsForToken(k).executeAsList().map { it.media_id to it.idx }.toSet()
                    refs = refs?.intersect(found) ?: found
                }
                val first = words.first()
                out += hits(refs.orEmpty().toList(), limit) { tokens, cueText ->
                    tokens.firstOrNull { t -> (first.entryId != null && t.id == first.entryId) || t.l == lemmaOf(first) }?.let { it.s to it.e }
                        ?: cueText.indexOf(text).takeIf { it >= 0 }?.let { it to it + text.length }
                }
            }
            if (out.size < limit) {
                val seen = out.map { it.mediaId to it.cueIndex }.toSet()
                val rows = q.mediaCuesContaining(text, (limit * 2).toLong()).executeAsList().filter { (it.media_id to it.idx.toInt()) !in seen }
                val media = mediaFor(rows.map { it.media_id })
                for (row in rows) {
                    if (out.size >= limit) break
                    val m = media[row.media_id] ?: continue
                    val at = row.text.indexOf(text)
                    out += hitOf(row, m, at to at + text.length)
                }
            }
            out
        }
    }

    /** Every cue of one media item, in order (for re-reading a show's lines). */
    @Throws(Exception::class)
    suspend fun cues(mediaId: String): List<Cue> = withContext(Dispatchers.IO) {
        q.mediaCuesOf(mediaId).executeAsList().map { Cue(it.start_ms, it.end_ms, it.text) }
    }

    private fun hits(refs: List<Pair<String, Long>>, limit: Int, locate: (List<CueToken>, String) -> Pair<Int, Int>?): List<SentenceHit> {
        val rows = refs.mapNotNull { (m, i) -> q.mediaCueAt(m, i).executeAsOneOrNull() }
            .sortedWith(compareBy<Media_cue>({ it.text.length }, { it.media_id }, { it.idx }))
            .take(limit)
        val media = mediaFor(rows.map { it.media_id })
        return rows.mapNotNull { row ->
            val m = media[row.media_id] ?: return@mapNotNull null
            hitOf(row, m, locate(decode(row.tokens), row.text))
        }
    }

    private fun mediaFor(ids: List<String>): Map<String, IndexedMedia> =
        if (ids.isEmpty()) emptyMap() else q.mediaIndexByIds(ids.distinct()).executeAsList().map { it.toIndexed() }.associateBy { it.mediaId }

    private fun hitOf(row: Media_cue, m: IndexedMedia, span: Pair<Int, Int>?): SentenceHit = SentenceHit(
        source = SentenceSource.LIBRARY,
        japanese = row.text,
        english = null,
        highlightStart = span?.first ?: -1,
        highlightEnd = span?.second ?: -1,
        mediaId = m.mediaId,
        mediaTitle = m.title,
        mediaKind = m.kind,
        locator = m.locator,
        cueIndex = row.idx.toInt(),
        clip = clipSpan(row.start_ms, row.end_ms, m.kind),
    )

    private fun decode(tokens: String): List<CueToken> = runCatching { json.decodeFromString(tokenList, tokens) }.getOrDefault(emptyList())

    private suspend fun cueTokens(text: String): List<CueToken> {
        val tokens = runCatching { tokenize(text) }.getOrNull() ?: return emptyList()
        return tokens.filter { isWord(it) }.map { CueToken(it.start, it.end, lemmaOf(it), it.entryId) }
    }

    private fun Media_index.toIndexed() = IndexedMedia(
        media_id, title, runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.VIDEO), locator,
        runCatching { CueSource.valueOf(cue_source) }.getOrDefault(CueSource.FILE), cue_count.toInt(), indexed_at,
    )

    companion object {
        /** Padding around a line when it is played or cut, like [ClipService.DEFAULT_PADDING_MS]. */
        const val CLIP_PADDING_MS = ClipService.DEFAULT_PADDING_MS

        /** Start/end with padding, and the frame at the middle of the line for video. */
        fun clipSpan(startMs: Long, endMs: Long, kind: MediaKind): ClipSpan = ClipSpan(
            (startMs - CLIP_PADDING_MS).coerceAtLeast(0),
            endMs + CLIP_PADDING_MS,
            if (kind == MediaKind.VIDEO) startMs + (endMs - startMs) / 2 else null,
        )

        internal fun isWord(t: Token): Boolean = t.surface.any { it.isLetterOrDigit() }

        /** The dictionary form when known, else the surface; NFC, never NFKC (rule 7). */
        internal fun lemmaOf(t: Token): String = normalizeNfc(t.dictionaryForm ?: t.surface)

        /** Index keys of one cue: JMdict ids and lemmas, plus the hiragana-folded lemma for kana spellings. */
        internal fun indexKeys(tokens: List<CueToken>): Set<String> = buildSet {
            for (t in tokens) {
                t.id?.let { add("e:$it") }
                add("w:${t.l}")
                val folded = Kana.toHiragana(t.l)
                if (folded != t.l) add("w:$folded")
            }
        }
    }
}

/** Sentence search results by group, plus what happened with the optional online source. */
data class SentenceSearchResult(
    val library: List<SentenceHit>,
    val tatoeba: List<SentenceHit>,
    val online: OnlineExamplesResult,
) {
    /** Library first, then Tatoeba, then online lines: the order the dictionary shows them in (BRIEF_V2 §6.2). */
    val all: List<SentenceHit> get() = library + tatoeba + ((online as? OnlineExamplesResult.Found)?.hits.orEmpty())
}

/**
 * Dictionary "sentence search" (BRIEF_V2 §6.2): lines from the learner's own media first, then Tatoeba (from the
 * dictionary pack), then — only when the learner turned it on — the optional online source. Every hit carries its
 * [SentenceSource] so the UI can label it.
 */
class SentenceSearch(
    private val bank: SentenceBank,
    private val dictionary: suspend () -> DictionaryRepository?,
    private val online: OnlineExamples? = null,
) {
    /** Sentences for a dictionary entry: [word] (headword) and [reading] are used to highlight Tatoeba lines. */
    @Throws(Exception::class)
    suspend fun forEntry(entryId: Long, word: String, reading: String? = null, limit: Int = 20): SentenceSearchResult {
        val library = bank.linesForEntry(entryId, limit)
        val tatoeba = dictionary()?.exampleSentences(entryId, limit).orEmpty().map { s ->
            val span = locate(s.japanese, word, reading)
            SentenceHit(SentenceSource.TATOEBA, s.japanese, s.english, span.first, span.second, tatoebaId = s.id)
        }
        val remote = online?.search(word, limit) ?: OnlineExamplesResult.Disabled
        return SentenceSearchResult(library, tatoeba, remote)
    }

    /** Free-text sentence search over the library (and the online source when on). */
    @Throws(Exception::class)
    suspend fun forText(query: String, limit: Int = 20): SentenceSearchResult {
        val library = bank.search(query, limit)
        val remote = online?.search(query, limit) ?: OnlineExamplesResult.Disabled
        return SentenceSearchResult(library, emptyList(), remote)
    }

    companion object {
        /** Where [word] (or its reading, kana-folded) occurs in [sentence]; (-1, -1) when it doesn't verbatim. */
        fun locate(sentence: String, word: String, reading: String?): Pair<Int, Int> {
            for (candidate in listOfNotNull(word, reading, reading?.let { Kana.toKatakana(it) }).filter { it.isNotEmpty() }) {
                val at = sentence.indexOf(candidate)
                if (at >= 0) return at to at + candidate.length
            }
            // Inflected forms: the longest prefix of the word (at least its first character, if it is a kanji) that occurs.
            for (len in word.length - 1 downTo 1) {
                val stem = word.substring(0, len)
                if (len == 1 && !Kana.containsKanji(stem)) break
                val at = sentence.indexOf(stem)
                if (at >= 0) return at to at + len
            }
            return -1 to -1
        }
    }
}
