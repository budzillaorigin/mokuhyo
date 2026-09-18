package app.tsumugi.exam.opi

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.OpiInterviewerTurn
import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.ai.prompts.OpiRate
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.ai.prompts.Turn
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.practice.OpiBank
import app.tsumugi.practice.OpiQuestion
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import app.tsumugi.practice.OpiPhase as BankPhase

/** An interviewer line; [english] is only set for scripted (bank) questions and shown after the interview. */
data class InterviewerLine(val japanese: String, val english: String, val phase: OpiPhase, val engine: String?)

data class OpiRating(
    val ilr: IlrLevel?,
    val actfl: String?,
    val functions: Int? = null,
    val accuracy: Int? = null,
    val vocabulary: Int? = null,
    val fluency: Int? = null,
    val rationale: String = "",
    val strengths: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    /** Set when a model produced the rating (AI-generated badge). */
    val engine: String? = null,
    /** No model: the learner rates themselves against the ILR checklist instead. */
    val needsSelfRating: Boolean = false,
)

/**
 * OPI-style practice interview (BRIEF §5.11): warm-up → level checks → probes → role-play → wind-down, adapting the
 * working level up on strong answers and down on breakdown. With a model the interviewer is `opi_interviewer_turn`;
 * without one, questions come from the practice pack's scripted banks per ILR level (BRIEF §7.3). Unofficial practice.
 *
 * Adaptation is a documented heuristic on answer length (a stand-in for "sustained speech at this level"): an answer
 * at least as long as the next level's typical answer moves the working level up; one under half the current
 * level's typical length counts as breakdown and moves it down.
 */
