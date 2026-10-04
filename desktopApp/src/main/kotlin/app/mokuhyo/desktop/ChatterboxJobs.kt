package app.mokuhyo.desktop

import app.mokuhyo.exam.ExamContent
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.Skill
import app.mokuhyo.lang.LanguageRegistry
import app.mokuhyo.lang.VoiceRotation
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.OggOpus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Chatterbox pre-render plumbing (BRIEF_PHASE8 N-00; owner exception D-041). Build tools, not app features:
 *
 *  - `--chatterbox-jobs OUT.jsonl --voices voices/chatterbox_voices.json [--packs DIR] [--languages ja,ko,…]` writes one job
 *    per shipped clip (listening passages, track dialogues, exemplar answers): its lines, each with the reference voice
 *    picked by [VoiceRotation] from the language's pool (distinct voices for distinct speakers, rotation by clip id).
 *    Chinese lines are segmented with ICU (spaces between words) so the render host never needs a segmentation model.
 *  - `--import-clips DIR [--packs DIR]` encodes `DIR/<lang>/<id>.wav` into `<packs>/<lang>/audio/<id>.ogg` (Ogg Opus,
 *    16 kHz mono, replacing the Piper clip) and records which voice spoke each clip in `<packs>/<lang>/audio/voices.json`.
 */
object ChatterboxJobs {
    private val json = Json { ignoreUnknownKeys = true }

    /** A pool voice: [ref] is the reference clip file name ("" = Chatterbox's built-in voice). */
    data class PoolVoice(val ref: String, val gender: String)

    fun pools(file: File): Map<String, List<PoolVoice>> = json.parseToJsonElement(file.readText()).jsonObject.getValue("languages").jsonObject
        .mapValues { (_, v) -> v.jsonArray.map { o -> o.jsonObject.let { PoolVoice(it.getValue("ref").jsonPrimitive.content, it.getValue("gender").jsonPrimitive.content) } } }

    fun jobs(args: Array<String>): Int {
        val out = Smoke.arg(args, "--chatterbox-jobs")?.let(::File) ?: return fail("--chatterbox-jobs OUT.jsonl is required")
        val pools = Smoke.arg(args, "--voices")?.let(::File)?.takeIf { it.isFile }?.let(::pools) ?: return fail("--voices <chatterbox_voices.json> is required")
        val packs = Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") } ?: return fail("--packs")
        val langs = Smoke.arg(args, "--languages")?.split(",") ?: pools.keys.toList()
        val registry = LanguageRegistry(packs)
        val lines = mutableListOf<String>()
        for (lang in langs) {
            val pool = pools[lang].orEmpty().ifEmpty { continue }
            val specs = pool.map { VoiceSpec(it.ref, lang, it.gender, "chatterbox", "") }
            val module = registry.module(lang)
            for (p in passages(packs, lang)) {
                val cast = VoiceRotation.assign(specs, p.script.map { it.speaker to it.voice }.distinctBy { it.first }, p.id)
                val arr = buildJsonArray {
                    p.script.forEach { ln ->
                        val text = if (lang == "zh-Hans") module.segment(ln.text).joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim() else ln.text
                        add(buildJsonObject { put("text", text); put("ref", cast.getValue(ln.speaker).id); put("speaker", ln.speaker) })
                    }
                }
                lines += buildJsonObject { put("id", p.id); put("lang", lang); put("lines", arr); put("pause", 0.6) }.toString()
            }
        }
        out.writeText(lines.joinToString("\n") + "\n")
        println("chatterbox-jobs: ${lines.size} jobs → $out")
        return 0
    }

    /** Every clip a pack ships: listening passages, track dialogues, exemplar answers (same ids as RenderAudio). */
    fun passages(packs: File, lang: String): List<ExamPassage> {
        val content = File(packs, "$lang/exam.json").takeIf { it.isFile }?.let { ExamContent.parse(it.readText()) }
        val dialogues = File(packs, "$lang/track-${app.mokuhyo.lexicon.TrackIds.CUAS}.json").takeIf { it.isFile }
            ?.let { f -> app.mokuhyo.lexicon.Track.parse(f.readText()).dialogues.map { it.asPassage(lang) } }.orEmpty()
        val exemplars = File(packs, "$lang/exemplars.json").takeIf { it.isFile }?.let { f ->
            app.mokuhyo.opi.ExemplarPack.parse(f.readText()).exemplars.map { e ->
                ExamPassage(e.id, app.mokuhyo.exam.ExamKind.DLPT_LISTENING, lang, e.level, "exemplar", e.prompt, "",
                    listOf(app.mokuhyo.exam.ScriptLine("candidate", "male", e.response)), e.source, e.verified)
            }
        }.orEmpty()
        return (content?.passagesFor(Skill.LISTENING).orEmpty() + dialogues + exemplars).sortedBy { it.id }
    }

    fun importClips(args: Array<String>): Int {
        val dir = Smoke.arg(args, "--import-clips")?.let(::File)?.takeIf { it.isDirectory } ?: return fail("--import-clips DIR is required")
        val packs = Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") } ?: return fail("--packs")
        val jobs = Smoke.arg(args, "--jobs")?.let(::File)?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
            ?.associate { l -> json.parseToJsonElement(l).jsonObject.let { o -> o.getValue("id").jsonPrimitive.content to o } }.orEmpty()
        var n = 0
        dir.listFiles().orEmpty().filter { it.isDirectory }.forEach { langDir ->
            val audio = File(packs, "${langDir.name}/audio").apply { mkdirs() }
            val credits = File(audio, "voices.json").takeIf { it.isFile }?.let { json.parseToJsonElement(it.readText()).jsonObject.toMutableMap() } ?: mutableMapOf()
            langDir.listFiles { f -> f.extension == "wav" && !f.name.endsWith(".part.wav") }.orEmpty().sortedBy { it.name }.forEach { wav ->
                val id = wav.nameWithoutExtension
                File(audio, "$id.ogg").writeBytes(OggOpus.encode(AudioIO.toPcm16kMono(wav.readBytes()), bitrate = 32_000))
                val refs = jobs[id]?.get("lines")?.jsonArray?.map { it.jsonObject.getValue("ref").jsonPrimitive.content.ifEmpty { "builtin" } }?.distinct()
                credits[id] = buildJsonObject { put("engine", "chatterbox-multilingual"); put("voices", JsonArray(refs.orEmpty().map(::JsonPrimitive))) }
                n++
            }
            File(audio, "voices.json").writeText(kotlinx.serialization.json.JsonObject(credits.toSortedMap()).toString())
        }
        println("import-clips: $n clips encoded into $packs")
        return 0
    }

    private fun fail(msg: String): Int {
        println("chatterbox: FAIL $msg")
        return 1
    }
}
