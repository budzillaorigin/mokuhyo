package app.tsumugi.reader

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.GenerateReadingQuestions
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.Stage
import app.tsumugi.jp.Kana
import app.tsumugi.jp.Mora
import app.tsumugi.jp.Pitch
import app.tsumugi.jp.PitchPattern
import app.tsumugi.jp.codePointList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// --- Learner-aware furigana (G-07) -----------------------------------------------------------------------------

/**
 * What the learner can read, for "furigana only above my level" (BRIEF_V2 G-07, DECISIONS D-114): their JLPT level
 * (5 = N5 … 1 = N1; null = unknown) and the kanji they know (kanji items at Guru or above).
 */
data class LearnerLevel(val jlpt: Int?, val knownKanji: Set<String>) {
    companion object {
        val UNKNOWN = LearnerLevel(null, emptySet())

        /** Known kanji from SRS stages: items "k:<kanji>" at Guru or above. */
        fun knownKanji(stages: Map<String, Stage>): Set<String> =
            stages.filter { (id, stage) -> id.startsWith("k:") && stage >= Stage.GURU }.keys.map { it.removePrefix("k:") }.toSet()

        /**
         * A JLPT level estimate from the passed kanji-path level when the learner hasn't set one: path levels
         * 1–10 ≈ N5, 11–20 ≈ N4, 21–30 ≈ N3, 31–45 ≈ N2, above ≈ N1 (the path orders kanji by the same lists).
         */
        fun jlptFromPathLevel(passedLevel: Int): Int = when {
            passedLevel <= 10 -> 5
            passedLevel <= 20 -> 4
            passedLevel <= 30 -> 3
            passedLevel <= 45 -> 2
            else -> 1
        }
    }
}

object LearnerFurigana {
    /**
     * Whether to draw ruby over [token] for this learner: never over kana; never over a word they know (Guru+);
     * not over a word at or below their JLPT level whose kanji they all know; otherwise yes (words above their
     * level, words on no JLPT list, and words with a kanji they haven't learned).
     */
    fun show(token: ReaderToken, level: LearnerLevel): Boolean {
        if (!token.hasKanji) return false
        if (token.known) return false
        val kanji = token.surface.codePointList().filter(Kana::isKanji).map { cp -> codePointString(cp) }
        val allKanjiKnown = kanji.all { it in level.knownKanji }
        val atOrBelowLevel = level.jlpt != null && token.jlpt != null && token.jlpt >= level.jlpt
        return !(atOrBelowLevel && allKanjiKnown)
    }

    private fun codePointString(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()
}

/** [FuriganaMode.UNKNOWN_ONLY] with the learner's full level ([LearnerFurigana.show]); other modes as before. */
fun ReaderToken.showFurigana(mode: FuriganaMode, level: LearnerLevel): Boolean = when (mode) {
    FuriganaMode.UNKNOWN_ONLY -> LearnerFurigana.show(this, level)
    else -> showFurigana(mode, level.jlpt)
}

// --- Pitch overlay (G-07) --------------------------------------------------------------------------------------

/**
 * Accent of one reader token for the pitch overlay: [morae] of its reading with high/low [heights] (one extra
 * entry for a following particle, as in [app.tsumugi.jp.PitchAccent.heights]). [downstep] is null when the word
 * isn't in the pitch table or is inflected (the reader has no parts of speech to apply the conjugation rules to,
 * so it says "unknown" instead of guessing, as D-055 does).
 */
data class TokenPitch(
    val start: Int,
    val end: Int,
    val reading: String,
    val morae: List<String>,
    val downstep: Int?,
    val pattern: PitchPattern?,
    val heights: List<Boolean>,
    /** Other accents the dictionary lists for the word. */
    val alternatives: List<Int>,
)

object ReaderPitch {
    /**
     * Pitch for each dictionary word in [tokens]. [lookup] is `(word, reading) → Kanjium accents`, normally
     * [app.tsumugi.dictionary.DictionaryRepository.pitchAccents]. Lookups are batched per distinct word.
     */
    @Throws(Exception::class)
    suspend fun overlay(tokens: List<ReaderToken>, lookup: suspend (String, String) -> List<Int>): List<TokenPitch> {
        val cache = HashMap<Pair<String, String>, List<Int>>()
        return tokens.filter { it.isWord && it.reading != null && Kana.isJapanese(it.surface) }.map { t ->
            val reading = Kana.toHiragana(t.reading!!)
            val morae = Mora.split(reading)
            val inflected = t.deinflection.isNotEmpty() || (t.dictionaryForm != null && t.dictionaryForm != t.surface)
            val word = t.dictionaryForm ?: t.surface
            val lemmaReading = Kana.toHiragana(t.lemmaReading ?: reading)
            val accents = if (inflected) emptyList() else cache.getOrPut(word to lemmaReading) { lookup(word, lemmaReading) }
            val downstep = accents.firstOrNull()?.takeIf { it in 0..morae.size }
            val accent = downstep?.let { Pitch.accent(reading, it) }
            TokenPitch(t.start, t.end, reading, morae, downstep, accent?.pattern, accent?.heights().orEmpty(), accents.drop(1))
        }
    }
}

// --- Comprehension questions (G-07) ----------------------------------------------------------------------------

/** Comprehension questions for a document. Always AI-generated (rule 10): [source] is "llm" and the UI badges them. */
data class ReadingQuestions(
    val documentId: String,
    val level: String,
    val questions: List<GenerateReadingQuestions.Question>,
    val engine: String,
    val fromCache: Boolean,
) {
    val source: String get() = "llm"
}

sealed interface ReadingQuestionsResult {
    data class Ready(val value: ReadingQuestions) : ReadingQuestionsResult

