package app.tsumugi.courses

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.ParaphraseWordJa
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemSource
import app.tsumugi.grammar.GrammarPoint
import app.tsumugi.grammar.GrammarService
import app.tsumugi.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

enum class ExplanationLanguage { ENGLISH, JAPANESE }

/**
 * Monolingual mode (BRIEF_V2 §6.6, DECISIONS D-232): a synced learner preference. Explanations and glosses of content
 * at [fromLevel] or harder (JLPT 1 = N1) are shown in Japanese only. Off by default; turning it on starts at N2, and
 * the learner may move it earlier (N3, N4, N5).
 */
class MonolingualSettings(private val settings: SettingsRepository) {
    /** The easiest JLPT level shown Japanese-only, or null when monolingual mode is off. */
    @Throws(Exception::class)
    suspend fun fromLevel(): Int? = settings.get(SettingsRepository.MONOLINGUAL_FROM_LEVEL)?.toIntOrNull()?.takeIf { it in 1..5 }

    /** [level] null or 0 turns the mode off; 2 = N2 and N1 (the default), 3–5 = earlier. */
    @Throws(Exception::class)
    suspend fun setFromLevel(level: Int?) =
        settings.put(SettingsRepository.MONOLINGUAL_FROM_LEVEL, (level?.takeIf { it in 1..5 } ?: 0).toString())

    @Throws(Exception::class)
    suspend fun enabled(): Boolean = fromLevel() != null

    @Throws(Exception::class)
    suspend fun setEnabled(on: Boolean) = setFromLevel(if (on) fromLevel() ?: DEFAULT_FROM_LEVEL else null)

    /** Which language to explain content of JLPT [level] in; null level (untagged content) uses [learnerLevel]. */
    @Throws(Exception::class)
    suspend fun languageFor(level: Int?, learnerLevel: Int? = null): ExplanationLanguage =
        resolve(fromLevel(), level ?: learnerLevel)

    companion object {
        const val DEFAULT_FROM_LEVEL = 2

        /** Pure rule: Japanese when the mode is on and the content is at [fromLevel] or harder (smaller number). */
        fun resolve(fromLevel: Int?, level: Int?): ExplanationLanguage =
            if (fromLevel != null && level != null && level in 1..fromLevel) ExplanationLanguage.JAPANESE else ExplanationLanguage.ENGLISH
    }
}

/**
 * A grammar point's explanation in the language the setting asks for. [language] may be [ExplanationLanguage.ENGLISH]
 * even in monolingual mode when the pack has no Japanese text for the point yet ([japaneseMissing]); the UI says so
 * rather than hiding it. [aiGenerated]: show the "AI-generated" badge (CLAUDE.md rule 10).
 */
data class GrammarExplanation(
    val pointId: String,
    val language: ExplanationLanguage,
    val meaning: String,
    val nuance: String,
    val aiGenerated: Boolean,
    val japaneseMissing: Boolean,
)

/**
 * A word's gloss in the right language. English: the JMdict [glosses]. Japanese: [paraphrase], [example] and [note] from
 * `paraphrase_word_ja` (always AI-generated, labeled with [engine]). When Japanese was wanted but no model could write it,
 * [language] is ENGLISH and [unavailableReason] says why, so nothing silently degrades (CLAUDE.md rule 1).
 */
data class WordExplanation(
    val language: ExplanationLanguage,
    val glosses: List<String>,
    val paraphrase: String,
    val example: String,
    val note: String,
    val engine: String?,
    val cached: Boolean,
    val unavailableReason: String?,
) {
    val aiGenerated: Boolean get() = language == ExplanationLanguage.JAPANESE
}

/** The word a paraphrase is asked for. [ref] keys the cache: "jmdict:<id>" or "text:<headword>|<reading>". */
data class ParaphraseRequest(
    val word: String,
    val reading: String,
    val glosses: List<String>,
    val partOfSpeech: List<String> = emptyList(),
    val entryId: Long? = null,
    /** JLPT level of the word, if tagged (1–5). */
    val jlpt: Int? = null,
) {
    val ref: String get() = entryId?.let { "jmdict:$it" } ?: "text:$word|$reading"
}

/**
 * Right-language explanations for grammar and words (BRIEF_V2 §6.6). Grammar uses our own Japanese explanations from
 * the grammar pack. Words get an LLM paraphrase on demand through the [AiGateway], cached per word in `ai_paraphrase`
 * so each word is generated once per device (D-234).
 */
