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
import app.tsumugi.audio.AudioInstallProgress
import app.tsumugi.audio.AudioKeys
import app.tsumugi.audio.AudioSet
import app.tsumugi.audio.InstalledAudioPack
import app.tsumugi.audio.PairSide as AudioPairSide
import app.tsumugi.coverage.TextCoverage
import app.tsumugi.immersion.ImmersionMode
import app.tsumugi.immersion.ImmersionOrigin
import app.tsumugi.immersion.ImmersionSession
import app.tsumugi.lyrics.LyricsSong
import app.tsumugi.media.Cue
import app.tsumugi.media.OnlineExamplesResult
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

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

    // --- Audio packs (rule 20, D-095/D-096) and Phase 11 screens: thin adapters, types in SwiftBridges.kt (D-197) ---

    /** The installed pre-rendered clip for [key] as a file path, or null: play system TTS instead. */
    fun audioClipPath(graph: AppGraph, key: String): String? = graph.audio.clip(key)?.toString()

    /** `pair/<id>/a|b` without Swift naming the clashing `PairSide` enum. */
    fun pairClipKey(pairId: Long, sideA: Boolean): String = AudioKeys.minimalPair(pairId, if (sideA) AudioPairSide.A else AudioPairSide.B)

    /** Every audio set id in display order ("exam", "dialogues", "minimal-pairs", "pitch", "grammar"). */
    fun audioSetIds(): List<String> = AudioSet.entries.map { it.id }

    fun audioPacks(graph: AppGraph): List<AudioPackRow> = graph.audio.installed().map(::audioRow)

    fun audioProgress(progress: AudioInstallProgress): AudioProgressRow =
        AudioProgressRow(progress.set, progress.phase.name, progress.bytesDone, progress.bytesTotal, progress.fraction)

    /** Installs a picked `audio-<set>.zip` the platform copied to [path] (verified by structure and clip sizes). */
    @Throws(Exception::class)
    suspend fun installAudioFile(graph: AppGraph, path: String): AudioPackRow = audioRow(graph.audio.installFile(path.toPath(), null))

    /** The packs a server offers at [baseUrl] (the folder holding `audio-manifest.json`, or its URL). */
    @Throws(Exception::class)
    suspend fun fetchAudioManifest(graph: AppGraph, baseUrl: String): List<AudioManifestRow> {
        val installed = graph.audio.installed().associate { it.set.id to it.version }
        return graph.audio.fetchManifest(baseUrl).packs.filter { AudioSet.fromId(it.set) != null }.map { e ->
            AudioManifestRow(e, e.set, e.file, e.version, e.bytes, e.clips, e.audioSeconds, e.credits, installed[e.set])
        }
    }

    /** Downloads and installs one pack; cancel the calling task to stop (the old version stays). */
    @Throws(Exception::class)
    suspend fun downloadAudioPack(graph: AppGraph, baseUrl: String, row: AudioManifestRow): AudioPackRow =
        audioRow(graph.audio.download(baseUrl, row.entry))

    @Throws(Exception::class)
    suspend fun removeAudioPack(graph: AppGraph, setId: String) {
        AudioSet.fromId(setId)?.let { graph.audio.remove(it) }
    }

    private fun audioRow(p: InstalledAudioPack) = AudioPackRow(p.set.id, p.version, p.clips, p.bytesOnDisk, p.credits)

    /** Per-day immersion minutes for the heat-map, oldest first. */
    @Throws(Exception::class)
    suspend fun immersionDays(graph: AppGraph, days: Int): List<ImmersionDayRow> = graph.immersion.days(days).map { d ->
        ImmersionDayRow(d.date.toString(), d.activeMinutes, d.passiveMinutes, d.totalMinutes, d.targetMinutes, d.targetMet, d.bySource)
    }

    /** A manual entry on [isoDate] (yyyy-MM-dd, today or earlier). */
    @Throws(Exception::class)
    suspend fun addManualImmersion(graph: AppGraph, isoDate: String, minutes: Int, active: Boolean, source: ImmersionOrigin, title: String?): ImmersionSession =
        graph.immersion.addManual(LocalDate.parse(isoDate), minutes, if (active) ImmersionMode.ACTIVE else ImmersionMode.PASSIVE, source, title)

    /** Logged sessions of the last [days] days, newest first. */
    @Throws(Exception::class)
    suspend fun recentImmersionSessions(graph: AppGraph, days: Int): List<ImmersionSession> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return graph.immersion.sessions(today.minus(DatePeriod(days = (days - 1).coerceAtLeast(0))), today).sortedByDescending { it.startedAt }
    }

    /** Library lines, Tatoeba and (when on) the online source for a dictionary entry. */
    @Throws(Exception::class)
    suspend fun sentencesForEntry(graph: AppGraph, entryId: Long, word: String, reading: String?, limit: Int): SentenceSearchRows {
        val r = graph.sentenceSearch.forEntry(entryId, word, reading, limit)
        val online = r.online
        return SentenceSearchRows(
            library = r.library,
            tatoeba = r.tatoeba,
            online = (online as? OnlineExamplesResult.Found)?.hits.orEmpty(),
            onlineEnabled = online !is OnlineExamplesResult.Disabled,
            onlineFailure = (online as? OnlineExamplesResult.Failed)?.reason,
            onlineSourceName = graph.onlineExamples.sourceName,
        )
    }

    /** Cues as SRT text (Whisper subtitles fed to coverage and 1T search, which take subtitle text). */
    fun cuesToSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
            append(c.text.trim()).append("\n\n")
        }
    }

    private fun srtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        fun pad(v: Long, n: Int) = v.toString().padStart(n, '0')
        return "${pad(t / 3_600_000, 2)}:${pad(t / 60_000 % 60, 2)}:${pad(t / 1000 % 60, 2)},${pad(t % 1000, 3)}"
    }

    /** Aligns plain lyrics with Whisper over the song's audio; cancel the calling task to stop. */
    @Throws(Exception::class)
    suspend fun alignLyrics(graph: AppGraph, songId: String, mediaHash: String, reader: PcmWindowReader, onProgress: (SubtitleProgress) -> Unit): LyricsSong =
        graph.lyrics.align(songId, mediaHash, CallbackPcmSource(reader), onProgress)

    /** `TextCoverage.newWordsTo95` (a `new…` getter is renamed by the Objective-C export). */
    fun coverageWordsTo95(coverage: TextCoverage): Int = coverage.newWordsTo95

    // --- Phase 12 iOS UI (D-260…D-269) --------------------------------------------------------------------------
    // Adapters only: no default arguments, no nullable primitives or enums as parameters, no sealed/nested results.

    /** Graded stories at [jlpt] (6 = level 0 … 1), or every level when [jlpt] is 0. */
    @Throws(Exception::class)
    suspend fun gradedStories(graph: AppGraph, jlpt: Int): List<app.tsumugi.reader.GradedPassageSummary> =
        graph.reader.graded.stories(jlpt.takeIf { it > 0 })

    /** The read-along lines of [story] with their text and timings (untimed without the readers audio pack). */
    @Throws(Exception::class)
    suspend fun readAlongPlan(graph: AppGraph, story: app.tsumugi.reader.GradedStory): ReadAlongPlan {
        val track = graph.reader.graded.readAlong(story)
        return ReadAlongPlan(
            story.id, track.timed, track.totalMs,
            track.lines.map { t ->
                ReadAlongRow(
                    t.line.index, t.line.start, t.line.end, story.text(t.line), t.line.speaker, t.line.voice, t.line.clipKey,
                    t.startMs ?: -1L, t.endMs ?: -1L,
                )
            },
        )
    }

    /** Scores and stores a graded-reader quiz; [choices] has one index per question, -1 = unanswered. */
    @Throws(Exception::class)
    suspend fun submitGradedQuiz(graph: AppGraph, story: app.tsumugi.reader.GradedStory, choices: List<Int>): app.tsumugi.reader.ReaderQuizResult =
        graph.reader.graded.submitQuiz(story, choices.map { c -> c.takeIf { it >= 0 } })

    /** The post-reading summary graded by the learner's model (AI-generated), or why it couldn't be. */
    @Throws(Exception::class)
    suspend fun gradeReaderSummary(graph: AppGraph, story: app.tsumugi.reader.GradedStory, summary: String): SummaryGradeRow =
        when (val r = graph.reader.graded.gradeSummary(story, summary)) {
            is app.tsumugi.reader.SummaryGradeResult.Graded -> SummaryGradeRow(
                true, r.grade.content, r.grade.accuracy, r.grade.language, r.grade.total, r.grade.corrected, r.grade.feedback, r.engine, "",
            )
            is app.tsumugi.reader.SummaryGradeResult.Unavailable -> SummaryGradeRow(false, 0, 0, 0, 0, "", "", "", r.reason)
        }

    /** A track's drills of one type code ("keigo", "email", …), or all of them when [typeCode] is "". */
    @Throws(Exception::class)
    suspend fun trackDrills(repo: app.tsumugi.tracks.TrackRepository, trackId: String, typeCode: String): List<app.tsumugi.tracks.TrackDrill> =
        repo.drills(trackId, app.tsumugi.tracks.DrillType.of(typeCode))

    /** A track's word lessons at the default size. */
    @Throws(Exception::class)
    suspend fun trackLessons(repo: app.tsumugi.tracks.TrackRepository, trackId: String): List<app.tsumugi.tracks.TrackLesson> =
        repo.lessons(trackId, app.tsumugi.tracks.TrackRepository.DEFAULT_LESSON_SIZE)

    fun drillTypeCode(drill: app.tsumugi.tracks.TrackDrill): String = drill.type.code

    /** `Track.description` (a `description` member collides with NSObject's in the Objective-C export). */
    fun trackDescription(summary: app.tsumugi.tracks.TrackSummary): String = summary.track.description

    /** The email body as text runs and slots, in order. */
    fun emailSegments(drill: app.tsumugi.tracks.EmailDrill): List<EmailSegmentRow> = drill.segments.map {
        when (it) {
            is app.tsumugi.tracks.EmailSegment.Text -> EmailSegmentRow(it.text, -1)
            is app.tsumugi.tracks.EmailSegment.Slot -> EmailSegmentRow("", it.blank)
        }
    }

    /** The fill-in sentence around its blank: [before, after]. */
    fun fillInParts(drill: app.tsumugi.tracks.FillInDrill): List<String> = listOf(drill.parts.first, drill.parts.second)

    /** [target label (尊敬語 …), form code (dictionary | masu | past | masu-past | te)]. */
    fun keigoPrompt(drill: app.tsumugi.tracks.KeigoDrill): List<String> = listOf(drill.target.labelJa, drill.form.code)

    /** A memorize-and-perform session starting at the full script. */
    fun performanceSession(drill: app.tsumugi.tracks.PerformDrill): app.tsumugi.tracks.PerformanceSession =
        app.tsumugi.tracks.PerformanceSession(drill, app.tsumugi.tracks.FadeLevel.FULL)

    /** The session's fade step: 0 full, 1 half, 2 initial, 3 cue only. */
    fun performanceStep(session: app.tsumugi.tracks.PerformanceSession): Int = session.level.ordinal

    /**
     * The hands-free plan for [set]: [preset] "short" | "default" | "long", the answer pause fixed or proportional,
     * the repeat pause on or off. Answer lengths come from the installed audio packs' clip durations when present.
     */
    @Throws(Exception::class)
    suspend fun drillPlan(graph: AppGraph, set: app.tsumugi.practice.DrillSet, preset: String, repeatPause: Boolean, fixedPause: Boolean): app.tsumugi.practice.DrillPlan {
        val base = when (preset) {
            "short" -> app.tsumugi.practice.DrillTiming.SHORT
            "long" -> app.tsumugi.practice.DrillTiming.LONG
            else -> app.tsumugi.practice.DrillTiming.DEFAULT
        }
        val timing = base.copy(
            repeat = repeatPause,
            pauseMode = if (fixedPause) app.tsumugi.practice.DrillTiming.PauseMode.FIXED else app.tsumugi.practice.DrillTiming.PauseMode.PROPORTIONAL,
        )
        val ms = HashMap<String, Long>()
        for (s in listOf(AudioSet.GRAMMAR, AudioSet.DIALOGUES)) {
            graph.audio.index(s)?.clips?.forEach { (key, info) -> if (info.ms > 0) ms[key] = info.ms }
        }
        return app.tsumugi.practice.DrillPlayback.plan(set, timing) { item -> ms[item.audioKey]?.takeIf { graph.audio.clip(item.audioKey) != null } }
    }

    fun drillCursor(plan: app.tsumugi.practice.DrillPlan, start: Int): app.tsumugi.practice.DrillCursor =
        app.tsumugi.practice.DrillCursor(plan, start)

    /** `DrillSetSummary.description` (a `description` member collides with NSObject's in the Objective-C export). */
    fun drillSetDescription(summary: app.tsumugi.practice.DrillSetSummary): String = summary.description

    /** PROMPT | ANSWER_PAUSE | ANSWER | REPEAT_PAUSE | GAP. */
    fun drillStepCode(step: app.tsumugi.practice.DrillStep): String = step.kind.name

    /** Items in the plan's set (Swift never reads `DrillPlan.set`, a `set`-named member). */
    fun drillPlanItems(plan: app.tsumugi.practice.DrillPlan): List<app.tsumugi.practice.DrillItem> = plan.set.items

    /** The three onomatopoeia types for the filter. */
    fun onomatopoeiaTypes(): List<OnomatopoeiaTypeRow> =
        app.tsumugi.onomatopoeia.OnomatopoeiaType.entries.map { OnomatopoeiaTypeRow(it.name, it.labelJa, it.labelEn) }

    fun onomatopoeiaTypeCode(word: app.tsumugi.onomatopoeia.OnomatopoeiaWord): String = word.type.name

    fun onomatopoeiaTypeLabel(word: app.tsumugi.onomatopoeia.OnomatopoeiaWord): String = word.type.labelJa

    /** Words of [theme] ("" = all) and [typeCode] ("" = all types). */
    @Throws(Exception::class)
    suspend fun onomatopoeiaWords(repo: app.tsumugi.onomatopoeia.OnomatopoeiaRepository, theme: String, typeCode: String, withFeelOnly: Boolean): List<app.tsumugi.onomatopoeia.OnomatopoeiaWord> =
        repo.words(theme.ifEmpty { null }, app.tsumugi.onomatopoeia.OnomatopoeiaType.of(typeCode), withFeelOnly)

    /** A quiz: [kindCode] WORD_FOR_SCENE | SCENE_FOR_WORD ("" = mixed), [theme] "" = all themes. */
    @Throws(Exception::class)
    suspend fun onomatopoeiaQuiz(repo: app.tsumugi.onomatopoeia.OnomatopoeiaRepository, count: Int, kindCode: String, theme: String, seed: Long): List<app.tsumugi.onomatopoeia.OnomatopoeiaQuestion> =
        repo.quiz(count, app.tsumugi.onomatopoeia.OnomatopoeiaQuizKind.entries.firstOrNull { it.name == kindCode }, theme.ifEmpty { null }, seed)

    fun onomatopoeiaQuizIsScene(question: app.tsumugi.onomatopoeia.OnomatopoeiaQuestion): Boolean =
        question.kind == app.tsumugi.onomatopoeia.OnomatopoeiaQuizKind.WORD_FOR_SCENE

    /** Monolingual mode's easiest Japanese-only level (1–5), or 0 when it's off. */
    @Throws(Exception::class)
    suspend fun monolingualFromLevel(graph: AppGraph): Int = graph.monolingual.fromLevel() ?: 0

    /** 0 turns monolingual mode off; 1–5 = that JLPT level and harder in Japanese only. */
    @Throws(Exception::class)
    suspend fun setMonolingualFromLevel(graph: AppGraph, level: Int) = graph.monolingual.setFromLevel(level.takeIf { it in 1..5 })

    /** [point]'s explanation in the language the setting asks for, or in English when [english] is true. */
    @Throws(Exception::class)
    suspend fun grammarExplanation(graph: AppGraph, point: app.tsumugi.grammar.GrammarPoint, english: Boolean): app.tsumugi.courses.GrammarExplanation =
        if (english) {
            graph.explanations.grammar(point, app.tsumugi.courses.ExplanationLanguage.ENGLISH)
        } else {
            graph.explanations.grammar(point)
        }

    /** [point]'s explanation in Japanese whatever the setting says (the "日本語で" toggle). */
    @Throws(Exception::class)
    suspend fun grammarExplanationJapanese(graph: AppGraph, point: app.tsumugi.grammar.GrammarPoint): app.tsumugi.courses.GrammarExplanation =
        graph.explanations.grammar(point, app.tsumugi.courses.ExplanationLanguage.JAPANESE)

    fun explanationIsJapanese(explanation: app.tsumugi.courses.GrammarExplanation): Boolean =
        explanation.language == app.tsumugi.courses.ExplanationLanguage.JAPANESE

    /**
     * A word's gloss in the right language. [jlpt] 0 = untagged (the course level decides). [generate] false = cache
     * only (lists). [japanese] true = the paraphrase whatever the setting says (the "explain in Japanese" button).
     */
    @Throws(Exception::class)
    suspend fun wordExplanation(
        graph: AppGraph, entryId: Long, word: String, reading: String, glosses: List<String>, jlpt: Int, generate: Boolean, japanese: Boolean,
    ): app.tsumugi.courses.WordExplanation {
        val request = app.tsumugi.courses.ParaphraseRequest(word, reading, glosses, emptyList(), entryId.takeIf { it > 0 }, jlpt.takeIf { it in 1..5 })
        return if (japanese) {
            graph.explanations.paraphrase(request, "N${request.jlpt ?: graph.courses.courseLevel()}", generate)
        } else {
            graph.explanations.word(request, graph.courses.courseLevel(), generate)
        }
    }

    /** Drops a cached paraphrase the learner flagged, so the next request asks the model again. */
    @Throws(Exception::class)
    suspend fun forgetWordParaphrase(graph: AppGraph, entryId: Long, word: String, reading: String) =
        graph.explanations.forgetParaphrase(app.tsumugi.courses.ParaphraseRequest(word, reading, emptyList(), emptyList(), entryId.takeIf { it > 0 }, null))

    /** The "level check → probe" map after an interview, flattened for the chart. */
    fun opiProbeMap(session: OpiSession): OpiProbeRows {
        val map = session.probeMap()
        return OpiProbeRows(
            floor = map.floor?.label.orEmpty(),
            ceiling = map.ceiling?.label.orEmpty(),
            turns = map.turns.map { t ->
                OpiProbeTurnRow(
                    t.index, opiPhaseTitle(t.phase), t.isProbe, t.isLevelCheck, t.question, t.domain?.title.orEmpty(),
                    t.targetLevel.label, t.targetLevel.ordinal, t.levelBefore.label, t.levelAfter?.label.orEmpty(),
                    t.levelAfter?.ordinal ?: -1, t.answerLength ?: -1, t.outcome.name,
                )
            },
            levels = map.byLevel.map { OpiProbeLevelRow(it.level.label, it.level.ordinal, it.sustained, it.partial, it.breakdown) },
            levelLabels = IlrLevel.entries.map { it.label },
            domains = map.domains.map { it.title },
            missingDomains = map.missingDliDomains.map { it.title },
        )
    }
}
