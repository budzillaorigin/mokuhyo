package app.mokuhyo.standto

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * The daily stand-to (BRIEF_PHASE8 N-12): one tap, 8–10 minutes — a numbers set, three lexicon items, one listening
 * clip at the learner's band, and one speaking turn in After-action mode. The plan respects the hardware tier: no
 * speaking turn without a language model (the time goes to numbers and lexicon instead).
 */
object StandToPlanner {
    enum class StepKind(val title: String) { NUMBERS("Numbers"), LEXICON("Lexicon"), LISTENING("Listening clip"), SPEAKING("Speaking turn") }

    @Serializable
    data class Step(val kind: StepKind, val minutes: Double, val count: Int = 1, val passageId: String? = null, val level: String? = null)

    @Serializable
    data class Recipe(val steps: List<Step>) {
        val minutes: Double get() = steps.sumOf { it.minutes }
    }

    /** Minutes per unit, used to size the recipe. */
    private const val PER_NUMBER = 0.33
    private const val PER_TERM = 0.5
    private const val LISTENING = 3.0
    private const val SPEAKING = 2.5

    /**
     * [candidates]: listening passage ids per ILR level the learner hasn't practised recently. [listeningLevel]: the
     * learner's latest listening estimate (null = no test yet: 1+). [hasSpeechInput] only changes how the turn is
     * answered (typed when false), not whether there is one.
     */
    fun plan(hasModel: Boolean, listeningLevel: String?, candidates: Map<String, List<String>>, random: Random): Recipe {
        val band = listeningLevel?.takeIf { candidates[it]?.isNotEmpty() == true }
            ?: LEVELS.sortedBy { kotlin.math.abs(LEVELS.indexOf(it) - LEVELS.indexOf(listeningLevel ?: "1+")) }.firstOrNull { candidates[it]?.isNotEmpty() == true }
        val passage = band?.let { candidates.getValue(it).random(random) }
        val steps = mutableListOf<Step>()
        val numbers = if (hasModel) 6 else 9
        val terms = if (hasModel) 3 else 5
        steps += Step(StepKind.NUMBERS, numbers * PER_NUMBER, numbers)
        steps += Step(StepKind.LEXICON, terms * PER_TERM, terms)
        if (passage != null) steps += Step(StepKind.LISTENING, LISTENING, 1, passage, band)
        if (hasModel) steps += Step(StepKind.SPEAKING, SPEAKING, 1, level = listeningLevel ?: "1+")
        // Without a clip (no listening content), more numbers keep it near eight minutes.
        if (passage == null) steps[0] = steps[0].copy(count = numbers + 8, minutes = (numbers + 8) * PER_NUMBER)
        return Recipe(steps)
    }

    val LEVELS = listOf("0+", "1", "1+", "2", "2+", "3")
}

/** One finished stand-to, stored in the `stand_to` table. */
@Serializable
data class StandToResult(
    val numbersRight: Int = 0,
    val numbersTotal: Int = 0,
    val termsReviewed: Int = 0,
    val listeningRight: Boolean? = null,
    val spoke: Boolean = false,
    val minutes: Double = 0.0,
)

/** The weekly summary card: the last seven days. */
data class WeeklySummary(val days: Int, val sessions: Int, val numbersAccuracy: Int?, val termsReviewed: Int, val listeningRight: Int, val listeningTotal: Int, val minutes: Int) {
    companion object {
        /** [records]: (finished at epoch ms, result). [now] epoch ms; [dayOf] maps a timestamp to a local calendar day number. */
        fun of(records: List<Pair<Long, StandToResult>>, now: Long, dayOf: (Long) -> Long): WeeklySummary {
            val today = dayOf(now)
            val week = records.filter { today - dayOf(it.first) in 0..6 }
            val n = week.sumOf { it.second.numbersTotal }
            val l = week.mapNotNull { it.second.listeningRight }
            return WeeklySummary(
                days = week.map { dayOf(it.first) }.distinct().size, sessions = week.size,
                numbersAccuracy = if (n == 0) null else week.sumOf { it.second.numbersRight } * 100 / n,
                termsReviewed = week.sumOf { it.second.termsReviewed }, listeningRight = l.count { it }, listeningTotal = l.size,
                minutes = week.sumOf { it.second.minutes }.toInt(),
            )
        }
    }
}

/** The `stand_to` log (append-only). */
class StandToRepository(private val db: app.mokuhyo.db.MokuhyoDatabase) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun save(learnerId: String, lang: String, startedAt: Long, finishedAt: Long, recipe: StandToPlanner.Recipe, result: StandToResult) {
        db.standtoQueries.insertStandTo(app.mokuhyo.db.Stand_to(kotlin.uuid.Uuid.random().toString(), learnerId, lang, startedAt, finishedAt,
            json.encodeToString(StandToPlanner.Recipe.serializer(), recipe), json.encodeToString(StandToResult.serializer(), result), null))
    }

    fun since(learnerId: String, lang: String, fromMs: Long): List<Pair<Long, StandToResult>> =
        db.standtoQueries.standTosSince(learnerId, lang, fromMs).executeAsList().map { it.finishedAt to json.decodeFromString(StandToResult.serializer(), it.resultJson) }
}