class OpiSession(
    private val banks: Map<IlrLevel, OpiBank>,
    private val gateway: AiGateway,
    startLevel: IlrLevel = IlrLevel.L1,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
) {
    val startedAt: Instant = clock.now()
    private val history = mutableListOf<Turn>()
    private val lines = mutableListOf<Pair<Speaker, String>>()
    private val asked = mutableSetOf<String>()
    private var turnsInPhase = 0
    private var questionPhase = OpiPhase.WARMUP
    private val interviewerTask = OpiInterviewerTurn { input -> scripted(input.phase) }

    var phase: OpiPhase = OpiPhase.WARMUP
        private set
    var workingLevel: IlrLevel = startLevel.coerceIn(IlrLevel.lowerRange.first(), IlrLevel.lowerRange.last())
        private set
    var finished: Boolean = false
        private set

    /** Transcript (hidden during the interview, shown after). */
    val transcript: List<Pair<Speaker, String>> get() = lines.toList()
    private val englishForScripted = mutableMapOf<String, String>()

    /** The interviewer's next line, or null once the interview is over. */
    suspend fun next(): InterviewerLine? {
        if (finished) return null
        val input = OpiInterviewerTurn.Input(phase, actflFor(workingLevel), history.toList(), turnsInPhase)
        val (text, english, next, engine) = when (val r = gateway.run(interviewerTask, input)) {
            is AiResult.Ok -> Quad(r.value.utterance, "", r.value.nextPhase, r.engine)
            is AiResult.Fallback -> Quad(r.value.utterance, englishForScripted[r.value.utterance].orEmpty(), r.value.nextPhase, null)
            is AiResult.Unavailable -> {
                finished = true
                return null
            }
        }
        val line = InterviewerLine(text, english, phase, engine)
        questionPhase = phase
        history += Turn(Speaker.PARTNER, text)
        lines += Speaker.PARTNER to text
        turnsInPhase++
        advance(next)
        return line
    }

    /** Records the candidate's answer (STT transcript or typed) and adapts the working level. */
    fun answer(text: String) {
        val answer = text.trim()
        history += Turn(Speaker.LEARNER, answer)
        lines += Speaker.LEARNER to answer
        // Judge the answer by the phase of the question it answers (the phase may already have moved on).
        if (questionPhase == OpiPhase.LEVEL_CHECK || questionPhase == OpiPhase.PROBE) adapt(answer)
    }

    /** Ends early (the learner stops); the rating uses what was said. */
    fun stop() {
        finished = true
    }

    suspend fun rate(): OpiRating {
        finished = true
        if (history.none { it.speaker == Speaker.LEARNER }) return OpiRating(null, null, needsSelfRating = true)
        return when (val r = gateway.run(OpiRate(), OpiRate.Input(history.toList()))) {
            is AiResult.Ok -> r.value.let {
                OpiRating(ilrFor(it.level), it.level, it.functions, it.accuracy, it.vocabulary, it.fluency, it.rationale, it.strengths, it.nextSteps, r.engine)
            }
            else -> OpiRating(null, null, needsSelfRating = true)
        }
    }

    /** Checklist for self-rating, lowest level first: (level, statement). */
    fun checklist(): List<Pair<IlrLevel, String>> =
        IlrLevel.lowerRange.flatMap { level -> banks[level]?.checklist.orEmpty().map { level to it } }

    /** Self-rating: the highest level whose statements (and every lower level's) are all checked. */
    fun selfRate(checked: Set<String>): OpiRating {
        var level: IlrLevel? = null
        for (l in IlrLevel.lowerRange) {
            val statements = banks[l]?.checklist.orEmpty()
            if (statements.isEmpty()) continue
            if (statements.all { it in checked }) level = l else break
        }
        return OpiRating(level, level?.let(::actflFor), rationale = "Self-rated against the ILR speaking descriptors.")
    }

    private fun advance(suggested: OpiPhase) {
        val limit = PLAN.getValue(phase)
        val target = when {
            suggested > phase -> suggested
            turnsInPhase >= limit -> OpiPhase.entries.getOrNull(phase.ordinal + 1)
            else -> phase
        }
        when {
            target == null -> finished = true
            target != phase -> {
                phase = target
                turnsInPhase = 0
            }
        }
        if (phase == OpiPhase.WINDDOWN && turnsInPhase >= PLAN.getValue(OpiPhase.WINDDOWN)) finished = true
    }

    private fun adapt(answer: String) {
        val length = answer.count { !it.isWhitespace() }
        val index = IlrLevel.lowerRange.indexOf(workingLevel)
        val up = IlrLevel.lowerRange.getOrNull(index + 1)
        workingLevel = when {
            up != null && length >= TYPICAL_LENGTH.getValue(up) -> up
            length * 2 < TYPICAL_LENGTH.getValue(workingLevel) && index > 0 -> IlrLevel.lowerRange[index - 1]
            else -> workingLevel
        }
    }

    /** Scripted fallback: an unasked bank question for the phase at the working level (probes aim one level up). */
    private fun scripted(phase: OpiPhase): OpiInterviewerTurn.Output? {
        val bankPhase = BANK_PHASE.getValue(phase)
        val index = IlrLevel.lowerRange.indexOf(workingLevel)
        val aim = if (phase == OpiPhase.PROBE) (index + 1).coerceAtMost(IlrLevel.lowerRange.lastIndex) else index
        val order = IlrLevel.lowerRange.indices.sortedBy { kotlin.math.abs(it - aim) }.map { IlrLevel.lowerRange[it] }
        val question: OpiQuestion = order.firstNotNullOfOrNull { level ->
            banks[level]?.phase(bankPhase)?.filter { it.promptJa !in asked }?.takeIf { it.isNotEmpty() }?.random(random)
        } ?: return null
        asked += question.promptJa
        englishForScripted[question.promptJa] = question.promptEn
        val next = if (turnsInPhase + 1 >= PLAN.getValue(phase)) OpiPhase.entries.getOrElse(phase.ordinal + 1) { phase } else phase
        return OpiInterviewerTurn.Output(question.promptJa, next, question.note)
    }

    private data class Quad(val text: String, val english: String, val next: OpiPhase, val engine: String?)

    companion object {
        /** Planned turns per phase (a 15–30 minute interview). */
        val PLAN = mapOf(
            OpiPhase.WARMUP to 2,
            OpiPhase.LEVEL_CHECK to 3,
            OpiPhase.PROBE to 3,
            OpiPhase.ROLEPLAY to 2,
            OpiPhase.WINDDOWN to 1,
        )

        private val BANK_PHASE = mapOf(
            OpiPhase.WARMUP to BankPhase.WARM_UP,
            OpiPhase.LEVEL_CHECK to BankPhase.LEVEL_CHECK,
            OpiPhase.PROBE to BankPhase.PROBE,
            OpiPhase.ROLEPLAY to BankPhase.ROLE_PLAY,
            OpiPhase.WINDDOWN to BankPhase.WIND_DOWN,
        )

        /** Typical answer length (non-space characters) for sustained speech at each level; the adaptation heuristic. */
        val TYPICAL_LENGTH = mapOf(
            IlrLevel.L0_PLUS to 4,
            IlrLevel.L1 to 10,
            IlrLevel.L1_PLUS to 20,
            IlrLevel.L2 to 35,
            IlrLevel.L2_PLUS to 55,
            IlrLevel.L3 to 80,
        )

        /** ACTFL ↔ ILR crosswalk (ACTFL's published approximate equivalence). */
        private val ACTFL_TO_ILR = mapOf(
            "Novice Low" to IlrLevel.L0,
            "Novice Mid" to IlrLevel.L0_PLUS,
            "Novice High" to IlrLevel.L0_PLUS,
            "Intermediate Low" to IlrLevel.L1,
            "Intermediate Mid" to IlrLevel.L1,
            "Intermediate High" to IlrLevel.L1_PLUS,
            "Advanced Low" to IlrLevel.L2,
            "Advanced Mid" to IlrLevel.L2,
            "Advanced High" to IlrLevel.L2_PLUS,
            "Superior" to IlrLevel.L3,
        )

        fun ilrFor(actfl: String): IlrLevel? = ACTFL_TO_ILR[actfl]

        fun actflFor(ilr: IlrLevel): String = when (ilr) {
            IlrLevel.L0 -> "Novice Low"
            IlrLevel.L0_PLUS -> "Novice High"
            IlrLevel.L1 -> "Intermediate Mid"
            IlrLevel.L1_PLUS -> "Intermediate High"
            IlrLevel.L2 -> "Advanced Mid"
            IlrLevel.L2_PLUS -> "Advanced High"
            else -> "Superior"
        }
    }
}
