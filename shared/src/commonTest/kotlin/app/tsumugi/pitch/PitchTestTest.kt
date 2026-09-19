package app.tsumugi.pitch

import app.tsumugi.audio.PitchTestItem
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.speaking.PronunciationService
import app.tsumugi.speech.PitchVerdict
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PitchTestTest {

    private fun item(id: String, text: String, reading: String, downstep: Int, group: String = reading, confusable: List<String> = emptyList()): PitchTestItem {
        val morae = app.tsumugi.jp.Mora.count(reading)
        return PitchTestItem(
            id, id.drop(1).toLongOrNull() ?: 0, text, reading, morae, downstep, AccentPattern.of(downstep, morae).code, group,
            "gloss $text", reading + "が", confusableWith = confusable,
        )
    }

    /** Shaped like the pitch pack's items.json (D-094): 箸/橋/端 is one same-kana group. */
    private val items = listOf(
        item("p1", "箸", "はし", 1, confusable = listOf("p2", "p3")),
        item("p2", "橋", "はし", 2, confusable = listOf("p1", "p3")),
        item("p3", "端", "はし", 0, confusable = listOf("p1", "p2")),
        item("p4", "雨", "あめ", 1, confusable = listOf("p5")),
        item("p5", "飴", "あめ", 0, confusable = listOf("p4")),
        item("p6", "花", "はな", 2),
        item("p7", "心", "こころ", 2),
        item("p8", "桜", "さくら", 0),
        item("p9", "命", "いのち", 1),
        item("p10", "男", "おとこ", 3),
        item("p11", "妹", "いもうと", 4),
        item("p12", "友達", "ともだち", 0),
        item("p13", "日本語", "にほんご", 0),
        item("p14", "飲み物", "のみもの", 2),
    )

    @Test
    fun patternsAndMarks() {
        assertEquals(AccentPattern.ODAKA, AccentPattern.of(2, 2))
        assertEquals(AccentPattern.NAKADAKA, AccentPattern.of(2, 3))
        assertEquals(listOf(AccentPattern.HEIBAN, AccentPattern.ATAMADAKA, AccentPattern.ODAKA), AccentPattern.possible(2))
        assertEquals("は↑し↓が", accentMarks("はし", 2))
        assertEquals("は↓しが", accentMarks("はし", 1))
        assertEquals("は↑しが", accentMarks("はし", 0))
        assertEquals("こ↑こ↓ろが", accentMarks("こころ", 2))
        assertEquals("きょ↑う↓が", accentMarks("きょう", 2), "morae, not characters")
    }

    @Test
    fun staircaseMovesTheLevel() {
        assertEquals(1, PitchDifficulty.next(1, correct = true, streak = 2))
        assertEquals(2, PitchDifficulty.next(1, correct = true, streak = 3))
        assertEquals(5, PitchDifficulty.next(5, correct = true, streak = 3))
        assertEquals(2, PitchDifficulty.next(3, correct = false, streak = 0))
        assertEquals(1, PitchDifficulty.next(1, correct = false, streak = 0))
    }

    @Test
    fun questionsFollowTheLevel() {
        val picker = PitchQuestionPicker(items, Random(7))
        repeat(20) {
            val q = assertNotNull(picker.next(1))
            assertEquals(PitchQuestionMode.PATTERN, q.mode)
            assertEquals(2, q.item.moraCount, "level 1 uses two-mora words")
            assertEquals(listOf("HEIBAN", "ATAMADAKA", "ODAKA"), q.options.map { it.id })
            assertTrue(q.expected in q.options.map { it.id })
            assertEquals("pitch/${q.item.id}", q.clipKey)
        }
        repeat(20) {
            val q = assertNotNull(picker.next(4))
            assertEquals(PitchQuestionMode.DOWNSTEP, q.mode)
            assertEquals((0..q.item.moraCount).map { it.toString() }, q.options.map { it.id })
            assertEquals(q.item.downstep.toString(), q.expected)
        }
        val modes = (1..40).mapNotNull { picker.next(5)?.mode }.toSet()
        assertEquals(setOf(PitchQuestionMode.DOWNSTEP, PitchQuestionMode.WORD_PAIR), modes)
        val pair = assertNotNull(picker.next(1, mode = PitchQuestionMode.WORD_PAIR))
        assertTrue(pair.options.size >= 2 && pair.options.all { picker.item(it.id)?.reading == pair.item.reading }, "a same-kana group")
        // Not the same word twice in a row.
        val ids = (1..30).mapNotNull { picker.next(3)?.item?.id }
        assertTrue(ids.zipWithNext().none { (a, b) -> a == b })
        assertNull(PitchQuestionPicker(emptyList()).next(1))
    }

    @Test
    fun weakPatternsComeUpMoreOften() {
        val picker = PitchQuestionPicker(items, Random(3))
        val drawn = (1..300).mapNotNull { picker.next(3, mapOf(AccentPattern.HEIBAN to 1.0))?.item }
        val heiban = drawn.count { patternOf(it) == AccentPattern.HEIBAN }
        assertTrue(heiban > drawn.size / 3, "heiban drawn $heiban of ${drawn.size}")
    }

    @Test
    fun sessionRecordsAnswersAndStats() = runTest {
        val clock = TestClock()
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val service = PitchTestService(db, "device", items, clock = clock)
        assertTrue(service.available)
        assertEquals(listOf(PitchDrill.PATTERN_TEST, PitchDrill.DOWNSTEP_TEST, PitchDrill.WORD_PAIRS), service.drills())
        assertFalse(PitchTestService(db, "device", emptyList()).available, "hidden without the pitch pack")

        val session = service.start(random = Random(11))
        assertEquals(1, session.level)
        repeat(3) {
            val q = assertNotNull(session.next())
            clock.advance(2.seconds)
            val fb = session.answer(q.expected, 1200)
            assertTrue(fb.correct)
            assertEquals(accentMarks(q.item.reading, q.item.downstep), fb.marks)
        }
        assertEquals(2, session.level, "three right in a row: up a level")
        val q = assertNotNull(session.next())
        val wrong = q.options.first { it.id != q.expected }.id
        clock.advance(2.seconds)
        val fb = session.answer(wrong, 3000)
        assertFalse(fb.correct)
        assertEquals(1, fb.nextLevel, "a miss: down a level")
        assertNotNull(fb.chosenMarks)

        val stats = service.stats()
        assertEquals(PitchTally(4, 3), stats.total)
        assertEquals(1, stats.level, "the next session starts where this one ended")
        assertEquals(4, stats.byMoraCount.values.sumOf { it.attempts })
        assertEquals(4, stats.byMode[PitchQuestionMode.PATTERN]?.attempts)
        val expected = patternOf(q.item)
        val chosen = AccentPattern.valueOf(wrong)
        val pair = stats.pairs.first { setOf(it.a, it.b) == setOf(expected, chosen) }
        assertEquals(1, pair.confusions)
        assertTrue(stats.weakness.getValue(expected) > 0.0)
        assertEquals(4, service.recent().size)
        assertEquals(1, service.start().level)
    }

    @Test
    fun statsFromRows() {
        fun row(pattern: String, morae: Int, mode: String, answer: String, correct: Boolean, at: Long, level: Int = 2) =
            PitchAnswerRow("p", mode, "", answer, correct, pattern, morae, level, 1000, at)
        val rows = listOf(
            row("HEIBAN", 2, "PATTERN", "ODAKA", false, 1),
            row("HEIBAN", 2, "PATTERN", "HEIBAN", true, 2),
            row("ODAKA", 2, "DOWNSTEP", "0", false, 3), // downstep 0 in a 2-mora word = 平板
            row("NAKADAKA", 3, "PATTERN", "NAKADAKA", true, 4, level = 4),
        )
        val stats = PitchStats.of(rows)
        assertEquals(PitchTally(2, 1), stats.byPattern[AccentPattern.HEIBAN])
        assertEquals(PitchTally(3, 1), stats.byMoraCount[2])
        val hb = stats.pairs.first { setOf(it.a, it.b) == setOf(AccentPattern.HEIBAN, AccentPattern.ODAKA) }
        assertEquals(3, hb.attempts)
        assertEquals(2, hb.confusions)
        assertEquals(hb, stats.pairs.first(), "the most confused pair comes first")
        assertEquals(4, stats.level)
        assertEquals(PitchTally(0, 0), PitchStats.of(emptyList()).total)
        assertEquals(1, PitchStats.of(emptyList()).level)
    }

    @Test
    fun productionGoesThroughThePronunciationAnalyzer() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val targets = PitchTestService.productionTargets(items[1])
        assertEquals("はし", targets[0].reading)
        assertEquals(2, targets[0].downstep)
        assertTrue(targets[0].followedByParticle)
        assertEquals("が", targets[1].reading)

        val pronunciation = PronunciationService({ null }, { null })
        val service = PitchTestService(db, "device", items, pronunciation)
        val silence = FloatArray(16_000)
        val result = assertNotNull(service.production(items[1], silence, transcript = "はしが", referencePcm16k = silence))
        assertEquals("は↑し↓が", result.expectedMarks)
        assertTrue(result.verdict == PitchVerdict.UNCLEAR || result.verdict == PitchVerdict.FLAT, "silence can't match: ${result.verdict}")
        assertFalse(result.matched)
        assertNotNull(result.shadowing)
        assertNull(PitchTestService(db, "device", items).production(items[1], silence), "no panel, no production link")
    }
}
