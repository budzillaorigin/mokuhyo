package app.mokuhyo.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app process's resident memory, for timing reports (BRIEF_INTERVIEW_SPEED N1). Native model weights live outside
 * the Java heap, so the heap size alone says nothing useful. Linux reports the true peak (`VmHWM`); macOS and Windows
 * report the current resident size, and callers sample it at each turn end and keep the maximum. Out-of-process voices
 * (Piper) are not included. Null when the platform can't tell.
 */
object ProcessMemory {
    fun peakRssBytes(): Long? = runCatching {
        when (Os.current) {
            Os.LINUX -> status("VmHWM") ?: status("VmRSS")
            Os.MACOS -> command("ps", "-o", "rss=", "-p", pid())?.trim()?.toLongOrNull()?.times(1024)
            Os.WINDOWS -> command("tasklist", "/FI", "PID eq ${pid()}", "/FO", "CSV", "/NH")
                ?.let { line -> Regex("\"([\\d.,\\s\\u00a0]+) K\"").find(line)?.groupValues?.get(1) }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024)
        }
    }.getOrNull()

    private fun pid() = ProcessHandle.current().pid().toString()

    private fun status(key: String): Long? = File("/proc/self/status").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)

    private fun command(vararg cmd: String): String? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        return out.takeIf { p.exitValue() == 0 }
    }
}
