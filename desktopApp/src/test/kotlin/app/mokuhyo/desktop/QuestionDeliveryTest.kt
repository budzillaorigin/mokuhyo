package app.mokuhyo.desktop

import app.mokuhyo.desktop.QuestionDelivery.State
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QC follow-up F2: an interview test answers only a question that was actually heard. */
class QuestionDeliveryTest {
    private val voice = VoiceSpec("es_ES-test-medium", "es", "female", "piper", "CC0")
    private val defaultOutput = Voice.output

    @AfterTest
    fun restore() {
        SpeechProvider.set(null)
        Voice.output = defaultOutput
    }

    private class FakeSpeech(val speak: suspend () -> ByteArray?) : SpeechOutput {
        val voice = VoiceSpec("es_ES-test-medium", "es", "female", "piper", "CC0")
        override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) = speak()?.let { SpokenAudio(it, this.voice) }
        override fun voicesFor(language: String) = listOf(voice)
    }

    @Test
    fun heardOpensTheTestAnswersAndANewQuestionClosesThemAgain() = runBlocking<Unit> {
        val d = QuestionDelivery(test = true)
        d.newQuestion(0)
        assertFalse(d.canAnswer)
        d.deliver(0) { Voice.Outcome.Played(voice) }
        assertEquals(State.HEARD, d.state)
        assertTrue(d.canAnswer)
        d.newQuestion(2)
        assertEquals(State.PENDING, d.state)
        assertFalse(d.canAnswer)
    }

    @Test
    fun failedStaysClosedUntilTheLearnerFallsBackToText() = runBlocking<Unit> {
        val d = QuestionDelivery(test = true)
        d.newQuestion(0)
        d.deliver(0) { Voice.Outcome.PlaybackFailed(voice, "line unavailable") }
        assertEquals(State.FAILED, d.state)
        assertTrue(d.needsRecovery)
        assertFalse(d.canAnswer)
        assertTrue(d.problem!!.contains("line unavailable"))
        d.fallBackToText()
        assertTrue(d.canAnswer)
        assertTrue(d.textFallback)
    }

    @Test
    fun cancelWhilePendingOrPlayingKeepsTestAnswersClosed() = runBlocking<Unit> {
        val d = QuestionDelivery(test = true)
        d.newQuestion(0)
        d.stop() // Cancel before the voice even started
        assertEquals(State.CANCELLED, d.state)
        assertFalse(d.canAnswer)
        assertEquals(QuestionDelivery.STOPPED, d.problem)

        d.newQuestion(1)
        val started = CompletableDeferred<Unit>()
        val job = launch { d.deliver(1) { started.complete(Unit); delay(60_000); Voice.Outcome.Played(voice) } }
        started.await()
        assertEquals(State.PLAYING, d.state)
        assertFalse(d.canAnswer)
        job.cancel()
        job.join()
        assertEquals(State.CANCELLED, d.state)
        assertFalse(d.canAnswer)
        assertTrue(d.needsRecovery)
    }

    @Test
    fun practiceMayAlwaysAnswer() = runBlocking<Unit> {
        val d = QuestionDelivery(test = false)
        d.newQuestion(0)
        assertTrue(d.canAnswer)
        d.deliver(0) { Voice.Outcome.NoVoice }
        assertTrue(d.canAnswer)
    }

    /** The report's probe: delayed TTS, the real Voice.say, Cancel before any audio. */
    @Test
    fun cancelDuringSynthesisProducesNoAudioAndNoAnswers() = runBlocking<Unit> {
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-f2").toFile()))
        try {
            val synthesizing = CompletableDeferred<Unit>()
            val played = AtomicInteger()
            SpeechProvider.set(FakeSpeech { synthesizing.complete(Unit); delay(2_000); AudioIO.wav(ShortArray(16_000)) })
            Voice.output = { _, _, _, started, _ -> started(); played.incrementAndGet() }
            val d = QuestionDelivery(test = true)
            d.newQuestion(0)
            val job = launch(Dispatchers.Default) { d.deliver(0) { Voice.say(app, "¿Cómo se llama?", "es") } }
            synthesizing.await()
            job.cancel()
            d.stop()
            job.join()
            delay(2_500) // past the synthesis delay: nothing may play late
            assertEquals(0, played.get())
            assertEquals(State.CANCELLED, d.state)
            assertFalse(d.canAnswer)
        } finally {
            app.close()
        }
    }

    /** Repeat while the question is playing doesn't start a second playback, and Cancel stops the one playing. */
    @Test
    fun repeatDoesNotOverlapAndCancelStopsPlayback() = runBlocking<Unit> {
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-f2").toFile()))
        try {
            val playing = CountDownLatch(1)
            val stoppedByCancel = AtomicBoolean(false)
            val playbacks = AtomicInteger()
            SpeechProvider.set(FakeSpeech { AudioIO.wav(ShortArray(16_000)) })
            Voice.output = { _, _, stop, started, _ ->
                started()
                playbacks.incrementAndGet()
                playing.countDown()
                val until = System.nanoTime() + 5_000_000_000L
                while (!stop() && System.nanoTime() < until) Thread.sleep(5)
                stoppedByCancel.set(stop())
            }
            val d = QuestionDelivery(test = true)
            d.newQuestion(0)
            val first = launch(Dispatchers.Default) { d.deliver(0) { Voice.say(app, "¿Dónde vive?", "es") } }
            assertTrue(playing.await(5, TimeUnit.SECONDS))
            assertTrue(d.playing)
            assertNull(d.deliver(0) { Voice.say(app, "¿Dónde vive?", "es") }, "a Repeat while playing is ignored")
            assertEquals(1, playbacks.get())
            withTimeout(2_000) {
                first.cancel()
                first.join()
            }
            assertTrue(stoppedByCancel.get(), "Cancel stopped the output, not just the coroutine")
            assertEquals(State.CANCELLED, d.state)
            assertFalse(d.canAnswer)
        } finally {
            app.close()
        }
    }
}
