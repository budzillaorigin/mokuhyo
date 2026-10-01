package app.mokuhyo.exam

import app.mokuhyo.exam.IlrEstimator
import app.mokuhyo.exam.IlrLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IlrEstimatorTest {

    private fun block(level: IlrLevel, total: Int, correct: Int, textType: String = "news", timeMs: Long = 60_000) =
        (0 until total).map { IlrEstimator.Result(level, correct = it < correct, textType = textType, timeMs = timeMs) }

    @Test
    fun levelsParseAndOrder() {
        assertEquals(IlrLevel.L1_PLUS, IlrLevel.parse("1+"))
        assertEquals(IlrLevel.L0_PLUS, IlrLevel.parse("0+"))
        assertNull(IlrLevel.parse("5"))
        assertTrue(IlrLevel.L2 < IlrLevel.L2_PLUS)
        assertEquals("2+", IlrLevel.L2_PLUS.label)
    }

    @Test
    fun highestSustainedLevel() {
        val results = block(IlrLevel.L1, 10, 10) + block(IlrLevel.L1_PLUS, 10, 9) + block(IlrLevel.L2, 10, 8) +
            block(IlrLevel.L2_PLUS, 10, 5) + block(IlrLevel.L3, 10, 3)
        val estimate = IlrEstimator.estimate(results)
        assertEquals(IlrLevel.L2, estimate.level)
        assertTrue(estimate.confident)
    }

    @Test
    fun needsTwentyItemsSustained() {
        // 8/10 at level 1 alone is 10 items: not enough to call it sustained.
        val estimate = IlrEstimator.estimate(block(IlrLevel.L1, 10, 8))
        assertNull(estimate.level)
        assertEquals(IlrLevel.L1, estimate.provisional)
        assertFalse(estimate.confident)
    }

    @Test
    fun floorBelowMustHold() {
        // Lucky at 2 but weak at 1 and 1+: the floor fails, so the estimate stays below.
        val results = block(IlrLevel.L0_PLUS, 10, 10) + block(IlrLevel.L1, 10, 9) + block(IlrLevel.L1_PLUS, 10, 4) +
            block(IlrLevel.L2, 10, 8)
        assertEquals(IlrLevel.L1, IlrEstimator.estimate(results).level)
    }

    @Test
    fun oneSlightlyWeakerLowerLevelDoesNotBreakTheFloor() {
        val results = block(IlrLevel.L1, 10, 10) + block(IlrLevel.L1_PLUS, 10, 6) + block(IlrLevel.L2, 10, 8)
        assertEquals(IlrLevel.L2, IlrEstimator.estimate(results).level)
    }

    @Test
    fun levelsWithFewItemsCanBeSustainedButNotClaimedAlone() {
        // Only 3 items at 2+; they can't carry the estimate.
        val results = block(IlrLevel.L1, 10, 10) + block(IlrLevel.L2, 12, 10) + block(IlrLevel.L2_PLUS, 3, 3)
        assertEquals(IlrLevel.L2, IlrEstimator.estimate(results).level)
    }

    @Test
    fun breakdownByTextTypeAndTime() {
        val results = block(IlrLevel.L1, 4, 4, "sign", 30_000) + block(IlrLevel.L2, 4, 1, "editorial", 90_000)
        val estimate = IlrEstimator.estimate(results)
        val editorial = estimate.byTextType.first { it.key == "editorial" }
        assertEquals(1, editorial.correct)
        assertEquals(4, editorial.total)
        assertEquals(60_000, estimate.meanTimeMs)
        assertEquals(listOf("editorial"), estimate.weakTextTypes)
    }

    @Test
    fun emptyAttempt() {
        val estimate = IlrEstimator.estimate(emptyList())
        assertNull(estimate.level)
        assertNull(estimate.provisional)
        assertEquals(0, estimate.meanTimeMs)
    }
}
