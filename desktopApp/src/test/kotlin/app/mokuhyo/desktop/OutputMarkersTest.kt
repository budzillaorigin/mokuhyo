package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.TimelineTrace
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.TurnTimeline
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** QC 2026-10-30 V12: the timeline says what the learner hears, not when a WAV arrived or a device opened. */
class OutputMarkersTest {
    private val voice = VoiceSpec("fixture", "es", "female", "piper", "CC0")
    private val previous = Voice.output

    /** 300 ms of silence, 500 ms of speech-level tone, 200 ms of silence at 16 kHz. */
    private val fixture = AudioIO.wav(ShortArray(16_000) { i -> if (i in 4_800 until 12_800) (if (i % 2 == 0) 5_000 else -5_000).toShort() else 0 })

    @AfterTest
    fun restore() {
        Voice.output = previous
        SpeechProvider.set(null)
    }

    private fun <T> withTimeline(body: suspend (AppGraph, TurnTimeline, () -> Long, (Long) -> Unit) -> T): T {
        var clock = 1_000_000_000L
        val timeline = TurnTimeline("s", "TEST", "es") { clock }
        timeline.beginTurn()
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-markers").toFile()))
        SpeechProvider.set(object : SpeechOutput {
            override fun voicesFor(language: String) = listOf(voice)
            override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) = SpokenAudio(fixture, this@OutputMarkersTest.voice)
        })
        try {
            return runBlocking { withContext(TimelineTrace(timeline, 0)) { body(app, timeline, { clock }) { clock += it * 1_000_000 } } }
        } finally {
            app.close()
        }
    }

    private fun TurnTimeline.at(stage: TurnStage) = events().filter { it.stage == stage }.map { it.atNanos / 1_000_000 }

    @Test
    fun leadingSilenceSeparatesDeviceOpenFromFirstAudibleSample() = withTimeline { app, timeline, _, advance ->
        Voice.output = { _, _, _, started, _ -> advance(50); started(); advance(1_000) }
        val spoken = assertNotNull(Voice.render(app, "Hola.", "es"))
        assertTrue(Voice.playRendered(app, spoken).ok)
        assertEquals(emptyList(), timeline.at(TurnStage.TTS_FIRST_PCM), "a WAV engine has no first-PCM moment")
        assertEquals(1, timeline.at(TurnStage.TTS_SYNTH_DONE).size)
        val open = timeline.at(TurnStage.OUTPUT_START).single()
        assertEquals(open + 300, timeline.at(TurnStage.OUTPUT_AUDIBLE).single(), "first audible = device open + leading silence")
        assertEquals(timeline.at(TurnStage.OUTPUT_END).single() - 200, timeline.at(TurnStage.SPEECH_END).single())
        val turn = timeline.snapshot().turns.single()
        assertEquals(300L, turn.stages["leading_silence"])
        assertEquals(listOf("piper:fixture"), turn.voices, "the voice that actually rendered")
    }

    @Test
    fun aFailedDeviceIsAFailureAndNothingIsMarkedAudible() = withTimeline { app, timeline, _, _ ->
        Voice.output = { _, _, _, _, _ -> throw IllegalStateException("line unavailable") }
        val spoken = assertNotNull(Voice.render(app, "Hola.", "es"))
        val outcome = Voice.playRendered(app, spoken)
        assertFalse(outcome.ok)
        assertEquals(emptyList(), timeline.at(TurnStage.OUTPUT_AUDIBLE))
        assertEquals(emptyList(), timeline.at(TurnStage.SPEECH_END))
        // The benchmark's physical output reports the same failure instead of swallowing it.
        assertEquals("line unavailable", BenchInterview.physicalOutput({ throw IllegalStateException("line unavailable") }) {})
    }

    @Test
    fun gapsBetweenUtterancesAreMeasuredFromSpeechEndToNextAudibleSample() = withTimeline { app, timeline, _, advance ->
        Voice.output = { _, _, _, started, _ -> started(); advance(1_000) }
        val spoken = assertNotNull(Voice.render(app, "Hola.", "es"))
        Voice.playRendered(app, spoken)
        advance(100)
        Voice.playRendered(app, spoken)
        // 200 ms trailing silence + 100 ms between plays + 300 ms leading silence.
        assertEquals(listOf(600L), timeline.snapshot().turns.single().gapsMs)
    }
}