    /** No model, or the model failed: the UI explains [reason] and links to AI settings (no fake questions). */
    data class Unavailable(val reason: String) : ReadingQuestionsResult
}

/**
 * Comprehension questions through the `generate_reading_questions` prompt (BRIEF_V2 G-07, D-114), cached per
 * document in `reader_question` so reopening a document is instant and the model isn't asked twice. The passage
 * sent to the model is the first [maxChars] characters, cut at a paragraph end.
 */
class ReadingQuestionService(
    private val db: TsumugiDatabase,
    private val gateway: suspend () -> AiGateway,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.readerQueries
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(Exception::class)
    suspend fun cached(documentId: String): ReadingQuestions? = withContext(Dispatchers.IO) {
        q.questionsFor(documentId).executeAsOneOrNull()?.let { row ->
            runCatching { json.decodeFromString(GenerateReadingQuestions.Output.serializer(), row.questions) }.getOrNull()
                ?.let { ReadingQuestions(documentId, row.level, it.questions, row.engine, fromCache = true) }
        }
    }

    /** Cached questions, or new ones for [document] at [level] ("N4"); [regenerate] ignores the cache. */
    @Throws(Exception::class)
    suspend fun questions(document: ReaderDocument, level: String = "N4", count: Int = 3, regenerate: Boolean = false): ReadingQuestionsResult {
        if (!regenerate) cached(document.id)?.let { return ReadingQuestionsResult.Ready(it) }
        val passage = passageFor(document.body)
        if (passage.isBlank()) return ReadingQuestionsResult.Unavailable("This document has no text to ask about")
        return when (val r = gateway().run(GenerateReadingQuestions(), GenerateReadingQuestions.Input(passage, level, count.coerceIn(1, 5)))) {
            is AiResult.Ok -> {
                withContext(Dispatchers.IO) {
                    q.putQuestions(document.id, level, json.encodeToString(GenerateReadingQuestions.Output.serializer(), r.value), r.engine, clock.now().toEpochMilliseconds())
                }
                ReadingQuestionsResult.Ready(ReadingQuestions(document.id, level, r.value.questions, r.engine, fromCache = false))
            }
            is AiResult.Fallback -> ReadingQuestionsResult.Unavailable(r.reason)
            is AiResult.Unavailable -> ReadingQuestionsResult.Unavailable(r.reason)
        }
    }

    @Throws(Exception::class)
    suspend fun clear(documentId: String) = withContext(Dispatchers.IO) { q.deleteQuestions(documentId) }

    companion object {
        const val MAX_CHARS = 1_500

        fun passageFor(body: String, maxChars: Int = MAX_CHARS): String {
            if (body.length <= maxChars) return body.trim()
            val cut = body.substring(0, maxChars)
            val end = cut.lastIndexOf('\n').takeIf { it > maxChars / 2 } ?: cut.lastIndexOf('。').takeIf { it > 0 }?.plus(1) ?: maxChars
            return cut.substring(0, end).trim()
        }
    }
}

// --- Graded passage packs (G-07; content in Phase 12) ------------------------------------------------------------

/** A graded-reader pack as installed (BRIEF_V2 §6.4). */
data class ReaderPackInfo(val id: String, val title: String, val version: String, val passageCount: Int)

/** One passage in a list. [source] is "llm" until reviewed ("verified"), and the UI badges it (rule 10). */
data class GradedPassageSummary(
    val id: String,
    val packId: String,
    val title: String,
    val jlpt: Int?,
    val ilr: String?,
    val length: Int,
    val source: String,
)

data class GradedPassage(
    val summary: GradedPassageSummary,
    val body: String,
    /** Furigana the pack supplies, authoritative like Aozora ruby. */
    val ruby: List<RubyHint>,
    val author: String?,
)

/**
 * Where graded passages come from. Phase 12 adds a pack-backed implementation (a read-only SQLite pack like the
 * others); until then [EmptyReaderPacks] gives the reader an honest empty state (rule 9).
 */
interface ReaderPackRepository {
    @Throws(Exception::class)
    suspend fun packs(): List<ReaderPackInfo>

    /** Passages of [packId] (all packs when null), optionally only one JLPT level, in reading order. */
    @Throws(Exception::class)
    suspend fun passages(packId: String? = null, jlpt: Int? = null): List<GradedPassageSummary>

    @Throws(Exception::class)
    suspend fun passage(id: String): GradedPassage?
}

object EmptyReaderPacks : ReaderPackRepository {
    override suspend fun packs(): List<ReaderPackInfo> = emptyList()
    override suspend fun passages(packId: String?, jlpt: Int?): List<GradedPassageSummary> = emptyList()
    override suspend fun passage(id: String): GradedPassage? = null
}

/** A graded passage as a reader document (kind PACK, keyed by a stable pseudo-URL so reopening reuses it). */
fun GradedPassage.toImportedText(): ImportedText =
    ImportedText(summary.title, body, SourceKind.PACK, "pack://${summary.packId}/${summary.id}", author, ruby)
