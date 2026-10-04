package app.mokuhyo.desktop

import app.mokuhyo.ai.AiCall
import java.io.File
import java.time.Instant

/**
 * `<data dir>/logs/mokuhyo.log` (BRIEF_PHASE8 N-00b): one line per AI call — time, task, engine, duration, outcome and
 * the reason when it failed — so "the model didn't answer" can be diagnosed after the fact. Rotates at [maxBytes]
 * keeping [keep] old files (`mokuhyo.log.1` …). Holds no prompt or learner text. Stays on this computer.
 */
class RollingLog(dir: File, private val maxBytes: Long = 512 * 1024, private val keep: Int = 3) {
    val file = File(dir, "mokuhyo.log")

    @Synchronized
    fun line(text: String) {
        runCatching {
            file.parentFile.mkdirs()
            if (file.length() > maxBytes) rotate()
            file.appendText("${Instant.now()} $text\n")
        }
    }

    fun ai(call: AiCall) = line(
        "ai task=${call.task} engine=${call.engine ?: "-"} ms=${call.ms} outcome=${call.outcome}" +
            (call.reason?.let { " reason=\"${it.replace('"', '\'').replace('\n', ' ').take(300)}\"" } ?: ""),
    )

    private fun rotate() {
        for (i in keep - 1 downTo 1) File(file.path + ".$i").takeIf { it.exists() }?.renameTo(File(file.path + ".${i + 1}"))
        file.renameTo(File(file.path + ".1"))
        File(file.path + ".${keep + 1}").delete()
    }

    /** The last [n] lines, newest last (Settings → AI). */
    fun tail(n: Int = 20): List<String> = runCatching { file.readLines().takeLast(n) }.getOrDefault(emptyList())
}
