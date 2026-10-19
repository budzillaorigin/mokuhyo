package app.mokuhyo.desktop

import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.opi.OpiPack
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.tts.VoiceService
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * `--listening-kit --language es,fa,id [--out DIR] [--prompts 10] [--seed 7] [--include-os]` (BRIEF_INTERVIEW_SPEED
 * N6): a blind listening comparison for native speakers. The same [count] interviewer prompts per language (spread
 * over the phases) are spoken by every source available here — each bundled Piper voice, the prerendered clip when the
 * pack has one, and with `--include-os` the operating-system voice — into shuffled, unlabeled files
 * `<lang>/<lang>-NNN.wav`. `key.csv` maps files to sources and stays with the owner; `scoring-sheet.csv` (one row per
 * file: intelligibility, pronunciation, naturalness, 1–5, scored separately) goes to raters with the audio.
 * `tools/voices/listening_kit.py score` joins returned sheets to the key. A future candidate engine joins by being a
 * [app.mokuhyo.tts.SpeechEngine] in the voice service; nothing here names an engine.
 *
 * OS voices are off by default: their terms don't allow redistributing recordings (D-013), so the owner decides
 * whether a private evaluation may include them.
 */
object ListeningKit {
    fun run(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val out = (Smoke.arg(args, "--out")?.let(::File) ?: File("build/listening-kit")).apply { mkdirs() }
        val count = Smoke.arg(args, "--prompts")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val random = Random(Smoke.arg(args, "--seed")?.toIntOrNull() ?: 7)
        val langs = Smoke.arg(args, "--language")?.split(",")?.map { it.trim() } ?: return@runBlocking fail("--language")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-kit").toFile()))
        val voices = VoiceService.create()
        val key = StringBuilder("file,language,source,voice,prompt_id\n")
        val sheet = StringBuilder("file,language,intelligibility_1to5,pronunciation_1to5,naturalness_1to5,comments\n")
        var files = 0
        for (lang in langs) {
            val pack = app.opi(lang) ?: run { println("listening-kit: no OPI pack for $lang"); null } ?: continue
            val prompts = pick(pack, count)
            val sources = voices.voicesFor(lang).filter { it.engine == "piper" || ("--include-os" in args && it.engine == "os") }
            val renders = ArrayList<Triple<String, String, ByteArray>>() // source, prompt id, wav
            for ((id, text) in prompts) {
                for (v in sources) voices.synthesize(text, lang, v)?.let { renders += Triple(label(v), id, it.wav) }
                PromptClips.load(app, lang, id, text)?.let { c -> renders += Triple("prerendered:${c.entry.engine}:${c.entry.voice}", id, c.wav) }
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
            println("listening-kit: $lang ${prompts.size} prompts × ${renders.size / prompts.size.coerceAtLeast(1)} sources")
        }
        File(out, "key.csv").writeText(key.toString())
        File(out, "scoring-sheet.csv").writeText(sheet.toString())
        File(out, "README.txt").writeText(README)
        voices.close()
        app.close()
        println("listening-kit: $files files in $out (keep key.csv; give raters the language folders, scoring-sheet.csv and README.txt)")
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

    private fun label(v: VoiceSpec) = "${v.engine}:${v.id}"

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
