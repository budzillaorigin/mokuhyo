package app.tsumugi.practice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swotter-style drill timing (BRIEF_V2 §6.10): prompt → pause → model answer → repeat. */
class DrillPlaybackTest {

    private fun set(vararg answers: String) = DrillSet(
        DrillSetSummary("drill-g-n5-1", "N5 grammar drill 1", 5, DrillKind.GRAMMAR, "", "derived"),
        answers.mapIndexed { i, a -> DrillItem("Prompt number $i", a, "grammar/p$i/0", "g:p$i", "tatoeba") },
    )

    private val kinds = listOf(DrillStepKind.PROMPT, DrillStepKind.ANSWER_PAUSE, DrillStepKind.ANSWER, DrillStepKind.REPEAT_PAUSE)

    @Test
    fun eachItemIsPromptPauseAnswerRepeatWithGapsBetween() {
        val plan = DrillPlayback.plan(set("これはペンです。", "駅はどこですか。"))
        assertEquals(kinds + DrillStepKind.GAP + kinds, plan.steps.map { it.kind })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 1), plan.steps.map { it.itemIndex })
        assertEquals(plan.steps.sumOf { it.durationMs }, plan.totalMs)
        assertEquals(4, plan.firstStepOf(1))
    }

    @Test
    fun proportionalPauseGrowsWithTheAnswerAndIsClamped() {
        val t = DrillTiming()
        assertEquals((2_000 * 1.5 + 1_000).toLong(), t.answerPause(2_000))
        assertEquals(t.minPauseMs, t.answerPause(100))
        assertEquals(t.maxPauseMs, t.answerPause(60_000))
        assertTrue(DrillTiming.LONG.answerPause(3_000) > DrillTiming.SHORT.answerPause(3_000))
    }

    @Test
    fun fixedPauseAndNoRepeatAreConfigurable() {
        val timing = DrillTiming(pauseMode = DrillTiming.PauseMode.FIXED, fixedPauseMs = 3_000, repeat = false, gapMs = 0)
        val plan = DrillPlayback.plan(set("短い。", "これはとても長い文で、答えるのに時間がかかります。"), timing)
        assertEquals(listOf(3_000L, 3_000L), plan.steps.filter { it.kind == DrillStepKind.ANSWER_PAUSE }.map { it.durationMs })
        assertTrue(plan.steps.none { it.kind == DrillStepKind.REPEAT_PAUSE || it.kind == DrillStepKind.GAP })
        assertEquals(0, timing.repeatPause(5_000))
        assertFailsWith<IllegalArgumentException> { DrillTiming(minPauseMs = 5_000, maxPauseMs = 1_000) }
    }

    @Test
    fun realClipLengthsReplaceTheEstimate() {
        val s = set("これはペンです。")
        val estimated = DrillPlayback.plan(s)
        val real = DrillPlayback.plan(s) { item -> if (item.audioKey == "grammar/p0/0") 4_000 else null }
        assertEquals(4_000, real.steps.single { it.kind == DrillStepKind.ANSWER }.durationMs)
        assertEquals(DrillTiming.DEFAULT.answerPause(4_000), real.steps.single { it.kind == DrillStepKind.ANSWER_PAUSE }.durationMs)
        assertEquals(DrillPlayback.estimateJapaneseMs("これはペンです。"), estimated.steps.single { it.kind == DrillStepKind.ANSWER }.durationMs)
    }

    @Test
    fun estimatesScaleWithMoraeAndWords() {
        // Kanji count as two morae; small kana fold into the previous mora.
        assertEquals(DrillPlayback.estimateJapaneseMs("きゃく"), DrillPlayback.estimateJapaneseMs("きく"))
        assertEquals(DrillPlayback.estimateJapaneseMs("山"), DrillPlayback.estimateJapaneseMs("やま"))
        assertTrue(DrillPlayback.estimateJapaneseMs("今日は良い天気ですね。") > DrillPlayback.estimateJapaneseMs("はい。"))
        assertTrue(DrillPlayback.estimateEnglishMs("Where is the station?") > DrillPlayback.estimateEnglishMs("Yes."))
    }

    @Test
    fun cursorAdvancesSkipsAndGoesBack() {
        val plan = DrillPlayback.plan(set("一。", "二。", "三。"))
        val cursor = DrillCursor(plan)
        assertEquals(DrillStepKind.PROMPT, cursor.current?.kind)
        cursor.advance()
        assertEquals(DrillStepKind.ANSWER_PAUSE, cursor.current?.kind)
        cursor.skipItem()
        assertEquals(1, cursor.itemIndex)
        assertEquals(DrillStepKind.PROMPT, cursor.current?.kind)
        cursor.advance()
        cursor.previousItem() // mid-item: restart this item
        assertEquals(1, cursor.itemIndex)
        assertEquals(DrillStepKind.PROMPT, cursor.current?.kind)
        cursor.previousItem() // at the prompt: go to the previous item
        assertEquals(0, cursor.itemIndex)
        cursor.skipItem()
        cursor.skipItem()
        cursor.skipItem()
        assertTrue(cursor.finished)
        assertNull(cursor.current)
        assertEquals(0, cursor.remainingMs)
        assertEquals(plan.totalMs, DrillCursor(plan).remainingMs)
    }
}
