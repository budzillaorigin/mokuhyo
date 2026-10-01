package app.mokuhyo.ai.jni

import java.io.File
import java.util.Locale

/**
 * Finds and loads `mokuhyo_native` (llama.cpp + whisper.cpp + ggml, built by `native/build.sh|ps1`), once per JVM.
 *
 * Layout: `<dir>/<variant>/<System.mapLibraryName("mokuhyo_native")>`, variant ∈ {metal, vulkan, cpu}. `<dir>` is the
 * first of these that holds a variant:
 * 1. system property `mokuhyo.native.dir` (or env `MOKUHYO_NATIVE_DIR`);
 * 2. `<compose.application.resources.dir>/native` (the packaged app);
 * 3. `native/build/<os>-<arch>/` under the working directory or any of its parents (a dev checkout).
 *
 * The GPU variant (metal on macOS, vulkan on Windows/Linux) is tried first unless [preferCpu] is set (system property
 * `mokuhyo.native.preferCpu=true`, env `MOKUHYO_NATIVE_PREFER_CPU=1`, or [configure] before the first load); if it
 * fails to load (no Vulkan loader, missing file…) the CPU variant is loaded instead and [status] says why, for
 * Settings to show. A JVM can hold only one variant, so the choice is fixed after the first [load].
 */
object NativeLibrary {
    const val LIBRARY = "mokuhyo_native"

    /** What loaded. [variant] is null when nothing could be loaded; then [error] says why. */
    data class Status(
        /** "metal", "vulkan" or "cpu"; null when no library loaded. */
        val variant: String?,
        /** Absolute path of the loaded library. */
        val path: String?,
        /**
         * Why the GPU isn't used: the GPU variant failed to load (or "preferCpu") and the CPU variant loaded instead,
         * or the GPU variant loaded but found no GPU device. Null when the GPU is in use.
         */
        val gpuError: String?,
        /** Why nothing loaded (null when a variant loaded). */
        val error: String?,
        /** True when the loaded variant has a GPU backend with at least one GPU device: offload layers to it. */
        val gpuActive: Boolean,
        /** Directories searched, in order, whether or not they exist (for the error message and logs). */
        val searched: List<String>,
    ) {
        val isLoaded: Boolean get() = variant != null
    }

    @Volatile private var preferCpuOverride: Boolean? = null
    @Volatile private var loaded: Status? = null

    /** Platform key used in the dev build path, e.g. `macos-arm64`, `windows-x86_64`, `linux-x86_64`. */
    val platform: String = "${osName()}-${archName()}"

    /** The GPU variant for this OS: metal on macOS, vulkan elsewhere. */
    val gpuVariant: String = if (osName() == "macos") "metal" else "vulkan"

    /**
     * Sets [preferCpu] for this process. Effective only before the first [load]; returns false when the library is
     * already loaded (the new choice then applies after a restart).
     */
    @Synchronized
    fun configure(preferCpu: Boolean): Boolean {
        preferCpuOverride = preferCpu
        return loaded == null
    }

    val preferCpu: Boolean
        get() = preferCpuOverride
            ?: (System.getProperty("mokuhyo.native.preferCpu")?.toBooleanStrictOrNull()
                ?: System.getenv("MOKUHYO_NATIVE_PREFER_CPU")?.let { it == "1" || it.equals("true", ignoreCase = true) }
                ?: false)

    /** Load status, or null before the first [load]. */
    val status: Status? get() = loaded

    /** Loads the library once (thread-safe) and returns the status; later calls return the same status. */
    @Synchronized
    fun load(): Status {
        loaded?.let { return it }
        val dirs = searchDirs()
        val status = loadFrom(dirs)
        loaded = status
        return status
    }

    /** True when [load] succeeded. */
    fun isAvailable(): Boolean = load().isLoaded

    /** `llama_print_system_info()`: CPU features and backends compiled in, for logs. Null when not loaded. */
    fun systemInfo(): String? = if (isAvailable()) LlamaNative.nativeSystemInfo() else null

