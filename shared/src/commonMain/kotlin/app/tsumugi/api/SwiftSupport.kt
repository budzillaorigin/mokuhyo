package app.tsumugi.api

import app.tsumugi.ai.AiResult
import app.tsumugi.ai.ModelFile
import app.tsumugi.ai.ModelInfo
import app.tsumugi.ai.ModelKind
import app.tsumugi.ai.Sha256
import app.tsumugi.ai.prompts.JlptExplainItem
import app.tsumugi.ai.prompts.TranslateSentence
import app.tsumugi.domain.ItemKind
import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamService
import app.tsumugi.exam.ExamSession
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.opi.OpiRating
import app.tsumugi.exam.opi.OpiSession
import app.tsumugi.speaking.AiConfig
import app.tsumugi.speaking.AiService
import app.tsumugi.speaking.LlmEngine
import app.tsumugi.speaking.SttEngine
import app.tsumugi.speaking.TtsEngine
import app.tsumugi.speech.PronunciationReport
import app.tsumugi.speech.SynthesizedAudioFiles
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.StudyItem
import app.tsumugi.study.activities.PomodoroSession
import app.tsumugi.platform.excludeFromBackup
import app.tsumugi.platform.freeBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.Clock
import app.tsumugi.integrations.ankiconnect.AnkiPushResult
import app.tsumugi.integrations.notion.NotionPushResult
import app.tsumugi.media.GeneratedSubtitles
import app.tsumugi.media.MediaHash
import app.tsumugi.media.SubtitleProgress
import app.tsumugi.reader.TokenPitch
import app.tsumugi.review.ContentReviewService
import app.tsumugi.review.ReviewCandidate
import app.tsumugi.review.ReviewKind
import app.tsumugi.review.Verdict as ReviewVerdictKind
import app.tsumugi.speech.PronunciationAnalyzer
import app.tsumugi.study.FocusTimer
import app.tsumugi.study.TodayBlockKind

/** A picker option: [key] is the enum name, stable across releases. */
data class EngineChoice(val key: String, val label: String, val detail: String)

/** Speech-to-text result, or [error] when the configured engine failed. */
data class SttOutcome(val text: String, val engine: String, val error: String?)

/** `GET /v1/models` result for "Test connection". */
data class ProbeOutcome(val models: List<String>, val error: String?)

/** A labeled AI answer; [engine] is null when nothing was generated and [unavailable] says why. */
data class AiExplanation(val explanation: String, val whyWrong: String, val keyPoint: String, val engine: String?, val unavailable: String?)

data class TranscriptLine(val learner: Boolean, val text: String)

/** One DLPT text-type filter option: the passage text type and how many items have it. */
data class DlptTextTypeCount(val textType: String, val items: Int)

data class ChecklistEntry(val level: String, val statement: String)

/** A pronunciation report, or why the recording couldn't be analyzed (F-33: an error, never a silent nil). */
data class PronunciationOutcome(val report: PronunciationReport?, val error: String?)

/** Cards due now for one item kind (Reviews tab queue). */
data class KindCount(val kind: ItemKind, val count: Int)

/** An item whose card keeps lapsing (Reviews tab leech list); [lapses] is its worst card's count. */
data class LeechEntry(val item: StudyItem, val direction: String, val lapses: Int)

/**
 * `translate_sentence` result for the reader. [engine] is set when a model wrote [translation] (show it as
 * AI-generated); otherwise [unavailable] says why, and [needsSetup] means no engine is configured at all.
 */
data class TranslationOutcome(
    val translation: String,
    val literal: String,
    val notes: String,
    val engine: String?,
    val unavailable: String?,
    val needsSetup: Boolean,
)

/** One model file the app downloads itself (iOS background URLSession, F-13). */
data class ModelFileDownload(
    val name: String,
    val url: String,
    val bytes: Long,
    /** Where the platform downloader puts the finished file before calling [DownloadedModelInstaller.install]. */
    val targetPath: String,
    /** The file is already at [targetPath] with the right size (downloaded, not yet verified): install it directly. */
    val downloaded: Boolean,
)

/** Files to fetch for a model, or [error] instead (e.g. not enough storage, with [retryable] false). */
data class ModelDownloadPlan(val files: List<ModelFileDownload>, val error: String?, val retryable: Boolean)

