package app.mokuhyo.desktop

import app.mokuhyo.ai.GpuInfo
import app.mokuhyo.ai.LocalLlmBridge
import app.mokuhyo.ai.LocalSttBridge
import app.mokuhyo.ai.jni.LlamaJniBridge
import app.mokuhyo.ai.jni.NativeDevices
import app.mokuhyo.ai.jni.NativeLibrary
import app.mokuhyo.ai.jni.WhisperJniBridge

/** llama.cpp + whisper.cpp over JNI (native/). One bridge of each per process; models load on first use. */
class JniRuntime private constructor(private val status0: NativeLibrary.Status) : NativeRuntime {
    private val llmBridge by lazy { LlamaJniBridge().also { created += { it.shutdown() } } }
    private val sttBridge by lazy { WhisperJniBridge().also { created += { it.shutdown() } } }
    private val created = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    init {
        // Free native models before exit (the Metal backend aborts if buffers outlive the process's static teardown).
        Runtime.getRuntime().addShutdownHook(Thread({ shutdown() }, "mokuhyo-native-shutdown"))
    }

    fun shutdown() {
        created.forEach { runCatching { it() } }
        created.clear()
    }

    override val available: Boolean get() = status0.isLoaded
    override val gpuActive: Boolean get() = status0.gpuActive

    override val status: String
        get() = when {
            !status0.isLoaded -> "Not available: ${status0.error}"
            status0.gpuActive -> "${status0.variant!!.replaceFirstChar { it.uppercase() }} (GPU)"
            status0.gpuError == "preferCpu" -> "CPU (GPU turned off in Settings)"
            status0.gpuError != null -> "CPU — ${status0.gpuError}"
            else -> "CPU"
        }

    override fun gpus(): List<GpuInfo> = if (!available) emptyList() else NativeDevices.list().map {
        GpuInfo(it.name, it.backend, it.totalBytes, unifiedMemory = it.integrated)
    }

    override fun llm(): LocalLlmBridge? = if (available) llmBridge else null
    override fun stt(): LocalSttBridge? = if (available) sttBridge else null

    companion object {
        fun tryCreate(preferCpu: Boolean): NativeRuntime {
            NativeLibrary.configure(preferCpu)
            return JniRuntime(NativeLibrary.load())
        }
    }
}
