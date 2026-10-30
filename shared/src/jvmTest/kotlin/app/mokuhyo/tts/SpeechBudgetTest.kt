package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.SpeechBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QC 2026-10-30 V6: synthesis and fallback voices share one output-start budget per response. Virtual time: a fake
 * engine that takes [ms] per request (cancellable), as a slow Piper with its 20 s + 150 ms/char per-request timeout would.
 */
class SpeechBudgetTest {
    private class SlowEngine(private val ms: Long, private val ids: List<String>, private val failing: Set<String> = emptySet()) : SpeechEngine {
        override val engine = "slow"
        val cancelledAt = ArrayList<Long>()
        var now: () -> Long = { 0 }
        override fun voicesFor(language: String) = ids.map { VoiceSpec(it, language, "female", engine, "test") }
        override suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray? = try {
            delay(ms)
            if (voice.id in failing) null else AudioIO.wav(ShortArray(1_600) { 1_000 })
        } catch (e: CancellationException) {
            cancelledAt += now()
            throw e
        }
    }

    @Test
    fun aSlowEngineIsCutOffAtTheBudgetAndCancelledPromptly() = runTest {
        val slow = SlowEngine(10_000, listOf("a")).also { e -> e.now = { testScheduler.currentTime } }
        val got = withContext(SpeechBudget(3_000) { testScheduler.currentTime }) { VoiceService(listOf(slow)).synthesize("Hola.", "es") }
        assertNull(got, "nothing was ready in time: the caller shows its ready fallback (the text)")
        assertTrue(testScheduler.currentTime <= 3_000 + 250, "gave up at ${testScheduler.currentTime} ms")
        assertEquals(1, slow.cancelledAt.size)
        assertTrue(slow.cancelledAt.single() - 3_000 <= 250, "synthesis cancelled at ${slow.cancelledAt.single()} ms")
    }

    @Test
    fun fallbackVoicesShareTheBudgetInsteadOfEachGettingTheirOwnTimeout() = runTest {
        // Voice a takes 3 s and fails, voice b would take 3 s more: on main the wait was 6 s (each attempt had its own timeout).
        val slow = SlowEngine(3_000, listOf("a", "b"), failing = setOf("a")).also { e -> e.now = { testScheduler.currentTime } }
        val got = withContext(SpeechBudget(4_000) { testScheduler.currentTime }) { VoiceService(listOf(slow)).synthesize("Hola.", "es") }
        assertNull(got)
        assertTrue(testScheduler.currentTime <= 4_000 + 250, "total wait ${testScheduler.currentTime} ms")
    }

    @Test
    fun withinBudgetNothingChangesAndAStartedReplyIsNeverCutOff() = runTest {
        val slow = SlowEngine(1_000, listOf("a"))
        val budget = SpeechBudget(500) { testScheduler.currentTime }
        budget.start() // audio already playing: V10's policy applies, not the budget
        assertNotNull(withContext(budget) { VoiceService(listOf(slow)).synthesize("Hola.", "es") })
        assertNotNull(withContext(SpeechBudget(5_000) { testScheduler.currentTime }) { VoiceService(listOf(slow)).synthesize("Hola.", "es") })
        assertNotNull(VoiceService(listOf(slow)).synthesize("Hola.", "es"), "no budget, no limit (as before)")
    }
}
