package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Delivery
import app.mokuhyo.speech.TimelineTrace
import app.mokuhyo.speech.TurnTimeline
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QC 2026-10-30 V5: a reply keeps one voice; what actually spoke is reported and every fallback is logged. */
class VoiceIdentityTest {
    private val a = VoiceSpec("voice-a", "es", "female", "piper", "MIT")
    private val b = VoiceSpec("voice-b", "es", "female", "piper", "MIT")
    private val previous = Voice.output
    private val played = ArrayList<String>()

    @AfterTest
    fun restore() {
        Voice.output = previous
        SpeechProvider.set(null)
    }

    /** Voice A renders only the sentences in [aSpeaks]; otherwise the service falls back to B, as VoiceService does. */
    private fun run(aSpeaks: Set<String>, vararg sentences: String): Triple<Voice.Streamed, AppGraph, TurnTimeline> {
        val wavs = HashMap<Int, String>()
        SpeechProvider.set(object : SpeechOutput {
            override fun voicesFor(language: String) = listOf(a, b)
            override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double): SpokenAudio {
                val by = if (voice?.id == a.id && text in aSpeaks) a else b
                val wav = AudioIO.wav(ShortArray(160 + wavs.size) { 1_000 })
                wavs[wav.size] = "${by.id}:$text"
                return SpokenAudio(wav, by)
            }
        })
        Voice.output = { wav, _, _, started, _ -> started(); played += wavs.getValue(wav.size) }
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-identity").toFile()))
        val timeline = TurnTimeline("s", "TEST", "es").also { it.beginTurn() }
        val channel = Channel<String>(Channel.UNLIMITED).apply { sentences.forEach { trySend(it) }; close() }
        val streamed = runBlocking { withContext(TimelineTrace(timeline, 0)) { Voice.sayStreamed(app, channel, "es", a)!! } }
        return Triple(streamed, app, timeline)
    }

    @Test
    fun aFallbackBeforeAnythingPlayedIsReportedAsTheVoiceThatSpoke() {
        val (streamed, app, timeline) = run(emptySet(), "Hola.", "¿Cómo está?")
        try {
            assertEquals(Voice.Outcome.Played(b), streamed.outcome, "the reply was spoken by B, not the requested A")
            assertEquals(listOf("voice-b:Hola.", "voice-b:¿Cómo está?"), played)
            val turn = timeline.snapshot().turns.single()
            assertEquals(listOf("piper:voice-a->piper:voice-b"), turn.voiceFallbacks.distinct())
            assertTrue(app.log.tail(5).any { "tts fallback es piper:voice-a->piper:voice-b" in it }, app.log.tail(5).toString())
        } finally { app.close() }
    }

    @Test
    fun aVoiceThatFailsAfterSentenceOneStopsTheReplyInsteadOfSwitching() {
        val (streamed, app, _) = run(setOf("Hola."), "Hola.", "¿Cómo está?", "¿Y su familia?")
        try {
            assertEquals(listOf("voice-a:Hola."), played, "B never speaks part of A's reply")
            assertEquals(Voice.Outcome.VoiceChanged(a, b), streamed.outcome)
            assertEquals(listOf(Delivery.COMPLETED, Delivery.FAILED, Delivery.GENERATED), streamed.reply.utterances.map { it.delivery })
        } finally { app.close() }
    }
}
