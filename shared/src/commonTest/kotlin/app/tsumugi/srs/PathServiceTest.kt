package app.tsumugi.srs

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.path.db.PathDatabase
import app.tsumugi.path.db.Path_item
import app.tsumugi.path.db.Path_prereq
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.study.LessonSession
import app.tsumugi.study.LessonState
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class PathServiceTest {

    private val clock = TestClock()
    private val userDb = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(userDb, "device", clock)
    private val settings = SettingsRepository(userDb, clock)

    /** Tiny path shaped like build_kanji_path.py output. */
    private val pathDb = PathDatabase(inMemoryDriver(PathDatabase.Schema)).also { db ->
        val q = db.pathQueries
        fun item(id: String, kind: String, level: Long, ord: Long, text: String, keyword: String, readings: String) =
            q.insertItem(Path_item(id, kind, level, ord, text, text, keyword, "[\"$keyword\"]", readings, "[]", null, null, null))
        item("r:口", "RADICAL", 1, 1, "口", "mouth", "[]")
        item("r:一", "RADICAL", 1, 2, "一", "one", "[]")
        item("k:口", "KANJI", 1, 3, "口", "mouth", "[\"こう\",\"くち\"]")
        item("k:日", "KANJI", 1, 4, "日", "day", "[\"にち\",\"ひ\"]")
        item("v:1", "VOCAB", 1, 5, "口", "mouth", "[\"くち\"]")
        item("k:本", "KANJI", 2, 1, "本", "book", "[\"ほん\"]")
        q.insertPrereq(Path_prereq("k:口", "r:口"))
        q.insertPrereq(Path_prereq("k:日", "r:口"))
        q.insertPrereq(Path_prereq("k:日", "r:一"))
        q.insertPrereq(Path_prereq("v:1", "k:口"))
    }
    private val path = PathService(pathDb, srs, settings, PathProgressStore(userDb, clock))

    @Test
    fun startsWithRadicalLessons() = runTest {
        val status = path.status()
        assertEquals(1, status.currentLevel)
        assertEquals(2, status.maxLevel)
        assertEquals(listOf("r:口", "r:一"), path.lessonQueue().map { it.id })
        assertEquals(listOf("mouth"), path.item("k:口")!!.meanings)
    }

    @Test
    fun lessonSessionTeachesQuizzesAndIntroducesCards() = runTest {
        val lessons = path.lessonQueue(2)
        val session = LessonSession(path, lessons, Random(3))
        assertIs<LessonState.Presenting>(session.state.value)
        session.nextItem()
        session.nextItem() // past the last item → quiz
        var guard = 0
        while (session.state.value !is LessonState.Complete && guard++ < 20) {
            val q = assertIs<LessonState.Quizzing>(session.state.value).question
            session.submit(q.expected.first())
            session.next()
        }
        assertIs<LessonState.Complete>(session.state.value)
        assertEquals(0, path.lessonQueue().size, "kanji wait for their radicals to reach Guru")
        clock.advance(10.minutes)
        assertEquals(2, srs.dueCount(), "one meaning card per radical")
    }

    @Test
    fun wrongQuizAnswerRepeatsQuestion() = runTest {
        val session = LessonSession(path, path.lessonQueue(1), Random(3))
        session.startQuiz()
        val q = assertIs<LessonState.Quizzing>(session.state.value).question
        session.submit("definitely wrong")
        val feedback = assertIs<LessonState.QuizFeedback>(session.state.value)
        assertTrue(!feedback.correct)
        session.next()
        assertEquals(q, assertIs<LessonState.Quizzing>(session.state.value).question)
    }

    @Test
    fun skipLevelAndManualUnlock() = runTest {
        path.skipToLevel(2)
        assertEquals(2, path.status().currentLevel)
        path.unlockManually("k:本")
        assertTrue(path.lessonQueue().any { it.id == "k:本" })
        assertEquals(ItemKind.KANJI, path.item("k:本")!!.kind)
    }
}
