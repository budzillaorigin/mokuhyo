package app.tsumugi.exam

import app.tsumugi.exam.dlpt.IlrEstimator
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.jlpt.JlptScoring
import app.tsumugi.exam.jlpt.LevelBlueprint
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class AnswerRecord(val itemId: String, val choice: Int?, val correct: Boolean, val timeMs: Long)

/** What gets stored in `exam_attempt.scoring` (JSON) and shown on the result screen. */
@Serializable
data class AttemptScoring(
    val groups: List<GroupRecord> = emptyList(),
    val total: Int? = null,
    val totalMax: Int? = null,
    val passMark: Int? = null,
    val passed: Boolean? = null,
    val complete: Boolean = true,
    val ilr: String? = null,
    val ilrProvisional: String? = null,
    val ilrConfident: Boolean = false,
    val byType: List<Tally> = emptyList(),
    val byLevel: List<Tally> = emptyList(),
    val meanTimeMs: Long = 0,
    val weakAreas: List<String> = emptyList(),
) {
    @Serializable
    data class GroupRecord(val group: String, val scaled: Int, val scaledMax: Int, val minimum: Int, val correct: Int, val administered: Int, val metMinimum: Boolean)

    @Serializable
    data class Tally(val key: String, val correct: Int, val total: Int)
}

data class ExamResult(
    val form: ExamForm,
    val startedAt: Instant,
    val submittedAt: Instant,
    val answers: List<AnswerRecord>,
    val scoring: AttemptScoring,
    val summary: String,
) {
    /** Grammar ("g:") and vocabulary ("v:") refs from items answered wrong, for "add to SRS". */
    val missedRefs: List<String> get() {
        val wrong = answers.filter { !it.correct }.map { it.itemId }.toSet()
        return form.items.filter { it.item.id in wrong }.flatMap { it.item.refs }.distinct()
    }
}

/**
 * A timed exam in progress (BRIEF §5.11). Strict modes (full mock, DLPT full/slices) run each section on its
 * real clock with no pausing; when a section's time runs out it closes and the next opens, and a closed section
 * can't be reopened. Within the open section the learner can move freely, as on paper. Listening audio plays once
 * in strict modes. Drills are untimed and replayable.
 */
