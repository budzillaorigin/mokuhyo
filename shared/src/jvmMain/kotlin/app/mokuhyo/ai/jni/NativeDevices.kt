package app.mokuhyo.ai.jni

/**
 * A compute device as ggml sees it, for the hardware probe (BRIEF §6.1). On Apple silicon the Metal device is
 * unified memory: [totalBytes] is Metal's recommended working-set size, not separate VRAM.
 */
data class GpuDevice(
    /** Vendor description, e.g. "Apple M4" or "NVIDIA GeForce RTX 4070"; falls back to the ggml device name. */
    val name: String,
    /** "Metal", "Vulkan" or "CPU". */
    val backend: String,
    val totalBytes: Long,
    val freeBytes: Long,
    /** Integrated GPU sharing system memory (Apple silicon, most laptop iGPUs). */
    val integrated: Boolean = false,
)

/** The ggml backend device registry of the loaded native library (real GPU names and memory). */
object NativeDevices {
    /**
     * Every GPU device plus the CPU, as compiled into the loaded variant (the CPU build lists only the CPU).
     * Empty when the native library isn't available. Cheap; safe from any thread.
     */
    fun list(): List<GpuDevice> {
        if (!NativeLibrary.isAvailable()) return emptyList()
        return (0 until GgmlNative.nativeDeviceCount()).mapNotNull { i ->
            val info = GgmlNative.nativeDeviceInfo(i) ?: return@mapNotNull null
            val (name, description, registry, type) = info
            val backend = when {
                type == "cpu" -> "CPU"
                registry.equals("MTL", ignoreCase = true) || registry.contains("Metal", ignoreCase = true) -> "Metal"
                registry.contains("Vulkan", ignoreCase = true) -> "Vulkan"
                else -> return@mapNotNull null // accelerators (BLAS, …) aren't devices the probe cares about
            }
            val memory = GgmlNative.nativeDeviceMemory(i) ?: longArrayOf(0, 0)
            GpuDevice(
                name = description.ifBlank { name },
                backend = backend,
                totalBytes = memory[1],
                freeBytes = memory[0],
                integrated = type == "igpu" || backend == "Metal",
            )
        }
    }
}
