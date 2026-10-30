package app.mokuhyo.desktop

import app.mokuhyo.ai.*
import app.mokuhyo.settings.Settings
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.AudioIO
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TopicCaptureTest {
    @Test fun topicCaptureConfirmsAndSpeaksWithoutStartingCpuCritique() {
        val previousOutput = Voice.output
        val heard = AtomicBoolean()
        Voice.output = { _, _, _, started, _ -> started(); heard.set(true) }
        try {
            InterviewHarness().use { h ->
                val fixtureVoice = VoiceSpec("test-voice", "es", "female", "test", "CC0")
                SpeechProvider.set(object : SpeechOutput {
                    override fun voicesFor(language: String) = listOf(fixtureVoice)
                    override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) =
                        SpokenAudio(AudioIO.wav(ShortArray(1600) { 1000 }), voice ?: fixtureVoice)
                })
                // Enable the model-dependent UI; all inference uses the in-memory gateway below, never an endpoint.
                h.app.settings.put(Settings.Key.USE_OLLAMA, "true")
                h.app.settings.put(Settings.Key.OLLAMA_MODEL, "test-fixture")
                h.app.settings.put(Settings.Key.CONFIRM_TRANSCRIPT, "true")
                var calls = 0
                h.replace("gateway", AiGateway({ object : LanguageModel {
                    override val id = "test"
                    override val isLocal = true
                    override suspend fun complete(request: CompletionRequest): CompletionResult {
                        calls++
                        return CompletionResult("""{"reply":"¿Y luego?","reply_english":"And then?"}""", id, null)
                    }
                } }))
                h.show(); h.click("Topic conversation"); h.click("Choose a topic"); h.type("Names"); h.click("Start")
                h.click("● Record"); repeat(5) { h.pump() }; h.click("■ Stop")
                h.until("topic recognizer wasn't called") { h.transcription != null }
                repeat(10) { h.pump() }
                assertFalse(h.cancelled)
                h.transcription!!("""[{"text":"Me llamo Ana."}]""", null)
                h.until("confirmation missing") { "What I heard" in h.texts() }
                h.click("Send")
                h.until("reply was held for feedback") { "¿Y luego?" in h.texts() }
                h.until("reply did not reach the audio output") { heard.get() }
                assertEquals(1, calls, "CPU feedback must wait until the session ends")
                assertEquals("Me llamo Ana.", h.app.conversations.stored(h.app.conversations.list(h.app.learnerId, "es").single()).transcript[1].text)
            }
        } finally { Voice.output = previousOutput }
    }
}
