package app.mokuhyo.platform

import app.mokuhyo.ai.GpuInfo
import app.mokuhyo.ai.HardwareInfo
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * Probes RAM, CPU cores, free disk and GPUs (BRIEF §6.1). GPUs come from the inference engine's own device list
 * when the native library is loaded ([nativeGpus], real VRAM as Vulkan/Metal report it); otherwise from small OS
 * queries with short timeouts. Runs on a background thread (it may spawn a process).
 */
object HardwareProbe {
    fun probe(dataDir: File, nativeGpus: () -> List<GpuInfo> = { emptyList() }): HardwareInfo {
        val os = Os.current
        val ram = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
        var dir: File? = dataDir
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val gpus = runCatching { nativeGpus() }.getOrDefault(emptyList()).filter { it.backend != "CPU" }
            .ifEmpty { osGpus(os, ram) }
        return HardwareInfo(
            os = os.id,
            arch = Os.arch,
            ramBytes = ram,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            freeDiskBytes = dir?.usableSpace,
            gpus = gpus,
        )
    }

    private fun osGpus(os: Os, ram: Long): List<GpuInfo> = runCatching {
        when (os) {
            Os.MACOS -> {
                val brand = run("sysctl", "-n", "machdep.cpu.brand_string").trim()
                if (brand.startsWith("Apple")) listOf(GpuInfo(brand, "Metal", ram, unifiedMemory = true)) else emptyList()
            }
            Os.LINUX -> nvidiaSmi().ifEmpty { amdSysfs() }
            Os.WINDOWS -> nvidiaSmi().ifEmpty { windowsRegistry() }
        }
    }.getOrDefault(emptyList())

    private fun nvidiaSmi(): List<GpuInfo> = runCatching {
        run("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits").lines().filter { it.isNotBlank() }.map { line ->
            val (name, mib) = line.split(",").map { it.trim() }
            GpuInfo(name, "Vulkan", mib.toLong() * 1024 * 1024)
        }
    }.getOrDefault(emptyList())

    private fun amdSysfs(): List<GpuInfo> = File("/sys/class/drm").listFiles().orEmpty()
        .filter { it.name.matches(Regex("card\\d+")) }
        .mapNotNull { card ->
            val vram = File(card, "device/mem_info_vram_total").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            vram?.let { GpuInfo("AMD GPU (${card.name})", "Vulkan", it) }
        }

    private fun windowsRegistry(): List<GpuInfo> {
        val script = "Get-ItemProperty 'HKLM:\\SYSTEM\\ControlSet001\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}\\0*' " +
            "-ErrorAction SilentlyContinue | ForEach-Object { \"\$(\$_.DriverDesc)|\$(\$_.'HardwareInformation.qwMemorySize')\" }"
        return run("powershell", "-NoProfile", "-NonInteractive", "-Command", script).lines().mapNotNull { line ->
            val parts = line.trim().split("|")
            val bytes = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            GpuInfo(parts[0], "Vulkan", bytes)
        }
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("${cmd.first()} timed out")
        }
        return out
    }
}

enum class Os(val id: String) {
    WINDOWS("windows"), MACOS("macos"), LINUX("linux");

    companion object {
        val current: Os by lazy {
            val name = System.getProperty("os.name").lowercase()
            when {
                name.contains("win") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MACOS
                else -> LINUX
            }
        }

        val arch: String by lazy {
            when (System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> "arm64"
                else -> "x86_64"
            }
        }
    }
}

/**
 * Per-OS data directory (BRIEF §3.3): `%APPDATA%/Mokuhyo`, `~/Library/Application Support/Mokuhyo`,
 * `~/.local/share/mokuhyo` (or `$XDG_DATA_HOME/mokuhyo`). `mokuhyo.data.dir` overrides it (tests, portable zip).
 */
object AppDirs {
    fun dataDir(): File {
        System.getProperty("mokuhyo.data.dir")?.let { return File(it) }
        val home = System.getProperty("user.home")
        return when (Os.current) {
            Os.WINDOWS -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Mokuhyo")
            Os.MACOS -> File(home, "Library/Application Support/Mokuhyo")
            Os.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "mokuhyo")
        }
    }

    fun ensure(root: File = dataDir()): File {
        listOf("db", "recordings", "models", "packs", "logs").forEach { File(root, it).mkdirs() }
        return root
    }
}
