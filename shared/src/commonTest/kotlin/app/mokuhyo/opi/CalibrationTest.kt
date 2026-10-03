package app.mokuhyo.opi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-04: agreement statistics and the confidence band (calibrated and uncalibrated). */
class CalibrationTest {
    @Test
    fun agreementCountsExactAndWithinOne() {
        assertEquals(2 to 4, Calibration.agreement(listOf("2" to "2", "2+" to "2", "1" to "1", "3" to "2+", "1" to "2+", "x" to "2")))
    }

    @Test
    fun bandIsUncalibratedWithoutRealSamples() {
        val b = Calibration.band("2", null, "Spanish", "Tier B")
        assertFalse(b.calibrated)
        assertTrue("Uncalibrated" in b.sentence)
        val fixtureOnly = Calibration.Table(entries = listOf(Calibration.Entry("es", "B", "m", 10, 8, 10, fixture = true)))
        assertEquals(null, fixtureOnly.entry("es", "B"), "fixture samples never calibrate")
        assertFalse(Calibration.band("2", Calibration.Entry("es", "B", "m", 3, 3, 3), "Spanish", "Tier B").calibrated, "too few samples")
    }

    @Test
    fun bandWidthFollowsAgreement() {
        val good = Calibration.band("2", Calibration.Entry("es", "B", "m", 10, 6, 9), "Spanish", "Tier B")
        assertEquals("1+" to "2+", good.low to good.high)
        assertTrue(good.calibrated && "Based on 10 calibrated samples for Spanish on Tier B" in good.sentence)
        val weak = Calibration.band("2", Calibration.Entry("es", "B", "m", 10, 3, 6), "Spanish", "Tier B")
        assertEquals("1" to "3", weak.low to weak.high)
        val edge = Calibration.band("3", Calibration.Entry("es", "B", "m", 10, 6, 9), "Spanish", "Tier B")
        assertEquals("2+" to "3", edge.low to edge.high)
    }
}
