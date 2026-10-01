package app.mokuhyo.ai.jni

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-Kotlin parts of the JNI bridges; always run, natives or not. */
class StopMatcherTest {
    private fun run(stops: List<String>, pieces: List<String>): Pair<String, String> {
        val m = StopMatcher(stops)
        val streamed = StringBuilder()
        for (p in pieces) {
            streamed.append(m.append(p))
            if (m.stopped) break
        }
        if (!m.stopped) streamed.append(m.finish())
        return m.text to streamed.toString()
    }

    @Test
    fun noStopsStreamsEverything() {
        val (text, streamed) = run(emptyList(), listOf("Hel", "lo", " world"))
        assertEquals("Hello world", text)
        assertEquals(text, streamed)
    }

    @Test
    fun stopInsideOnePieceIsCutAndExcluded() {
        val (text, streamed) = run(listOf("END"), listOf("abc", "dENDef", "never"))
        assertEquals("abcd", text)
        assertEquals("abcd", streamed)
    }

    @Test
    fun stopSplitAcrossPiecesNeverLeaksIntoTheStream() {
        val m = StopMatcher(listOf("<|im_end|>"))
        assertEquals("Hi", m.append("Hi<|im")) // "<|im" might start the stop string: held back
        assertFalse(m.stopped)
        assertEquals("<|im there", m.append(" there")) // it didn't: released with the new text
        assertEquals("", m.append("<|im_"))
        assertEquals("", m.append("end|> trailing"))
        assertTrue(m.stopped)
        assertEquals("Hi<|im there", m.text)
    }

    @Test
    fun heldBackPrefixIsReleasedWhenItTurnsOutNotToBeAStop() {
        val (text, streamed) = run(listOf("STOP"), listOf("a S", "T", "x", "!"))
        assertEquals("a STx!", text)
        assertEquals(text, streamed)
    }

    @Test
    fun earliestOfSeveralStopsWins() {
        val (text, _) = run(listOf("\n\n", "User:"), listOf("answer User: more\n\nx"))
        assertEquals("answer ", text)
    }

    @Test
    fun heldBackTailIsReleasedAtTheEnd() {
        val (text, streamed) = run(listOf("###"), listOf("done #"))
        assertEquals("done #", text)
        assertEquals("done #", streamed)
    }

    @Test
    fun multiByteTextIsUntouched() {
        val (text, streamed) = run(listOf("。"), listOf("こんに", "ちは", "。次"))
        assertEquals("こんにちは", text)
        assertEquals(text, streamed)
    }

    @Test
    fun whisperLanguageReducesBcp47ToPrimarySubtag() {
        assertEquals("zh", WhisperJniBridge.whisperLanguage("zh-Hans"))
        assertEquals("pt", WhisperJniBridge.whisperLanguage("pt-BR"))
        assertEquals("ja", WhisperJniBridge.whisperLanguage("JA"))
        assertEquals("auto", WhisperJniBridge.whisperLanguage(""))
    }

    @Test
    fun chatMlFallbackEndsWithAnOpenAssistantTurn() {
        val prompt = LlamaJniBridge.chatMl(listOf(ChatMessage(Role.SYSTEM, "s"), ChatMessage(Role.USER, "u")))
        assertEquals("<|im_start|>system\ns<|im_end|>\n<|im_start|>user\nu<|im_end|>\n<|im_start|>assistant\n", prompt)
    }
}
