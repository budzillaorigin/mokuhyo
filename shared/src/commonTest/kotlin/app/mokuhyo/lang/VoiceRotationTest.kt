package app.mokuhyo.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceRotationTest {
    private fun v(id: String, g: String) = VoiceSpec(id, "es", g, "piper", "CC0-1.0")
    private val es = listOf(v("davefx", "male"), v("sharvard-f", "female"), v("sharvard-m", "male"), v("claude-mx", "female"))

    @Test
    fun dialogueSpeakersGetDistinctVoices() {
        repeat(50) { i ->
            val a = VoiceRotation.assign(es, listOf("guard" to "male", "officer" to "male", "clerk" to "female", "farmer" to "female"), "es-dlg-$i")
            assertEquals(4, a.values.toSet().size, "four speakers, four voices ($i)")
            assertEquals("male", a.getValue("guard").gender)
            assertEquals("female", a.getValue("clerk").gender)
        }
    }

    @Test
    fun passagesRotateThroughEveryVoiceOfAGender() {
        val used = (1..40).map { VoiceRotation.assign(es, listOf("narrator" to "male"), "es-dl-1-news-%03d".format(it)).getValue("narrator").id }.toSet()
        assertEquals(setOf("davefx", "sharvard-m"), used)
    }

    @Test
    fun personaVoiceIsStableAndOfTheirGender() {
        val a = VoiceRotation.forPersona(es, "es-persona-03", "female")
        assertEquals(a, VoiceRotation.forPersona(es, "es-persona-03", "female"))
        assertEquals("female", a!!.gender)
        val all = (1..12).mapNotNull { VoiceRotation.forPersona(es, "es-persona-$it", "male")?.id }.toSet()
        assertTrue(all.size == 2)
    }

    @Test
    fun fallsBackWhenAGenderIsMissing() {
        val ru = listOf(v("denis", "male"), v("dmitri", "male"))
        val a = VoiceRotation.assign(ru, listOf("a" to "female", "b" to "male"), "x")
        assertNotEquals(a.getValue("a"), a.getValue("b"))
    }
}
