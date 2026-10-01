package app.mokuhyo.tts

import app.mokuhyo.platform.Os
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The operating system's own text-to-speech, used as the fallback voice (BRIEF §3.3): macOS `say`, Windows SAPI
 * through PowerShell. Linux has no guaranteed system voice, so it relies on the bundled voices. Renders to a WAV
 * file, never speaks directly, so the app's player, speed control and recordings work the same for every source.
 */
object OsVoice {
    /** macOS voice names per language (preinstalled on current macOS; others fall back to the default voice). */
    private val macVoices = mapOf(
        "ja" to "Kyoko", "es" to "Monica", "fr" to "Thomas", "de" to "Anna", "pt-BR" to "Luciana", "ru" to "Milena",
        "zh-Hans" to "Tingting", "ko" to "Yuna", "ar" to "Maged", "id" to "Damayanti",
    )

    /** Windows culture names for SAPI voice selection. */
    private val windowsCultures = mapOf(
        "ja" to "ja-JP", "es" to "es-ES", "fr" to "fr-FR", "de" to "de-DE", "pt-BR" to "pt-BR", "ru" to "ru-RU",
        "zh-Hans" to "zh-CN", "ko" to "ko-KR", "ar" to "ar-SA", "fa" to "fa-IR", "id" to "id-ID",
    )

    val available: Boolean get() = Os.current == Os.MACOS || Os.current == Os.WINDOWS

    /** WAV bytes for [text] in [lang], or null when this OS has no usable voice for it. */
    fun synthesize(text: String, lang: String, rate: Double = 1.0): ByteArray? {
        val out = File.createTempFile("mokuhyo-osvoice", ".wav")
        val input = File.createTempFile("mokuhyo-osvoice", ".txt").apply { writeText(text, Charsets.UTF_8) }
        try {
            val cmd = when (Os.current) {
                Os.MACOS -> buildList {
                    add("say")
                    macVoices[lang]?.let { addAll(listOf("-v", it)) }
                    addAll(listOf("-r", (175 * rate).toInt().toString(), "-o", out.absolutePath, "--file-format=WAVE", "--data-format=LEI16@22050", "-f", input.absolutePath))
                }
                Os.WINDOWS -> {
                    val culture = windowsCultures[lang] ?: return null
                    val script = """
                        Add-Type -AssemblyName System.Speech
                        ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                        ${'$'}v = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -eq '$culture' } | Select-Object -First 1
                        if (-not ${'$'}v) { exit 3 }
                        ${'$'}s.SelectVoice(${'$'}v.VoiceInfo.Name)
                        ${'$'}s.Rate = ${((rate - 1.0) * 10).toInt()}
                        ${'$'}s.SetOutputToWaveFile('${out.absolutePath}')
                        ${'$'}s.Speak([IO.File]::ReadAllText('${input.absolutePath}', [Text.Encoding]::UTF8))
                        ${'$'}s.Dispose()
                    """.trimIndent()
                    listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                }
                Os.LINUX -> return null
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            return if (p.exitValue() == 0 && out.length() > 44) out.readBytes() else null
        } finally {
            out.delete()
            input.delete()
        }
    }
}
