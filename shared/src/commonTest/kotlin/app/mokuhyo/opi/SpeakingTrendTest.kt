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

    /** QC follow-up F4: the stored decision agrees with what is written to the trend, for every combination. */
    @Test
    fun storedDecisionMatchesTheTrendEntry() {
        for (test in listOf(true, false)) for (model in listOf(true, false)) for (fallback in listOf(true, false)) for (cal in listOf(true, false))
            for ((spoken, typed) in listOf(0 to 0, 5 to 0, 4 to 1, 1 to 4)) {
                val d = SpeakingTrend.decide(test, model, spoken, typed, fallback, cal)
                val e = SpeakingTrend.entry(test, model, spoken, typed, fallback, cal)
                when (d.status) {
                    SpeakingTrend.Status.COUNTED_FIRM -> assertEquals(false, e?.provisional)
                    SpeakingTrend.Status.COUNTED_PROVISIONAL -> assertEquals(true, e?.provisional)
                    else -> assertNull(e)
                }
            }
        assertEquals(SpeakingTrend.Status.NOT_APPLICABLE, SpeakingTrend.decide(true, true, 5, 0, false, true, rated = false).status)
    }

    /** BRIEF_INTERVIEW_SPEED N5: scripted (guided) questions make an interview provisional or keep it out, with the reason stored. */
    @Test
    fun guidedQuestionsAffectTheTrendAndTheReasonIsStored() {
        val mostly = SpeakingTrend.decide(true, true, 10, 0, false, calibrated = true, guided = 6, questions = 10)
        assertEquals(SpeakingTrend.Status.EXCLUDED, mostly.status)
        assertEquals(SpeakingTrend.GUIDED, mostly.reason)
        assertNull(SpeakingTrend.entry(true, true, 10, 0, false, true, guided = 6, questions = 10))
        assertNotNull(SpeakingTrend.exclusion(true, true, 10, 0, false, guided = 6, questions = 10))
        val partly = SpeakingTrend.decide(true, true, 10, 0, false, calibrated = true, guided = 2, questions = 10)
        assertEquals(SpeakingTrend.Status.COUNTED_PROVISIONAL, partly.status)
        assertEquals("partly guided", partly.reason)
        assertEquals(true, SpeakingTrend.entry(true, true, 10, 0, false, true, guided = 2, questions = 10)?.provisional)
        assertEquals(SpeakingTrend.Status.COUNTED_FIRM, SpeakingTrend.decide(true, true, 10, 0, false, calibrated = true, guided = 0, questions = 10).status)
        assertEquals("practice; " + SpeakingTrend.GUIDED, SpeakingTrend.decide(false, false, 0, 10, false, false, guided = 10, questions = 10).reason)
        assertEquals("ILR 1 (self-rated — ${SpeakingTrend.GUIDED})",
            SpeakingTrend.label("OPI", "1", false, true, SpeakingTrend.Status.EXCLUDED, "self-rated; ${SpeakingTrend.GUIDED}", null).text)
    }
}
