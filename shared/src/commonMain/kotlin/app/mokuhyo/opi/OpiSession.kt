package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.exam.IlrLevel
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** An interviewer line; [english] is shown after the interview. [engine] is null for scripted (bank) questions. */
data class InterviewerLine(
    val text: String, val english: String, val phase: OpiPhase, val engine: String?, val domain: String?,
    /** Why the model's question wasn't used (scripted fallback), for diagnostics. */
    val fallbackReason: String? = null,
)

/** The rating shown after an interview (BRIEF §6.2). */
@Serializable
data class OpiRating(
    val estimate: String?,
    val sustained: String? = null,
    val breakdown: String? = null,
    val factors: Map<String, OpiRate.Factor> = emptyMap(),
    val rationale: String = "",
    val nextSteps: List<String> = emptyList(),
    /** Set when a model produced the rating (AI-generated badge). */
    val engine: String? = null,
    /** No model: the learner rates themselves against the ILR checklist instead. */
    val selfRated: Boolean = false,
    val needsSelfRating: Boolean = false,
    /** Why the model's rating was not available (diagnostics; shown in the self-rating prompt). */
    val failure: String? = null,
)

/** "Cultural appropriateness" for the OPI debrief (BRIEF_PHASE8 C-06): not part of the ILR scale. */
@Serializable
data class CulturalReview(val flags: List<OpiCulturalReview.TurnFlag>, val summary: String, val engine: String? = null)

/** Answers judged at a level hold up (SUSTAINED), partly (PARTIAL) or not (BREAKDOWN); others aren't rated. */
enum class OpiTurnOutcome { SUSTAINED, PARTIAL, BREAKDOWN, NOT_RATED }

@Serializable
data class OpiTurnRecord(
    val index: Int,
    val phase: OpiPhase,
    val question: String,
    val english: String = "",
    val domain: String? = null,
    val targetLevel: IlrLevel,
    val levelBefore: IlrLevel,
    val answer: String? = null,
    val answerWords: Int? = null,
    val levelAfter: IlrLevel? = null,
    val outcome: OpiTurnOutcome = OpiTurnOutcome.NOT_RATED,
    val engine: String? = null,
) {
    val rated: Boolean get() = outcome != OpiTurnOutcome.NOT_RATED
}

/**
 * Practice OPI (BRIEF §6.2): warm-up → level checks → probes → role-play → wind-down, adapting the working level up
 * on sustained answers and down on breakdown. With a model, `opi_interviewer_turn` writes each question in the
 * language; without one, the language's scripted bank (pack `opi.json`) supplies them and the learner self-rates.
 *
 * Adaptation is a documented heuristic on answer length in words ([wordCount] uses the language's own segmenter,
 * scaled to English-equivalent words): at least the next level's typical length moves the working level up; under
 * half the current level's moves it down. Test mode (`test = true`) runs the full 20–30 minute plan.
 */
