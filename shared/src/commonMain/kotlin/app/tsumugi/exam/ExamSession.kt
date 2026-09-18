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
 * Everything needed to rebuild an [ExamSession] after process death (F-24, DECISIONS D-080), stored as JSON in
 * `exam_in_progress.state`. The form is kept as item and passage ids (the content is re-read from the packs and
 * banks). [deadlines] holds each opened timed section's end as absolute epoch ms; null for untimed or unopened
 * sections, so remaining time always comes from the wall clock.
 */
@Serializable
data class ExamProgress(
    val id: String,
    val exam: String,
    val level: String,
    val mode: String,
    val sections: List<SectionRef>,
    val passageIds: List<String>,
    val startedAt: Long,
    val sectionIndex: Int,
    val index: Int,
    val finished: Boolean,
    val deadlines: List<Long?>,
    val choices: Map<String, Int> = emptyMap(),
    val timeMs: Map<String, Long> = emptyMap(),
    val plays: Map<String, Int> = emptyMap(),
) {
    @Serializable
    data class SectionRef(val title: String, val minutes: Int?, val listening: Boolean, val items: List<ItemRef>)

    @Serializable
    data class ItemRef(val id: String, val group: String, val typeTitle: String)

    val answeredCount: Int get() = choices.size
    val totalCount: Int get() = sections.sumOf { it.items.size }
}

/** Where an [ExamSession] writes its progress. [ExamService] provides the database-backed one. */
interface ExamProgressStore {
    fun save(progress: ExamProgress)
    fun clear(id: String)
}

/**
 * A timed exam in progress (BRIEF §5.11). Strict modes (full mock, DLPT full/slices) run each section on its
 * real clock with no pausing; when a section's time runs out it closes and the next opens, and a closed section
 * can't be reopened. Within the open section the learner can move freely, as on paper. Listening audio plays once
 * in strict modes. Drills are untimed and replayable.
 *
 * Section ends are absolute deadlines, so the state follows the wall clock: after the app was away, calling
 * [tick] until it returns false closes every section whose deadline passed, in order, and each next section is
 * taken to have opened at the previous one's deadline (F-24). With a [store], the session saves itself after
 * every answer, move, audio play and section change, and clears itself on [submit]. A new session starts saving
 * at [begin] or its first change, so a form built only for a preview never replaces an unfinished attempt.
 */
