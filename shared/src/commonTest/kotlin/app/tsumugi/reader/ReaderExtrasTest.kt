package app.tsumugi.reader

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.FakeModel
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.Stage
import app.tsumugi.jp.PitchPattern
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderExtrasTest {
    private val dictionary = DictionaryRepository(DictionaryFixture.create())
    private var stages = mapOf("jmdict:${DictionaryFixture.GAKKOU}" to Stage.GURU, "v:${DictionaryFixture.NEKO}" to Stage.APPRENTICE)
    private val analyzer = ReaderAnalyzer(dictionary, { stages })
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))

    @Test
    fun furiganaOnlyAboveTheLearnersLevel() = runTest {
        val tokens = analyzer.page("学校の猫が寿司を食べた。", 0, 1).single().sentences.single().tokens
        val gakkou = tokens.first { it.surface == "学校" }
        val neko = tokens.first { it.surface == "猫" }
        val sushi = tokens.first { it.surface == "寿司" }

        // An N5 learner who knows the kanji 猫: the N5 word 猫 needs no ruby, 寿司 (on no list) does.
        val n5 = LearnerLevel(5, setOf("猫"))
        assertFalse(LearnerFurigana.show(gakkou, n5), "known word (Guru)")
        assertFalse(LearnerFurigana.show(neko, n5), "at level, kanji known")
        assertTrue(LearnerFurigana.show(sushi, n5), "not on a JLPT list")
        assertFalse(LearnerFurigana.show(tokens.first { !it.hasKanji && it.surface.isNotBlank() }, n5), "kana")

        // Same learner without the kanji 猫: ruby stays until the kanji is learned.
        assertTrue(LearnerFurigana.show(neko, LearnerLevel(5, emptySet())))
        // Unknown level: only known words hide.
        assertTrue(neko.showFurigana(FuriganaMode.UNKNOWN_ONLY, LearnerLevel.UNKNOWN))
        assertFalse(neko.showFurigana(FuriganaMode.NONE, n5))

        assertEquals(setOf("語"), LearnerLevel.knownKanji(mapOf("k:語" to Stage.GURU, "k:水" to Stage.APPRENTICE, "v:1" to Stage.MASTER)))
        assertEquals(5, LearnerLevel.jlptFromPathLevel(3))
        assertEquals(3, LearnerLevel.jlptFromPathLevel(25))
        assertEquals(1, LearnerLevel.jlptFromPathLevel(60))
    }

    @Test
    fun pitchOverlayFromTheDictionaryPitchTable() = runTest {
        val tokens = analyzer.page("猫が食べた。", 0, 1).single().sentences.single().tokens
        val overlay = ReaderPitch.overlay(tokens) { w, r -> dictionary.pitchAccents(w, r) }
        val neko = overlay.first { it.start == 0 }
        assertEquals(1, neko.downstep)
        assertEquals(PitchPattern.ATAMADAKA, neko.pattern)
        assertEquals(listOf("ね", "こ"), neko.morae)
        assertEquals(listOf(true, false, false), neko.heights)
        val tabeta = overlay.first { it.reading == "たべた" }
        assertNull(tabeta.downstep, "inflected: unknown rather than a guess")
        assertTrue(tabeta.heights.isEmpty())
    }

    @Test
    fun aozoraRubyIsPersistedAndAuthoritative() = runTest {
        val repo = ReaderRepository(db, clock)
        val text = AozoraImporter.parseText("猫の話\n作者\n\n猫《ねこま》が居た。\n")
        val id = repo.save(text)
        val doc = repo.document(id)!!
        assertEquals(text.ruby, doc.ruby)
        assertTrue(doc.ruby.any { it.base == "猫" && it.reading == "ねこま" })

        val neko = analyzer.page(doc.body, 0, 1, doc.ruby).single().sentences.single().tokens.first { it.surface == "猫" }
        assertEquals("ねこま", neko.reading, "the source's reading wins over the dictionary's")
        assertTrue(neko.readingFromSource)
        // Without the hints the dictionary reading is used.
        val plain = analyzer.page(doc.body, 0, 1).single().sentences.single().tokens.first { it.surface == "猫" }
        assertEquals("ねこ", plain.reading)
        assertFalse(plain.readingFromSource)
    }

    @Test
    fun comprehensionQuestionsAreGeneratedOnceAndLabeled() = runTest {
        val repo = ReaderRepository(db, clock)
        val id = repo.save(TextImporter.import("今日は晴れです。公園へ行きます。"))
        val doc = repo.document(id)!!
        val reply = """{"questions":[{"question":"今日の天気はどうですか。","choices":["晴れ","雨","雪","くもり"],"answer":0,"explanation":"The first sentence says it is sunny."}]}"""
        val model = FakeModel(reply)
        val service = ReadingQuestionService(db, { AiGateway({ model }) }, clock)

        val first = assertIs<ReadingQuestionsResult.Ready>(service.questions(doc, "N5", count = 1)).value
        assertEquals("llm", first.source)
        assertFalse(first.fromCache)
        assertEquals("晴れ", first.questions.single().choices[first.questions.single().answer])

        val again = assertIs<ReadingQuestionsResult.Ready>(service.questions(doc, "N5", count = 1)).value
        assertTrue(again.fromCache)
        assertEquals(1, model.requests.size, "cached per document")

        // Re-importing the document replaces its text, so the cache is dropped.
        repo.save(TextImporter.import("今日は雨です。"))
        assertEquals(1, service.cached(id)!!.questions.size, "a different paste is a different document")

        val noModel = ReadingQuestionService(db, { AiGateway({ null }) }, clock)
        val other = repo.document(repo.save(TextImporter.import("明日は雪です。")))!!
        assertIs<ReadingQuestionsResult.Unavailable>(noModel.questions(other))
    }

    @Test
    fun passagesForTheModelAreCutAtAParagraph() {
        val body = "あ".repeat(900) + "\n" + "い".repeat(900)
        assertEquals("あ".repeat(900), ReadingQuestionService.passageFor(body))
        assertEquals("短い。", ReadingQuestionService.passageFor("短い。"))
    }

    @Test
    fun gradedPassagePacksStartEmptyAndOpenAsPackDocuments() = runTest {
        assertTrue(EmptyReaderPacks.packs().isEmpty())
        assertNull(EmptyReaderPacks.passage("x"))
        val passage = GradedPassage(
            GradedPassageSummary("p1", "graded-n5", "ねこ", 5, "0+", 10, "llm"), "猫がいます。", listOf(RubyHint(0, "猫", "ねこ")), null,
        )
        val text = passage.toImportedText()
        assertEquals(SourceKind.PACK, text.kind)
        assertEquals("pack://graded-n5/p1", text.sourceUrl)
        val repo = ReaderRepository(db, clock)
        val id = repo.save(text)
        assertEquals(id, repo.save(text), "reopening reuses the document")
        assertEquals(passage.ruby, repo.document(id)!!.ruby)
    }
}
