package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiSettings
import app.mokuhyo.ai.GgufReader
import app.mokuhyo.ai.LanguageModel
import app.mokuhyo.ai.LocalLlamaModel
import app.mokuhyo.ai.ModelInfo
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.SpeechRecognizer
import app.mokuhyo.ai.WhisperRecognizer
import app.mokuhyo.ai.jni.NativeLibrary
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.history.ConversationDraft
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.lang.IcuSentences
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.speech.SpokenReply
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.opi.CorrectionsMode
import app.mokuhyo.opi.OpiSession
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.platform.ProcessMemory
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.FallbackKind
import app.mokuhyo.speech.TimelineSetup
import app.mokuhyo.speech.TimelineTrace
import app.mokuhyo.speech.TimingReport
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.TurnTimeline
import app.mokuhyo.speech.Vad
import app.mokuhyo.speech.traceMark
import app.mokuhyo.tts.VoiceService
import app.mokuhyo.tts.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * `--bench-interview` (BRIEF_INTERVIEW_SPEED N1): plays fixed learner answers through the real ASR → LLM → TTS path,
 * with no microphone, and writes a JSON + Markdown timing report.
 *
 * ```
 * --bench-interview [--language es[,fr,…]|all] [--turns 10] [--model <gguf> --model-id <manifest id>] [--whisper <bin>]
 *                   [--clips <dir>] [--answers tools/bench/answers.json] [--out <dir>] [--cpu] [--play] [--data-dir <dir>]
 * ```
 * Per language: one cold turn (the model loads), then `--turns` warm turns alternating a short answer and a
 * two-minute one. The learner's audio is `<clips>/<lang>-short.wav` / `<lang>-long.wav` when given (self-recorded),
 * else the answer text spoken once by the app's own voice service before timing starts (synthetic). Endpointing is the
 * real [Vad] run over the clip plus trailing silence; the headline is back-dated to the clip's last speech exactly as
 * in a live session. Playback goes to a null sink unless `--play` (then the default output device).
 *
 * With no model the session's scripted bank answers (fallback rate 100 %); with no Whisper the answer text is used
 * directly; with no voice there is no audio. Every stage that couldn't run is named in the report. Without the model,
 * Whisper and a voice the report is a dry run and says so: it is never a latency measurement.
 */
