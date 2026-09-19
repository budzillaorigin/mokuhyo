package app.tsumugi.reader

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.audio.AudioKeys
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.exam.ExamService
import app.tsumugi.immersion.ImmersionLog
import app.tsumugi.immersion.MilestoneMeasure
import app.tsumugi.immersion.RoadmapService
import app.tsumugi.readers.db.ReadersDatabase
import app.tsumugi.review.ReadersReviewSource
import app.tsumugi.review.ReviewKind
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathProgressStore
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The graded-reader pack (Phase 12, D-200..D-209) over a hand-built readers.sqlite with the real schema. */
class GradedReadersTest {
    private val clock = TestClock()

    private val body = "ケン「おはよう。」\n今日は雨です。ゆいは傘を持っています。"

    private val packDriver: SqlDriver = inMemoryDriver(ReadersDatabase.Schema).also { d ->
        fun exec(sql: String) = d.execute(null, sql, 0)
        exec("INSERT INTO pack_meta VALUES ('pack_version', '1')")
        exec("INSERT INTO reader_level VALUES ('N6', 0, 'Level 0', 6, '0+', 0, 14, 20, 60, 'en')")
        exec("INSERT INTO reader_level VALUES ('N5', 1, 'N5', 5, '0+', 0, 18, 30, 80, 'en')")
        exec("INSERT INTO reader_level VALUES ('N3', 3, 'N3', 3, '1+', 14, 38, 80, 160, 'ja')")
        exec(
            "INSERT INTO reader_story VALUES ('gr-n6-001', 0, 'N6', 6, 'story', 'weather', '雨の朝', 'A rainy morning', " +
                "'$body', 24, 7, 'N5 · ILR 0+', '0+', 0.98, '[{\"name\":\"ケン\",\"voice\":\"male\"}]', '[]', 20, 'llm', 0)",
        )
        exec(
            "INSERT INTO reader_story VALUES ('gr-n3-001', 0, 'N3', 3, 'news', 'town', '町のニュース', 'Town news', " +
                "'図書館が夜も開くことになった。', 14, 20, 'N3 · ILR 1+', '1+', 0.97, '[]', '[]', 20, 'verified', 1)",
        )
        // Offsets are UTF-16 indices into the body, as build_readers.py writes them.
        exec("INSERT INTO reader_sentence VALUES ('gr-n6-001', 0, 0, 9, 'ケン', 'male')")
        exec("INSERT INTO reader_sentence VALUES ('gr-n6-001', 1, 10, 17, '', 'narration')")
        exec("INSERT INTO reader_sentence VALUES ('gr-n6-001', 2, 17, 29, '', 'narration')")
        exec("INSERT INTO reader_vocab VALUES ('gr-n6-001', 0, 1301940, '傘', 'かさ', 'umbrella')")
        exec("INSERT INTO reader_question VALUES ('gr-n6-001', 0, 'detail', 'en', 'What is the weather?', '[\"Rain\",\"Sun\",\"Snow\"]', 0, 'It says 雨です.')")
        exec("INSERT INTO reader_question VALUES ('gr-n6-001', 1, 'detail', 'en', 'What does Yui have?', '[\"A bag\",\"An umbrella\",\"A cat\"]', 1, 'She has 傘.')")
        exec("INSERT INTO reader_question VALUES ('gr-n3-001', 0, 'gist', 'ja', '何が変わりましたか。', '[\"図書館\",\"駅\",\"店\"]', 0, 'The library opens at night.')")
        for ((genre, jaTitle) in listOf("story" to "「{title}」", "news" to "「{title}」という見出し")) {
            exec("INSERT INTO reader_task VALUES ('$genre', 'PREDICTION', 0, '$jaTitle', 'From \"{title}\", guess.', '{}')")
            exec("INSERT INTO reader_task VALUES ('$genre', 'SKIM', 0, '{seconds}秒で', 'Skim in {seconds} seconds.', '{\"rate\":1.0,\"find\":[\"who\",\"where\"]}')")
            exec("INSERT INTO reader_task VALUES ('$genre', 'CLOSE', 1, '二つ目', 'Second.', '{}')")
            exec("INSERT INTO reader_task VALUES ('$genre', 'CLOSE', 0, '一つ目', 'First.', '{}')")
            exec(
                "INSERT INTO reader_task VALUES ('$genre', 'OUTPUT', 0, '{min}〜{max}字', 'About {min}–{max} characters.', " +
                    "'{\"rubric\":[{\"id\":\"content\",\"en\":\"Main points\",\"ja\":\"要点\"}]}')",
            )
        }
    }
    private val repo = PackReaderRepository { ReadersDatabase(packDriver) }