    /**
     * Threads for CPU inference: performance cores where the OS reports them (Apple silicon), else physical cores,
     * else half the logical processors; at least 2, at most 16.
     */
    fun recommendedThreads(): Int {
        val reported = if (isAvailable()) runCatching { GgmlNative.nativePerformanceCores() }.getOrDefault(0) else 0
        val physical = reported.takeIf { it > 0 } ?: linuxPhysicalCores() ?: (Runtime.getRuntime().availableProcessors() / 2)
        return physical.coerceIn(2, 16)
    }

    private fun loadFrom(dirs: List<File>): Status {
        val searched = dirs.map { it.path }
        val name = System.mapLibraryName(LIBRARY)
        // The first directory holding any variant wins, so a packaged app never mixes in a dev build.
        val dir = dirs.firstOrNull { d -> listOf(gpuVariant, "cpu").any { File(File(d, it), name).isFile } }
        fun find(variant: String): File? = dir?.let { File(File(it, variant), name) }?.takeIf { it.isFile }

        var gpuError: String? = null
        if (preferCpu) {
            gpuError = "preferCpu"
        } else {
            val gpu = find(gpuVariant)
            if (gpu == null) {
                gpuError = "no $gpuVariant build found"
            } else {
                try {
                    System.load(gpu.absolutePath)
                    val active = hasGpuDevice()
                    val note = if (active) null else "the $gpuVariant build found no GPU device; running on the CPU"
                    return Status(gpuVariant, gpu.absolutePath, note, null, active, searched)
                } catch (e: Throwable) { // UnsatisfiedLinkError, SecurityException
                    gpuError = "${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        val cpu = find("cpu")
            ?: return Status(null, null, gpuError, "$name not found under <dir>/{$gpuVariant,cpu}/ for dir in ${searched.joinToString()}", false, searched)
        return try {
            System.load(cpu.absolutePath)
            Status("cpu", cpu.absolutePath, gpuError, null, false, searched)
        } catch (e: Throwable) {
            Status(null, cpu.absolutePath, gpuError, "${e.javaClass.simpleName}: ${e.message}", false, searched)
        }
    }

    /** A GPU build on a machine without a usable GPU (e.g. no Vulkan driver) still runs, on the CPU. */
    private fun hasGpuDevice(): Boolean = runCatching {
        (0 until GgmlNative.nativeDeviceCount()).any { i ->
            val type = GgmlNative.nativeDeviceInfo(i)?.getOrNull(3)
            type == "gpu" || type == "igpu"
        }
    }.getOrDefault(false)

    internal fun searchDirs(): List<File> {
        val out = mutableListOf<File>()
        (System.getProperty("mokuhyo.native.dir") ?: System.getenv("MOKUHYO_NATIVE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let { out += File(it) }
        System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }
            ?.let { out += File(it, "native") }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            out += File(dir, "native/build/$platform")
            dir = dir.parentFile
        }
        return out.distinctBy { it.absolutePath }
    }

    internal fun osName(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        return when {
            os.startsWith("mac") || os.contains("darwin") -> "macos"
            os.startsWith("windows") -> "windows"
            else -> "linux"
        }
    }

    internal fun archName(): String = when (System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64", "x64" -> "x86_64"
        else -> System.getProperty("os.arch").orEmpty()
    }

    /** Unique (physical id, core id) pairs in /proc/cpuinfo; null when unavailable. */
    private fun linuxPhysicalCores(): Int? = runCatching {
        val file = File("/proc/cpuinfo")
        if (!file.isFile) return null
        var physical = ""
        val cores = mutableSetOf<String>()
        file.forEachLine { line ->
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "physical id" -> physical = value
                "core id" -> cores += "$physical/$value"
            }
        }
        cores.size.takeIf { it > 0 }
    }.getOrNull()

}

/** `llama_print_system_info()` of the loaded library, or null when it isn't loaded (for logs). */
fun nativeSystemInfo(): String? = NativeLibrary.systemInfo()
