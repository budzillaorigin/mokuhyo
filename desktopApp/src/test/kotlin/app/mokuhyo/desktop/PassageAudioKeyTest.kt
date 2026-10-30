package app.mokuhyo.desktop

import app.mokuhyo.exam.ExamKind
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ScriptLine
import app.mokuhyo.lang.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** QC 2026-10-19: a cached passage render is redone when the voice that speaks it changes. */
class PassageAudioKeyTest {
    private val passage = ExamPassage("p1", ExamKind.DLPT_LISTENING, "es", "1", "news", "T", "", listOf(ScriptLine("A", "female", "Hola.")), "llm", false)
    private val v1 = VoiceSpec("es_ES-a-medium", "es", "female", "piper", "CC0")

    @Test
    fun keyNamesTheActualVoice() {
        val k = PassageAudio.cacheKey(passage, mapOf("A" to v1))
        assertEquals(k, PassageAudio.cacheKey(passage, mapOf("A" to v1)))
        assertNotEquals(k, PassageAudio.cacheKey(passage, mapOf("A" to v1.copy(id = "es_ES-b-high"))))
        assertNotEquals(k, PassageAudio.cacheKey(passage, mapOf("A" to v1.copy(engine = "os"))))
        assertNotEquals(k, PassageAudio.cacheKey(passage, mapOf("A" to v1.copy(speaker = 3))))
    }

    @Test
    fun linesJoinByTheBoundaryPolicyWithNoFinalPad() {
        // QC 2026-10-30 V2: each line's edge silence counts toward its gap; nothing follows the last line.
        val rate = 1000
        val line = ShortArray(400) { if (it in 100 until 300) 2000 else 0 } // 100 ms lead, 100 ms trail
        val policy = app.mokuhyo.speech.BoundaryPolicies.default
        val out = PassageAudio.compose(listOf(line to rate, line to rate, line to rate), listOf("A", "A", "B"), rate)
        val voiced = out.indices.filter { kotlin.math.abs(out[it].toInt()) >= 328 }
        val gaps = voiced.zipWithNext().filter { (a, b) -> b - a > 1 }.map { (a, b) -> b - a - 1 }
        assertEquals(listOf(policy.targetMs(app.mokuhyo.speech.Boundary.SENTENCE_END), policy.targetMs(app.mokuhyo.speech.Boundary.SPEAKER_TURN)), gaps)
        assertEquals(100, out.size - 1 - voiced.last(), "the last line keeps its own trailing silence and nothing more")
    }
}
