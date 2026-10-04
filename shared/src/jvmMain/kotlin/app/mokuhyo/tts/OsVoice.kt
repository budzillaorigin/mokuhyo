package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The operating system's own text-to-speech, used as the fallback voice (BRIEF §3.3): macOS `say`, Windows SAPI 5 and
 * WinRT `Windows.Media.SpeechSynthesis` (OneCore voices such as Haruka) through PowerShell. Linux has no guaranteed system voice, so it relies on the bundled voices. Renders to a WAV
 * file, never speaks directly, so the app's player, speed control and recordings work the same for every source.
 *
 * Voices are picked from the OS's own list ([voices]: `say -v ?`, SAPI `GetInstalledVoices`) by locale. When no
 * installed voice matches the language, [synthesize] returns null rather than letting the default (English) voice
 * read the text, which would be junk audio.
 */
object OsVoice {
    /**
     * One installed system voice. [locale] as the OS reports it (`ja_JP`, `ar_001`, `es-ES`). On Windows [api] says which
     * speech API owns it ("sapi" or "winrt") and [id] is the WinRT voice id used to select it.
     */
    data class Info(val name: String, val locale: String, val gender: String, val api: String = "", val id: String = "")

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

    /** Every voice installed on this computer (listed once per process until [rescan]; empty on Linux or on failure). */
    fun voices(): List<Info> {
        cached?.let { return it }
        val list = runCatching {
            when (Os.current) {
                Os.MACOS -> parseSayVoices(run(listOf("say", "-v", "?"), 10))
                Os.WINDOWS -> parseWindowsVoices(run(powershell(listScript), 30))
                Os.LINUX -> emptyList()
            }
        }.getOrDefault(emptyList())
        cached = list
        return list
    }

    /** Forgets the voice list so the next call lists the OS's voices again (Settings → "Rescan voices"). */
    fun rescan(): List<Info> {
        cached = null
        return voices()
    }

