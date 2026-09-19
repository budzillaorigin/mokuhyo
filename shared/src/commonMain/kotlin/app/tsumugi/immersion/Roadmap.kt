package app.tsumugi.immersion

import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.srs.PathProgressStore

/** The four stages of the immersion roadmap (BRIEF_V2 §6.11). Our own framing and text. */
enum class RoadmapStage(val number: Int, val title: String, val summary: String) {
    FOUNDATIONS(
        1, "Foundations",
        "Kana, the first few hundred words and the core grammar, with short daily listening so the sound of Japanese becomes familiar.",
    ),
    COMPREHENSION(
        2, "Comprehension",
        "Lots of input you mostly understand: shows, podcasts and graded readers. Words come from your own media; speaking can wait.",
    ),
    OUTPUT(
        3, "Output",
        "Start speaking and writing regularly: shadowing, conversations with the practice partner, short texts you get corrected.",
    ),
    REFINEMENT(
        4, "Refinement",
        "Polish accuracy, register and pitch; read and listen to native material of every kind and close the remaining gaps.",
    ),
    ;

    companion object {
        fun of(number: Int): RoadmapStage = entries.firstOrNull { it.number == number } ?: if (number > 4) REFINEMENT else FOUNDATIONS
    }
}

/** What a milestone measures, always in the app's own numbers. */
enum class MilestoneMeasure {
    /** Words at Guru or above, plus words marked known. */
    KNOWN_WORDS,

    /** Hours in the immersion log. */
    IMMERSION_HOURS,

    /** Share of graded-reader comprehension questions answered correctly (0–100). Phase 12 provides it. */
    READER_COMPREHENSION,

    /** The latest OPI practice interview estimate, as an ILR level index (0 = 0, 1 = 0+, 2 = 1, …). */
    OPI_LEVEL,
}

/** The learner's current numbers. Null = not measured yet (no graded readers taken, no OPI practice done). */
data class RoadmapInputs(
    val knownWords: Int,
    val immersionHours: Double,
    val readerComprehensionPercent: Double? = null,
    val opiLevel: IlrLevel? = null,
)

data class Milestone(
    val id: String,
    val stage: RoadmapStage,
    val measure: MilestoneMeasure,
    val target: Double,
    val title: String,
    /** The learner's value in the milestone's unit; null when this measure isn't available yet. */
    val current: Double?,
    val met: Boolean,
    /** 0..1 toward [target]. */
    val progress: Double,
)

data class RoadmapStageStatus(val stage: RoadmapStage, val milestones: List<Milestone>, val reached: Boolean, val complete: Boolean)

data class RoadmapStatus(
    /** The stage the learner is in: the highest reached, never lower than a stage reached before (rule 11). */
    val current: RoadmapStage,
    val stages: List<RoadmapStageStatus>,
    /** The first unmet milestone of the current stage, what to aim for next; null when everything is met. */
    val next: Milestone?,
    val inputs: RoadmapInputs,
)

/**
 * The immersion roadmap (BRIEF_V2 §6.11, DECISIONS D-168). Each stage lists milestones that, when met, move the
 * learner on to the next stage. The graded-reader score can't be measured before Phase 12 ships graded readers, and
 * until then it doesn't hold the learner back (it shows as "not measured yet"). An OPI milestone needs an OPI practice
 * interview, which the learner can take any time.
 *
 * The reached stage is a persisted fact on the `path_progress` track [TRACK] (MAX merge), so a lapse in known words
 * or a device that hasn't synced yet never moves the learner back a stage (rule 11).
 */
object Roadmap {
    const val TRACK = "roadmap"

    private class Spec(val id: String, val stage: RoadmapStage, val measure: MilestoneMeasure, val target: Double, val title: String)

