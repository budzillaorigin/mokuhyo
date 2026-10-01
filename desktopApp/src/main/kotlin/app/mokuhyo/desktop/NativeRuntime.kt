package app.mokuhyo.desktop

import app.mokuhyo.ai.GpuInfo
import app.mokuhyo.ai.LocalLlmBridge
import app.mokuhyo.ai.LocalSttBridge

/** The embedded inference engines (llama.cpp + whisper.cpp over JNI), or why they aren't available. */
interface NativeRuntime {
    /** One line for Settings → AI, e.g. "Metal (GPU)" or "CPU — GPU library failed to load: …". */
    val status: String
    val available: Boolean
    val gpuActive: Boolean
    fun gpus(): List<GpuInfo>
    fun llm(): LocalLlmBridge?
    fun stt(): LocalSttBridge?

    class Unavailable(private val reason: String) : NativeRuntime {
        override val status get() = "Not available: $reason"
        override val available = false
        override val gpuActive = false
        override fun gpus() = emptyList<GpuInfo>()
        override fun llm(): LocalLlmBridge? = null
        override fun stt(): LocalSttBridge? = null
    }

    companion object {
        fun create(preferCpu: Boolean): NativeRuntime = JniRuntime.tryCreate(preferCpu)
    }
}
