package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.lang.Languages
import app.mokuhyo.opi.OpiInterviewerTurn
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Preflight
import app.mokuhyo.speech.Preflight.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * The checks behind an interview's Start (BRIEF_INTERVIEW_SPEED N4), built for this computer and language. Device
 * work runs on [Dispatchers.IO]. Tests replace [checks] (as they replace [Voice.output]) so a headless runner with no
 * audio devices exercises the interview, and test the real checks on their own.
 */
object InterviewPreflight {
    @Volatile
    internal var checks: (app: AppGraph, lang: String, gateway: AiGateway, test: Boolean, warm: OpiInterviewerTurn.Input?) -> List<Preflight.Check> = ::real

    fun real(app: AppGraph, lang: String, gateway: AiGateway, test: Boolean, warm: OpiInterviewerTurn.Input?): List<Preflight.Check> {
        val name = Languages.of(lang)?.nameEnglish ?: lang
        val recognizer = app.recognizer()
        return listOf(
            Preflight.Check(Preflight.Kind.MICROPHONE, "Microphone opens") {
                if (recognizer == null) return@Check Result.NotNeeded("No speech recognition installed: you'll type your answers.")
                withContext(Dispatchers.IO) {
                    try {
                        val rec = AudioIO.record(app.settings.get(Settings.Key.INPUT_DEVICE))
                        delay(250)
                        rec.stop()
                        Result.Ok(app.settings.get(Settings.Key.INPUT_DEVICE) ?: "system default input")
                    } catch (e: Exception) {
                        Result.Failed("The microphone didn't open (${e.message}). Pick the right input in Settings → Speech & audio, and check " +
                            "the system's microphone permission for Mokuhyo.")
                    }
                }
            },
            Preflight.Check(Preflight.Kind.OUTPUT, "Speakers play a short tone") {
                withContext(Dispatchers.IO) {
                    try {
                        Voice.play(app, tone())
                        Result.Ok(app.settings.get(Settings.Key.OUTPUT_DEVICE) ?: "system default output")
                    } catch (e: Exception) {
                        Result.Failed("The speakers didn't play (${e.message}). Check the speakers or headphones and the output device in " +
                            "Settings → Speech & audio.")
                    }
                }
            },
            Preflight.Check(Preflight.Kind.VOICE, "A voice for $name") {
                val voice = runCatching { app.speech.voicesFor(lang).firstOrNull() }.getOrNull()
                when {
                    voice != null -> Result.Ok(Voice.describe(voice))
                    test -> Result.Failed("No offline voice can speak $name on this computer, so a spoken test isn't possible. Install a " +
                        "$name voice in the system settings (Settings → Speech & audio → This computer's voices shows what was found), " +
                        "or use guided text practice.")
                    else -> Result.NotNeeded("No offline voice for $name on this computer: the questions are shown as text (text practice).")
                }
            },
            Preflight.Check(Preflight.Kind.TTS, "The voice is ready") {
                if (runCatching { app.speech.voicesFor(lang).isEmpty() }.getOrDefault(true)) return@Check Result.NotNeeded("No voice to start.")
                // Starts the voice's process (Piper) so the first question doesn't wait for it.
                if (Voice.render(app, Voice.sample(lang), lang) != null) Result.Ok()
                else Result.Failed("The $name voice didn't start. Settings → Speech & audio → Play test voice shows the error.")
            },
            Preflight.Check(Preflight.Kind.ASR, "Speech recognition loaded") {
                if (recognizer == null) return@Check Result.NotNeeded("Not installed (Settings → Speech & audio → Speech recognition).")
                try {
                    withContext(Dispatchers.IO) { recognizer.transcribe(ShortArray(8_000), app.languages.module(lang).sttLanguage) }
                    Result.Ok()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Result.Failed("Speech recognition didn't load (${e.message}). Choose or re-download the model in Settings → Speech & audio.")
                }
            },
            Preflight.Check(Preflight.Kind.LLM, "AI model loaded and warmed") {
                if (!gateway.hasModel()) return@Check Result.NotNeeded("No AI model: the interview uses guided (scripted) questions.")
                val input = warm ?: OpiInterviewerTurn.Input(lang, "", OpiPhase.WARMUP, IlrLevel.L1)
                when (val error = withContext(Dispatchers.Default) { gateway.warm(OpiInterviewerTurn(), input) }) {
                    null -> Result.Ok(gateway.modelId())
                    else -> Result.Failed("The AI model didn't load ($error). Settings → AI → Test the model shows the details.")
                }
            },
        )
    }

    /** 250 ms of a quiet 660 Hz tone with soft edges. */
    fun tone(): ByteArray {
        val n = 4_000
        return AudioIO.wav(ShortArray(n) { i ->
            val edge = minOf(i, n - i).coerceAtMost(400) / 400.0
            (sin(2 * PI * 660 * i / 16_000) * 3_000 * edge).toInt().toShort()
        })
    }
}