object BenchInterview {
    fun run(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val dataDir = Smoke.arg(args, "--data-dir")?.let(::File) ?: Files.createTempDirectory("mokuhyo-bench").toFile()
        val app = AppGraph(AppDirs.ensure(dataDir))
        app.probeHardware()
        val out = (Smoke.arg(args, "--out")?.let(::File) ?: File("build/bench")).apply { mkdirs() }
        val turns = Smoke.arg(args, "--turns")?.toIntOrNull()?.coerceIn(1, 200) ?: 10
        val langs = when (val l = Smoke.arg(args, "--language") ?: app.language.value) {
            "all" -> app.chosenLanguages().ifEmpty { Languages.all.map { it.code } }
            else -> l.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val answers = loadAnswers(Smoke.arg(args, "--answers")?.let(::File) ?: Resources.repoDir?.let { File(it, "tools/bench/answers.json") })
        val notRun = LinkedHashMap<String, String>()
        if (answers == null) return@runBlocking fail("no answers file (tools/bench/answers.json or --answers)")

        // Engines: the explicit files, else whatever the installed app uses on this computer (read from its own data
        // folder, which the bench otherwise never writes to: bench sessions go to the temporary database above).
        val installed = if (Smoke.arg(args, "--model") == null || Smoke.arg(args, "--whisper") == null) installedEngines() else null
        val runtime = JniRuntime.tryCreate(preferCpu = "--cpu" in args)
        val info: ModelInfo? = Smoke.arg(args, "--model-id")?.let { id -> app.manifest.models.firstOrNull { it.id == id } } ?: installed?.model
        val modelFile = Smoke.arg(args, "--model")?.let(::File)?.takeIf { it.isFile } ?: installed?.modelFile
        val llm = runtime.llm()
        val model: LanguageModel? = if (info != null && modelFile != null && llm != null)
            LocalLlamaModel(llm, info, modelFile.absolutePath, inspect = GgufReader::read) else null
        if (model == null) notRun["llm"] = when {
            llm == null -> "native runtime: ${runtime.status}"
            else -> "no model file (pass --model <gguf> --model-id <id>, or install a tier in the app)"
        }
        val sttInfo = app.manifest.models.firstOrNull { it.kind == ModelKind.STT }
        val whisper = Smoke.arg(args, "--whisper")?.let(::File)?.takeIf { it.isFile } ?: installed?.whisper
        val stt = runtime.stt()
        val recognizer: SpeechRecognizer? = if (whisper != null && stt != null) WhisperRecognizer(stt, whisper.absolutePath, sttInfo?.name ?: "Whisper") else null
        if (recognizer == null) notRun["asr"] = if (stt == null) "native runtime: ${runtime.status}" else "no Whisper model (pass --whisper <bin>)"
        val voices = VoiceService.create()
        val gateway = AiGateway({ model }, AiSettings(timeoutMs = 180_000))
        val play = "--play" in args
        // The app's own deadline unless --deadline-ms says otherwise (0 = wait for the model every time).
        val deadline = (Smoke.arg(args, "--deadline-ms")?.toLongOrNull() ?: AppGraph.DEFAULT_REPLY_DEADLINE_MS).takeIf { it > 0 }
        if (!play) notRun["playback"] = "null sink (pass --play to use the default output device)"

        println("bench-interview: ${langs.joinToString()} · model ${info?.id ?: "none"} · whisper ${whisper?.name ?: "none"} · ${runtime.status}")
        val sessions = ArrayList<TimingReport.Session>()
        val cancels = ArrayList<TimingReport.CancelSample>()
        for (lang in langs) {
            val pack = app.opi(lang)
            val texts = answers[lang]
            if (pack == null || texts == null) {
                notRun["lang:$lang"] = if (pack == null) "no OPI pack for $lang (build_packs.py)" else "no bench answers for $lang"
                continue
            }
            val module = app.languages.module(lang)
            val voice = runCatching { voices.voicesFor(lang).firstOrNull() }.getOrNull()
            if (voice == null) notRun["tts:$lang"] = "no voice for $lang on this computer"
            val clipsDir = Smoke.arg(args, "--clips")?.let(::File)
            val clips = mapOf("short" to clip(clipsDir, lang, "short", texts.first, voices, 0), "long" to clip(clipsDir, lang, "long", texts.second, voices, answers.longSeconds))
            clips.forEach { (k, v) -> if (v == null) notRun["clip:$lang/$k"] = "no self-recorded clip and no voice to render one: ASR fed the text" }

            val timeline = TurnTimeline("bench-$lang", "BENCH", lang)
            timeline.setup = TimelineSetup(info?.id, info?.files?.firstOrNull()?.sha256?.take(16), voice?.let { "${it.engine}:${it.id}" },
                if (model == null) null else if (runtime.gpuActive) "gpu" else "cpu",
                NativeLibrary.recommendedThreads().takeIf { model != null }, NativeLibrary.recommendedThreads().takeIf { recognizer != null })
            val words: (String) -> Int = { s -> module.segment(s).count { it.isWord } }
            var session = OpiSession(lang, pack.profile, pack.questions, pack.rolePlays, gateway, words, IlrLevel.L2, replyDeadlineMs = deadline)
            val id = app.conversations.newId()

            suspend fun output(spoken: SpokenAudio) {
                if (play) runCatching { AudioIO.open(spoken.wav).also { traceMark(TurnStage.OUTPUT_START) }.run() }
                else traceMark(TurnStage.OUTPUT_START)
                traceMark(TurnStage.OUTPUT_END)
            }

            suspend fun speak(text: String) = withContext(Dispatchers.IO) {
                if (voice == null) return@withContext
                withTimelineTts(timeline) { voices.synthesize(text, lang, voice) }?.let { output(it) }
            }

            // The live path: the question streams sentence by sentence into synthesis while the model writes it (N3).
            suspend fun streamed(): app.mokuhyo.opi.InterviewerLine? = coroutineScope {
                val sentences = Channel<String>(2)
                val speaking = async(Dispatchers.IO) {
                    if (voice == null) { for (s in sentences) Unit; null }
                    else SpokenReply<SpokenAudio>({ s -> withTimelineTts(timeline) { voices.synthesize(s, lang, voice) } }, { a -> output(a); true }).speak(sentences)
                }
                try { session.next(speak = { sentences.send(it) }, splitter = IcuSentences(lang)) } finally { sentences.close() }.also { speaking.await() }
            }

            suspend fun partnerLine(turn: Int) = withContext(TimelineTrace(timeline, turn)) {
                var line = streamed()
                if (line == null) {
                    // The interview plan ended: a fresh session on the same, already warm, model.
                    session = OpiSession(lang, pack.profile, pack.questions, pack.rolePlays, gateway, words, IlrLevel.L2, replyDeadlineMs = deadline)
                    line = streamed()
                }
                if (line == null) return@withContext
                if (line.engine == null) timeline.note(turn) { fallback = FallbackKind.ofReason(line.fallbackReason ?: "no AI model is set up") }
                if (!line.spokenByStream) speak(line.text)
            }

            // Turn 0: the first question, cold (the model loads on this call).
            val first = timeline.beginTurn(cold = true)
            partnerLine(first)
            timeline.note(first) { peakRssBytes = ProcessMemory.peakRssBytes() }
            for (k in 1..turns) {
                val length = if (k % 2 == 1) "short" else "long"
                val text = if (length == "short") texts.first else texts.second
                val pcm = clips[length]
                val turn = timeline.beginTurn()
                withContext(TimelineTrace(timeline, turn)) {
                    // The learner just stopped: the VAD's tail is the endpoint wait, back-dated like a live capture.
                    val tail = pcm?.let { endpointTailMs(it) } ?: Vad().silenceStopMs.toLong()
                    timeline.markBefore(TurnStage.LAST_SPEECH, tail)
                    timeline.mark(TurnStage.VAD_ENDPOINT)
                    timeline.mark(TurnStage.CAPTURE_STOP)
                    timeline.note { answerMs = pcm?.let { it.size / 16L } ?: estimateMs(text) }
                    traceMark(TurnStage.ASR_QUEUED)
                    val heard = if (pcm != null && recognizer != null) withContext(Dispatchers.IO) {
                        runCatching { recognizer.transcribe(pcm, module.sttLanguage, "").text }.getOrDefault(text)
                    } else text
                    session.answer(heard.ifBlank { text })
                    withContext(Dispatchers.IO) {
                        app.conversations.checkpoint(id, app.learnerId, lang, "OPI", null, session.startedAt.toEpochMilliseconds(),
                            StoredConversation(session.transcript, opiTurns = session.turns,
                                draft = ConversationDraft(id, System.currentTimeMillis(), opi = session.checkpoint())), CorrectionsMode.OFF)
                    }
                    traceMark(TurnStage.CHECKPOINT_SAVED)
                }
                partnerLine(turn)
                timeline.note(turn) { peakRssBytes = ProcessMemory.peakRssBytes() }
                timeline.snapshot().turns.last().stages[TurnTimeline.FIRST_AUDIO]?.let { println("bench-interview: $lang turn $k ($length) first audio $it ms") }
            }
            cancels += cancelProbes(model, gateway, recognizer, clips["long"], module.sttLanguage, voices, voice, texts.second, lang, pack, words)
            sessions += timeline.snapshot()
        }
        val real = model != null && recognizer != null && sessions.isNotEmpty() && sessions.all { it.voiceId != null }
        val report = app.timings.report(sessions, real, "--bench-interview", cancels, notRun)
        val files = Timings.export(report, File(out, "bench-interview"))
        println(report.toMarkdown())
        println("bench-interview: wrote ${files.joinToString { it.path }}")
        voices.close()
        app.close()
        0
    }

    private class Installed(val model: ModelInfo?, val modelFile: File?, val whisper: File?)

    /** The model tier and Whisper model the installed app has chosen, if any (its data folder is only read). */
    private fun installedEngines(): Installed? = runCatching {
        val dir = AppDirs.dataDir().takeIf { File(it, "db/mokuhyo.sqlite").isFile } ?: return null
        val real = AppGraph(dir)
        try {
            val m = real.chosenModel()
            Installed(m, m?.let { real.modelFile(it) }, real.sttModel()?.let { real.modelFile(it) })
        } finally {
            real.close()
        }
    }.getOrNull()

    /** Answer texts per language and the long clip's target length. */
    class Answers(private val byLang: Map<String, Pair<String, String>>, val longSeconds: Int) {
        operator fun get(lang: String) = byLang[lang]
    }

    private fun loadAnswers(file: File?): Answers? {
        val root = file?.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject } ?: return null
        val map = root["answers"]!!.jsonObject.mapValues { (_, v) -> v.jsonObject.let { it["short"]!!.jsonPrimitive.content to it["long"]!!.jsonPrimitive.content } }
        return Answers(map, root["longTargetSeconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 115)
    }

    /**
     * The learner's audio for one answer at 16 kHz: a self-recorded `<lang>-<length>.wav`, else [text] rendered by the
     * voice service (repeated until [minSeconds]) and cached next to the clips. Null when neither exists.
     */
    private suspend fun clip(dir: File?, lang: String, length: String, text: String, voices: SpeechOutput, minSeconds: Int): ShortArray? {
        dir?.let { File(it, "$lang-$length.wav") }?.takeIf { it.isFile }?.let { return AudioIO.toPcm16kMono(it.readBytes()) }
        val once = runCatching { voices.synthesize(text, lang) }.getOrNull() ?: return null
        val pcm = AudioIO.toPcm16kMono(once.wav)
        if (pcm.isEmpty()) return null
        val pause = ShortArray(16_000 / 2)
        var all = pcm
        while (all.size < minSeconds * 16_000L) all = all + pause + pcm
        return all
    }

    /** Runs the live [Vad] over the clip and then silence: how long after the last speech it ends the recording. */
    internal fun endpointTailMs(pcm: ShortArray): Long {
        val vad = Vad()
        val frame = 16_000 * vad.frameMs / 1000
        var i = 0
        while (i + frame <= pcm.size) {
            if (vad.feed(rms(pcm, i, frame)) == Vad.Event.AUTO_STOP) return vad.trailingSilenceMs.toLong()
            i += frame
        }
        // Trailing silence until the VAD decides (a clip normally ends right after the speech).
        repeat(100) { if (vad.feed(0.0) == Vad.Event.AUTO_STOP) return vad.trailingSilenceMs.toLong() }
        return vad.silenceStopMs.toLong()
    }

    private fun rms(pcm: ShortArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (k in from until from + n) { val v = pcm[k] / 32768.0; sum += v * v }
        return sqrt(sum / n)
    }

    /** About 150 words a minute, for answers fed as text. */
    private fun estimateMs(text: String) = (text.split(' ').size.coerceAtLeast(text.length / 4) * 400L)

    private suspend fun <T : app.mokuhyo.lang.SpokenAudio?> withTimelineTts(timeline: TurnTimeline, synth: suspend () -> T): T {
        traceMark(TurnStage.TTS_START)
        val start = System.nanoTime()
        val spoken = synth()
        if (spoken != null) traceMark(TurnStage.TTS_FIRST_PCM)
        if (spoken != null) timeline.note {
            ttsSynthMs += (System.nanoTime() - start) / 1_000_000
            ttsAudioMs += ((Wav.info(spoken.wav)?.durationSeconds ?: 0.0) * 1000).toLong()
        }
        return spoken
    }

    /** Stop budgets: how long a running model reply, transcription and synthesis take to actually stop when cancelled. */
    private suspend fun cancelProbes(
        model: LanguageModel?, gateway: AiGateway, recognizer: SpeechRecognizer?, longClip: ShortArray?, sttLanguage: String,
        voices: SpeechOutput, voice: app.mokuhyo.lang.VoiceSpec?, longText: String, lang: String, pack: app.mokuhyo.opi.OpiPack, words: (String) -> Int,
    ): List<TimingReport.CancelSample> = buildList {
        suspend fun probe(stage: String, after: Long, work: suspend () -> Unit) {
            val job = CoroutineScope(Dispatchers.IO).launch { runCatching { work() } }
            delay(after)
            val t = System.nanoTime()
            job.cancel()
            job.join()
            add(TimingReport.CancelSample(stage, (System.nanoTime() - t) / 1_000_000))
        }
        repeat(3) {
            if (model != null) probe("llm", 400) { OpiSession(lang, pack.profile, pack.questions, pack.rolePlays, gateway, words, IlrLevel.L2).next() }
            if (recognizer != null && longClip != null) probe("asr", 400) { recognizer.transcribe(longClip, sttLanguage, "") }
            if (voice != null) probe("tts", 150) { voices.synthesize(longText, lang, voice) }
        }
    }

    private fun fail(msg: String): Int {
        println("bench-interview: FAIL $msg")
        return 1
    }
}
