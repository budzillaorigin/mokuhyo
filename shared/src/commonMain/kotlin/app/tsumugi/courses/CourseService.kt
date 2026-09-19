package app.tsumugi.courses

import app.tsumugi.coverage.KnowledgeSnapshot
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.WordState
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.exam.AttemptSummary
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamService
import app.tsumugi.grammar.GrammarService
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathService
import app.tsumugi.study.LearnerLevel

/**
 * Structured JLPT courses (BRIEF_V2 §6.6). It gathers the level's kanji and words (kanji-path items with that JLPT
 * tag), grammar points, exam-bank item types, blueprint sections and the learner's JLPT attempts, then hands them to
 * [CourseBuilder]. Any missing pack just leaves its part empty, so the view shows an honest empty state.
 */
class CourseService(
    private val settings: SettingsRepository,
    val mastery: GrammarMasteryStore,
    private val knowledge: LearnerKnowledge,
    private val path: suspend () -> PathService?,
    private val grammar: suspend () -> GrammarService?,
    private val exams: suspend () -> ExamService?,
) {
    /** The level the course view opens on: the saved choice, else the level matching the kanji-path level. */
    @Throws(Exception::class)
    suspend fun courseLevel(): Int {
        settings.get(SettingsRepository.COURSE_LEVEL)?.toIntOrNull()?.takeIf { it in LEVELS }?.let { return it }
        val pathLevel = path()?.status()?.currentLevel ?: return 5
        return LearnerLevel.jlptForPathLevel(pathLevel)
    }

    @Throws(Exception::class)
    suspend fun setCourseLevel(level: Int) = settings.put(SettingsRepository.COURSE_LEVEL, level.coerceIn(1, 5).toString())

    /** The course for N[level] (1–5). */
    @Throws(Exception::class)
    suspend fun course(level: Int): JlptCourse = CourseBuilder.build(level, input(level, Shared.load(this)))

    /** "One book to pass": what remains for N[level]. */
    @Throws(Exception::class)
    suspend fun remaining(level: Int): LevelRemaining = CourseBuilder.remaining(course(level))

    /** Progress bars for N5 … N1 (easiest first). */
    @Throws(Exception::class)
    suspend fun overview(): List<LevelProgress> {
        val shared = Shared.load(this)
        return LEVELS.sortedDescending().map { LevelProgress(it, CourseBuilder.progress(input(it, shared))) }
    }

    /** Ticks or unticks a grammar point's mastery checkbox (never touches SRS). */
    @Throws(Exception::class)
    suspend fun setMastered(pointId: String, mastered: Boolean) = mastery.setMastered(pointId, mastered)

    @Throws(Exception::class)
    suspend fun masteredIds(): Set<String> = mastery.masteredIds()

    /** Inputs shared by every level, loaded once per call. */
    private class Shared(
        val pathItems: List<PathItem>,
        val snapshot: KnowledgeSnapshot,
        val mastered: Set<String>,
        val exams: ExamService?,
        val attempts: List<AttemptSummary>,
    ) {
        companion object {
            suspend fun load(s: CourseService): Shared {
                val exams = s.exams()
                return Shared(
                    pathItems = s.path()?.items().orEmpty(),
                    snapshot = s.knowledge.snapshot(),
                    mastered = s.mastery.masteredIds(),
                    exams = exams,
                    attempts = exams?.history(ExamKind.JLPT, ATTEMPT_LIMIT).orEmpty(),
                )
            }
        }
    }

    private suspend fun input(level: Int, shared: Shared): CourseInput {
        val items = shared.pathItems.filter { it.jlpt == level }
        val kanji = items.filter { it.kind == ItemKind.KANJI }.map {
            CourseKanji(it.id, it.text, it.keyword, it.level, shared.snapshot.kanji(it.text) == WordState.KNOWN)
        }
        val words = items.filter { it.kind == ItemKind.VOCAB }.map {
            CourseWord(
                it.id, it.text, it.readings.firstOrNull().orEmpty(), it.meanings.firstOrNull() ?: it.keyword, it.entryId, it.level,
                shared.snapshot.word(it.entryId, it.text) == WordState.KNOWN,
            )
        }
        val grammar = grammar()?.points(level).orEmpty().map { s ->
            CourseGrammar(s.point.id, s.point.title, s.point.meaning, s.point.id in shared.mastered, s.stage, s.point.source != ItemSource.VERIFIED)
        }
        val blueprint = shared.exams?.blueprints()?.level(level)
        val coverage = shared.exams?.coverage().orEmpty().firstOrNull { it.exam == ExamKind.JLPT && it.level == "N$level" }
        return CourseInput(
            kanji = kanji,
            words = words,
            grammar = grammar,
            sections = blueprint?.sections.orEmpty().map { s -> CourseSectionSpec(s.id, s.title, s.minutes, s.items.associate { it.type to it.count }) },
            passMark = blueprint?.passMark,
            bankTypes = coverage?.types.orEmpty(),
            typeTitles = blueprint?.sections.orEmpty().flatMap { it.items }.associate { it.type to it.title },
            attempts = shared.attempts.filter { it.level == "N$level" }.map { a ->
                CourseAttempt(a.mode, a.scoring.byType.associate { it.key to (it.correct to it.total) })
            },
        )
    }

    companion object {
        val LEVELS = 1..5
        const val ATTEMPT_LIMIT = 1000
    }
}
