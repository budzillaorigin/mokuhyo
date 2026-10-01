package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The operating system's own text-to-speech, used as the fallback voice (BRIEF §3.3): macOS `say`, Windows SAPI
 * through PowerShell. Linux has no guaranteed system voice, so it relies on the bundled voices. Renders to a WAV
 * file, never speaks directly, so the app's player, speed control and recordings work the same for every source.
 *
 * Voices are picked from the OS's own list ([voices]: `say -v ?`, SAPI `GetInstalledVoices`) by locale. When no
 * installed voice matches the language, [synthesize] returns null rather than letting the default (English) voice
 * read the text, which would be junk audio.
 */
object OsVoice {
    /** One installed system voice. [locale] as the OS reports it (`ja_JP`, `ar_001`, `es-ES`). */
    data class Info(val name: String, val locale: String, val gender: String)

    /** Preferred regions per app language, best first; a voice of any other region of the language ranks after. */
    private val preferredRegions = mapOf(
        "ja" to listOf("JP"), "es" to listOf("ES", "MX"), "fr" to listOf("FR", "CA"), "de" to listOf("DE", "AT", "CH"),
        "ru" to listOf("RU"), "ko" to listOf("KR"), "ar" to listOf("001", "SA", "EG"), "fa" to listOf("IR"),
        "id" to listOf("ID"),
    )

    /**
     * Gender of macOS voices (`say` doesn't report it). Base names: "Eddy (German (Germany))" → "Eddy",
     * "Kyoko (Enhanced)" → "Kyoko". Voices not listed here are "unknown".
     */
    private val macGenders: Map<String, String> = buildMap {
        listOf(
            "Kyoko", "O-ren", "Yuna", "Sora", "Mónica", "Monica", "Paulina", "Marisol", "Angélica", "Isabela",
            "Amélie", "Amelie", "Audrey", "Aurélie", "Marie", "Anna", "Petra", "Helena", "Luciana", "Fernanda",
            "Milena", "Katya", "Tingting", "Ting-Ting", "Lili", "Lilian", "Shasha", "Yu-shu", "Meijia", "Sinji",
            "Laila", "Mariam", "Damayanti", "Dina", "Flo", "Grandma", "Sandy", "Shelley", "Samantha", "Alice",
        ).forEach { put(it, "female") }
        listOf(
            "Otoya", "Hattori", "Minsu", "Jorge", "Juan", "Diego", "Carlos", "Thomas", "Jacques", "Nicolas", "Markus",
            "Yannick", "Viktor", "Felipe", "Yuri", "Li-Mu", "Han", "Maged", "Majed", "Tarik", "Eddy", "Grandpa",
            "Reed", "Rocko", "Fred", "Daniel", "Alex",
        ).forEach { put(it, "male") }
    }

    /** Eloquence voices (Eddy, Flo, …) sound more synthetic than the language's own voices; rank them last. */
    private val eloquence = setOf("Eddy", "Flo", "Grandma", "Grandpa", "Reed", "Rocko", "Sandy", "Shelley")

    val available: Boolean get() = Os.current == Os.MACOS || Os.current == Os.WINDOWS

    @Volatile private var cached: List<Info>? = null

    /** Every voice installed on this computer (listed once per process; empty on Linux or on failure). */
    fun voices(): List<Info> {
        cached?.let { return it }
        val list = runCatching {
            when (Os.current) {
                Os.MACOS -> parseSayVoices(run(listOf("say", "-v", "?"), 10))
                Os.WINDOWS -> parseSapiVoices(run(powershell(listScript), 20))
                Os.LINUX -> emptyList()
            }
        }.getOrDefault(emptyList())
        cached = list
        return list
    }

    /** Installed voices that speak [lang] (BCP-47 app code), best first: preferred region, own voices, enhanced. */
    fun voicesFor(lang: String, all: List<Info> = voices()): List<Info> {
        val code = lang.lowercase()
        val base = code.substringBefore('-')
        return all.filter { matches(code, it.locale) }.sortedWith(
            compareBy<Info>(
                { regionRank(base, it.locale) },
                { if (baseName(it.name) in eloquence) 1 else 0 },
                { if (it.name.contains("Premium")) 0 else if (it.name.contains("Enhanced")) 1 else 2 },
                { it.name },
            ),
        )
    }

    /** [voicesFor] as app [VoiceSpec]s (`engine = "os"`, id `os:<name>`). */
    fun specsFor(lang: String): List<VoiceSpec> = voicesFor(lang).map { v ->
        VoiceSpec(id = "os:${v.name}", language = lang, gender = v.gender, engine = "os", license = "operating-system voice (${Os.current.id})")
    }

    /** WAV bytes for [text] in [lang], or null when this OS has no voice for [lang]. */
    fun synthesize(text: String, lang: String, rate: Double = 1.0): ByteArray? = synthesize(text, lang, rate, null)

