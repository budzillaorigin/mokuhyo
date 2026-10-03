package app.mokuhyo.desktop

import app.mokuhyo.lang.VoiceRotation
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.OggOpus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Audio for a listening passage (BRIEF §5.3, CLAUDE.md "pre-rendered where consistency matters"): the pack's
 * pre-rendered clip when there is one, else the voice service speaks the script line by line (voices by speaker
 * gender) and the result is cached under the data dir so every replay sounds the same.
 */
class PassageAudio(private val app: AppGraph) {
    sealed interface Result {
        class Ready(val wav: ByteArray, val source: String) : Result
        class Unavailable(val reason: String) : Result
    }

    suspend fun load(passage: ExamPassage): Result = withContext(Dispatchers.IO) {
        passage.audio?.let { rel -> app.packFile(passage.language, rel)?.let { return@withContext Result.Ready(decode(it), "pre-rendered clip") } }
        val key = digest(passage.language + passage.script.joinToString("\n") { it.voice + "|" + it.text })
        val cached = File(app.dataDir, "cache/audio/${passage.language}/${passage.id}-$key.wav")
        if (cached.isFile) return@withContext Result.Ready(cached.readBytes(), "voice service (cached)")
        val wav = render(app.speech, passage) ?: return@withContext Result.Unavailable(
            "No voice for this language is available on this computer. Practice shows the transcript instead; tests skip this passage.",
        )
        cached.parentFile.mkdirs()
        cached.writeBytes(wav)
        Result.Ready(wav, "voice service" + (VOICE_NOTES[passage.language]?.let { " — $it" } ?: ""))
    }

    private suspend fun render(speech: SpeechOutput, passage: ExamPassage): ByteArray? {
        val voices = speech.voicesFor(passage.language)
        if (voices.isEmpty()) return null
        // Speaker variety (BRIEF_PHASE8 N-07): rotate by passage id; distinct speakers get distinct voices when there are enough.
        val cast = VoiceRotation.assign(voices, passage.script.map { it.speaker to it.voice }.distinctBy { it.first }, passage.id)
        val pcm = ArrayList<ShortArray>()
        for (line in passage.script) {
            val voice = cast.getValue(line.speaker)
            val spoken = speech.synthesize(line.text, passage.language, voice) ?: return null
            pcm += AudioIO.toPcm16kMono(spoken.wav)
            pcm += ShortArray(16_000 * 6 / 10) // 0.6 s between lines
        }
        val all = ShortArray(pcm.sumOf { it.size }).also { out -> var o = 0; pcm.forEach { it.copyInto(out, o); o += it.size } }
        return AudioIO.wav(all)
    }

    companion object {
        /** Honest labels for voices with known problems (docs/LANGUAGES.md, PROGRESS gate_lang known gap). */
        val VOICE_NOTES = mapOf("fa" to "synthetic voice; some words are mispronounced")
    }

    /** Pack clips are Ogg Opus (or WAV); the player wants WAV. */
    private fun decode(file: File): ByteArray {
        val bytes = file.readBytes()
        return if (OggOpus.isOggOpus(bytes)) AudioIO.wav(OggOpus.decode(bytes)) else bytes
    }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

}
