package app.mokuhyo.desktop

import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.speech.AudioIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

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
        Result.Ready(wav, "voice service")
    }

    private suspend fun render(speech: SpeechOutput, passage: ExamPassage): ByteArray? {
        val voices = speech.voicesFor(passage.language)
        if (voices.isEmpty()) return null
        val speakers = passage.script.map { it.speaker }.distinct()
        val pcm = ArrayList<ShortArray>()
        for (line in passage.script) {
            // Distinct speakers get distinct voices when there are enough; otherwise match gender.
            val byGender = voices.filter { it.gender == line.voice }.ifEmpty { voices }
            val voice = byGender[speakers.indexOf(line.speaker).coerceAtLeast(0) % byGender.size]
            val spoken = speech.synthesize(line.text, passage.language, voice) ?: return null
            pcm += AudioIO.toPcm16kMono(spoken.wav)
            pcm += ShortArray(16_000 * 6 / 10) // 0.6 s between lines
        }
        val all = ShortArray(pcm.sumOf { it.size }).also { out -> var o = 0; pcm.forEach { it.copyInto(out, o); o += it.size } }
        return AudioIO.wav(all)
    }

    private fun decode(file: File): ByteArray = file.readBytes()

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    @Suppress("unused")
    private fun silence(ms: Int): ByteArray {
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val bytes = ByteArray(16 * ms * 2)
        val out = ByteArrayOutputStream()
        AudioSystem.write(AudioInputStream(bytes.inputStream(), format, (bytes.size / 2).toLong()), javax.sound.sampled.AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }
}
