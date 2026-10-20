package app.mokuhyo.desktop

import app.mokuhyo.ai.Sha256
import app.mokuhyo.opi.OpiPack
import app.mokuhyo.opi.PromptAudioIndex
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.OggOpus
import app.mokuhyo.tts.VoiceService
import app.mokuhyo.tts.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Prerendered interview prompts (BRIEF_INTERVIEW_SPEED N5): the run-time loader and the build tools that make them.
 *
 * At run time a guided turn plays its question's clip when the pack has one whose hash matches its index entry and
 * whose text is still the question's; otherwise [load] returns null and the caller speaks with the live voice (and
 * shows text if that fails too). A damaged clip never throws out of here.
 */
object PromptClips {
    private val indexes = ConcurrentHashMap<String, Result<PromptAudioIndex?>>()

    fun index(app: AppGraph, lang: String): PromptAudioIndex? = indexes.getOrPut(lang) {
        runCatching { app.packFile(lang, PromptAudioIndex.FILE)?.let { PromptAudioIndex.parse(it.readText()) } }
    }.getOrNull()

    /** A ready clip: WAV bytes to play, and the entry (engine, voice, credit) for the line's label. */
    class Clip(val wav: ByteArray, val entry: PromptAudioIndex.Entry)

    /** The clip for bank prompt [id] saying [text], or null when it isn't rendered, changed, missing or damaged. */
    suspend fun load(app: AppGraph, lang: String, id: String, text: String): Clip? = withContext(Dispatchers.IO) {
        val entry = index(app, lang)?.usable(id, text) ?: return@withContext null
        val file = app.packFile(lang, entry.relativePath()) ?: return@withContext null
        runCatching {
            val bytes = file.readBytes()
            if (Sha256.hex(bytes) != entry.audioSha256) return@runCatching null
            AudioIO.clipWav(bytes).takeIf { Wav.isValid(it) }?.let { Clip(it, entry) }
        }.getOrNull()
    }

    internal fun forget() = indexes.clear()

