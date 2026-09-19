package app.tsumugi.reader

import app.tsumugi.courses.ExplanationLanguage
import app.tsumugi.courses.Explanations
import app.tsumugi.domain.Stage
import app.tsumugi.grammar.GrammarExercise
import app.tsumugi.grammar.GrammarPoint
import app.tsumugi.grammar.GrammarService
import app.tsumugi.srs.SrsRepository

/**
 * A grammar construction found in a reader sentence (BRIEF_V2 §6.16): the point, a one-line explanation in the
 * language monolingual mode asks for (our own Japanese text from the grammar pack when set, D-232/D-233), where it
 * occurs in the sentence, and what "practice this point" will do.
 */
data class DetectedConstruction(
    val pointId: String,
    val title: String,
    val structure: String,
    val jlpt: Int,
    /** One line: the first sentence of the meaning, at most [ReaderGrammar.MAX_LINE] characters. */
    val explanation: String,
    val language: ExplanationLanguage,
    /** Show the "AI-generated" badge (CLAUDE.md rule 10). */
    val aiGenerated: Boolean,
    /** Monolingual mode wanted Japanese but the pack has none for this point yet; [explanation] is English. */
    val japaneseMissing: Boolean,
    /** Offsets of each match within the sentence text (end exclusive), for underlining. */
    val spans: List<IntRange>,
    /** The learner's stage for the point (null = not in reviews yet). */
    val stage: Stage?,
    /** An exercise can be built (the pack has an example sentence). */
    val hasExercises: Boolean,
) {
    /** What "practice this point" does: add it to reviews first, else open an exercise. */
    val practiceAction: PracticeKind get() = when {
        stage == null -> PracticeKind.ADD_TO_REVIEWS
        hasExercises -> PracticeKind.EXERCISE
        else -> PracticeKind.NONE
    }
}

enum class PracticeKind { ADD_TO_REVIEWS, EXERCISE, NONE }

/** The outcome of "practice this point". */
sealed interface GrammarPracticeResult {
    /** The point joined reviews (one cloze card, introduced now); the UI can open its exercise next. */
    data class AddedToReviews(val itemId: String, val exercise: GrammarExercise?) : GrammarPracticeResult

    /** Already in reviews: a fresh exercise on the point. */
    data class Exercise(val exercise: GrammarExercise) : GrammarPracticeResult

    /** No exercise can be built (no example sentence in this pack yet); [reason] says so. */
    data class Unavailable(val reason: String) : GrammarPracticeResult
}

/**
 * Grammar detection in the reader (BRIEF_V2 §6.16). The analyzer already tags each sentence with the grammar points
 * whose patterns match outside dictionary words (F-39, [ReaderSentence.grammarPointIds]); this adds the one-line
 * explanation, the match spans and the "practice this point" action (add to SRS, or open its exercises).
 */