/**
 * Hand-off between a platform downloader (iOS background `URLSession`) and the model folder [app.tsumugi.ai.ModelManager]
 * reads (BRIEF_V2 F-13, D-072). [plan] lists the missing files after the same free-space rule as `ModelManager.download`;
 * [install] SHA-256-verifies a finished file on [Dispatchers.IO], moves it into place and writes the `.sha256` marker
 * that `ModelManager.isInstalled` checks. The folder layout (`modelsDir/<id>/<file>`) is ModelManager's.
 */
class DownloadedModelInstaller(
    private val fs: FileSystem,
    private val modelsDir: Path,
    private val freeBytes: (Path) -> Long? = { null },
) {
    /** Missing files of [model] with their download targets. Drops stale `.part` files of the in-process downloader. */
    @Throws(Exception::class)
    fun plan(model: ModelInfo): ModelDownloadPlan {
        val dir = modelsDir / model.id
        fs.createDirectories(dir)
        val missing = model.files.filterNot { isInstalled(dir, it) }
        missing.forEach { fs.delete(dir / "${it.name}.part", mustExist = false) }
        val targets = missing.map { f ->
            val target = dir / "${f.name}$DOWNLOAD_SUFFIX"
            ModelFileDownload(f.name, f.url, f.bytes, target.toString(), fs.metadataOrNull(target)?.size == f.bytes)
        }
        val remaining = targets.filterNot { it.downloaded }.sumOf { it.bytes }
        val needed = remaining + remaining / 2
        val free = freeBytes(dir)
        if (remaining > 0 && free != null && free < needed) {
            return ModelDownloadPlan(
                emptyList(),
                "Not enough storage for ${model.name}: needs ${gb(needed)} GB free, ${gb(free)} GB available. Free up space and try again.",
                retryable = false,
            )
        }
        return ModelDownloadPlan(targets, null, retryable = true)
    }

    /**
     * Verifies [downloadedPath] against [fileName]'s size and SHA-256 and installs it. Returns an error message, or
     * null when the file is installed. A bad file is deleted, so the next attempt downloads it again.
     */
    @Throws(Exception::class)
    suspend fun install(model: ModelInfo, fileName: String, downloadedPath: String): String? = withContext(Dispatchers.IO) {
        val file: ModelFile = model.files.firstOrNull { it.name == fileName } ?: return@withContext "unknown file $fileName"
        val src = downloadedPath.toPath()
        val size = fs.metadataOrNull(src)?.size
        if (size != file.bytes) {
            fs.delete(src, mustExist = false)
            return@withContext "$fileName: expected ${file.bytes} bytes, got ${size ?: 0}"
        }
        val sha = Sha256()
        val buffer = ByteArray(256 * 1024)
        fs.source(src).buffer().use { input ->
            while (true) {
                val n = input.read(buffer, 0, buffer.size)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        val actual = sha.hexDigest()
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            fs.delete(src, mustExist = false)
            return@withContext "$fileName: checksum mismatch, the download was discarded"
        }
        val dir = modelsDir / model.id
        fs.createDirectories(dir)
        fs.atomicMove(src, dir / file.name)
        fs.write(dir / "${file.name}.sha256") { writeUtf8(actual) }
        null
    }

    private fun isInstalled(dir: Path, file: ModelFile): Boolean {
        val marker = dir / "${file.name}.sha256"
        if (fs.metadataOrNull(dir / file.name)?.size != file.bytes || !fs.exists(marker)) return false
        return fs.read(marker) { readUtf8().trim() }.equals(file.sha256, ignoreCase = true)
    }

    private fun gb(bytes: Long): String {
        val tenths = (bytes * 10 + (1L shl 30) - 1) / (1L shl 30)
        return "${tenths / 10}.${tenths % 10}"
    }

    companion object {
        const val DOWNLOAD_SUFFIX = ".download"
    }
}

/**
 * Small non-generic, non-throwing adapters for the Swift app (Kotlin generics, `Result`, `Duration`, `Pair`
 * and exceptions from suspend functions don't bridge cleanly through Objective-C). No logic lives here.
 */
object SwiftSupport {
    fun llmChoices(): List<EngineChoice> = listOf(
        EngineChoice(LlmEngine.NONE.name, "Off", "Deterministic fallbacks only; no generated text."),
        EngineChoice(LlmEngine.LOCAL.name, "On-device model", "Runs on this device with llama.cpp. Works offline."),
        EngineChoice(LlmEngine.ENDPOINT.name, "My own server", "Any OpenAI-compatible server you run (Ollama, LM Studio, llama-server, vLLM)."),
    )

    fun sttChoices(): List<EngineChoice> = listOf(
        EngineChoice(SttEngine.SYSTEM.name, "System (on-device)", "Apple's on-device recognizer, when it supports Japanese offline."),
        EngineChoice(SttEngine.WHISPER_LOCAL.name, "Whisper on device", "Downloaded Whisper model, runs on this device."),
        EngineChoice(SttEngine.WHISPER_ENDPOINT.name, "Whisper on my server", "A Whisper-compatible /v1/audio/transcriptions server you run."),
    )

    fun ttsChoices(): List<EngineChoice> = listOf(
        EngineChoice(TtsEngine.SYSTEM.name, "System voices", "The device's Japanese voices."),
        EngineChoice(TtsEngine.VOICEVOX.name, "VOICEVOX", "A VOICEVOX engine you run, e.g. on a home server."),
    )

    fun llmKey(config: AiConfig): String = config.llm.name
    fun sttKey(config: AiConfig): String = config.stt.name
    fun ttsKey(config: AiConfig): String = config.tts.name

    fun buildConfig(
        llm: String,
        localModelId: String?,
        endpointUrl: String,
        endpointModel: String,
        stt: String,
        localSttModelId: String?,
        sttEndpointUrl: String,
        tts: String,
        voicevoxUrl: String,
        voicevoxSpeaker: Int,
    ): AiConfig = AiConfig(
        llm = LlmEngine.entries.firstOrNull { it.name == llm } ?: LlmEngine.NONE,
        localModelId = localModelId,
        endpointUrl = endpointUrl,
        endpointModel = endpointModel,
        stt = SttEngine.entries.firstOrNull { it.name == stt } ?: SttEngine.SYSTEM,
        localSttModelId = localSttModelId,
        sttEndpointUrl = sttEndpointUrl,
        tts = TtsEngine.entries.firstOrNull { it.name == tts } ?: TtsEngine.SYSTEM,
        voicevoxUrl = voicevoxUrl,
        voicevoxSpeaker = voicevoxSpeaker,
    )

    fun models(ai: AiService, speech: Boolean): List<ModelInfo> = ai.models?.models(kind(speech)).orEmpty()

    fun recommended(ai: AiService, speech: Boolean): ModelInfo? = ai.models?.recommend(kind(speech), ai.deviceRamGb)

    /** Deletes a downloaded model; returns an error message or null. */
    fun deleteModel(ai: AiService, model: ModelInfo): String? =
        runCatching { ai.models?.delete(model) }.exceptionOrNull()?.let { it.message ?: "couldn't delete the model" }

    @Throws(Exception::class)
    suspend fun probe(ai: AiService, url: String, apiKey: String?): ProbeOutcome =
        ai.probeEndpoint(url, apiKey?.takeIf { it.isNotBlank() }).fold(
            onSuccess = { ProbeOutcome(it, null) },
            onFailure = { ProbeOutcome(emptyList(), it.message ?: "connection failed") },
        )

    /**
     * Transcribes 16 kHz mono samples in [-1, 1] with the configured engine. Null means "no engine configured here;
     * use the OS recognizer".
     */
    @Throws(Exception::class)
    suspend fun transcribe(ai: AiService, samples: FloatArray): SttOutcome? {
        val recognizer = ai.recognizer() ?: return null
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        return try {
            val t = recognizer.transcribe(pcm, "ja")
            SttOutcome(t.text, t.engine, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SttOutcome("", "", e.message ?: "transcription failed")
        }
    }

    /**
     * VOICEVOX audio written to a new, uniquely named WAV file in `dataDir/tts`; null means "use the system voice".
     * Each call gets its own file, so a synthesis never overwrites audio that is still playing (F-30). Call
     * [deleteSynthesized] when playback ends and [cleanSynthesized] at launch; old leftovers are also pruned on
     * each synthesis ([SynthesizedAudioFiles]).
     */
    @Throws(Exception::class)
    suspend fun synthesizeToFile(graph: AppGraph, text: String, speed: Double): String? = try {
        graph.ai.synthesizer()?.synthesize(text, null, speed)?.let { bytes ->
            withContext(Dispatchers.IO) {
                val files = ttsFiles(graph)
                files.write(bytes).toString().also { excludeFromBackup(files.dir) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Deletes one file returned by [synthesizeToFile] (after playback finished or was interrupted). */
    fun deleteSynthesized(graph: AppGraph, path: String) = ttsFiles(graph).delete(path.toPath())

    /** Deletes every synthesized file; call at startup (nothing is playing yet). */
    fun cleanSynthesized(graph: AppGraph) = ttsFiles(graph).prune(olderThanMs = null)

    private fun ttsFiles(graph: AppGraph) = SynthesizedAudioFiles(graph.platform.fileSystem, graph.platform.dataDir / "tts")

    @Throws(Exception::class)
    suspend fun analyzePronunciation(graph: AppGraph, sentence: String, transcript: String?, samples: FloatArray): PronunciationOutcome = try {
        PronunciationOutcome(graph.pronunciation.analyze(sentence, transcript, samples, null), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PronunciationOutcome(null, e.message ?: "couldn't analyze the recording")
    }

    @Throws(Exception::class)
    suspend fun explainItem(
        ai: AiService,
        level: String,
        question: String,
        choices: List<String>,
        correctIndex: Int,
        /** -1 when unanswered. */
        chosenIndex: Int,
        stimulus: String?,
    ): AiExplanation {
        val input = JlptExplainItem.Input(level, question, choices, correctIndex, chosenIndex.takeIf { it >= 0 }, stimulus)
        return when (val r = ai.gateway().run(JlptExplainItem(), input)) {
            is AiResult.Ok -> AiExplanation(r.value.explanation, r.value.whyWrong, r.value.keyPoint, r.engine, null)
            is AiResult.Fallback -> AiExplanation(r.value.explanation, r.value.whyWrong, r.value.keyPoint, null, null)
            is AiResult.Unavailable -> AiExplanation("", "", "", null, r.reason)
        }
    }

    @Throws(Exception::class)
    suspend fun dlpt(exams: ExamService, listening: Boolean, minutes: Int): ExamSession =
        exams.dlpt(if (listening) ExamKind.DLPT_LISTENING else ExamKind.DLPT_READING, minutes, kotlin.time.Clock.System.now().toEpochMilliseconds())

    /** DLPT form with the range (upper = ILR 3–4) and text-type filter (empty = all) (BRIEF_V2 G-08, §6.16). */
    @Throws(Exception::class)
    suspend fun dlptFiltered(exams: ExamService, listening: Boolean, minutes: Int, upper: Boolean, textTypes: List<String>): ExamSession =
        exams.dlpt(
            if (listening) ExamKind.DLPT_LISTENING else ExamKind.DLPT_READING, minutes, kotlin.time.Clock.System.now().toEpochMilliseconds(),
            if (upper) app.tsumugi.exam.dlpt.DlptRange.UPPER else app.tsumugi.exam.dlpt.DlptRange.LOWER, textTypes.toSet(),
        )

    /** Text types for the DLPT filter chips: "type" to item count, most items first. */
    @Throws(Exception::class)
    suspend fun dlptTextTypes(exams: ExamService, listening: Boolean, upper: Boolean): List<DlptTextTypeCount> =
        exams.dlptTextTypes(
            if (listening) ExamKind.DLPT_LISTENING else ExamKind.DLPT_READING,
            if (upper) app.tsumugi.exam.dlpt.DlptRange.UPPER else app.tsumugi.exam.dlpt.DlptRange.LOWER,
        ).map { DlptTextTypeCount(it.first, it.second) }

    @Throws(Exception::class)
    suspend fun opi(graph: AppGraph, startLevel: String): OpiSession? = graph.opi(IlrLevel.parse(startLevel) ?: IlrLevel.L1)

    fun opiStartLevels(): List<String> = IlrLevel.lowerRange.map { it.label }

    fun opiTranscript(session: OpiSession): List<TranscriptLine> = session.transcript.map { TranscriptLine(it.first == Speaker.LEARNER, it.second) }

    fun opiPhaseTitle(phase: OpiPhase): String = when (phase) {
        OpiPhase.WARMUP -> "Warm-up"
        OpiPhase.LEVEL_CHECK -> "Level check"
        OpiPhase.PROBE -> "Probe"
        OpiPhase.ROLEPLAY -> "Role-play"
        OpiPhase.WINDDOWN -> "Wind-down"
    }

    fun opiChecklist(session: OpiSession): List<ChecklistEntry> = session.checklist().map { ChecklistEntry(it.first.label, it.second) }

    fun opiSelfRate(session: OpiSession, checked: List<String>): OpiRating = session.selfRate(checked.toSet())

    fun ilrLabel(rating: OpiRating): String? = rating.ilr?.label

    @Throws(Exception::class)
    suspend fun saveOpi(exams: ExamService, session: OpiSession, rating: OpiRating): String =
        exams.saveOpi(session.startedAt, session.transcript.map { it.first.name to it.second }, rating)

    /** Transcript of a saved OPI attempt. */
    @Throws(Exception::class)
    suspend fun opiAttemptTranscript(exams: ExamService, attemptId: String): List<TranscriptLine> =
        exams.opiTranscript(attemptId).map { TranscriptLine(it.first == Speaker.LEARNER.name, it.second) }

    // --- Reviews tab (F-07) ------------------------------------------------------------------------------------

    /** Cards due now, per item kind, in kind order. */
    @Throws(Exception::class)
    suspend fun reviewQueue(graph: AppGraph): List<KindCount> = withContext(Dispatchers.IO) {
        val now = Clock.System.now().toEpochMilliseconds()
        graph.userDatabase.srsQueries.dueCountByKind(now) { kind, count -> kind to count.toInt() }.executeAsList()
            .mapNotNull { (kind, count) -> ItemKind.entries.firstOrNull { it.name == kind }?.let { KindCount(it, count) } }
            .sortedBy { it.kind.ordinal }
    }

    /** Items whose cards lapsed [SrsRepository.LEECH_LAPSES]+ times, worst first, one row per item. */
    @Throws(Exception::class)
    suspend fun leeches(graph: AppGraph, limit: Int): List<LeechEntry> {
        val cards = withContext(Dispatchers.IO) {
            graph.userDatabase.srsQueries.leeches(SrsRepository.LEECH_LAPSES.toLong()).executeAsList()
        }.distinctBy { it.item_id }.take(limit)
        val items = graph.srs.items(cards.map { it.item_id })
        return cards.mapNotNull { c -> items[c.item_id]?.let { LeechEntry(it, c.direction, c.lapses.toInt()) } }
    }

    // --- Reader translation (F-08) -----------------------------------------------------------------------------

    /** Translates one Japanese sentence into English with the configured model (never a canned answer). */
    @Throws(Exception::class)
    suspend fun translate(ai: AiService, text: String): TranslationOutcome {
        val gateway = ai.gateway()
        if (!gateway.hasModel()) {
            return TranslationOutcome("", "", "", null, ai.unavailableReason() ?: "No AI model is set up.", needsSetup = true)
        }
        return when (val r = gateway.run(TranslateSentence(), TranslateSentence.Input(text))) {
            is AiResult.Ok -> TranslationOutcome(r.value.translation, r.value.literal, r.value.notes, r.engine, null, needsSetup = false)
            is AiResult.Fallback -> TranslationOutcome("", "", "", null, r.reason, needsSetup = false)
            is AiResult.Unavailable -> TranslationOutcome("", "", "", null, r.reason, needsSetup = false)
        }
    }

    // --- Background model downloads (F-13, iOS) ----------------------------------------------------------------

    /** The installer for `dataDir/models`, the folder `AiService.models` uses. */
    fun modelInstaller(graph: AppGraph): DownloadedModelInstaller =
        DownloadedModelInstaller(graph.platform.fileSystem, graph.platform.dataDir / "models", ::freeBytes)

    fun pomodoroRemainingSeconds(session: PomodoroSession): Long = session.remaining.inWholeSeconds

    fun pomodoroBreakRemainingSeconds(session: PomodoroSession): Long = session.breakRemaining.inWholeSeconds

    private fun kind(speech: Boolean) = if (speech) ModelKind.STT else ModelKind.LLM

    // --- Phase 10 (BRIEF_V2 G-01…G-16): thin adapters, types in SwiftBridges.kt ------------------------------

    fun focusTimer(block: TodayBlockKind?, minutes: Int): FocusTimer = FocusTimer.forBlock(block, minutes)

    fun focusRemainingSeconds(timer: FocusTimer): Long = timer.remaining.inWholeSeconds

    fun focusBreakRemainingSeconds(timer: FocusTimer): Long = timer.breakRemaining.inWholeSeconds

    /** The media content key (D-113) of a local file, off the main thread. */
    @Throws(Exception::class)
    suspend fun mediaHash(graph: AppGraph, path: String): String = MediaHash.of(graph.platform.fileSystem, path)

    /** Whisper subtitles for the media keyed [mediaHash] (cached); cancel the calling task to stop. */
    @Throws(Exception::class)
    suspend fun generateSubtitles(graph: AppGraph, mediaHash: String, reader: PcmWindowReader, onProgress: (SubtitleProgress) -> Unit): GeneratedSubtitles =
        graph.subtitles.generate(mediaHash, CallbackPcmSource(reader), "ja", onProgress)

    @Throws(Exception::class)
    suspend fun exportReviewCsv(graph: AppGraph, outPath: String): Int = graph.reviewCsv.export(graph.platform.fileSystem, outPath) { _, _ -> }

    @Throws(Exception::class)
    suspend fun exportBackup(graph: AppGraph, outPath: String): Int = graph.backup.exportTo(graph.platform.fileSystem, outPath)

    @Throws(Exception::class)
    suspend fun pushItemsToNotion(graph: AppGraph, onProgress: (PushProgress) -> Unit): NotionPushResult =
        graph.pushItemsToNotion(emptyList()) { d, t -> onProgress(PushProgress(d, t)) }

    @Throws(Exception::class)
    suspend fun pushStatsToNotion(graph: AppGraph, days: Int, onProgress: (PushProgress) -> Unit): NotionPushResult =
        graph.pushStatsToNotion(days) { d, t -> onProgress(PushProgress(d, t)) }

    /** Pushes every mined item (source = user) to desktop Anki. */
    @Throws(Exception::class)
    suspend fun pushMinedToAnki(graph: AppGraph, onProgress: (PushProgress) -> Unit): AnkiPushResult =
        graph.ankiConnect.push(graph.minedItems()) { d, t -> onProgress(PushProgress(d, t)) }

    /** "は↑し↓" for a reader token's pitch, or null when the accent is unknown (inflected or not in the table). */
    fun pitchMarks(pitch: TokenPitch): String? {
        if (pitch.downstep == null || pitch.heights.isEmpty()) return null
        val labels = pitch.morae + List((pitch.heights.size - pitch.morae.size).coerceAtLeast(0)) { "" }
        return PronunciationAnalyzer.marks(labels.take(pitch.heights.size), pitch.heights.take(labels.size))
    }

    @Throws(Exception::class)
    suspend fun learnerFurigana(graph: AppGraph): LearnerFuriganaFilter = LearnerFuriganaFilter(graph.reader.learnerLevel())

    fun reviewKinds(): List<ReviewKind> = ReviewKind.entries

    @Throws(Exception::class)
    suspend fun reviewQueue(service: ContentReviewService, kind: ReviewKind?): List<ReviewEntry> =
        service.queue(kind).map { (c, v) -> ReviewEntry(c, v?.verdict?.code, v?.notes.orEmpty(), v?.edits.orEmpty()) }

    @Throws(Exception::class)
    suspend fun reviewCounts(service: ContentReviewService): List<ReviewKindCount> =
        service.summary().entries.sortedBy { it.key.ordinal }.map { (k, v) -> ReviewKindCount(k, v.first, v.second) }

    /** Records "accept" | "edit" | "reject" for [candidate]; returns the stored verdict code. */
    @Throws(Exception::class)
    suspend fun decideReview(service: ContentReviewService, candidate: ReviewCandidate, verdictCode: String, notes: String, edits: Map<String, String>): String {
        val verdict = ReviewVerdictKind.of(verdictCode) ?: throw IllegalArgumentException("unknown verdict $verdictCode")
        return service.decide(candidate, verdict, notes, edits).verdict.code
    }
}