    // ---- build tools -----------------------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `--prompt-audio --language es,ja [--packs DIR] [--render] [--jobs OUT.jsonl --voices voices/voicevox_voices.json]
     * [--import DIR] [--estimate] [--force]`:
     *  - always (re)writes `<pack>/prompts.json` with the text hashes, keeping clips whose text is unchanged;
     *  - `--render`: speaks every missing prompt with the language's first bundled Piper voice into
     *    `<pack>/audio/prompts/<id>.ogg` (24 kHz Ogg Opus, as passages) and records hash, length, size, voice;
     *  - `--jobs`: writes render jobs for `tools/voices/voicevox_render.py` / `chatterbox_render.py` (one reference voice
     *    per language, the first of the pool), and `--import DIR` encodes `DIR/<lang>/<id>.wav` back with its credit;
     *  - `--estimate`: prints the size the clips would add per language and in total, from the text and the passages'
     *    measured bitrate, before anything is rendered.
     * OS voices are never rendered: their terms don't allow redistributing recordings (D-013).
     */
    fun tool(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val packs = Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") } ?: return@runBlocking fail("--packs")
        val langs = Smoke.arg(args, "--language")?.split(",")?.map { it.trim() } ?: return@runBlocking fail("--language")
        var total = 0L
        var estimate = 0L
        val jobs = ArrayList<String>()
        val pools = Smoke.arg(args, "--voices")?.let(::File)?.takeIf { it.isFile }?.let { f ->
            json.parseToJsonElement(f.readText()).jsonObject.let { root ->
                val credits = root["credits"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                val engine = root["engine"]?.jsonPrimitive?.content ?: "chatterbox-multilingual"
                Triple(ChatterboxJobs.pools(f), credits, engine)
            }
        }
        val voices = if ("--render" in args) VoiceService.create() else null
        for (lang in langs) {
            val packFile = File(packs, "$lang/opi.json").takeIf { it.isFile } ?: return@runBlocking fail("no $lang/opi.json")
            val pack = OpiPack.parse(packFile.readText())
            val indexFile = File(packs, "$lang/${PromptAudioIndex.FILE}")
            var index = PromptAudioIndex.build(pack, indexFile.takeIf { it.isFile }?.let { PromptAudioIndex.parse(it.readText()) })
            val texts = PromptAudioIndex.texts(pack)
            if ("--force" in args) index = index.copy(prompts = index.prompts.map { it.copy(audioSha256 = null, seconds = null, bytes = null) })
            val dir = File(packs, "$lang/audio/prompts")
            voices?.let { v ->
                val piper = v.voicesFor(lang).firstOrNull { it.engine == "piper" }
                if (piper == null) println("prompt-audio: $lang has no bundled Piper voice; use --jobs with VOICEVOX/Chatterbox or leave it to the live voice")
                else index = index.copy(prompts = index.prompts.map { e ->
                    if (e.rendered) return@map e
                    val audio = v.synthesize(texts.getValue(e.id), lang, piper) ?: return@map e
                    val (pcm, rate) = AudioIO.toMonoPcm(audio.wav)
                    store(dir, e, AudioIO.resample(pcm, rate, ChatterboxJobs.CLIP_RATE), "piper", piper.id, null)
                })
            }
            Smoke.arg(args, "--import")?.let(::File)?.let { File(it, lang) }?.takeIf { it.isDirectory }?.let { wavs ->
                val ref = pools?.first?.get(lang)?.firstOrNull()?.ref
                index = index.copy(prompts = index.prompts.map { e ->
                    val wav = File(wavs, "${e.id}.wav").takeIf { it.isFile && !e.rendered } ?: return@map e
                    val (pcm, rate) = AudioIO.toMonoPcm(wav.readBytes())
                    store(dir, e, AudioIO.resample(pcm, rate, ChatterboxJobs.CLIP_RATE), pools?.third ?: "chatterbox-multilingual", ref, ref?.let { pools.second[it] })
                })
            }
            if (Smoke.arg(args, "--jobs") != null) {
                val ref = pools?.first?.get(lang)?.firstOrNull()?.ref ?: return@runBlocking fail("--jobs needs --voices with a pool for $lang")
                index.prompts.filter { !it.rendered }.forEach { e ->
                    jobs += buildJsonObject {
                        put("id", e.id); put("lang", lang); put("pause", 0.0)
                        put("lines", buildJsonArray { add(buildJsonObject { put("text", texts.getValue(e.id)); put("ref", ref); put("speaker", "interviewer") }) })
                    }.toString()
                }
            }
            indexFile.writeText(index.toJson() + "\n")
            val chars = texts.values.sumOf { it.length }
            val est = estimateBytes(lang, chars)
            estimate += est
            total += index.renderedBytes
            println("prompt-audio: $lang ${index.prompts.size} prompts, ${index.prompts.size - index.missing} rendered " +
                "(${index.renderedBytes / 1024} KB); estimated ${est / 1024} KB when all are rendered")
        }
        Smoke.arg(args, "--jobs")?.let { File(it).writeText(jobs.joinToString("\n") + "\n"); println("prompt-audio: ${jobs.size} jobs → $it") }
        voices?.close()
        println("prompt-audio: rendered ${total / 1024} KB in total; estimate for these languages ${estimate / 1024} KB")
        0
    }

    private fun store(dir: File, e: PromptAudioIndex.Entry, pcm: ShortArray, engine: String, voice: String?, credit: String?): PromptAudioIndex.Entry {
        dir.mkdirs()
        val ogg = OggOpus.encode(pcm, bitrate = 32_000, sampleRate = ChatterboxJobs.CLIP_RATE)
        File(dir, "${e.id}.ogg").writeBytes(ogg)
        println("prompt-audio: ${e.id} ${"%.1f".format(pcm.size / ChatterboxJobs.CLIP_RATE.toDouble())} s ${ogg.size / 1024} KB")
        return e.copy(audioSha256 = Sha256.hex(ogg), seconds = Math.round(pcm.size * 10.0 / ChatterboxJobs.CLIP_RATE) / 10.0, bytes = ogg.size.toLong(),
            engine = engine, voice = voice, credit = credit)
    }

    /**
     * Expected Ogg Opus size for [chars] characters of prompts: 32 kbit/s plus Ogg overhead (≈ 4.3 KB/s, an estimate,
     * not a measurement) at a typical speaking rate per script (characters per second).
     */
    fun estimateBytes(lang: String, chars: Int): Long {
        val cps = when (lang) { "ja", "zh-Hans" -> 7.0; "ko" -> 8.0; else -> 14.0 }
        return (chars / cps * 4_300).toLong()
    }

    private fun fail(msg: String): Int {
        println("prompt-audio: FAIL $msg")
        return 1
    }
}