class ExamSession(
    val form: ExamForm,
    private val blueprint: LevelBlueprint?,
    private val clock: Clock = Clock.System,
    private val store: ExamProgressStore? = null,
    restored: ExamProgress? = null,
) {
    /** Identifies this attempt's in-progress row. */
    val attemptId: String = restored?.id ?: kotlin.uuid.Uuid.random().toString()
    val startedAt: Instant = restored?.let { Instant.fromEpochMilliseconds(it.startedAt) } ?: clock.now()
    private val choices = mutableMapOf<String, Int>()
    private val timeMs = mutableMapOf<String, Long>()
    private val plays = mutableMapOf<String, Int>()
    private val deadlines: Array<Long?> = arrayOfNulls(form.sections.size)

    // Time away (between the last save and a resume) isn't charged to any item.
    private var viewingSince: Instant = clock.now()
    private var saving: Boolean = restored != null

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

    init {
        if (restored != null) {
            choices += restored.choices
            timeMs += restored.timeMs
            plays += restored.plays
            restored.deadlines.forEachIndexed { i, d -> if (i < deadlines.size) deadlines[i] = d }
            sectionIndex = restored.sectionIndex.coerceIn(0, (form.sections.size - 1).coerceAtLeast(0))
            index = restored.index.coerceIn(0, ((form.sections.getOrNull(sectionIndex)?.items?.size ?: 1) - 1).coerceAtLeast(0))
            finished = restored.finished || form.isEmpty
            if (!finished && timed(sectionIndex) && deadlines[sectionIndex] == null) open(sectionIndex, clock.now())
        } else if (!finished) {
            open(0, startedAt)
        }
    }

    /**
     * Starts saving this attempt now (it replaces any other unfinished attempt on the device). Call when the learner
     * presses Start; otherwise the first answer or move does it.
     */
    fun begin() {
        if (!finished) persist()
    }

    /** The open section's end as epoch ms, or null when untimed. */
    val sectionDeadlineMs: Long? get() = if (finished) null else deadlines.getOrNull(sectionIndex)

    fun choiceFor(itemId: String): Int? = choices[itemId]

    fun choose(choice: Int) {
        val item = current ?: return
        if (finished || choice !in item.item.choices.indices) return
        choices[item.item.id] = choice
        persist()
    }

    fun goTo(i: Int) {
        val s = section ?: return
        if (finished || i !in s.items.indices) return
        record(clock.now())
        index = i
        persist()
    }

    fun next() = goTo(index + 1)
    fun previous() = goTo(index - 1)

    /** Closes the current section early and opens the next one; finishes after the last. */
    fun nextSection() {
        if (finished) return
        val now = clock.now()
        record(now)
        advance(now)
        persist()
    }

    /** Milliseconds left in the open section, from the wall clock, or null when untimed. */
    fun remainingMs(): Long? {
        val deadline = sectionDeadlineMs ?: return null
        return (deadline - clock.now().toEpochMilliseconds()).coerceAtLeast(0)
    }

    /**
     * Call once a second, and in a loop until it returns false when the app comes back to the foreground. Returns
     * true when the open section's deadline had passed and it closed; the next section opens at that deadline.
     */
    fun tick(): Boolean {
        if (finished) return false
        val deadline = deadlines.getOrNull(sectionIndex) ?: return false
        if (clock.now().toEpochMilliseconds() < deadline) return false
        val end = Instant.fromEpochMilliseconds(deadline)
        record(end)
        advance(end)
        persistIfSaving()
        return true
    }

    fun canPlayAudio(itemId: String): Boolean = !form.mode.strict || (plays[itemId] ?: 0) == 0

    fun audioPlayed(itemId: String) {
        plays[itemId] = (plays[itemId] ?: 0) + 1
        persist()
    }

    /** The state [ExamService.resume] rebuilds the session from. */
    fun progress(): ExamProgress = ExamProgress(
        id = attemptId,
        exam = form.exam.name,
        level = form.level,
        mode = form.mode.name,
        sections = form.sections.map { s ->
            ExamProgress.SectionRef(s.title, s.minutes, s.listening, s.items.map { ExamProgress.ItemRef(it.item.id, it.group, it.typeTitle) })
        },
        passageIds = form.passages.keys.toList(),
        startedAt = startedAt.toEpochMilliseconds(),
        sectionIndex = sectionIndex,
        index = index,
        finished = finished,
        deadlines = deadlines.toList(),
        choices = choices.toMap(),
        timeMs = timeMs.toMap(),
        plays = plays.toMap(),
    )

    private fun timed(section: Int): Boolean =
        form.sections.getOrNull(section)?.minutes != null && (form.mode.strict || form.mode == ExamMode.SECTION)

    private fun open(section: Int, at: Instant) {
        val minutes = form.sections[section].minutes
        deadlines[section] = if (timed(section) && minutes != null) at.toEpochMilliseconds() + minutes * 60_000L else null
    }

    private fun advance(at: Instant) {
        if (sectionIndex + 1 >= form.sections.size) {
            finished = true
        } else {
            sectionIndex++
            index = 0
            open(sectionIndex, at)
        }
    }

    private fun persist() {
        saving = true
        store?.save(progress())
    }

    private fun persistIfSaving() {
        if (saving) store?.save(progress())
    }

    /** Ends the attempt and scores it. Unanswered items count as wrong. */
    fun submit(): ExamResult {
        record(clock.now())
        finished = true
        if (saving) store?.clear(attemptId)
        val answers = form.items.map { f ->
            val choice = choices[f.item.id]
            AnswerRecord(f.item.id, choice, choice == f.item.answer, timeMs[f.item.id] ?: 0)
        }
        val (scoring, summary) = score(answers)
        return ExamResult(form, startedAt, clock.now(), answers, scoring, summary)
    }

    private fun record(until: Instant) {
        val spent = (until - viewingSince).inWholeMilliseconds
        current?.let { if (spent > 0) timeMs[it.item.id] = (timeMs[it.item.id] ?: 0) + spent }
        if (until > viewingSince) viewingSince = until
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
