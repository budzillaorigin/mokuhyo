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
    fun turnChangesPauseLongerThanOneSpeakersNextLine() {
        assert(PassageAudio.pauseMs("A", "B") > PassageAudio.pauseMs("A", "A"))
    }
}
