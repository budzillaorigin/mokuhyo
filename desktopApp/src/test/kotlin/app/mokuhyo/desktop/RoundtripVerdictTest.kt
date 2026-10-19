package app.mokuhyo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QC 2026-10-19 #6: the round-trip gate requires every declared clip, not just a non-empty run. */
class RoundtripVerdictTest {
    @Test
    fun partialSetFailsUnlessAllowed() {
        val expected = mapOf("es" to 3)
        val scores = mapOf("es" to listOf(0.95, 0.9))
        assertTrue(LangSmoke.roundtripVerdict(expected, mapOf("es" to 1), scores, 0.8, allowMissing = false).getValue("es").startsWith("FAIL"))
        assertEquals("OK", LangSmoke.roundtripVerdict(expected, mapOf("es" to 1), scores, 0.8, allowMissing = true).getValue("es"))
    }

    @Test
    fun fullSetPassesOnScoreAndALanguageWithNoClipsFails() {
        assertEquals("OK", LangSmoke.roundtripVerdict(mapOf("es" to 2), emptyMap(), mapOf("es" to listOf(0.9, 0.85)), 0.8, false).getValue("es"))
        assertTrue(LangSmoke.roundtripVerdict(mapOf("es" to 2), emptyMap(), mapOf("es" to listOf(0.5, 0.6)), 0.8, false).getValue("es").startsWith("FAIL"))
        val v = LangSmoke.roundtripVerdict(mapOf("es" to 1, "fr" to 1), mapOf("fr" to 1), mapOf("es" to listOf(0.9)), 0.8, true)
        assertTrue(v.getValue("fr").startsWith("FAIL (no clips"))
    }
}
