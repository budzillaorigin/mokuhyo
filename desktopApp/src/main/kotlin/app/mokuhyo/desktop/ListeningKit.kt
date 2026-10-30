package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.opi.OpiPack
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.tts.VoiceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * `--listening-kit --language es,fa,id [--out DIR] [--prompts 10] [--seed 7] [--candidates piper:a,piper:b]
 * [--include-os] [--import-audition DIR[,DIR…]]` (BRIEF_INTERVIEW_SPEED N6): a blind listening comparison for native
 * speakers. The same [count] interviewer prompts per language (spread over the phases) are spoken by every candidate
 * into shuffled, unlabeled files `<lang>/<lang>-NNN.wav`. `key.csv` maps files to sources and stays with the owner;
 * `scoring-sheet.csv` (one row per file, 1–5 scores kept separate) goes to raters with the audio.
 * `tools/voices/listening_kit.py score` joins returned sheets to the key.
 *
 * Candidates (QC 2026-10-30 V5) are an explicit list: `--candidates engine:id,…`, or without it every voice the voice
 * service lists here whose engine is not restricted, plus the pack's prerendered clip. Each candidate must render
 * itself: when the service falls back to another voice, that sample is recorded as missing in `missing.csv` and never
 * filed under the candidate's name. An engine joins only by being listed by the voice service or named here; nothing
 * joins automatically. Externally rendered auditions (D-048: the owner's own-machine sets, never committed) join with
 * `--import-audition DIR`: `DIR/audition.json` ([ExternalAudition]) plus `DIR/<lang>/<prompt id>.wav`.
 *
 * OS voices are restricted and off unless `--include-os` is given, even when named: their terms don't allow
 * redistributing recordings (D-013), so the owner decides whether a private evaluation may include them.
 */
object ListeningKit {
    /** Engines whose recordings may not be redistributed: included only with explicit opt-in. */
    val RESTRICTED = setOf("os")

    data class Rendered(val source: String, val promptId: String, val wav: ByteArray)
    data class Missing(val source: String, val promptId: String, val reason: String)
    data class Takes(val rendered: List<Rendered>, val missing: List<Missing>)

    /**
     * An externally rendered audition set. Every field except [settings] must be non-blank; a WAV only counts when it
     * is in the set's folder under its prompt id.
     */
    data class ExternalAudition(
        val dir: File, val label: String, val engine: String, val model: String, val revision: String,
        val weightSha256: String, val referenceSha256: String, val settings: String,
    ) {
        val source get() = "external:$label"

        companion object {
            private val required = listOf("label", "engine", "model", "revision", "weight_sha256", "reference_sha256")

            fun read(dir: File): ExternalAudition {
                val meta = File(dir, "audition.json").takeIf { it.isFile } ?: error("$dir has no audition.json")
                val o = kotlinx.serialization.json.Json.parseToJsonElement(meta.readText()).jsonObject
                val missing = required.filter { o[it]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
                require(missing.isEmpty()) { "$meta lacks ${missing.joinToString()}" }
                fun f(k: String) = o.getValue(k).jsonPrimitive.content
                return ExternalAudition(dir, f("label"), f("engine"), f("model"), f("revision"), f("weight_sha256"),
                    f("reference_sha256"), o["settings"]?.toString() ?: "{}")
            }
        }
    }

    fun label(v: VoiceSpec) = "${v.engine}:${v.id}" + (v.speaker?.let { "#$it" } ?: "")

    /**
     * The candidates to compare: [wanted] labels (`engine:id`) exactly as given, else every listed voice of an
     * unrestricted engine. A restricted engine needs [allowRestricted]; a wanted voice that isn't listed is missing.
     */
    fun candidates(listed: List<VoiceSpec>, wanted: List<String>?, allowRestricted: Boolean): Pair<List<VoiceSpec>, List<Pair<String, String>>> {
        val missing = ArrayList<Pair<String, String>>()
        val chosen = if (wanted == null) listed.filter { it.engine !in RESTRICTED || allowRestricted }
        else wanted.mapNotNull { w -> listed.firstOrNull { label(it) == w || "${it.engine}:${it.id}" == w } ?: null.also { missing += w to "not available here" } }
        val (ok, restricted) = chosen.partition { it.engine !in RESTRICTED || allowRestricted }
        restricted.forEach { missing += label(it) to "restricted recording source: needs --include-os" }
        return ok.distinct() to missing
    }

    /** Each candidate speaks each prompt itself, or is recorded as missing; never another voice's audio under its name. */
    suspend fun render(output: SpeechOutput, lang: String, prompts: List<Pair<String, String>>, candidates: List<VoiceSpec>): Takes {
        val rendered = ArrayList<Rendered>()
        val missing = ArrayList<Missing>()
        for ((id, text) in prompts) for (v in candidates) {
            val got = runCatching { output.synthesize(text, lang, v) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            when {
                got == null -> missing += Missing(label(v), id, "synthesis failed")
                label(got.voice) != label(v) -> missing += Missing(label(v), id, "fell back to ${label(got.voice)}")
                else -> rendered += Rendered(label(got.voice), id, got.wav)
            }
        }
        return Takes(rendered, missing)
    }

    /** An external set's WAV for each prompt, or missing. */
    fun imported(set: ExternalAudition, lang: String, prompts: List<Pair<String, String>>): Takes {
        val rendered = ArrayList<Rendered>()
        val missing = ArrayList<Missing>()
        for ((id, _) in prompts) {
            val wav = File(set.dir, "$lang/$id.wav").takeIf { it.isFile }
            if (wav == null) missing += Missing(set.source, id, "no $lang/$id.wav in the audition set") else rendered += Rendered(set.source, id, wav.readBytes())
        }
        return Takes(rendered, missing)
    }

    fun run(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val out = (Smoke.arg(args, "--out")?.let(::File) ?: File("build/listening-kit")).apply { mkdirs() }
        val count = Smoke.arg(args, "--prompts")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val random = Random(Smoke.arg(args, "--seed")?.toIntOrNull() ?: 7)
        val langs = Smoke.arg(args, "--language")?.split(",")?.map { it.trim() } ?: return@runBlocking fail("--language")
        val wanted = Smoke.arg(args, "--candidates")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val external = try {
            Smoke.arg(args, "--import-audition")?.split(",")?.map { ExternalAudition.read(File(it.trim())) }.orEmpty()
        } catch (e: Exception) {
            return@runBlocking fail("--import-audition: ${e.message}")
        }
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-kit").toFile()))
        val voices = VoiceService.create()
        val key = StringBuilder("file,language,source,voice,prompt_id\n")
        val sheet = StringBuilder("file,language,intelligibility_1to5,pronunciation_1to5,naturalness_1to5,comments\n")
        val missingCsv = StringBuilder("language,source,voice,prompt_id,reason\n")
        var files = 0
        for (lang in langs) {
            val pack = app.opi(lang) ?: run { println("listening-kit: no OPI pack for $lang"); null } ?: continue
            val prompts = pick(pack, count)
            val (sources, unavailable) = candidates(voices.voicesFor(lang), wanted, "--include-os" in args)
            println("listening-kit: $lang candidates ${sources.map(::label) + external.map { it.source }}")
            val takes = mutableListOf(render(voices, lang, prompts, sources))
            takes += Takes(emptyList(), unavailable.flatMap { (s, why) -> prompts.map { Missing(s, it.first, why) } })
            external.forEach { takes += imported(it, lang, prompts) }
            val renders = takes.flatMap { it.rendered }.map { Triple(it.source, it.promptId, it.wav) }.toMutableList()
            for ((id, text) in prompts) {
                PromptClips.load(app, lang, id, text)?.let { c -> renders += Triple("prerendered:${c.entry.engine}:${c.entry.voice}", id, c.wav) }
            }
            takes.flatMap { it.missing }.forEach { m ->
                missingCsv.append("$lang,${m.source.substringBefore(':')},${m.source.substringAfter(':', "")},${m.promptId},${m.reason.replace(',', ';')}\n")
                println("listening-kit: MISSING $lang ${m.source} ${m.promptId}: ${m.reason}")
            }
            if (renders.isEmpty()) { println("listening-kit: nothing can speak $lang here"); continue }
            val dir = File(out, lang).apply { mkdirs() }
            renders.shuffled(random).forEachIndexed { i, (source, id, wav) ->
                val name = "$lang-%03d.wav".format(i + 1)
                // One format for every source so a file's header doesn't give its engine away.
                val (pcm, rate) = AudioIO.toMonoPcm(wav)
                File(dir, name).writeBytes(AudioIO.wav(AudioIO.resample(pcm, rate, 24_000), 24_000))
                key.append("$lang/$name,$lang,${source.substringBefore(':')},${source.substringAfter(':', "")},$id\n")
                sheet.append("$lang/$name,$lang,,,,\n")
                files++
            }
            println("listening-kit: $lang ${prompts.size} prompts, ${renders.size} files")
        }
        File(out, "key.csv").writeText(key.toString())
        File(out, "missing.csv").writeText(missingCsv.toString())
        File(out, "sources.json").writeText(external.joinToString(",\n", "[\n", "\n]\n") { e ->
            buildJsonObject {
                put("source", e.source); put("engine", e.engine); put("model", e.model); put("revision", e.revision)
                put("weight_sha256", e.weightSha256); put("reference_sha256", e.referenceSha256); put("settings", e.settings)
            }.toString()
        })
        File(out, "scoring-sheet.csv").writeText(sheet.toString())
        File(out, "README.txt").writeText(README)
        voices.close()
        app.close()
        println("listening-kit: $files files in $out (keep key.csv, missing.csv and sources.json; give raters the language folders, scoring-sheet.csv and README.txt)")
        0
    }

    /** [count] prompts spread over the phases, role-play openings included, in a stable order. */
    internal fun pick(pack: OpiPack, count: Int): List<Pair<String, String>> {
        val byPhase = OpiPhase.entries.map { p ->
            if (p == OpiPhase.ROLEPLAY) pack.rolePlays.sortedBy { it.id }.map { it.id to it.opening }
            else pack.questions.filter { it.phase == p.wireName }.sortedBy { it.id }.map { it.id to it.prompt }
        }
        val out = ArrayList<Pair<String, String>>()
        var round = 0
        while (out.size < count && byPhase.any { it.size > round }) {
            byPhase.forEach { list -> list.getOrNull(round)?.let { if (out.size < count) out += it } }
            round++
        }
        return out
    }

    private const val README = """Mokuhyo blind listening comparison

Each file is one interviewer question read by one voice. The files are shuffled and unlabeled on purpose:
please don't try to guess which voice is which.

For every file in scoring-sheet.csv give three separate scores from 1 (poor) to 5 (native-like):
  intelligibility - could you understand every word without effort?
  pronunciation   - are the sounds, stress and words pronounced as a native speaker would?
  naturalness     - does it sound like a person speaking (rhythm, intonation, pauses)?
Add a comment for anything wrong (a mispronounced word, a wrong stress, a robotic phrase).
Score each quality on its own: a clear voice can still sound unnatural.

Persian and Indonesian need particular attention: Persian has a known pronunciation problem, and Indonesian is
spoken through a Malay model. Please note specific words.
"""

    private fun fail(msg: String): Int {
        println("listening-kit: FAIL $msg")
        return 1
    }
}
