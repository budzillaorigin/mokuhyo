package app.mokuhyo.opi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QC 2026-10-30 V13: the transcript-only rating makes no pronunciation claim, in its prompt or in what is shown. */
class OpiRateClaimsTest {
    private val input = OpiRate.Input("es", listOf(Turn(Speaker.PARTNER, "¿Dónde trabaja?"), Turn(Speaker.LEARNER, "Trabajo en una oficina grande.")), "usted")

    @Test
    fun thePromptRatesOnlyWhatATranscriptShows() {
        val text = OpiRate().messages(input).joinToString("\n") { it.content }
        assertFalse("pronunciation as reflected in the transcript" in text, text)
        assertTrue("accuracy (grammar, vocabulary, register)" in text)
        assertTrue("Do not judge or mention pronunciation" in text)
    }

    @Test
    fun theRenderedRatingDropsSoundClaims() {
        val f = OpiRate.Factor("1+", "Good control of basic grammar. Pronunciation is clear and the accent is mild. Uses polite register.", listOf("Trabajo en una oficina"))
        val out = OpiRate.Output(functions = f, contextContent = f, accuracy = f, textType = f, sustainedLevel = "1+", estimate = "1+",
            rationale = "Sustained 1+. Her intonation sounds native.", nextSteps = listOf("Practise narration in the past.", "Work on your pronunciation of r.", "Compare two places."))
        val shown = OpiRate.normalize(input, out)
        assertEquals("Good control of basic grammar. Uses polite register.", shown.accuracy.evidence)
        assertEquals("Sustained 1+.", shown.rationale)
        assertEquals(listOf("Practise narration in the past.", "Compare two places."), shown.nextSteps)
        assertEquals("Accuracy (grammar, vocabulary, register; from the transcript)", OpiRate.factorLabel("accuracy"))
    }
}
