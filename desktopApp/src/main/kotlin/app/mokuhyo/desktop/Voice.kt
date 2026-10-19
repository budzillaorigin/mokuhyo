package app.mokuhyo.desktop

import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.SpokenReply
import kotlinx.coroutines.channels.ReceiveChannel
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.currentTrace
import app.mokuhyo.tts.Wav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Speech output for the app: every caller goes through the voice service (bundled Piper, then the OS voice). */
object Voice {
    private val samples = mapOf(
        "ja" to "こんにちは。今日もよろしくお願いします。", "es" to "Hola. Vamos a practicar un poco hoy.",
        "fr" to "Bonjour. Pratiquons un peu aujourd'hui.", "de" to "Hallo. Lass uns heute ein bisschen üben.",
        "pt-BR" to "Olá. Vamos praticar um pouco hoje.", "ru" to "Здравствуйте. Давайте немного попрактикуемся.",
        "zh-Hans" to "你好。我们今天练习一下吧。", "ko" to "안녕하세요. 오늘 조금 연습해 봅시다.",
        "ar" to "مرحبا. لنتدرب قليلا اليوم.", "fa" to "سلام. بیایید امروز کمی تمرین کنیم.", "id" to "Halo. Mari kita berlatih sedikit hari ini.",
    )

    /** A short greeting in [lang] (voice checks and warm-up). */
    fun sample(lang: String): String = samples[lang] ?: samples.getValue("es")

    /** What happened when the app tried to say something (QC 2026-10-19 #2: callers must not treat silence as success). */
    sealed interface Outcome {
        /** It played to the end (or the caller stopped it) with [voice]. */
        data class Played(val voice: VoiceSpec) : Outcome

        /** No voice on this computer can speak the language. */
        data object NoVoice : Outcome

        /** A voice rendered it but the speakers couldn't play it. */
        data class PlaybackFailed(val voice: VoiceSpec, val message: String) : Outcome

        val ok: Boolean get() = this is Played

        /** A sentence for the learner when it didn't play. */
        val problem: String? get() = when (this) {
            is Played -> null
            NoVoice -> "No voice is available for this language on this computer."
            is PlaybackFailed -> "The voice couldn't be played: $message. Check the speakers or headphones and the output device in Settings → Speech & audio."
        }
    }

    /** How a voice is named to the learner: which engine, which voice. */
    fun describe(voice: VoiceSpec): String = when (voice.engine) {
        "piper" -> "bundled voice"
        "os" -> "operating-system voice"
        else -> voice.engine
    } + " · " + voice.id + (voice.speaker?.let { " #$it" } ?: "")

    /**
     * Where audio goes: the device (tests replace it to observe what would have played). [started] runs once the output
     * line is open and sound is about to flow: the timeline's "first partner audio" (BRIEF_INTERVIEW_SPEED N1).
     */
    @Volatile
    internal var output: (wav: ByteArray, device: String?, stop: () -> Boolean, started: () -> Unit) -> Unit = { wav, device, stop, started ->
        AudioIO.open(wav, device).also { started() }.run(stop)
    }

    /** Plays [wav] on the output device chosen in Settings: the one output path every screen uses. */
    fun play(app: AppGraph, wav: ByteArray, stop: () -> Boolean = { false }, started: () -> Unit = {}) =
        output(wav, app.settings.get(Settings.Key.OUTPUT_DEVICE), stop, started)

    /**
     * Synthesizes [text] with the app's voice service and plays it on the chosen output device. Cancelling the caller
     * stops it: nothing plays once cancelled during synthesis, and playback in progress stops (QC follow-up F2).
     */
    suspend fun say(app: AppGraph, text: String, lang: String, voice: VoiceSpec? = null, stop: () -> Boolean = { false }): Outcome {
        val spoken = render(app, text, lang, voice) ?: return Outcome.NoVoice
        return playRendered(app, spoken, stop)
    }

    /** Synthesizes [text] off the UI thread, marking the session timeline's TTS stages when the caller carries one (N1). */
    suspend fun render(app: AppGraph, text: String, lang: String, voice: VoiceSpec? = null): SpokenAudio? = withContext(Dispatchers.IO) {
        // Stage marks for the session timeline, when the caller carries one (N1). Text never goes to it.
        val trace = currentTrace()
        trace?.mark(TurnStage.TTS_START)
        val synthStart = System.nanoTime()
        val spoken = app.speech.synthesize(text, lang, voice) ?: return@withContext null
        trace?.mark(TurnStage.TTS_FIRST_PCM)
        trace?.timeline?.note(trace.turn) {
            ttsSynthMs += (System.nanoTime() - synthStart) / 1_000_000
            ttsAudioMs += ((Wav.info(spoken.wav)?.durationSeconds ?: 0.0) * 1000).toLong()
        }
        spoken
    }

    /** Plays already-synthesized speech; cancelling the caller stops it (QC follow-up F2). */
    suspend fun playRendered(app: AppGraph, spoken: SpokenAudio, stop: () -> Boolean = { false }): Outcome = withContext(Dispatchers.IO) {
        val trace = currentTrace()
        ensureActive()
        val job = coroutineContext.job
        try {
            play(app, spoken.wav, { stop() || !job.isActive }) { trace?.mark(TurnStage.OUTPUT_START) }
            trace?.mark(TurnStage.OUTPUT_END)
            ensureActive()
            Outcome.Played(spoken.voice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.PlaybackFailed(spoken.voice, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * Speaks a streamed reply (BRIEF_INTERVIEW_SPEED N3): each sentence as it arrives, the next one synthesized while
     * the current one plays, all in one voice (the first sentence's, or [voice]). Returns [Outcome.Played] when every
     * sentence played, otherwise why not; null when no sentence arrived at all. Cancelling the caller stops it.
     */
    suspend fun sayStreamed(app: AppGraph, sentences: ReceiveChannel<String>, lang: String, voice: VoiceSpec? = null): Outcome? {
        var used: VoiceSpec? = voice
        var problem: Outcome? = null
        val result = SpokenReply<SpokenAudio>(
            synth = { s ->
                val a = render(app, s, lang, used)
                if (a == null) problem = Outcome.NoVoice else if (used == null) used = a.voice
                a
            },
            play = { a -> playRendered(app, a).also { if (!it.ok) problem = it }.ok },
        ).speak(sentences)
        return when {
            result.played == 0 && !result.failed -> null
            result.failed -> problem ?: Outcome.NoVoice
            else -> Outcome.Played(used!!)
        }
    }

    /**
     * Plays a sample sentence through the same voice service and output path the app uses everywhere else (QC
     * 2026-10-19 #4); returns what the learner should read: the voice that played, or why nothing did.
     */
    suspend fun speakSample(app: AppGraph, lang: String): String =
        when (val o = say(app, samples[lang] ?: samples.getValue("es"), lang)) {
            is Outcome.Played -> "Test voice played: ${describe(o.voice)}."
            else -> o.problem!!
        }
}
