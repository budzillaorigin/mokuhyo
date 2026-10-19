package app.mokuhyo.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QC follow-up F3: leaving the Listening question while its output line is opening, or at any later point, closes
 * the line exactly once, and a play is counted only once playback is about to start. A proxy line records what was
 * done to it, so no audio hardware is needed.
 */
class PlaybackCancelTest {
    private class ProxyLine {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val writes = AtomicInteger()
        val writing = CountDownLatch(1)
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, m, args ->
            when (m.name) {
                "open" -> { opened.incrementAndGet(); null }
                "close" -> { closed.incrementAndGet(); null }
                "write" -> { writes.incrementAndGet(); writing.countDown(); Thread.sleep(5); args!![2] as Int }
                "isOpen", "isActive", "isRunning" -> false
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "ProxyLine"
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as SourceDataLine

        /** Ten seconds of silence: long enough to be cancelled mid-playback. */
        fun playback(): AudioInputStream =
            AudioInputStream(ByteArrayInputStream(ByteArray(320_000)), AudioFormat(16_000f, 16, 1, true, false), 160_000)
    }

    private class Run(val line: ProxyLine, val counted: AtomicInteger)

    /** Starts playOwned on another thread; [open] decides when (and how) the line opens. */
    private fun start(scope: kotlinx.coroutines.CoroutineScope, gate: CountDownLatch? = null, stop: () -> Boolean = { false },
                      open: (ProxyLine, Job) -> Unit = { l, _ -> l.line.open() }): Pair<Run, Job> {
        val line = ProxyLine()
        val counted = AtomicInteger()
        val job = scope.launch(Dispatchers.Default) {
            gate?.await() // blocking on purpose: the job is cancelled while it can't notice
            val self = coroutineContext.job
            AudioIO.playOwned(
                open = { open(line, self); AudioIO.Playback(line.line, line.playback()) },
                stop = stop,
                started = { counted.incrementAndGet() },
            )
        }
        return Run(line, counted) to job
    }

    @Test
    fun cancelledBeforeOpenNeverOpens() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        val (run, job) = start(this, gate)
        job.cancel()
        gate.countDown()
        withTimeout(5_000) { job.join() }
        assertEquals(0, run.line.opened.get())
        assertEquals(0, run.line.closed.get())
        assertEquals(0, run.counted.get())
    }

    @Test
    fun cancelledDuringOpenClosesOnceAndCountsNothing() = runBlocking<Unit> {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (run, job) = start(this) { l, _ ->
            l.line.open()
            opening.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        withTimeout(5_000) { job.join() }
        assertEquals(1, run.line.opened.get())
        assertEquals(1, run.line.closed.get())
        assertEquals(0, run.counted.get())
        assertEquals(0, run.line.writes.get())
    }

    @Test
    fun cancelledRightAfterOpenClosesOnceAndCountsNothing() = runBlocking<Unit> {
        // The line has opened and the caller is cancelled before control gets back to it.
        val (run, job) = start(this) { l, self -> l.line.open(); self.cancel() }
        withTimeout(5_000) { job.join() }
        assertEquals(1, run.line.opened.get())
        assertEquals(1, run.line.closed.get())
        assertEquals(0, run.counted.get())
        assertEquals(0, run.line.writes.get())
    }

    @Test
    fun stoppedWhileOpeningClosesOnceAndCountsNothing() = runBlocking<Unit> {
        val stop = AtomicBoolean(false)
        val (run, job) = start(this, stop = { stop.get() }) { l, _ -> l.line.open(); stop.set(true) }
        withTimeout(5_000) { job.join() }
        assertEquals(1, run.line.closed.get())
        assertEquals(0, run.counted.get())
    }

    @Test
    fun cancelledDuringPlaybackStopsAndClosesOnce() = runBlocking<Unit> {
        val (run, job) = start(this)
        assertTrue(run.line.writing.await(5, TimeUnit.SECONDS))
        withTimeout(2_000) { // well before the ten seconds of audio would end
            job.cancel()
            job.join()
        }
        assertEquals(1, run.counted.get(), "playback had started, so the play counts")
        assertEquals(1, run.line.closed.get())
        assertTrue(run.line.writes.get() < 70, "stopped early: ${run.line.writes.get()} writes")
    }
}
