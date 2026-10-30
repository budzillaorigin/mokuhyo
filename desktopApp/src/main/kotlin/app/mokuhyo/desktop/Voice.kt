package app.mokuhyo.desktop

import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.SpokenReply
import kotlinx.coroutines.channels.ReceiveChannel
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.EdgeSilence
import app.mokuhyo.speech.currentSpeechBudget
import app.mokuhyo.tts.DevicePcmLine
import app.mokuhyo.tts.PcmReplyPlayer
import app.mokuhyo.tts.SpeechEngine
import app.mokuhyo.tts.SpeechRequest
import app.mokuhyo.tts.VoiceService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        /** Nothing had played when the response's speech budget ran out (QC 2026-10-30 V6). */
        data object TimedOut : Outcome

        /**
         * The reply's voice failed after it had started and only [got] could speak the rest: the reply stopped rather than
         * switch voices mid-reply (QC 2026-10-30 V5).
         */
        data class VoiceChanged(val voice: VoiceSpec, val got: VoiceSpec) : Outcome

        val ok: Boolean get() = this is Played

        /** A sentence for the learner when it didn't play. */
        val problem: String? get() = when (this) {
            is Played -> null
            NoVoice -> "No voice is available for this language on this computer."
            TimedOut -> "The voice took too long to start, so the text is shown instead."
            is VoiceChanged -> "The voice stopped working part-way, so the reply stopped rather than continue in another voice; it is shown as text."
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
     * line is open and sound is about to flow: the device-open proxy for "first partner audio" (BRIEF_INTERVIEW_SPEED N1;
     * the first audible sample comes after the clip's leading silence, QC 2026-10-30 V12). [underrun] runs when the
     * line's buffer ran dry mid-utterance.
     */
    @Volatile
    internal var output: (wav: ByteArray, device: String?, stop: () -> Boolean, started: () -> Unit, underrun: () -> Unit) -> Unit = { wav, device, stop, started, underrun ->
        AudioIO.open(wav, device).also { it.onUnderrun = underrun; started() }.run(stop)
    }

    /** Plays [wav] on the output device chosen in Settings: the one output path every screen uses. */
    fun play(app: AppGraph, wav: ByteArray, stop: () -> Boolean = { false }, started: () -> Unit = {}, underrun: () -> Unit = {}) =
        output(wav, app.settings.get(Settings.Key.OUTPUT_DEVICE), stop, started, underrun)

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
        // A WAV engine's audio is ready all at once: synthesis complete, not first PCM (QC 2026-10-30 V12).
        trace?.mark(TurnStage.TTS_SYNTH_DONE)
        // Every substitution of the requested voice is logged and shown in diagnostics (QC 2026-10-30 V5). Ids only.
        val fallback = voice?.takeIf { !same(it, spoken.voice) }?.let { "${label(it)}->${label(spoken.voice)}" }
        fallback?.let { app.log.line("tts fallback $lang $it") }
        trace?.timeline?.note(trace.turn) {
            ttsSynthMs += (System.nanoTime() - synthStart) / 1_000_000
            ttsAudioMs += ((Wav.info(spoken.wav)?.durationSeconds ?: 0.0) * 1000).toLong()
            voices += label(spoken.voice)
            fallback?.let { voiceFallbacks += it }
        }
        spoken
    }

    /**
     * Plays already-synthesized speech; cancelling the caller stops it (QC follow-up F2). Marks device open (a proxy),
     * the first audible sample (open + the clip's leading silence), underruns and, when it played to the end, the last
     * audible sample (QC 2026-10-30 V12). Numbers only: no text or audio reaches the timeline.
     */
    suspend fun playRendered(app: AppGraph, spoken: SpokenAudio, stop: () -> Boolean = { false }): Outcome = withContext(Dispatchers.IO) {
        val trace = currentTrace()
        val budget = currentSpeechBudget()
        ensureActive()
        val job = coroutineContext.job
        val edges = trace?.let { runCatching { AudioIO.toMonoPcm(spoken.wav).let { (pcm, rate) -> EdgeSilence.of(pcm, rate) } }.getOrNull() }
        try {
            var stopped = false
            play(app, spoken.wav, { (stop() || !job.isActive).also { if (it) stopped = true } }, {
                budget?.start()
                trace?.mark(TurnStage.OUTPUT_START)
                if (edges != null && !edges.silent) trace.timeline.markAfter(TurnStage.OUTPUT_AUDIBLE, edges.leadingMs, trace.turn)
            }) { trace?.mark(TurnStage.OUTPUT_UNDERRUN) }
            trace?.mark(TurnStage.OUTPUT_END)
            if (edges != null && !edges.silent && !stopped) trace.timeline.markBefore(TurnStage.SPEECH_END, edges.trailingMs, trace.turn)
            ensureActive()
            Outcome.Played(spoken.voice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.PlaybackFailed(spoken.voice, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** `engine:id[#speaker]`, the identity compared across a reply and logged. */
    fun label(v: VoiceSpec): String = "${v.engine}:${v.id}" + (v.speaker?.let { "#$it" } ?: "")

    fun same(a: VoiceSpec, b: VoiceSpec): Boolean = a.engine == b.engine && a.id == b.id && a.speaker == b.speaker

    /** A streamed reply as delivered: the overall [outcome] and how far each sentence got ([reply], QC 2026-10-30 V10). */
    data class Streamed(val outcome: Outcome, val reply: SpokenReply.Outcome) {
        val heard: List<String> get() = reply.heard
    }

    /**
     * Speaks a streamed reply (BRIEF_INTERVIEW_SPEED N3): each sentence as it arrives, the next one synthesized while
     * the current one plays, all in one voice (the first sentence's, or [voice]). [Streamed.outcome] is
     * [Outcome.Played] when every sentence played, otherwise why not; null when no sentence arrived at all. Audio that
     * was ready before a failure still plays (V10). Cancelling the caller stops it.
     */
    suspend fun sayStreamed(app: AppGraph, sentences: ReceiveChannel<String>, lang: String, voice: VoiceSpec? = null): Streamed? {
        // An engine that streams PCM plays the whole reply through one output line (QC 2026-10-30 V4). No shipped
        // engine does (Piper and OS voices return WAVs), so this is the path for a future engine; sentence streaming
        // below stays the compatibility path.
        (app.speech as? VoiceService)?.pcmStreamFor(lang, voice)?.let { (engine, v) -> return sayPcm(app, engine, v, sentences, lang) }
        // One identity per reply (QC 2026-10-30 V5): [voice] is asked for; the first sentence that renders fixes the
        // voice (a fallback before anything played is allowed and logged). A later sentence that only another voice
        // could render stops the reply instead of switching voices part-way; V10's repair then applies.
        var used: VoiceSpec? = null
        var problem: Outcome? = null
        val result = SpokenReply<SpokenAudio>(
            synth = { s ->
                val a = render(app, s, lang, used ?: voice)
                val fixed = used
                when {
                    a == null -> { problem = if (currentSpeechBudget()?.exhausted == true) Outcome.TimedOut else Outcome.NoVoice; null }
                    fixed != null && !same(fixed, a.voice) -> {
                        app.log.line("tts reply stopped $lang: ${label(fixed)} failed mid-reply, only ${label(a.voice)} could continue")
                        problem = Outcome.VoiceChanged(fixed, a.voice)
                        null
                    }
                    else -> a.also { if (fixed == null) used = a.voice }
                }
            },
            play = { a -> playRendered(app, a).also { if (!it.ok) problem = it }.ok },
        ).speak(sentences)
        val outcome = when {
            result.utterances.isEmpty() -> return null
            result.failed -> problem ?: Outcome.NoVoice
            else -> Outcome.Played(used!!)
        }
        return Streamed(outcome, result)
    }

    private suspend fun sayPcm(app: AppGraph, engine: SpeechEngine, voice: VoiceSpec, sentences: ReceiveChannel<String>, lang: String): Streamed? = coroutineScope {
        val trace = currentTrace()
        val budget = currentSpeechBudget()
        val requests = Channel<SpeechRequest>(1)
        launch {
            var previous: String? = null
            try {
                for (s in sentences) {
                    requests.send(SpeechRequest(voice, lang, s, preceding = previous, intent = SpeechRequest.intentOf(s)))
                    previous = s
                }
            } finally {
                requests.close()
            }
        }
        val device = app.settings.get(Settings.Key.OUTPUT_DEVICE)
        val result = PcmReplyPlayer({ rate ->
            DevicePcmLine.open(rate, device).also { budget?.start(); trace?.mark(TurnStage.OUTPUT_START) }
        }).speak(engine, requests)
        trace?.mark(TurnStage.OUTPUT_END)
        trace?.timeline?.note(trace.turn) { repeat(result.played) { voices += label(voice) } }
        if (result.utterances.isEmpty()) return@coroutineScope null
        Streamed(if (result.failed) Outcome.PlaybackFailed(voice, "the streamed voice stopped") else Outcome.Played(voice), result)
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