class ReaderGrammar(
    private val grammar: suspend () -> GrammarService?,
    private val explanations: Explanations?,
    private val srs: SrsRepository,
) {
    private var patterns: Map<String, List<Regex>>? = null

    /** The constructions of [sentence], in the order their first match appears. Empty without the grammar pack. */
    @Throws(Exception::class)
    suspend fun constructions(sentence: ReaderSentence): List<DetectedConstruction> =
        constructions(sentence.text, sentence.grammarPointIds, sentence.tokens.map { WordSpan(it.start - sentence.start, it.end - sentence.start, it.entryId != null, it.surface, it.dictionaryForm, it.deinflection.isNotEmpty()) })

    /** Same, for any text: [pointIds] as the analyzer found them, [words] the dictionary words (offsets in [text]). */
    @Throws(Exception::class)
    suspend fun constructions(text: String, pointIds: List<String>, words: List<WordSpan> = emptyList()): List<DetectedConstruction> {
        if (pointIds.isEmpty()) return emptyList()
        val service = grammar() ?: return emptyList()
        val points = service.pointsByIds(pointIds.distinct())
        val regexes = patterns(service)
        val stages = srs.stagesFor(points.map { it.itemId })
        val withExamples = service.withExamples(points.map { it.id })
        return points.map { p ->
            val spans = regexes[p.id].orEmpty().flatMap { re -> re.findAll(text).map { it.range.first until it.range.last + 1 }.toList() }
                .filter { !it.isEmpty() && !cutsWord(words, it.first, it.last + 1) }
                .distinct().sortedBy { it.first }
            val (line, language, ai, missing) = explain(p)
            DetectedConstruction(p.id, p.title, p.structure, p.jlpt, line, language, ai, missing, spans, stages[p.itemId], p.id in withExamples)
        }.sortedBy { it.spans.firstOrNull()?.first ?: Int.MAX_VALUE }
    }

    /**
     * "Practice this point": a point not in reviews is added (one cloze card, introduced now) and its first exercise
     * returned with it; a point already in reviews gets a fresh exercise.
     */
    @Throws(Exception::class)
    suspend fun practice(pointId: String): GrammarPracticeResult {
        val service = grammar() ?: return GrammarPracticeResult.Unavailable("The grammar pack isn't installed.")
        val point = service.pointsByIds(listOf(pointId)).firstOrNull() ?: return GrammarPracticeResult.Unavailable("Unknown grammar point.")
        val inReviews = srs.stagesFor(listOf(point.itemId))[point.itemId] != null
        if (!inReviews) {
            service.learn(listOf(point))
            return GrammarPracticeResult.AddedToReviews(point.itemId, service.exercise(point.id))
        }
        return service.exercise(point.id)?.let { GrammarPracticeResult.Exercise(it) }
            ?: GrammarPracticeResult.Unavailable(NO_EXAMPLES)
    }

    private data class Line(val text: String, val language: ExplanationLanguage, val ai: Boolean, val missing: Boolean)

    private suspend fun explain(p: GrammarPoint): Line {
        val e = explanations?.grammar(p)
        return if (e == null) {
            Line(oneLine(p.meaning), ExplanationLanguage.ENGLISH, p.source != app.tsumugi.domain.ItemSource.VERIFIED, false)
        } else {
            Line(oneLine(e.meaning), e.language, e.aiGenerated, e.japaneseMissing)
        }
    }

    private suspend fun patterns(service: GrammarService): Map<String, List<Regex>> =
        patterns ?: service.detectionPatterns().groupBy({ it.first }, { it.second }).also { patterns = it }

    /** A dictionary word's surface span in a sentence (offsets in the sentence text). */
    data class WordSpan(
        val start: Int,
        val end: Int,
        val isWord: Boolean,
        val surface: String = "",
        val dictionaryForm: String? = null,
        val inflected: Boolean = false,
    )

    companion object {
        const val MAX_LINE = 90
        const val NO_EXAMPLES = "No example sentences for this point yet."

        /** First sentence of [text] (。 or ". "), cut at [MAX_LINE] characters with an ellipsis. */
        fun oneLine(text: String): String {
            val t = text.trim()
            val end = listOf(t.indexOf('。').let { if (it >= 0) it + 1 else -1 }, t.indexOf(". ").let { if (it >= 0) it + 1 else -1 })
                .filter { it > 0 }.minOrNull() ?: t.length
            val first = t.substring(0, end).trim()
            return if (first.length <= MAX_LINE) first else first.take(MAX_LINE - 1).trimEnd() + "…"
        }

        /**
         * True when [start, end) starts or ends strictly inside a word's lexical part (the F-39 rule the analyzer applies;
         * an inflected word only protects the stem it shares with its dictionary form, so ました still matches).
         */
        internal fun cutsWord(words: List<WordSpan>, start: Int, end: Int): Boolean = words.any { w ->
            if (!w.isWord) return@any false
            val lexemeEnd = w.start + lexeme(w)
            start in (w.start + 1) until lexemeEnd || end in (w.start + 1) until lexemeEnd
        }

        private fun lexeme(w: WordSpan): Int {
            val base = w.dictionaryForm
            if (!w.inflected || base == null || base == w.surface) return w.end - w.start
            var p = 0
            while (p < w.surface.length && p < base.length && w.surface[p] == base[p]) p++
            return p
        }
    }
}
