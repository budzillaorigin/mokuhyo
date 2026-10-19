package app.mokuhyo.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.ai.*
import app.mokuhyo.opi.*
import app.mokuhyo.speech.Vad
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Diagnostic probes: passing assertions confirm current defects, not desired behavior. */
class QcCurrentProbeTest {
    @Test fun voiceCaptureBusyBranchCancelsItsOwnScope() {
        System.setProperty("java.awt.headless", "true")
        val busy = mutableStateOf(false)
        val disposed = AtomicBoolean()
        val submitted = AtomicBoolean()
        var start: (() -> Unit)? = null
        var work: Job? = null
        val gate = CompletableDeferred<Unit>()
        // Minimal reproduction of SpeakingScreen's !busy branch + VoiceCapture's owned scope.
        @Composable fun Capture() {
            val scope = rememberCoroutineScope()
            DisposableEffect(Unit) { onDispose { disposed.set(true) } }
            SideEffect {
                start = {
                    busy.value = true
                    work = scope.launch {
                        withContext(Dispatchers.IO) { gate.await() }
                        busy.value = false
                        submitted.set(true)
                    }
                }
            }
        }
        val scene = ImageComposeScene(100, 100, Density(1f)) { if (!busy.value) Capture() }
        try {
            scene.render(0)
            assertNotNull(start).invoke()
            repeat(20) { scene.render((it + 1) * 16_000_000L); Thread.sleep(5) }
            gate.complete(Unit)
            repeat(20) { scene.render((it + 21) * 16_000_000L); Thread.sleep(5) }
            println("capture-lifecycle: disposed=${disposed.get()}, cancelled=${work?.isCancelled}, busy=${busy.value}, submitted=${submitted.get()}")
            assertTrue(disposed.get())
            assertTrue(assertNotNull(work).isCancelled)
            assertTrue(busy.value)
            assertFalse(submitted.get())
        } finally { scene.close() }
    }

    @Test fun vadMistakesImmediateSteadySpeechForNoAudio() {
        val vad = Vad()
        val events = List(40) { vad.feed(0.15) }.filterNotNull()
        println("vad-immediate-speech: events=$events, heard=${vad.heardSpeech}")
        assertEquals(listOf(Vad.Event.NO_AUDIO), events)
        assertFalse(vad.heardSpeech)
        val control = Vad()
        val controlEvents = (List(5) { 0.003 } + List(40) { 0.15 }).mapNotNull { control.feed(it) }
        assertEquals(listOf(Vad.Event.SPEECH_START), controlEvents)
    }

    @Test fun topicReplyWaitsForAfterActionCritique() = runBlocking<Unit> {
        val critiqueStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val model = object : LanguageModel {
            override val id = "qc-fake"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult {
                calls++
                if (calls == 1) return CompletionResult("""{"reply":"¿Y luego?","reply_english":"And then?"}""", id, null)
                critiqueStarted.complete(Unit)
                release.await()
                return CompletionResult("""{"corrected":"Me llamo Ana.","changes":[],"rewrite":"Me llamo Ana.","vocabulary":[],"turn_level":"1","pragmatics":[]}""", id, null)
            }
        }
        val session = TopicSession("es", OpiProfile("es", "usted"), Topic("qc", "daily_life", "Names", "¿Cómo se llama?"),
            AiGateway({ model }), mode = CorrectionsMode.AFTER_ACTION)
        val result = async { session.say("Me llamo Ana.") }
        withTimeout(3_000) { critiqueStarted.await() }
        println("topic-after-action: replyGenerated=true, returnedToVoice=${result.isCompleted}, modelCalls=$calls")
        assertFalse(result.isCompleted)
        assertTrue(session.exchanges.isEmpty())
        release.complete(Unit)
        assertEquals("¿Y luego?", assertNotNull(result.await()).reply)
    }
}