class Explanations(
    private val db: TsumugiDatabase,
    val monolingual: MonolingualSettings,
    private val grammar: suspend () -> GrammarService?,
    private val gateway: suspend () -> AiGateway?,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val q get() = db.coursesQueries

    /** [point]'s explanation in the language monolingual mode asks for at the point's level. */
    @Throws(Exception::class)
    suspend fun grammar(point: GrammarPoint): GrammarExplanation =
        grammar(point, monolingual.languageFor(point.jlpt))

    /** [point]'s explanation in [language], whatever the setting says (the "show English" toggle). */
    @Throws(Exception::class)
    suspend fun grammar(point: GrammarPoint, language: ExplanationLanguage): GrammarExplanation {
        val english = GrammarExplanation(point.id, ExplanationLanguage.ENGLISH, point.meaning, point.nuance, point.source != ItemSource.VERIFIED, false)
        if (language == ExplanationLanguage.ENGLISH) return english
        val ja = grammar()?.japanese(point.id) ?: return english.copy(japaneseMissing = true)
        return GrammarExplanation(point.id, ExplanationLanguage.JAPANESE, ja.meaning, ja.nuance, ja.source != ItemSource.VERIFIED, false)
    }

    /**
     * A word's gloss in the right language. In Japanese it returns the cached paraphrase, or asks the model once when
     * [generate] is true (false = cache only, for lists that shouldn't fire a model call per row). [learnerLevel] decides
     * for words without a JLPT tag.
     */
    @Throws(Exception::class)
    suspend fun word(request: ParaphraseRequest, learnerLevel: Int? = null, generate: Boolean = true): WordExplanation {
        val language = monolingual.languageFor(request.jlpt, learnerLevel)
        if (language == ExplanationLanguage.ENGLISH) return english(request, null)
        return paraphrase(request, levelLabel(request.jlpt ?: learnerLevel), generate)
    }

    /** The Japanese paraphrase regardless of the setting (an explicit "explain in Japanese" button). */
    @Throws(Exception::class)
    suspend fun paraphrase(request: ParaphraseRequest, level: String = "N2", generate: Boolean = true): WordExplanation {
        cached(request)?.let { return it }
        if (!generate) return english(request, NOT_GENERATED)
        val ai = gateway() ?: return english(request, "no AI model is set up")
        val input = ParaphraseWordJa.Input(request.word, request.reading, request.glosses, request.partOfSpeech, level)
        return when (val r = ai.run(ParaphraseWordJa(), input)) {
            is AiResult.Ok -> {
                withContext(Dispatchers.IO) {
                    q.putParaphrase(KIND_WORD_JA, request.ref, json.encodeToString(ParaphraseWordJa.Output.serializer(), r.value), r.engine, clock.now().toEpochMilliseconds())
                }
                japanese(request, r.value, r.engine, cached = false)
            }
            is AiResult.Fallback -> english(request, r.reason)
            is AiResult.Unavailable -> english(request, r.reason)
        }
    }

    /** Drops a cached paraphrase (the learner flagged it as wrong), so the next request asks the model again. */
    @Throws(Exception::class)
    suspend fun forgetParaphrase(request: ParaphraseRequest) = withContext(Dispatchers.IO) { q.deleteParaphrase(KIND_WORD_JA, request.ref) }

    private suspend fun cached(request: ParaphraseRequest): WordExplanation? = withContext(Dispatchers.IO) {
        val row = q.paraphrase(KIND_WORD_JA, request.ref).executeAsOneOrNull() ?: return@withContext null
        val out = runCatching { json.decodeFromString(ParaphraseWordJa.Output.serializer(), row.value_) }.getOrNull() ?: return@withContext null
        japanese(request, out, row.engine, cached = true)
    }

    private fun japanese(request: ParaphraseRequest, out: ParaphraseWordJa.Output, engine: String, cached: Boolean) =
        WordExplanation(ExplanationLanguage.JAPANESE, request.glosses, out.paraphrase, out.example, out.note, engine, cached, null)

    private fun english(request: ParaphraseRequest, reason: String?) =
        WordExplanation(ExplanationLanguage.ENGLISH, request.glosses, "", "", "", null, false, reason)

    private fun levelLabel(level: Int?) = "N${level ?: MonolingualSettings.DEFAULT_FROM_LEVEL}"

    companion object {
        const val KIND_WORD_JA = "word_ja"
        /** [WordExplanation.unavailableReason] when only the cache was consulted. */
        const val NOT_GENERATED = "not generated yet"
    }
}
