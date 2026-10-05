package app.mokuhyo.desktop

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import app.mokuhyo.ai.WhisperRecognizer
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.lang.LanguageRegistry
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.speech.AudioIO
import com.ibm.icu.text.Transliterator
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--smoke-lang` (BRIEF §11.1 gate_lang): for every language, speak one sentence with the voice service (bundled
 * voice, else the OS voice; a language with neither is logged as a fallback), transcribe it with Whisper and require
 * ≥ 60 % token match (segmented and folded by the language's own module).
 *
 *   Mokuhyo --smoke-lang --whisper <ggml-*.bin> [--packs <content/packs>] [--languages ja,es,…]
 */
object LangSmoke {
    val sentences = mapOf(
        "ja" to "明日の会議は午前十時から三階の会議室で行われます。",
        "es" to "La reunión de mañana empieza a las diez en la sala del tercer piso.",
        "fr" to "La réunion de demain commence à dix heures dans la salle du troisième étage.",
        "de" to "Die Besprechung morgen beginnt um zehn Uhr im Raum im dritten Stock.",
        "pt-BR" to "A reunião de amanhã começa às dez horas na sala do terceiro andar.",
        "ru" to "Завтрашнее совещание начнётся в десять часов в зале на третьем этаже.",
        "zh-Hans" to "明天的会议上午十点在三楼的会议室举行。",
        "ko" to "내일 회의는 오전 열 시에 삼 층 회의실에서 열립니다.",
        "ar" to "يبدأ اجتماع الغد في الساعة العاشرة في قاعة الطابق الثالث.",
        "fa" to "جلسه فردا ساعت ده در سالن طبقه سوم برگزار می‌شود.",
        "id" to "Rapat besok dimulai pukul sepuluh di ruang lantai tiga.",
    )

    data class Row(val lang: String, val voice: String, val transcript: String, val match: Double?, val ok: Boolean, val note: String)

    fun run(args: Array<String>, speech: SpeechOutput): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val whisper = Smoke.arg(args, "--whisper")?.let(::File)?.takeIf { it.isFile }
            ?: return@runBlocking fail("--whisper <ggml-*.bin> is required")
        val packs = Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") }
        val langs = Smoke.arg(args, "--languages")?.split(",") ?: Languages.all.map { it.code }
        // Languages that may have no voice on this computer without failing (open decision 4: Korean).
        val allowedFallback = Smoke.arg(args, "--allow-fallback")?.split(",")?.toSet().orEmpty()
        val runtime = JniRuntime.tryCreate(preferCpu = false)
        val stt = runtime.stt() ?: return@runBlocking fail("native library not available: ${runtime.status}")
        val registry = LanguageRegistry(packs)
        val recognizer = WhisperRecognizer(stt, whisper.absolutePath, whisper.name)
        val rows = langs.map { lang ->
            val module = registry.module(lang)
            val text = sentences.getValue(lang)
            val voiceId = Smoke.arg(args, "--voice")
            val voice = voiceId?.let { id -> speech.voicesFor(lang).firstOrNull { it.id == id } }
            val spoken = runCatching { speech.synthesize(text, lang, voice) }.getOrNull()
            if (spoken == null) {
                Row(lang, "none", "", null, ok = lang in allowedFallback, note = "FALLBACK: no voice for $lang on this computer (text-first; logged per DECISIONS)")
            } else {
                val heard = recognizer.transcribe(AudioIO.toPcm16kMono(spoken.wav), module.sttLanguage).text
                val match = tokenMatch(module, text, heard)
                Row(lang, "${spoken.voice.engine}:${spoken.voice.id}", heard, match, match >= 0.6, "")
            }
        }
        rows.forEach { r ->
            println("smoke-lang: %-7s %-5s voice=%-32s match=%s %s %s".format(
                r.lang, if (r.ok) "OK" else "FAIL", r.voice, r.match?.let { "%.0f%%".format(it * 100) } ?: "-", r.note,
                if (r.transcript.isNotEmpty()) "\"${r.transcript}\"" else "",
            ))
        }
        val failed = rows.filter { !it.ok }
        println(if (failed.isEmpty()) "smoke-lang: OK" else "smoke-lang: FAILED ${failed.map { it.lang }}")
        if (failed.isEmpty()) 0 else 1
    }

    /**
     * TTS → Whisper round trip for pre-rendered clips (BRIEF_PHASE8 N-00 gate: ≥ 80 % token match):
     * `Mokuhyo --roundtrip <jobs.jsonl> --clips <dir> --whisper <ggml-*.bin> [--min 0.8]`. Each job's expected text is
     * its lines joined; its clip is `<dir>/<lang>/<id>.wav`. Prints one row per clip and the mean per language.
     */
    fun roundtrip(args: Array<String>): Int = runBlocking {
        val jobs = Smoke.arg(args, "--roundtrip")?.let(::File)?.takeIf { it.isFile } ?: return@runBlocking fail("--roundtrip <jobs.jsonl> is required")
        val clips = Smoke.arg(args, "--clips")?.let(::File) ?: return@runBlocking fail("--clips <dir> is required")
        val whisper = Smoke.arg(args, "--whisper")?.let(::File)?.takeIf { it.isFile } ?: return@runBlocking fail("--whisper <ggml-*.bin> is required")
        val min = Smoke.arg(args, "--min")?.toDoubleOrNull() ?: 0.8
        val registry = LanguageRegistry(Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") })
        val runtime = JniRuntime.tryCreate(preferCpu = false)
        val stt = runtime.stt() ?: return@runBlocking fail("native library not available: ${runtime.status}")
        val recognizer = WhisperRecognizer(stt, whisper.absolutePath, whisper.name)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val scores = mutableMapOf<String, MutableList<Double>>()
        jobs.readLines().filter { it.isNotBlank() }.forEach { line ->
            val o = json.parseToJsonElement(line).jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            val lang = o.getValue("lang").jsonPrimitive.content
            // A render directory of WAVs, or a pack's audio folder of Ogg Opus clips (`--clips content/packs/<lang>/..`).
            val clip = File(clips, "$lang/$id.wav").takeIf { it.isFile } ?: File(clips, "$lang/audio/$id.ogg").takeIf { it.isFile } ?: return@forEach
            val wav = if (clip.extension == "ogg") File.createTempFile("roundtrip", ".wav").apply {
                deleteOnExit(); writeBytes(AudioIO.wav(app.mokuhyo.speech.OggOpus.decode(clip.readBytes())))
            } else clip
            val text = o.getValue("lines").jsonArray.joinToString(" ") { it.jsonObject.getValue("text").jsonPrimitive.content }
            val module = registry.module(lang)
            val heard = recognizer.transcribe(AudioIO.toPcm16kMono(wav.readBytes()), module.sttLanguage).text
            val m = tokenMatch(module, text.replace(" ", if (lang in setOf("zh-Hans", "ja")) "" else " "), heard)
            scores.getOrPut(lang) { mutableListOf() } += m
            println("roundtrip: %-7s %-28s %3.0f%% \"%s\"".format(lang, id, m * 100, heard.take(80)))
        }
        val failed = scores.filter { (_, v) -> v.average() < min }
        scores.forEach { (l, v) -> println("roundtrip: %-7s mean %3.0f%% over %d clips %s".format(l, v.average() * 100, v.size, if (l in failed) "FAIL" else "OK")) }
        if (failed.isEmpty() && scores.isNotEmpty()) 0 else 1
    }

    private val toSimplified: Transliterator by lazy { Transliterator.getInstance("Traditional-Simplified") }

    /** Share of the expected word tokens found, in order, in the transcript (LCS over folded word tokens). */
    fun tokenMatch(module: LanguageModule, expected: String, heard: String): Double {
        val norm = if (module.code == "zh-Hans") synchronized(toSimplified) { toSimplified.transliterate(heard) } else heard
        // Persian writes the verbal prefix می/نمی with a ZWNJ or a space interchangeably; compare them as one word.
        fun persian(s: String) = if (module.code == "fa") s.replace(Regex("(^|\\s)(ن?می)\\s+"), "$1$2\u200C") else s
        // Whisper writes "6時15分" where the script says "六時十五分": spell digit runs out with the language's own number
        // grammar on both sides, so number formatting is not counted as a mishearing (BRIEF_PHASE8 N-00 round trip).
        fun spelled(s: String) = module.numbers?.let { g ->
            Regex("\\d+").replace(s) { m -> m.value.toLongOrNull()?.let { g.cardinal(it) } ?: m.value }
        } ?: s
        fun words(s: String) = module.segment(persian(spelled(s))).filter { it.isWord }.map { module.normalizeForCompare(it.text) }
        val a = words(expected)
        val b = words(norm)
        if (a.isEmpty()) return 0.0
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp[a.size][b.size].toDouble() / a.size
    }

    private fun fail(msg: String): Int {
        println("smoke-lang: FAIL $msg")
        return 1
    }
}