    /**
     * WAV bytes for [text] in [lang] spoken by [voiceName] (one of [voices]; null = the best voice for [lang]), or
     * null when no installed voice speaks [lang]. Blocks up to 60 s; interrupting the thread kills the process.
     */
    fun synthesize(text: String, lang: String, rate: Double, voiceName: String?): ByteArray? {
        val candidates = voicesFor(lang)
        val voice = (voiceName?.let { n -> candidates.firstOrNull { it.name == n } } ?: candidates.firstOrNull())?.name
            ?: return null
        val out = File.createTempFile("mokuhyo-osvoice", ".wav")
        val input = File.createTempFile("mokuhyo-osvoice", ".txt").apply { writeText(text, Charsets.UTF_8) }
        try {
            val cmd = when (Os.current) {
                Os.MACOS -> listOf(
                    "say", "-v", voice, "-r", (175 * rate).toInt().toString(), "-o", out.absolutePath,
                    "--file-format=WAVE", "--data-format=LEI16@22050", "-f", input.absolutePath,
                )
                Os.WINDOWS -> powershell(
                    """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    ${'$'}s.SelectVoice('${ps(voice)}')
                    ${'$'}s.Rate = ${((rate - 1.0) * 10).toInt().coerceIn(-10, 10)}
                    ${'$'}s.SetOutputToWaveFile('${ps(out.absolutePath)}')
                    ${'$'}s.Speak([IO.File]::ReadAllText('${ps(input.absolutePath)}', [Text.Encoding]::UTF8))
                    ${'$'}s.Dispose()
                    """.trimIndent(),
                )
                Os.LINUX -> return null
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return null
                }
            } catch (e: InterruptedException) {
                p.destroyForcibly()
                throw e
            }
            return if (p.exitValue() == 0 && out.length() > 44) out.readBytes() else null
        } finally {
            out.delete()
            input.delete()
        }
    }

    // ---- listing ----

    private val listScript = """
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled } | ForEach-Object { ${'$'}i = ${'$'}_.VoiceInfo; "${'$'}(${'$'}i.Name)|${'$'}(${'$'}i.Culture.Name)|${'$'}(${'$'}i.Gender)" }
${'$'}s.Dispose()
"""

    /** Parses `say -v ?` lines: `Kyoko               ja_JP    # こんにちは…`, `Eddy (German (Germany)) de_DE    # …`. */
    internal fun parseSayVoices(output: String): List<Info> {
        val line = Regex("""^(.+?)\s+([a-z]{2,3}(?:_[A-Za-z0-9]{2,4})?)\s+#""")
        return output.lineSequence().mapNotNull { l ->
            val m = line.find(l.trimEnd()) ?: return@mapNotNull null
            val name = m.groupValues[1].trim()
            Info(name, m.groupValues[2], macGenders[baseName(name)] ?: "unknown")
        }.toList()
    }

    /** Parses `name|culture|gender` lines from the SAPI listing script. */
    internal fun parseSapiVoices(output: String): List<Info> = output.lineSequence().mapNotNull { l ->
        val parts = l.trim().split('|')
        if (parts.size < 3 || parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
        val gender = when (parts[2].trim().lowercase()) {
            "female" -> "female"
            "male" -> "male"
            else -> "unknown"
        }
        Info(parts[0].trim(), parts[1].trim(), gender)
    }.toList()

    /** Does an OS locale (`ja_JP`, `zh-CN`, `ar_001`) speak app language [code] (`ja`, `zh-hans`, `pt-br`)? */
    internal fun matches(code: String, locale: String): Boolean {
        val parts = locale.replace('_', '-').split('-')
        val lang = parts[0].lowercase()
        val rest = parts.drop(1).map { it.uppercase() }
        return when (code) {
            "zh-hans" -> lang == "zh" && ("CN" in rest || "SG" in rest || "HANS" in rest)
            "pt-br" -> lang == "pt" && "BR" in rest
            else -> lang == code.substringBefore('-') && (code.substringAfter('-', "").isEmpty() ||
                code.substringAfter('-').uppercase() in rest)
        }
    }

    private fun regionRank(base: String, locale: String): Int {
        val region = locale.replace('_', '-').split('-').drop(1).lastOrNull()?.uppercase() ?: return 50
        val prefs = preferredRegions[base] ?: return 0
        val i = prefs.indexOf(region)
        return if (i >= 0) i else 50
    }

    private fun baseName(name: String): String = name.substringBefore(" (").trim()

    private fun ps(s: String): String = s.replace("'", "''")

    private fun powershell(script: String): List<String> =
        listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)

    private fun run(cmd: List<String>, timeoutSeconds: Long): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { out.append(p.inputStream.bufferedReader(Charsets.UTF_8).readText()) }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("${cmd.first()} timed out")
        }
        reader.join(2_000)
        return synchronized(out) { out.toString() }
    }
}

/** [OsVoice] as a [SpeechEngine] for [VoiceService]: cancellation interrupts the render and kills `say`/PowerShell. */
class OsSpeechEngine : SpeechEngine {
    override val engine: String = "os"

    override fun voicesFor(language: String): List<VoiceSpec> = OsVoice.specsFor(language)

    override suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray? =
        runInterruptible(Dispatchers.IO) {
            OsVoice.synthesize(text, voice.language, speed, voice.id.removePrefix("os:"))
        }
}