    private val specs = listOf(
        Spec("s1-words", RoadmapStage.FOUNDATIONS, MilestoneMeasure.KNOWN_WORDS, 800.0, "Know 800 words"),
        Spec("s1-hours", RoadmapStage.FOUNDATIONS, MilestoneMeasure.IMMERSION_HOURS, 25.0, "Log 25 hours of immersion"),
        Spec("s2-words", RoadmapStage.COMPREHENSION, MilestoneMeasure.KNOWN_WORDS, 3000.0, "Know 3,000 words"),
        Spec("s2-hours", RoadmapStage.COMPREHENSION, MilestoneMeasure.IMMERSION_HOURS, 300.0, "Log 300 hours of immersion"),
        Spec("s2-readers", RoadmapStage.COMPREHENSION, MilestoneMeasure.READER_COMPREHENSION, 80.0, "Score 80% on graded-reader questions"),
        Spec("s3-words", RoadmapStage.OUTPUT, MilestoneMeasure.KNOWN_WORDS, 6000.0, "Know 6,000 words"),
        Spec("s3-hours", RoadmapStage.OUTPUT, MilestoneMeasure.IMMERSION_HOURS, 800.0, "Log 800 hours of immersion"),
        Spec("s3-opi", RoadmapStage.OUTPUT, MilestoneMeasure.OPI_LEVEL, IlrLevel.L1_PLUS.ordinal.toDouble(), "Reach ILR 1+ in an OPI practice interview"),
        Spec("s4-words", RoadmapStage.REFINEMENT, MilestoneMeasure.KNOWN_WORDS, 10000.0, "Know 10,000 words"),
        Spec("s4-hours", RoadmapStage.REFINEMENT, MilestoneMeasure.IMMERSION_HOURS, 1500.0, "Log 1,500 hours of immersion"),
        Spec("s4-opi", RoadmapStage.REFINEMENT, MilestoneMeasure.OPI_LEVEL, IlrLevel.L2_PLUS.ordinal.toDouble(), "Reach ILR 2+ in an OPI practice interview"),
    )

    /** Every milestone with the learner's numbers, and the stage they're in given [reachedBefore] (1–4, 0 = none). */
    fun evaluate(inputs: RoadmapInputs, reachedBefore: Int = 0): RoadmapStatus {
        val milestones = specs.map { s ->
            val current = when (s.measure) {
                MilestoneMeasure.KNOWN_WORDS -> inputs.knownWords.toDouble()
                MilestoneMeasure.IMMERSION_HOURS -> inputs.immersionHours
                MilestoneMeasure.READER_COMPREHENSION -> inputs.readerComprehensionPercent
                MilestoneMeasure.OPI_LEVEL -> inputs.opiLevel?.ordinal?.toDouble()
            }
            val met = current != null && current >= s.target
            val progress = when {
                current == null -> 0.0
                s.target <= 0 -> 1.0
                else -> (current / s.target).coerceIn(0.0, 1.0)
            }
            Milestone(s.id, s.stage, s.measure, s.target, s.title, current, met, progress)
        }
        // A stage is complete when its milestones are met. A graded-reader score that can't be measured yet (no
        // graded readers installed before Phase 12) is skipped; a missing OPI estimate is not: the learner can take one.
        fun complete(stage: RoadmapStage) = milestones
            .filter { it.stage == stage && !(it.current == null && it.measure == MilestoneMeasure.READER_COMPREHENSION) }
            .let { m -> m.isNotEmpty() && m.all { it.met } }
        var computed = 1
        for (stage in RoadmapStage.entries) {
            if (stage.number == computed && complete(stage) && computed < 4) computed++ else break
        }
        val current = RoadmapStage.of(maxOf(computed, reachedBefore.coerceIn(0, 4)))
        val stages = RoadmapStage.entries.map { s ->
            RoadmapStageStatus(s, milestones.filter { it.stage == s }, reached = s.number <= current.number, complete = complete(s))
        }
        val next = milestones.firstOrNull { it.stage == current && !it.met }
            ?: milestones.firstOrNull { it.stage.number > current.number && !it.met }
        return RoadmapStatus(current, stages, next, inputs)
    }
}

/**
 * The roadmap with the app's live numbers. Providers are functions so other modules plug in: [knownWords] (SRS
 * Guru+ plus words marked known — the known-words table replaces the default in AppGraph), [comprehension] (Phase
 * 12 graded readers; null until then) and [opiLevel] (the latest OPI practice estimate).
 */
class RoadmapService(
    private val log: ImmersionLog,
    private val progress: PathProgressStore,
    var knownWords: suspend () -> Int,
    var comprehension: suspend () -> Double? = { null },
    var opiLevel: suspend () -> IlrLevel? = { null },
) {
    /** Evaluates the roadmap and records a newly reached stage (it can only rise). */
    @Throws(Exception::class)
    suspend fun status(): RoadmapStatus {
        val inputs = RoadmapInputs(knownWords(), log.totalHours(), comprehension(), opiLevel())
        val before = progress.progress(Roadmap.TRACK).passedLevel
        val status = Roadmap.evaluate(inputs, before)
        if (status.current.number > before) progress.recordPassed(status.current.number, Roadmap.TRACK)
        return status
    }
}