class ExamSession(
    val form: ExamForm,
    private val blueprint: LevelBlueprint?,
    private val clock: Clock = Clock.System,
) {
    val startedAt: Instant = clock.now()
    private val choices = mutableMapOf<String, Int>()
    private val timeMs = mutableMapOf<String, Long>()
    private val plays = mutableMapOf<String, Int>()
    private var viewingSince: Instant = startedAt
    private var sectionStartedAt: Instant = startedAt

    var sectionIndex: Int = 0
        private set
    var index: Int = 0
        private set
    var finished: Boolean = form.isEmpty
        private set

    val section: FormSection? get() = form.sections.getOrNull(sectionIndex)
    val current: FormItem? get() = section?.items?.getOrNull(index)
    val passage: ExamPassage? get() = current?.item?.passageId?.let { form.passages[it] }
    val answeredCount: Int get() = choices.size
    val totalCount: Int get() = form.items.size

    fun choiceFor(itemId: String): Int? = choices[itemId]

    fun choose(choice: Int) {
        val item = current ?: return
        if (finished || choice !in item.item.choices.indices) return
        choices[item.item.id] = choice
    }

    fun goTo(i: Int) {
        val s = section ?: return
        if (finished || i !in s.items.indices) return
        record()
        index = i
    }

    fun next() = goTo(index + 1)
    fun previous() = goTo(index - 1)

    /** Closes the current section (early, or because time ran out) and opens the next one; finishes after the last. */
    fun nextSection() {
        if (finished) return
        record()
        if (sectionIndex + 1 >= form.sections.size) {
            finished = true
        } else {
            sectionIndex++
            index = 0
            sectionStartedAt = clock.now()
        }
    }

    /** Milliseconds left in the open section, or null when untimed. */
    fun remainingMs(): Long? {
        val minutes = section?.minutes ?: return null
        if (!form.mode.strict && form.mode != ExamMode.SECTION) return null
        val elapsed = (clock.now() - sectionStartedAt).inWholeMilliseconds
        return (minutes * 60_000L - elapsed).coerceAtLeast(0)
    }

    /** Call once a second from the UI; returns true when time ran out and the section moved on. */
    fun tick(): Boolean {
        if (finished) return false
        if (remainingMs() == 0L) {
            nextSection()
            return true
        }
        return false
    }

    fun canPlayAudio(itemId: String): Boolean = !form.mode.strict || (plays[itemId] ?: 0) == 0

    fun audioPlayed(itemId: String) {
        plays[itemId] = (plays[itemId] ?: 0) + 1
    }

    /** Ends the attempt and scores it. Unanswered items count as wrong. */
    fun submit(): ExamResult {
        record()
        finished = true
        val answers = form.items.map { f ->
            val choice = choices[f.item.id]
            AnswerRecord(f.item.id, choice, choice == f.item.answer, timeMs[f.item.id] ?: 0)
        }
        val (scoring, summary) = score(answers)
        return ExamResult(form, startedAt, clock.now(), answers, scoring, summary)
    }

    private fun record() {
        val now = clock.now()
        current?.let { timeMs[it.item.id] = (timeMs[it.item.id] ?: 0) + (now - viewingSince).inWholeMilliseconds }
        viewingSince = now
    }

    private fun score(answers: List<AnswerRecord>): Pair<AttemptScoring, String> {
        val byId = form.items.associateBy { it.item.id }
        val typeTallies = answers.groupBy { byId.getValue(it.itemId).item.type }
            .map { (type, list) -> AttemptScoring.Tally(type, list.count { it.correct }, list.size) }
        val correct = answers.count { it.correct }
        return if (form.exam == ExamKind.JLPT && blueprint != null) {
            val score = JlptScoring.score(blueprint, answers.map { a -> byId.getValue(a.itemId).let { JlptScoring.Answer(it.item.type, it.group, a.correct) } })
            val scoring = AttemptScoring(
                groups = score.groups.map { AttemptScoring.GroupRecord(it.group, it.scaled, it.scaledMax, it.minimum, it.correct, it.administered, it.metMinimum) },
                total = score.total,
                totalMax = score.groups.sumOf { it.scaledMax },
                passMark = score.passMark,
                passed = score.passed,
                complete = score.complete,
                byType = score.byType.map { AttemptScoring.Tally(it.type, it.correct, it.total) },
                meanTimeMs = meanTime(answers),
                weakAreas = score.byType.filter { it.total >= 2 && it.accuracy < 0.6 }.map { it.type },
            )
            val summary = if (score.complete) {
                "${form.level} · ${score.total}/180 · ${if (score.passed) "pass" else "not yet"}"
            } else {
                "${form.level} · ${form.mode.title} · $correct/${answers.size}"
            }
            scoring to summary
        } else {
            val results = answers.mapNotNull { a ->
                val f = byId.getValue(a.itemId)
                val level = IlrLevel.parse(f.item.level) ?: return@mapNotNull null
                IlrEstimator.Result(level, a.correct, form.passages[f.item.passageId]?.textType ?: f.item.type, a.timeMs)
            }
            // Slices carry only a few items per level; let them count toward a (provisional) estimate.
            val perLevel = results.size / results.map { it.level }.distinct().size.coerceAtLeast(1)
            val estimate = IlrEstimator.estimate(results, minPerLevel = perLevel.coerceIn(2, IlrEstimator.MIN_PER_LEVEL))
            val scoring = AttemptScoring(
                ilr = estimate.level?.label,
                ilrProvisional = estimate.provisional?.label,
                ilrConfident = estimate.confident,
                byType = typeTallies,
                byLevel = estimate.byLevel.map { AttemptScoring.Tally(it.key, it.correct, it.total) },
                meanTimeMs = estimate.meanTimeMs,
                weakAreas = estimate.weakTextTypes,
            )
            val ilr = when {
                estimate.level != null -> "ILR ${estimate.level.label}" + if (estimate.confident) "" else " (low confidence)"
                estimate.provisional != null -> "≈ ILR ${estimate.provisional.label} (provisional)"
                else -> "below ILR ${IlrLevel.lowerRange.first().label}"
            }
            scoring to "${form.exam.title} · $ilr · $correct/${answers.size}"
        }
    }

    private fun meanTime(answers: List<AnswerRecord>): Long = if (answers.isEmpty()) 0 else answers.sumOf { it.timeMs } / answers.size
}
