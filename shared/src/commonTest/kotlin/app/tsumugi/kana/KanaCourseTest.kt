package app.tsumugi.kana

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.jp.Kana
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.AnswerChecker
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KanaCourseTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "dev", clock)
    private val settings = SettingsRepository(db, clock)
    private val course = KanaCourse(srs)

    @Test
    fun theTableHasEveryKanaOnceWithRomaji() {
        for (script in KanaScript.entries) {
            val chars = KanaTable.chars(script)
            assertEquals(46, chars.count { it.group == KanaGroup.BASE }, "$script base")
            assertEquals(20, chars.count { it.group == KanaGroup.DAKUTEN })
            assertEquals(5, chars.count { it.group == KanaGroup.HANDAKUTEN })
            assertEquals(33, chars.count { it.group == KanaGroup.YOON })
            assertEquals(chars.size, chars.map { it.kana }.toSet().size, "no duplicates")
            assertTrue(chars.all { it.romaji.isNotEmpty() && it.romaji.all { r -> r.all { c -> c in 'a'..'z' } } })
            assertEquals(15, KanaTable.lessons(script).size)
        }
        assertTrue(KanaTable.chars(KanaScript.KATAKANA).all { c -> c.kana.all { Kana.isKatakana(it) } })
        assertTrue(KanaTable.chars(KanaScript.HIRAGANA).all { c -> c.kana.all { Kana.isHiragana(it) } })
        assertEquals(listOf("shi", "si"), KanaTable.byKana("し")!!.romaji)
        assertEquals(listOf("kya"), KanaTable.byKana("きゃ")!!.romaji)
        assertEquals(listOf("ja", "zya"), KanaTable.byKana("ジャ")!!.romaji)
        assertEquals(listOf("pa"), KanaTable.byKana("パ")!!.romaji)
    }

    @Test
    fun everyBaseKanaHasAnOriginalMnemonicMarkedAiGenerated() {
        val base = KanaTable.all.filter { it.group == KanaGroup.BASE }
        assertEquals(92, base.size)
        assertTrue(base.all { it.mnemonic.length in 20..160 }, base.filter { it.mnemonic.length !in 20..160 }.map { it.kana }.toString())
        assertTrue(KanaTable.all.all { it.mnemonicSource == "llm" || it.kana in REVIEWED_KANA_MNEMONICS })
        assertTrue(course.lessons().all { it.introSource == "llm" && it.intro.isNotBlank() })
        // Voiced kana explain the mark on the kana learned before.
        assertTrue(KanaTable.byKana("が")!!.mnemonic.contains("か"))
        assertTrue(KanaTable.byKana("ポ")!!.mnemonic.contains("ホ"))
        assertTrue(KanaTable.byKana("しゃ")!!.mnemonic.contains("し"))
    }

    @Test
    fun theCourseIsNeededOnlyAfterAZeroKanjiCheckOrEnrolling() = runTest {
        assertFalse(course.needed(settings), "no check recorded")
        KanaCourse.recordKanjiCheck(settings, 4)
        assertFalse(course.needed(settings))
        KanaCourse.recordKanjiCheck(settings, 0)
        assertTrue(course.needed(settings))

        // Passing both placement checks skips the course.
        val hira = course.placementQuestions(KanaScript.HIRAGANA, seed = 1)
        assertEquals(10, hira.size)
        val passed = course.gradePlacement(KanaScript.HIRAGANA, hira.map { KanaPlacementAnswer(it, it.romaji.last().uppercase()) }, settings)
        assertTrue(passed.passed)
        assertTrue(course.needed(settings), "katakana still to do")
        assertTrue(course.lessonQueue(settings).single().id.startsWith("kata-"))
        val kata = course.placementQuestions(KanaScript.KATAKANA, seed = 2)
        val failed = course.gradePlacement(KanaScript.KATAKANA, kata.mapIndexed { i, c -> KanaPlacementAnswer(c, if (i < 8) c.romaji.first() else "x") }, settings)
        assertFalse(failed.passed, "8/10 is below 90%")
        course.setSkipped(KanaScript.KATAKANA, true, settings)
        assertFalse(course.needed(settings))
        assertTrue(course.lessonQueue(settings).isEmpty())
    }

    @Test
    fun finishingALessonPutsItsKanaIntoSrs() = runTest {
        course.enroll(settings)
        assertTrue(course.needed(settings))
        val first = course.lessonQueue(settings, limit = 2)
        assertEquals(listOf("hira-1", "hira-2"), first.map { it.id })
        assertEquals("あいうえお", first[0].chars.joinToString("") { it.kana })

        val reviewIds = course.completeLesson(first[0], settings)
        assertEquals(5, reviewIds.size)
        val item = srs.item("kana:あ")!!
        assertEquals(ItemKind.KANA, item.kind)
        assertEquals(listOf("a"), item.meanings)
        assertEquals(0, item.level)
        val card = srs.card(SrsRepository.cardId("kana:あ", CardDirection.MEANING))
        assertNotNull(card)
        assertTrue(card.fsrs.state.name != "NEW", "introduced")
        assertEquals("hira-2", course.lessonQueue(settings).single().id)
        assertEquals(1, course.status(settings).hiraganaLessonsDone)
        // Idempotent.
        assertTrue(course.completeLesson(first[0], settings).isEmpty())

        // The MEANING card is answered with romaji, alternatives included.
        assertTrue(AnswerChecker.checkMeaning("si", KanaTable.byKana("し")!!.romaji).accepted)
        assertFalse(AnswerChecker.checkMeaning("su", KanaTable.byKana("し")!!.romaji).accepted)
    }

    @Test
    fun strokePracticeUsesTheDictionaryStrokes() = runTest {
        val kya = KanaTable.byKana("きゃ")!!
        val data = course.strokeData(kya) { c -> listOf("M0,0 L$c") }
        assertEquals(listOf("き", "ゃ"), data.map { it.first })
        assertEquals("M0,0 Lき", data[0].second.single())
    }

    @Test
    fun writingCardsAreOptional() = runTest {
        val withWriting = KanaCourse(srs) { true }
        withWriting.completeLesson(withWriting.lessons().first { it.id == "kata-1" }, settings)
        assertNotNull(srs.card(SrsRepository.cardId("kana:ア", CardDirection.WRITING)))
    }
}
