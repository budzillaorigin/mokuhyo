package app.mokuhyo.opi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** QC 2026-10-19 #3: a speaking estimate is never saved as firm without calibration, and typed interviews stay out. */
class SpeakingTrendTest {
    @Test
    fun uncalibratedModelRatingIsProvisional() {
        assertEquals(SpeakingTrend.Entry(0.3, provisional = true), SpeakingTrend.entry(true, true, spoken = 10, typed = 0, textFallback = false, calibrated = false))
    }

    @Test
    fun calibratedAllSpokenIsFirm() {
        assertEquals(SpeakingTrend.Entry(0.6, provisional = false), SpeakingTrend.entry(true, true, spoken = 10, typed = 0, textFallback = false, calibrated = true))
    }

    @Test
    fun someTypedAnswersMakeItProvisional() {
        assertEquals(true, SpeakingTrend.entry(true, true, spoken = 8, typed = 2, textFallback = false, calibrated = true)?.provisional)
    }

    @Test
    fun mostlyTypedPracticeSelfRatedOrTextFallbackStayOut() {
        assertNull(SpeakingTrend.entry(true, true, spoken = 2, typed = 8, textFallback = false, calibrated = true))
        assertNull(SpeakingTrend.entry(true, true, spoken = 0, typed = 0, textFallback = false, calibrated = true))
        assertNull(SpeakingTrend.entry(false, true, spoken = 10, typed = 0, textFallback = false, calibrated = true))
        assertNull(SpeakingTrend.entry(true, false, spoken = 10, typed = 0, textFallback = false, calibrated = true))
        assertNull(SpeakingTrend.entry(true, true, spoken = 10, typed = 0, textFallback = true, calibrated = true))
        assertNotNull(SpeakingTrend.exclusion(true, true, spoken = 2, typed = 8, textFallback = false))
    }
}
