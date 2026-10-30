package app.mokuhyo.desktop

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import app.mokuhyo.lang.VoiceRotation
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Boundary
import app.mokuhyo.speech.BoundaryPolicies
import app.mokuhyo.speech.BoundaryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

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
        // A pack clip that can't be decoded (truncated, damaged, unreadable) is a recoverable result, never an
        // exception out of the screen (QC follow-up F1): the voice service replaces it when it can.
        var damaged = false
        passage.audio?.let { rel ->
            app.packFile(passage.language, rel)?.let { file ->
                expected { decode(file) }?.let { return@withContext Result.Ready(it, clipSource(passage)) }
                damaged = true
            }
        }
        val voices = app.speech.voicesFor(passage.language)
        val unavailable = Result.Unavailable(
            if (damaged) DAMAGED else "No voice for this language is available on this computer. Practice shows the transcript instead; " +
                "in a test you can leave this recording out of the score.",
        )
        if (voices.isEmpty()) return@withContext unavailable
        val replaced = if (damaged) " — the pack recording was damaged" else ""
        val cast = cast(voices, passage)
        // The key names the voices that will actually speak, so installing or upgrading a voice re-renders (QC 2026-10-19).
        val key = cacheKey(passage, cast)
        val cached = File(app.dataDir, "cache/audio/${passage.language}/${passage.id}-$key.wav")
        if (cached.isFile) {
            expected { cached.readBytes().takeIf(::playable) }?.let { return@withContext Result.Ready(it, "voice service (cached)$replaced") }
            // A damaged cache file is thrown away and rendered again, once.
            cached.delete()
        }
        val rendered = try {
            render(app.speech, passage, cast)
        } catch (e: Exception) {
            if (!isExpected(e)) throw e
            return@withContext Result.Unavailable("The voice couldn't render this recording (${e.message ?: e::class.simpleName}). $RETRY")
        }
        val (wav, asCast) = rendered?.takeIf { playable(it.first) } ?: return@withContext unavailable
        // A line that fell back to another voice isn't what the key names: play it, but don't keep it.
        if (asCast) expected {
            cached.parentFile.mkdirs()
            cached.writeBytes(wav)
        }
        Result.Ready(wav, "voice service$replaced" + (VOICE_NOTES[passage.language]?.let { " — $it" } ?: ""))
    }

    /**
     * [load], with a backstop for the screen: any failure it didn't foresee (a future decoder bug, say) still becomes
     * [Result.Unavailable] with the retry and leave-out controls, never a blank panel. Cancellation still propagates.
     */
    suspend fun loadOrUnavailable(passage: ExamPassage): Result = try {
        load(passage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Unavailable("This recording couldn't be loaded (${e.message ?: e::class.simpleName}). $RETRY")
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
        val all = compose(lines, passage.script.map { it.speaker }, rate)
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
        const val RENDER_VERSION = 3

        /**
         * A passage's line clips (PCM, rate) at [rate], joined by the shared boundary policy (QC 2026-10-30 V2): a speaker
         * turn gets a longer gap than one speaker's next line, each line's own edge silence counts toward it, and nothing
         * follows the last line. The prerender tool ([RenderAudio]) joins the same way.
         */
        fun compose(lines: List<Pair<ShortArray, Int>>, speakers: List<String>, rate: Int, policy: BoundaryPolicy = BoundaryPolicies.default): ShortArray =
            policy.join(lines.map { (pcm, from) -> AudioIO.resample(pcm, from, rate) }, speakers.zipWithNext { a, b -> Boundary.between(a, b) }, rate)

        internal fun cacheKey(passage: ExamPassage, cast: Map<String, VoiceSpec>): String = digest(
            "v$RENDER_VERSION|${BoundaryPolicies.default.version}|" + passage.language + "|" + cast.entries.sortedBy { it.key }.joinToString(",") { (s, v) -> "$s=${v.engine}/${v.id}/${v.speaker}" } +
                "\n" + passage.script.joinToString("\n") { it.speaker + "|" + it.voice + "|" + it.text },
        )

        private fun digest(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        /** VOICEVOX character credits the characters' terms require (voices/voicevox_voices.json). */
        val VOICEVOX_CREDITS = mapOf("vv:8" to "VOICEVOX:春日部つむぎ", "vv:2" to "VOICEVOX:四国めたん", "vv:11" to "VOICEVOX:玄野武宏", "vv:11:low" to "VOICEVOX:玄野武宏")

        const val RETRY = "Try again, or leave it out of the score (practice shows the transcript instead)."
        const val DAMAGED = "This recording file is damaged. $RETRY"

        /** Failures a damaged or unreadable file produces; anything else is a bug and is left to [loadOrUnavailable]. */
        private fun isExpected(e: Exception): Boolean = e !is CancellationException &&
            (e is IOException || e is IllegalArgumentException || e is IllegalStateException || e is UnsupportedAudioFileException || e is IndexOutOfBoundsException)

        /** [block]'s value, or null when it failed the way a damaged file fails. Never swallows cancellation. */
        private inline fun <T> expected(block: () -> T): T? = try {
            block()
        } catch (e: Exception) {
            if (!isExpected(e)) throw e
            null
        }

        /** WAV (or AIFF) bytes whose header parses and that hold at least one frame of sound. */
        internal fun playable(wav: ByteArray): Boolean = try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(wav)).use { s ->
                s.frameLength != 0L && s.readNBytes(s.format.frameSize.coerceAtLeast(1)).isNotEmpty()
            }
        } catch (e: Exception) {
            if (!isExpected(e)) throw e
            false
        }

        /** Honest labels for voices with known problems (docs/LANGUAGES.md, PROGRESS gate_lang known gap). */
        val VOICE_NOTES = mapOf("fa" to "synthetic voice; some words are mispronounced")
    }

    /** Pack clips are Ogg Opus (or WAV); the player wants WAV. Throws when the file is damaged. */
    private fun decode(file: File): ByteArray =
        AudioIO.clipWav(file.readBytes()).also { require(playable(it)) { "the recording file is damaged" } }

}