    private fun userDb() = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))

    @Test
    fun levelsAndStoriesComeFromThePack() = runTest {
        val levels = repo.levels()
        assertEquals(listOf("N6", "N5", "N3"), levels.map { it.level })
        assertEquals(listOf(1, 0, 1), levels.map { it.storyCount })
        assertEquals(6, levels.first().jlpt)

        assertEquals(listOf("gr-n6-001", "gr-n3-001"), repo.passages().map { it.id }, "easiest level first")
        val level0 = repo.passages(jlpt = 6).single()
        assertEquals("N6", level0.level)
        assertEquals("llm", level0.source, "unreviewed stories keep the AI badge")
        assertEquals("verified", repo.passages(jlpt = 3).single().source)
        assertEquals(PackReaderRepository.PACK_ID, repo.packs().single().id)
        assertEquals(2, repo.packs().single().passageCount)
        assertTrue(repo.passages(packId = "other").isEmpty())
    }

    @Test
    fun aStoryOpensWithVocabularyQuestionsTasksAndLines() = runTest {
        val story = assertNotNull(repo.story("gr-n6-001"))
        assertTrue(story.isAiGenerated)
        assertEquals(listOf(CastMember("ケン", "male")), story.cast)
        assertEquals(StoryVocab(1301940, "傘", "かさ", "umbrella"), story.vocabulary.single())
        assertEquals(listOf("gr-n6-001-q1", "gr-n6-001-q2"), story.questions.map { it.id })
        assertEquals(listOf("A bag", "An umbrella", "A cat"), story.questions[1].choices)

        val tasks = story.tasks
        assertEquals("From \"雨の朝\", guess.", tasks.prediction?.prompt, "English prompts below N3")
        assertEquals("Skim in 20 seconds.", tasks.skim?.prompt)
        assertEquals(20, tasks.skim?.seconds)
        assertEquals(listOf("who", "where"), tasks.skim?.find)
        assertEquals(listOf("First.", "Second."), tasks.close.map { it.prompt })
        assertEquals("About 20–60 characters.", tasks.output?.prompt)
        assertEquals(20 to 60, tasks.output?.minChars to tasks.output?.maxChars)
        assertEquals("content", tasks.output?.rubric?.single()?.id)
        assertEquals(5, tasks.all.size)

        assertEquals(listOf("ケン「おはよう。」", "今日は雨です。", "ゆいは傘を持っています。"), story.lines.map { story.text(it) })
        assertEquals(AudioKeys.reader("gr-n6-001", 2), story.lines[2].clipKey)
        assertEquals("ケン", story.lines[0].speaker)

        val n3 = assertNotNull(repo.story("gr-n3-001"))
        assertEquals("「町のニュース」という見出し", n3.tasks.prediction?.prompt, "Japanese prompts from N3")
        assertFalse(n3.isAiGenerated)
        assertNull(repo.story("nope"))
    }

    @Test
    fun aStoryOpensInTheReaderAsAPackDocument() = runTest {
        val passage = assertNotNull(repo.passage("gr-n6-001"))
        val text = passage.toImportedText()
        assertEquals("pack://graded/gr-n6-001", text.sourceUrl)
        assertEquals(body, text.body)
    }

    @Test
    fun withoutThePackEverythingIsEmpty() = runTest {
        val empty = PackReaderRepository { null }
        assertTrue(empty.packs().isEmpty())
        assertTrue(empty.levels().isEmpty())
        assertTrue(empty.passages().isEmpty())
        assertNull(empty.story("gr-n6-001"))
    }

    @Test
    fun readAlongIsTimedOnlyWhenEveryClipIsInstalled() = runTest {
        val story = assertNotNull(repo.story("gr-n6-001"))
        val ms = mapOf(AudioKeys.reader(story.id, 0) to 900L, AudioKeys.reader(story.id, 1) to 1200L, AudioKeys.reader(story.id, 2) to 1500L)
        val track = ReadAlongTrack.of(story) { ms[it] }
        assertTrue(track.timed)
        assertEquals(listOf(0L to 900L, 900L to 2100L, 2100L to 3600L), track.lines.map { it.startMs to it.endMs })
        assertEquals(3600L, track.totalMs)
        assertEquals(1, track.lineAt(950)?.line?.index)
        assertNull(track.lineAt(3600))
        assertEquals(2, track.lineAtOffset(20)?.line?.index)

        val partial = ReadAlongTrack.of(story) { if (it.endsWith("/1")) null else ms[it] }
        assertFalse(partial.timed, "a missing clip means no highlight timing, never a drifting one")
        assertTrue(partial.lines.all { it.startMs == null && it.endMs == null })
        assertNull(partial.lineAt(0))
        assertEquals(0L, partial.totalMs)
        assertFalse(ReadAlongTrack.of(story) { null }.timed)
    }

    @Test
    fun quizzesFeedTheRoadmapComprehensionHook() = runTest {
        val db = userDb()
        val scores = GradedReaderScores(db, { "dev" }, clock)
        val story = assertNotNull(repo.story("gr-n6-001"))
        val n3 = assertNotNull(repo.story("gr-n3-001"))

        val first = scores.submit(story, listOf(0, 0))
        assertEquals(1 to 2, first.correct to first.total)
        assertNull(scores.comprehensionPercent(), "not measured before three stories")

        clock.advance(5.minutes)
        scores.submit(story, listOf(0, 1)) // the retake replaces the first attempt in the score
        clock.advance(5.minutes)
        scores.submit(n3, listOf(0))
        assertEquals(listOf("gr-n3-001", "gr-n6-001"), scores.latestByStory().map { it.storyId })
        assertNull(scores.comprehensionPercent())

        clock.advance(5.minutes)
        scores.submit(story.copy(passage = story.passage.copy(summary = story.passage.summary.copy(id = "gr-n6-002")),
            questions = story.questions.map { it.copy(id = it.id.replace("001", "002")) }), listOf(null, 2))
        assertEquals(60.0, scores.comprehensionPercent(), "3 of 5 on the latest attempt of each story")

        val settings = SettingsRepository(db, clock)
        val roadmap = RoadmapService(
            ImmersionLog(db, "dev", settings, clock) { TimeZone.UTC }, PathProgressStore(db, clock), knownWords = { 0 },
            comprehension = { scores.comprehensionPercent() },
        )
        val milestone = roadmap.status().stages.flatMap { it.milestones }.single { it.measure == MilestoneMeasure.READER_COMPREHENSION }
        assertEquals(60.0, milestone.current)
        assertFalse(milestone.met)
    }

    @Test
    fun readerQuizzesStayOutOfExamHistory() = runTest {
        val db = userDb()
        db.examAttemptQueries.insertAttempt("jlpt-1", "JLPT", "N3", "MOCK", 0, 1, "[]", "{}", "N3 · 100/180", "dev")
        GradedReaderScores(db, { "dev" }, clock).submit(assertNotNull(repo.story("gr-n6-001")), listOf(0, 1))
        val exams = ExamService(null, db, "dev", { null }, { null }, CollectionService(db, SrsRepository(db, "dev"), { null }, clock), clock)
        assertEquals(listOf("jlpt-1"), exams.history().map { it.id })
    }

    @Test
    fun unverifiedStoriesAreOfferedForInAppReview() = runTest {
        val candidates = ReadersReviewSource(packDriver).candidates()
        val c = candidates.single()
        assertEquals(ReviewKind.READER_PASSAGE, c.kind)
        assertEquals("gr-n6-001", c.id)
        assertEquals(setOf("title", "body"), c.fields.keys)
        assertTrue("  * An umbrella" in c.display, c.display)
    }

    @Test
    fun placeholdersAreFilled() {
        assertEquals("30秒で 「x」 {unknown}", PackReaderRepository.fill("{seconds}秒で 「{title}」 {unknown}", mapOf("seconds" to "30", "title" to "x")))
    }
}
