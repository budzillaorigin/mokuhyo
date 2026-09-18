package app.tsumugi.study

import app.tsumugi.domain.ItemKind
import app.tsumugi.kana.KanaCourse
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathService
import kotlin.random.Random

/** What the learner is studying for; shapes defaults (e.g. DLPT learners see ILR labels first). */
enum class LearningGoal(val label: String) {
    JLPT("Pass a JLPT level"),
    DLPT("DLPT / OPI (ILR levels)"),
    GENERAL("Read, listen and speak"),
}

/** One kanji of the placement check, taken from a band of path levels. */
data class PlacementQuestion(val item: PathItem, val band: Int)

/**
 * First-run setup (BRIEF §5.6 Laddrr-style skills check): goal, daily budget, and a quick kanji check that
 * suggests a starting path level. The learner can always change the level later (skip level / known-kanji
 * import), and nothing here is required to use the app.
 */
class Onboarding(private val settings: SettingsRepository, private val path: suspend () -> PathService?) {

    @Throws(Exception::class)
    suspend fun isDone(): Boolean = settings.bool(DONE, false)

    /**
     * 12 kanji spread over the path (levels 1–60 in bands of 5). "Know it" answers are counted per band; the
     * suggested level is the start of the first band where the learner knew fewer than half.
     */
    @Throws(Exception::class)
    suspend fun placementQuestions(seed: Long): List<PlacementQuestion> {
        val random = Random(seed)
        val items = path()?.items().orEmpty().filter { it.kind == ItemKind.KANJI }
        if (items.isEmpty()) return emptyList()
        return (0 until BANDS).flatMap { band ->
            val levels = (band * BAND_SIZE + 1)..((band + 1) * BAND_SIZE)
            items.filter { it.level in levels }.shuffled(random).take(PER_BAND).map { PlacementQuestion(it, band) }
        }
    }

    /** Suggested starting level from the answers (question → knew it). */
    fun suggestedLevel(answers: Map<PlacementQuestion, Boolean>): Int {
        val byBand = answers.entries.groupBy { it.key.band }
        for (band in 0 until BANDS) {
            val results = byBand[band].orEmpty()
            if (results.isEmpty()) continue
            if (results.count { it.value } * 2 < results.size) return band * BAND_SIZE + 1
        }
        return BANDS * BAND_SIZE - BAND_SIZE + 1
    }

    /**
     * Saves the choices; [startLevel] > 1 skips earlier path levels (their items can still be unlocked by hand).
     * [kanjiKnown] is how many placement kanji the learner knew (null when the check was skipped); a score of zero
     * starts the kana course before the path (G-13).
     */
    @Throws(Exception::class)
    suspend fun finish(goal: LearningGoal, budgetMinutes: Int, startLevel: Int, kanjiKnown: Int? = null) {
        if (kanjiKnown != null) KanaCourse.recordKanjiCheck(settings, kanjiKnown)
        settings.put(GOAL, goal.name)
        settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, budgetMinutes.toString())
        if (startLevel > 1) path()?.skipToLevel(startLevel)
        settings.put(DONE, "true")
    }

    @Throws(Exception::class)
    suspend fun goal(): LearningGoal = settings.get(GOAL)?.let { runCatching { LearningGoal.valueOf(it) }.getOrNull() } ?: LearningGoal.GENERAL

    companion object {
        const val DONE = "onboarding.done"
        const val GOAL = "onboarding.goal"
        private const val BAND_SIZE = 5
        private const val BANDS = 6 // levels 1–30 checked; beyond that, learners use the known-kanji import
        private const val PER_BAND = 2
    }
}
