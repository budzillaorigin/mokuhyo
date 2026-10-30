package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Preflight
import app.mokuhyo.speech.Preflight.Kind
import app.mokuhyo.speech.Preflight.State
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterviewPreflightTest {
    private val voice = VoiceSpec("es_ES-test-medium", "es", "female", "piper", "CC0")

    private fun speech(voices: List<VoiceSpec>) = object : SpeechOutput {
        override fun voicesFor(language: String) = voices
        override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) =
            voices.firstOrNull()?.let { SpokenAudio(AudioIO.wav(ShortArray(1_600) { 500 }), it) }
    }

    private fun <T> withApp(voices: List<VoiceSpec>, output: (ByteArray, String?, () -> Boolean, () -> Unit, () -> Unit) -> Unit, body: (AppGraph) -> T): T {
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-preflight").toFile()))
        val previous = Voice.output
        Voice.output = output
        SpeechProvider.set(speech(voices))
        try { return body(app) } finally { Voice.output = previous; SpeechProvider.set(null); app.close() }
    }

    private fun run(app: AppGraph, test: Boolean) = runBlocking { Preflight(InterviewPreflight.real(app, "es", AiGateway({ null }), test, null)).run() }

    @Test
    fun aReadyComputerPassesAndWhatIsntInstalledIsSaid() = withApp(listOf(voice), { _, _, _, started, _ -> started() }) { app ->
        val r = run(app, test = true)
        assertTrue(r.ready, r.items.toString())
        assertEquals(State.OK, r.state(Kind.OUTPUT))
        assertEquals(State.OK, r.state(Kind.VOICE))
        assertEquals(State.OK, r.state(Kind.TTS))
        assertEquals(State.NOT_NEEDED, r.state(Kind.ASR), "no Whisper model: typed answers")
        assertEquals(State.NOT_NEEDED, r.state(Kind.MICROPHONE))
        assertEquals(State.NOT_NEEDED, r.state(Kind.LLM), "no model: guided questions")
    }

    @Test
    fun noVoiceBlocksASpokenTestButNotPractice() = withApp(emptyList(), { _, _, _, started, _ -> started() }) { app ->
        val test = run(app, test = true)
        assertEquals(Kind.VOICE, test.blocking?.kind)
        assertTrue(test.blocking!!.message!!.contains("No offline voice can speak Spanish"), test.blocking!!.message)
        val practice = run(app, test = false)
        assertTrue(practice.ready)
        assertTrue(practice.items.first { it.kind == Kind.VOICE }.message!!.contains("text practice"))
    }

    @Test
    fun speakersThatDontPlayBlockStart() = withApp(listOf(voice), { _, _, _, _, _ -> error("line unavailable") }) { app ->
        val r = run(app, test = false)
        assertEquals(Kind.OUTPUT, r.blocking?.kind)
        assertTrue(r.blocking!!.message!!.contains("Settings → Speech & audio"))
    }

    @Test
    fun aFailedCheckKeepsTheInterviewFromStarting() {
        InterviewHarness().use { h ->
            InterviewPreflight.checks = { _, _, _, _, _ ->
                listOf(Preflight.Check(Kind.OUTPUT, "Speakers play a short tone") { Preflight.Result.Failed("Plug in headphones and press Check again.") })
            }
            h.show(); h.click("Start interview"); h.click("Start")
            h.until("the blocking message is missing") { "Not ready: Plug in headphones and press Check again." in h.texts() }
            h.until("Start should offer a re-check") { "Check again" in h.texts() }
            assertTrue(h.app.conversations.list(h.app.learnerId, "es").isEmpty(), "no question was asked or saved")
        }
    }
}