class OpiSession(
    val language: String,
    private val profile: OpiProfile,
    private val bank: List<BankQuestion>,
    private val rolePlays: List<RolePlay>,
    private val gateway: AiGateway,
    private val wordCount: (String) -> Int,
    startLevel: IlrLevel = IlrLevel.L1,
    val test: Boolean = false,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
) {
    val startedAt: Instant = clock.now()
    private val history = mutableListOf<Turn>()
    private val asked = mutableSetOf<String>()
    private val usedDomains = mutableListOf<String>()
    private val records = mutableListOf<OpiTurnRecord>()
    private var turnsInPhase = 0
    private var questionPhase = OpiPhase.WARMUP
    private var rolePlay: RolePlay? = null
    private val plan = if (test) TEST_PLAN else PRACTICE_PLAN

    var phase: OpiPhase = OpiPhase.WARMUP
        private set
    var workingLevel: IlrLevel = startLevel.coerceIn(IlrLevel.lowerRange.first(), IlrLevel.lowerRange.last())
        private set
    var finished: Boolean = false
        private set

    val transcript: List<Turn> get() = history.toList()
    val turns: List<OpiTurnRecord> get() = records.toList()

    /** The interviewer's next line, or null once the interview is over. */
    suspend fun next(): InterviewerLine? {
        if (finished) return null
        if (phase == OpiPhase.ROLEPLAY && rolePlay == null) rolePlay = pickRolePlay()
        val input = OpiInterviewerTurn.Input(
            language, profile.registerNotes, phase, workingLevel, history.toList(), turnsInPhase,
            rolePlay?.let { "${it.situation} You play: ${it.interviewerRole}." }?.takeIf { phase == OpiPhase.ROLEPLAY && turnsInPhase == 0 },
            usedDomains.distinct(),
        )
        val task = OpiInterviewerTurn { scripted(it.phase) }
        var reason: String? = null
        val (out, engine) = when (val r = gateway.run(task, input)) {
            is AiResult.Ok -> r.value to r.engine
            is AiResult.Fallback -> { reason = r.reason; r.value to null }
            is AiResult.Unavailable -> {
                finished = true
                return null
            }
        }
        val line = InterviewerLine(out.utterance, out.english, phase, engine, out.domain.ifBlank { null }, reason)
        records += OpiTurnRecord(records.size, phase, out.utterance, out.english, line.domain, targetFor(phase), workingLevel, engine = engine)
        line.domain?.let { usedDomains += it }
        questionPhase = phase
        history += Turn(Speaker.PARTNER, out.utterance)
        turnsInPhase++
        advance(out.nextPhase)
        return line
    }

    /** Records the candidate's answer (STT transcript or typed) and adapts the working level. */
    fun answer(text: String) {
        val answer = text.trim()
        history += Turn(Speaker.LEARNER, answer)
        val words = wordCount(answer)
        val judged = questionPhase == OpiPhase.LEVEL_CHECK || questionPhase == OpiPhase.PROBE
        if (judged) adapt(words)
        val last = records.lastOrNull()
        if (last != null && last.answer == null) {
            records[records.lastIndex] = last.copy(
                answer = answer, answerWords = words, levelAfter = workingLevel,
                outcome = if (judged && answer.isNotEmpty()) judge(words, last.targetLevel) else OpiTurnOutcome.NOT_RATED,
            )
        }
    }

    fun stop() {
        finished = true
    }

    /** The model's rating; with no model (or a failed one), [OpiRating.needsSelfRating]. */
    suspend fun rate(): OpiRating {
        finished = true
        if (history.none { it.speaker == Speaker.LEARNER && it.text.isNotBlank() }) return OpiRating(null, needsSelfRating = true)
        val input = OpiRate.Input(language, history.toList(), profile.registerNotes)
        return when (val r = gateway.run(OpiRate(), input)) {
            is AiResult.Ok -> OpiRate.normalize(input, r.value).let {
                OpiRating(
                    estimate = it.estimate, sustained = it.sustainedLevel, breakdown = it.breakdownLevel,
                    factors = mapOf("functions" to it.functions, "context_content" to it.contextContent, "accuracy" to it.accuracy, "text_type" to it.textType),
                    rationale = it.rationale, nextSteps = it.nextSteps, engine = r.engine,
                )
            }
            is AiResult.Fallback -> OpiRating(null, needsSelfRating = true, failure = r.reason)
            is AiResult.Unavailable -> OpiRating(null, needsSelfRating = true, failure = r.reason)
        }
    }

    /**
     * The cultural-appropriateness review (BRIEF_PHASE8 C-06): a separate model call over the same transcript. It is never
     * an input to [rate] and the rating never reads it, so pragmatic flags cannot change the ILR estimate.
     */
    suspend fun culturalReview(culturalNotes: List<String>): CulturalReview? {
        if (history.none { it.speaker == Speaker.LEARNER && it.text.isNotBlank() }) return null
        return when (val r = gateway.run(OpiCulturalReview(), OpiCulturalReview.Input(language, history.toList(), profile.registerNotes, culturalNotes))) {
            is AiResult.Ok -> CulturalReview(r.value.flags, r.value.summary, r.engine)
            else -> null
        }
    }

    /** Self-rating against the ILR checklist: the highest level whose statements (and every lower level's) are all checked. */
    fun selfRate(checklist: Map<String, List<String>>, checked: Set<String>): OpiRating {
        var level: IlrLevel? = null
        for (l in IlrLevel.lowerRange) {
            val statements = checklist[l.label].orEmpty()
            if (statements.isEmpty()) continue
            if (statements.all { it in checked }) level = l else break
        }
        return OpiRating(level?.label, rationale = "Self-rated against the ILR speaking descriptions.", selfRated = true)
    }

    private fun targetFor(phase: OpiPhase): IlrLevel {
        if (phase != OpiPhase.PROBE) return workingLevel
        val index = IlrLevel.lowerRange.indexOf(workingLevel)
        return IlrLevel.lowerRange[(index + 1).coerceAtMost(IlrLevel.lowerRange.lastIndex)]
    }

    private fun advance(suggested: OpiPhase) {
        val limit = plan.getValue(phase)
        // Tests keep to the plan so every candidate gets a full-length interview; practice may move on early.
        val target = when {
            !test && suggested > phase && turnsInPhase >= 1 -> suggested
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
        if (phase == OpiPhase.WINDDOWN && turnsInPhase >= plan.getValue(OpiPhase.WINDDOWN)) finished = true
    }

    private fun adapt(words: Int) {
        val index = IlrLevel.lowerRange.indexOf(workingLevel)
        val up = IlrLevel.lowerRange.getOrNull(index + 1)
        workingLevel = when {
            up != null && words >= TYPICAL_WORDS.getValue(up) -> up
            words * 2 < TYPICAL_WORDS.getValue(workingLevel) && index > 0 -> IlrLevel.lowerRange[index - 1]
            else -> workingLevel
        }
    }

    private fun pickRolePlay(exclude: Set<String> = emptySet()): RolePlay? {
        val aim = IlrLevel.lowerRange.indices.sortedBy { kotlin.math.abs(it - IlrLevel.lowerRange.indexOf(workingLevel)) }.map { IlrLevel.lowerRange[it].label }
        val fresh = rolePlays.filter { it.opening !in exclude }
        return aim.firstNotNullOfOrNull { lv -> fresh.filter { it.level == lv }.randomOrNull(random) } ?: fresh.randomOrNull(random)
    }

    /** Scripted fallback: an unasked bank question for the phase near the working level, in an unused domain if possible. */
    private fun scripted(phase: OpiPhase): OpiInterviewerTurn.Output? {
        if (phase == OpiPhase.ROLEPLAY) {
            // The bank has role-play cards rather than follow-up questions: each role-play turn opens a fresh card.
            val rp = (if (turnsInPhase == 0) rolePlay else null) ?: pickRolePlay(exclude = asked)
            if (rp != null && rp.opening !in asked) {
                rolePlay = rp
                asked += rp.opening
                return OpiInterviewerTurn.Output(rp.opening, rp.english, nextFor(phase), "role-play", "roleplay")
            }
        }
        val index = IlrLevel.lowerRange.indexOf(workingLevel)
        val aim = if (phase == OpiPhase.PROBE) (index + 1).coerceAtMost(IlrLevel.lowerRange.lastIndex) else index
        val order = IlrLevel.lowerRange.indices.sortedBy { kotlin.math.abs(it - aim) }.map { IlrLevel.lowerRange[it].label }
        val wire = phase.wireName
        val q = order.firstNotNullOfOrNull { lv ->
            val fresh = bank.filter { it.phase == wire && it.level == lv && it.prompt !in asked }
            fresh.filter { it.domain == null || it.domain !in usedDomains }.ifEmpty { fresh }.randomOrNull(random)
        } ?: bank.filter { it.phase == wire && it.prompt !in asked }.randomOrNull(random) ?: return null
        asked += q.prompt
        return OpiInterviewerTurn.Output(q.prompt, q.english, nextFor(phase), "", q.domain.orEmpty())
    }

    private fun nextFor(phase: OpiPhase) = if (turnsInPhase + 1 >= plan.getValue(phase)) OpiPhase.entries.getOrElse(phase.ordinal + 1) { phase } else phase

    companion object {
        /** Planned turns per phase: a short practice interview, and the full 20–30 minute test. */
        val PRACTICE_PLAN = mapOf(OpiPhase.WARMUP to 2, OpiPhase.LEVEL_CHECK to 3, OpiPhase.PROBE to 3, OpiPhase.ROLEPLAY to 2, OpiPhase.WINDDOWN to 1)
        val TEST_PLAN = mapOf(OpiPhase.WARMUP to 3, OpiPhase.LEVEL_CHECK to 6, OpiPhase.PROBE to 5, OpiPhase.ROLEPLAY to 3, OpiPhase.WINDDOWN to 2)

        /** Typical answer length (English-equivalent words) for sustained speech at each level; the adaptation heuristic. */
        val TYPICAL_WORDS = mapOf(
            IlrLevel.L0_PLUS to 2, IlrLevel.L1 to 6, IlrLevel.L1_PLUS to 12, IlrLevel.L2 to 22, IlrLevel.L2_PLUS to 35, IlrLevel.L3 to 50,
        )

        fun judge(words: Int, target: IlrLevel): OpiTurnOutcome {
            val typical = TYPICAL_WORDS[target] ?: return OpiTurnOutcome.NOT_RATED
            return when {
                words >= typical -> OpiTurnOutcome.SUSTAINED
                words * 2 >= typical -> OpiTurnOutcome.PARTIAL
                else -> OpiTurnOutcome.BREAKDOWN
            }
        }
    }
}
