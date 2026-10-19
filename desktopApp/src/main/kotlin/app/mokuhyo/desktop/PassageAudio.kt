package app.mokuhyo.desktop

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import app.mokuhyo.lang.VoiceRotation
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.AudioIO
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
        passage.audio?.let { rel -> app.packFile(passage.language, rel)?.let { return@withContext Result.Ready(decode(it), clipSource(passage)) } }
        val voices = app.speech.voicesFor(passage.language)
        val unavailable = Result.Unavailable(
            "No voice for this language is available on this computer. Practice shows the transcript instead; " +
                "in a test you can leave this recording out of the score.",
        )
        if (voices.isEmpty()) return@withContext unavailable
        val cast = cast(voices, passage)
        // The key names the voices that will actually speak, so installing or upgrading a voice re-renders (QC 2026-10-19).
        val key = cacheKey(passage, cast)
        val cached = File(app.dataDir, "cache/audio/${passage.language}/${passage.id}-$key.wav")
        if (cached.isFile) return@withContext Result.Ready(cached.readBytes(), "voice service (cached)")
        val (wav, asCast) = render(app.speech, passage, cast) ?: return@withContext unavailable
        // A line that fell back to another voice isn't what the key names: play it, but don't keep it.
        if (asCast) {
            cached.parentFile.mkdirs()
            cached.writeBytes(wav)
        }
        Result.Ready(wav, "voice service" + (VOICE_NOTES[passage.language]?.let { " — $it" } ?: ""))
    }

    private fun cast(voices: List<VoiceSpec>, passage: ExamPassage): Map<String, VoiceSpec> =
        // Speaker variety (BRIEF_PHASE8 N-07): rotate by passage id; distinct speakers get distinct voices when there are enough.
        VoiceRotation.assign(voices, passage.script.map { it.speaker to it.voice }.distinctBy { it.first }, passage.id)

    /** The passage's WAV, and whether every line was spoken by the voice [cast] gave it. */
    private suspend fun render(speech: SpeechOutput, passage: ExamPassage, cast: Map<String, VoiceSpec>): Pair<ByteArray, Boolean>? {
        val lines = ArrayList<Pair<ShortArray, Int>>()
        var asCast = true
        for (line in passage.script) {
            val wanted = cast.getValue(line.speaker)
            val spoken = speech.synthesize(line.text, passage.language, wanted) ?: return null
            if (spoken.voice.engine != wanted.engine || spoken.voice.id != wanted.id || spoken.voice.speaker != wanted.speaker) asCast = false
            lines += AudioIO.toMonoPcm(spoken.wav)
        }
        // Keep the voices' own sample rate for playback (Whisper gets its 16 kHz copy elsewhere): no lost bandwidth.
        val rate = lines.maxOf { it.second }
        val pcm = ArrayList<ShortArray>()
        passage.script.forEachIndexed { i, line ->
            val (samples, from) = lines[i]
            pcm += AudioIO.resample(samples, from, rate)
            val next = passage.script.getOrNull(i + 1) ?: return@forEachIndexed
            pcm += ShortArray(rate * pauseMs(line.speaker, next.speaker) / 1000)
        }
        val all = ShortArray(pcm.sumOf { it.size }).also { out -> var o = 0; pcm.forEach { it.copyInto(out, o); o += it.size } }
        return AudioIO.wav(all, rate) to asCast
    }

    /**
     * "pre-rendered clip", plus the engine and donor voices when the pack's `audio/voices.json` records them (Chatterbox
     * clips, BRIEF_PHASE8 N-00): the credit the voices' licenses ask for, and the honest label for AI audio.
     */
    private fun clipSource(passage: ExamPassage): String {
        val credits = app.packFile(passage.language, "audio/voices.json") ?: return "pre-rendered clip"
        val entry = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(credits.readText()).jsonObject[passage.id]?.jsonObject
        }.getOrNull() ?: return "pre-rendered clip"
        val voices = entry["voices"]?.jsonArray?.map { it.jsonPrimitive.content.removeSuffix(".wav") }.orEmpty()
        if (entry["engine"]?.jsonPrimitive?.content == "voicevox") {
            return "pre-rendered clip · " + voices.mapNotNull { VOICEVOX_CREDITS[it] }.distinct().joinToString()
        }
        return "pre-rendered clip · AI voice (Chatterbox Multilingual, Resemble AI; watermarked)" +
            if (voices.isEmpty()) "" else " · reference voices: ${voices.joinToString()}"
    }

    companion object {
        /** Bump when the way passages are rendered changes, so cached renders are redone. */
        const val RENDER_VERSION = 2

        /** A turn change gets a longer gap than one speaker's next sentence. */
        fun pauseMs(speaker: String, next: String): Int = if (speaker == next) 350 else 700

        internal fun cacheKey(passage: ExamPassage, cast: Map<String, VoiceSpec>): String = digest(
            "v$RENDER_VERSION|" + passage.language + "|" + cast.entries.sortedBy { it.key }.joinToString(",") { (s, v) -> "$s=${v.engine}/${v.id}/${v.speaker}" } +
                "\n" + passage.script.joinToString("\n") { it.speaker + "|" + it.voice + "|" + it.text },
        )

        private fun digest(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        /** VOICEVOX character credits the characters' terms require (voices/voicevox_voices.json). */
        val VOICEVOX_CREDITS = mapOf("vv:8" to "VOICEVOX:春日部つむぎ", "vv:2" to "VOICEVOX:四国めたん", "vv:11" to "VOICEVOX:玄野武宏", "vv:11:low" to "VOICEVOX:玄野武宏")

        /** Honest labels for voices with known problems (docs/LANGUAGES.md, PROGRESS gate_lang known gap). */
        val VOICE_NOTES = mapOf("fa" to "synthetic voice; some words are mispronounced")
    }

    /** Pack clips are Ogg Opus (or WAV); the player wants WAV. */
    private fun decode(file: File): ByteArray {
        return AudioIO.clipWav(file.readBytes())
    }

}
