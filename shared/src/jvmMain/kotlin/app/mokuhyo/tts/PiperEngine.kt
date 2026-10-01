package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The bundled Piper voices (BRIEF §3.3), run out-of-process: Piper is GPL-3 as a whole (it loads espeak-ng), so the
 * app only ever talks to it over stdin/stdout and never links it (CLAUDE.md rule 6).
 *
 * One long-running `piper --json-input` process per voice model: each request is one JSON line on stdin
 * (`{"text", "output_file", "length_scale", "speaker_id"}` — `length_scale` per line comes from voices/patch_piper.cmake),
 * and Piper answers with the output path on stdout once the WAV is written. Requests to one process are serialized.
 * A process with no request for [idleTimeoutMs] is stopped (stdin closed, then killed if it lingers). Every request
 * has a timeout; a timeout or a cancelled request kills the process (the next request starts a fresh one), because
 * Piper has no way to abort a sentence in flight.
 */
class PiperEngine(
    private val catalog: VoiceCatalog,
    private val idleTimeoutMs: Long = 120_000,
    private val requestTimeoutMs: (String) -> Long = { text -> (20_000L + 150L * text.length).coerceAtMost(180_000L) },
) : SpeechEngine, AutoCloseable {
    override val engine: String = "piper"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapLock = Mutex()
    private val processes = ConcurrentHashMap<String, PiperProcess>() // writes under mapLock
    private val counter = AtomicLong()
    private val workDir: File by lazy { Files.createTempDirectory("mokuhyo-piper").toFile() }

    override fun voicesFor(language: String): List<VoiceSpec> {
        if (catalog.piperExecutable == null || catalog.espeakData == null) return emptyList()
        return catalog.voices.filter { VoiceService.sameLanguage(it.spec.language, language) }.map { it.spec }
    }

    override suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray? = withContext(Dispatchers.IO) {
        if (voice.engine != engine) return@withContext null
        val pv = catalog.voices.firstOrNull { it.spec.id == voice.id } ?: return@withContext null
        val exe = catalog.piperExecutable ?: return@withContext null
        val espeak = catalog.espeakData ?: return@withContext null
        val lengthScale = pv.defaultLengthScale / speed.coerceIn(VoiceService.MIN_SPEED, VoiceService.MAX_SPEED)
        val out = File(workDir, "${counter.incrementAndGet()}.wav")
        val request = requestJson(text, out, voice.speaker ?: pv.spec.speaker, lengthScale)
        try {
            // A process can die between requests (crash, idle stop racing this call): start a fresh one once.
            repeat(2) {
                val proc = processFor(pv, exe, espeak)
                when (proc.speak(request, requestTimeoutMs(text))) {
                    PiperProcess.Result.OK -> {
                        proc.armIdle(scope, idleTimeoutMs) { retire(pv, proc) }
                        val bytes = if (out.isFile) out.readBytes() else null
                        return@withContext bytes?.takeIf { Wav.isValid(it) }
                    }
                    PiperProcess.Result.DEAD -> retire(pv, proc)
                    PiperProcess.Result.FAILED -> {
                        System.err.println("tts: piper ${pv.spec.id} failed: ${proc.stderrTail()}")
                        retire(pv, proc)
                        return@withContext null
                    }
                }
            }
            null
        } finally {
            out.delete()
        }
    }

    private suspend fun processFor(pv: VoiceCatalog.PiperVoice, exe: File, espeak: File): PiperProcess = mapLock.withLock {
        val key = pv.model.absolutePath
        processes[key]?.takeIf { it.alive }?.let { it.touch(); return it }
        val cmd = listOf(
            exe.absolutePath,
            "--model", pv.model.absolutePath,
            "--config", pv.config.absolutePath,
            "--espeak_data", espeak.absolutePath,
            "--json-input",
        )
        PiperProcess.start(cmd, workDir).also { processes[key] = it }
    }

    private suspend fun retire(pv: VoiceCatalog.PiperVoice, proc: PiperProcess) {
        mapLock.withLock {
            val key = pv.model.absolutePath
            if (processes[key] === proc) processes.remove(key)
        }
        proc.stop()
    }

    /** Live processes by model path (tests, Settings diagnostics). */
    internal fun liveProcesses(): Map<String, Process> =
        processes.filterValues { it.alive }.mapValues { it.value.process }

    override fun close() {
        scope.cancel()
        val all = processes.values.toList()
        processes.clear()
        all.forEach { it.stop() }
        workDir.takeIf { it.exists() }?.deleteRecursively()
    }

    internal companion object {
        /** One JSON line, ASCII only (non-ASCII as \u escapes) so no console code page can garble it. */
        fun requestJson(text: String, out: File, speaker: Int?, lengthScale: Double): String = buildString {
            append("{\"text\":").append(jsonString(text))
            append(",\"output_file\":").append(jsonString(out.absolutePath))
            append(",\"length_scale\":").append(String.format(java.util.Locale.ROOT, "%.4f", lengthScale))
            if (speaker != null) append(",\"speaker_id\":").append(speaker)
            append('}')
        }

        fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c.code < 0x20 || c.code > 0x7e -> append("\\u").append(String.format("%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

/** One running `piper --json-input`. */
internal class PiperProcess private constructor(val process: Process) {
    enum class Result { OK, DEAD, FAILED }

    private val stdin: OutputStream = process.outputStream
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val busy = Mutex()
    private val stderr = ArrayDeque<String>()
    @Volatile private var lastUsed = System.nanoTime()
    @Volatile private var idleJob: Job? = null

    val alive: Boolean get() = process.isAlive

    init {
        live.add(process)
        daemon("piper-stdout") {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { lines.trySend(it) }
            } catch (_: IOException) {
            } finally {
                lines.close()
                live.remove(process)
            }
        }
        daemon("piper-stderr") {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(stderr) {
                        stderr.addLast(line)
                        while (stderr.size > 20) stderr.removeFirst()
                    }
                }
            } catch (_: IOException) {
            }
        }
    }

    fun touch() {
        lastUsed = System.nanoTime()
    }

    fun stderrTail(): String = synchronized(stderr) { stderr.joinToString("\n") }

    /** Sends one request and waits for Piper's "done" line. Cancellation or timeout kills the process. */
    suspend fun speak(request: String, timeoutMs: Long): Result = busy.withLock {
        touch()
        try {
            if (!alive) return Result.DEAD
            try {
                stdin.write((request + "\n").toByteArray(Charsets.UTF_8))
                stdin.flush()
            } catch (_: IOException) {
                return Result.DEAD
            }
            val reply = try {
                withTimeoutOrNull(timeoutMs) { lines.receiveCatching() }
            } catch (e: CancellationException) {
                kill()
                throw e
            }
            when {
                reply == null -> { kill(); Result.FAILED } // timed out
                reply.isClosed -> Result.FAILED // Piper exited (bad input, crash)
                else -> Result.OK
            }
        } finally {
            touch()
        }
    }

    /** Stops the process after [idleMs] without a request, unless one is running then. */
    fun armIdle(scope: CoroutineScope, idleMs: Long, onIdle: suspend () -> Unit) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleMs)
            if (busy.tryLock()) {
                val idle = try {
                    System.nanoTime() - lastUsed >= TimeUnit.MILLISECONDS.toNanos(idleMs)
                } finally {
                    busy.unlock()
                }
                if (idle) onIdle()
            }
        }
    }

    /** Graceful stop: Piper exits at end of input; kill it if it hasn't within 2 s. */
    fun stop() {
        idleJob?.cancel()
        runCatching { stdin.close() }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) kill()
        } catch (_: InterruptedException) {
            kill()
            Thread.currentThread().interrupt()
        }
        live.remove(process)
    }

    fun kill() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        live.remove(process)
    }

    companion object {
        /** Every Piper process this JVM started; killed by a shutdown hook so none outlives the app. */
        private val live: MutableSet<Process> = ConcurrentHashMap.newKeySet<Process>().also { set ->
            Runtime.getRuntime().addShutdownHook(Thread({ set.forEach { it.destroyForcibly() } }, "piper-shutdown"))
        }

        fun start(cmd: List<String>, workDir: File): PiperProcess =
            PiperProcess(ProcessBuilder(cmd).directory(workDir).start())

        private fun daemon(name: String, body: () -> Unit) {
            Thread(body, name).apply { isDaemon = true }.start()
        }
    }
}
