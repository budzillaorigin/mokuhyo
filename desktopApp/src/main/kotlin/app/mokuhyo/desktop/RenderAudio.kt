package app.mokuhyo.desktop

import app.mokuhyo.exam.ExamContent
import app.mokuhyo.exam.Skill
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.OggOpus
import app.mokuhyo.tts.VoiceService
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--render-audio --language xx [--packs DIR] [--force]` (build tool, BRIEF §5.3 / `tools/packs/render_audio.py`):
 * renders every listening passage of the pack with the bundled voice service into `<packs>/<lang>/audio/<id>.ogg`
 * (Ogg Opus, 16 kHz mono). Speakers get distinct voices of their gender where the language has them. Languages whose
 * only voice is the OS voice are not rendered: the OS voices' terms don't allow redistributing recordings (D-013), so
 * those passages are spoken at run time.
 */
object RenderAudio {
    fun run(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val lang = Smoke.arg(args, "--language") ?: return@runBlocking fail("--language is required")
        val packs = Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") } ?: return@runBlocking fail("--packs")
        val examFile = File(packs, "$lang/exam.json").takeIf { it.isFile } ?: return@runBlocking fail("no $lang/exam.json; run build_packs.py first")
        val content = ExamContent.parse(examFile.readText())
        val voices = VoiceService.create()
        val piper = voices.voicesFor(lang).filter { it.engine == "piper" }
        if (piper.isEmpty()) {
            println("render-audio: $lang has no bundled voice; passages are spoken at run time by the OS voice (D-013)")
            voices.close()
            return@runBlocking 0
        }
        val outDir = File(packs, "$lang/audio").apply { mkdirs() }
        var made = 0
        var kept = 0
        // Track dialogues (BRIEF_PHASE8 C-03) are rendered the same way, keyed by dialogue id.
        val dialogues = File(packs, "$lang/track-${app.mokuhyo.lexicon.TrackIds.CUAS}.json").takeIf { it.isFile }
            ?.let { f -> app.mokuhyo.lexicon.Track.parse(f.readText()).dialogues.map { it.asPassage(lang) } }.orEmpty()
        (content.passagesFor(Skill.LISTENING) + dialogues).sortedBy { it.id }.forEach { p ->
            val out = File(outDir, "${p.id}.ogg")
            if (out.isFile && "--force" !in args) { kept++; return@forEach }
            val speakers = p.script.map { it.speaker }.distinct()
            val pcm = ArrayList<ShortArray>()
            for (line in p.script) {
                val byGender = piper.filter { it.gender == line.voice }.ifEmpty { piper }
                val voice = byGender[speakers.indexOf(line.speaker).coerceAtLeast(0) % byGender.size]
                val audio = voices.synthesize(line.text, lang, voice) ?: return@runBlocking fail("synthesis failed for ${p.id}")
                pcm += AudioIO.toPcm16kMono(audio.wav)
                pcm += ShortArray(16_000 * 6 / 10)
            }
            val all = ShortArray(pcm.sumOf { it.size }).also { a -> var o = 0; pcm.forEach { it.copyInto(a, o); o += it.size } }
            out.writeBytes(OggOpus.encode(all))
            made++
            println("render-audio: ${p.id} ${all.size / 16_000}s ${out.length() / 1024} KB")
        }
        voices.close()
        println("render-audio: $lang rendered $made, kept $kept")
        0
    }

    private fun fail(msg: String): Int {
        println("render-audio: FAIL $msg")
        return 1
    }
}