    /**
     * macOS only: when the only voices for [lang] are compact ones, a one-line hint with the System Settings path to a
     * natural (Enhanced or Premium) voice; null otherwise (BRIEF_PHASE8 N-00).
     */
    fun compactOnlyHint(lang: String, all: List<Info> = voices()): String? {
        if (Os.current != Os.MACOS) return null
        val vs = voicesFor(lang, all).filter { baseName(it.name) !in eloquence }
        if (vs.isEmpty() || vs.any { "Enhanced" in it.name || "Premium" in it.name }) return null
        return "Only the compact ${baseName(vs.first().name)} voice is installed. For a natural voice: System Settings → " +
            "Accessibility → Spoken Content → System voice → Manage Voices…, then download its Enhanced or Premium version " +
            "and press Rescan voices."
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
        val info = voiceName?.let { n -> candidates.firstOrNull { it.name == n } } ?: candidates.firstOrNull() ?: return null
        val voice = info.name
        val out = File.createTempFile("mokuhyo-osvoice", ".wav")
        val input = File.createTempFile("mokuhyo-osvoice", ".txt").apply { writeText(text, Charsets.UTF_8) }
        try {
            val cmd = when (Os.current) {
                Os.MACOS -> listOf(
                    "say", "-v", voice, "-r", (175 * rate).toInt().toString(), "-o", out.absolutePath,
                    "--file-format=WAVE", "--data-format=LEI16@22050", "-f", input.absolutePath,
                )
                Os.WINDOWS -> powershell(
                    if (info.api == "winrt") winrtSpeakScript(info.id, rate, input.absolutePath, out.absolutePath)
                    else sapiSpeakScript(voice, rate, input.absolutePath, out.absolutePath),
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

    // ---- Windows scripts ----

    internal fun sapiSpeakScript(voice: String, rate: Double, inputPath: String, outPath: String): String = """
Add-Type -AssemblyName System.Speech
${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
${'$'}s.SelectVoice('${ps(voice)}')
${'$'}s.Rate = ${((rate - 1.0) * 10).toInt().coerceIn(-10, 10)}
${'$'}s.SetOutputToWaveFile('${ps(outPath)}')
${'$'}s.Speak([IO.File]::ReadAllText('${ps(inputPath)}', [Text.Encoding]::UTF8))
${'$'}s.Dispose()
"""

    /** WinRT synthesis (OneCore voices): the stream it returns is already a RIFF/WAV file. */
    internal fun winrtSpeakScript(voiceId: String, rate: Double, inputPath: String, outPath: String): String = """
${'$'}ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
${'$'}asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
function Await(${'$'}op, [Type] ${'$'}t) { ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}t).Invoke(${'$'}null, @(${'$'}op)); ${'$'}task.Wait(-1) | Out-Null; ${'$'}task.Result }
${'$'}s = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer
${'$'}s.Voice = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { ${'$'}_.Id -eq '${ps(voiceId)}' } | Select-Object -First 1
${'$'}s.Options.SpeakingRate = ${"%.2f".format(java.util.Locale.ROOT, rate.coerceIn(0.5, 3.0))}
${'$'}text = [IO.File]::ReadAllText('${ps(inputPath)}', [Text.Encoding]::UTF8)
${'$'}stream = Await (${'$'}s.SynthesizeTextToStreamAsync(${'$'}text)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])
${'$'}reader = New-Object Windows.Storage.Streams.DataReader(${'$'}stream.GetInputStreamAt(0))
${'$'}n = [uint32]${'$'}stream.Size
Await (${'$'}reader.LoadAsync(${'$'}n)) ([uint32]) | Out-Null
${'$'}bytes = New-Object byte[] ${'$'}n
${'$'}reader.ReadBytes(${'$'}bytes)
[IO.File]::WriteAllBytes('${ps(outPath)}', ${'$'}bytes)
"""

    // ---- listing ----

    /**
     * Lists SAPI 5 voices and WinRT (OneCore) voices: `sapi|name|culture|gender` and `winrt|name|language|gender|id`.
     * Either half may fail on its own (no System.Speech, no WinRT) without losing the other.
     */
    internal val listScript = """
[Console]::OutputEncoding = [Text.Encoding]::UTF8
try {
  Add-Type -AssemblyName System.Speech
  ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled } | ForEach-Object { ${'$'}i = ${'$'}_.VoiceInfo; "sapi|${'$'}(${'$'}i.Name)|${'$'}(${'$'}i.Culture.Name)|${'$'}(${'$'}i.Gender)" }
  ${'$'}s.Dispose()
} catch { }
try {
  [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime] | Out-Null
  [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | ForEach-Object { "winrt|${'$'}(${'$'}_.DisplayName)|${'$'}(${'$'}_.Language)|${'$'}(${'$'}_.Gender)|${'$'}(${'$'}_.Id)" }
} catch { }
"""

    /**
     * Parses [listScript] output. A voice both APIs list (SAPI "Microsoft Haruka Desktop", WinRT "Microsoft Haruka")
     * keeps the SAPI entry; WinRT adds the OneCore voices SAPI can't see. Online-only voices (names containing
     * "Online") need the network and are left out (rule 2). Lines without the api prefix are old-format SAPI lines.
     */
    internal fun parseWindowsVoices(output: String): List<Info> {
        val sapi = mutableListOf<Info>()
        val winrt = mutableListOf<Info>()
        output.lineSequence().forEach { l ->
            val parts = l.trim().split('|')
            when (parts.firstOrNull()) {
                "sapi" -> parseSapiVoices(parts.drop(1).joinToString("|")).firstOrNull()?.let { sapi += it.copy(api = "sapi") }
                "winrt" -> if (parts.size >= 5 && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                    winrt += Info(parts[1].trim(), parts[2].trim(), gender(parts[3]), "winrt", parts[4].trim())
                }
                else -> parseSapiVoices(l).firstOrNull()?.let { sapi += it.copy(api = "sapi") }
            }
        }
        fun key(name: String) = name.lowercase().removeSuffix(" desktop").replace(Regex("\\s*-.*$"), "").trim()
        val seen = sapi.map { key(it.name) }.toSet()
        return (sapi + winrt.filter { key(it.name) !in seen }).filterNot { "online" in it.name.lowercase() }
    }

    private fun gender(s: String): String = when (s.trim().lowercase()) {
        "female" -> "female"
        "male" -> "male"
        else -> "unknown"
    }

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
        Info(parts[0].trim(), parts[1].trim(), gender(parts[2]))
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

    /**
     * PowerShell with the script as `-EncodedCommand` (base64 of UTF-16LE). Passing it as one `-Command` argument broke
     * on Windows: Java's process launcher does not escape the script's embedded double quotes, so the listing came back
     * empty and no OS voice was found (owner finding 2026-10-03, BRIEF_PHASE8 N-00).
     */
    internal fun powershell(script: String): List<String> =
        listOf("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encode(script))

    internal fun encode(script: String): String = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

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
