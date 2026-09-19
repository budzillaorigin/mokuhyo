package app.tsumugi.api

import app.tsumugi.audio.AudioPackRepository
import app.tsumugi.content.PackInstaller
import app.tsumugi.content.PackStatus
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.exam.ExamService
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.opi.OpiSession
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.speaking.AiService
import app.tsumugi.speaking.PronunciationService
import app.tsumugi.speaking.RoleplaySession
import app.tsumugi.study.activities.PomodoroSession
import app.tsumugi.grammar.GrammarPoint
import app.tsumugi.grammar.GrammarService
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.jp.tokenizer.LatticeTokenizer
import app.tsumugi.jp.tokenizer.MorphologicalAnalyzer
import app.tsumugi.tokenizer.db.TokenizerDatabase
import app.tsumugi.integrations.ImportService
import app.tsumugi.path.db.PathDatabase
import app.tsumugi.platform.PlatformServices
import app.tsumugi.reader.ReaderService
import app.tsumugi.sync.SyncAccount
import app.tsumugi.sync.SyncEngine
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.settings.DeviceState
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.FsrsParameters
import app.tsumugi.srs.FsrsScheduler
import app.tsumugi.srs.PathProgressStore
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.LessonSession
import app.tsumugi.study.Onboarding
import app.tsumugi.study.ReminderPlanner
import app.tsumugi.study.ReviewSession
import app.tsumugi.study.StatsService
import app.tsumugi.study.LearnerLevel
import app.tsumugi.study.MinimalPairService
import app.tsumugi.study.TodayBlockKind
import app.tsumugi.study.TodayCandidateSource
import app.tsumugi.study.TodayPlan
import app.tsumugi.study.TodayPlanner
import app.tsumugi.speaking.ConversationService
import app.tsumugi.speaking.FreeTalkSession
import app.tsumugi.sync.LeaderboardService
import app.tsumugi.study.WritingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import app.tsumugi.cards.PersonalCards
import app.tsumugi.domain.ItemSource
import app.tsumugi.export.BackupService
import app.tsumugi.export.DailyTotals
import app.tsumugi.export.RestoreProgress
import app.tsumugi.export.RestoreResult
import app.tsumugi.export.ReviewCsvExporter
import app.tsumugi.export.StudyReport
import app.tsumugi.export.StudyReportBuilder
import app.tsumugi.integrations.ankiconnect.AnkiConnectPush
import app.tsumugi.integrations.notion.NotionClient
import app.tsumugi.integrations.notion.NotionDayStats
import app.tsumugi.integrations.notion.NotionExport
import app.tsumugi.integrations.notion.NotionPushResult
import app.tsumugi.kana.KanaCourse
import app.tsumugi.media.ClipService
import app.tsumugi.media.PodcastService
import app.tsumugi.media.SubtitleGenerator
import app.tsumugi.reader.UrlImporter
import app.tsumugi.recordings.ImageStore
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import app.tsumugi.recordings.RecordingSync
import app.tsumugi.review.ContentReviewService
import app.tsumugi.review.ExamReviewSource
import app.tsumugi.review.GrammarReviewSource
import app.tsumugi.review.KanaMnemonicReviewSource
import app.tsumugi.review.PracticeReviewSource
import app.tsumugi.review.ReviewSource
import app.tsumugi.srs.StudyItem
import okio.Path.Companion.toPath
import app.tsumugi.coverage.CoverageService
import app.tsumugi.coverage.KnownWords
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.TextProfileStore
import app.tsumugi.coverage.TextProfiler
import app.tsumugi.decks.DeckLessons
import app.tsumugi.decks.MediaDeckService

/** Progress of a background rebuild of every card (after new FSRS weights). */
data class RecomputeProgress(val done: Int, val total: Int, val running: Boolean) {
    val fraction: Double get() = if (total == 0) 1.0 else done.toDouble() / total
}

/**
 * One lazily opened pack service with its own lock (BRIEF_V2 F-32): opening or installing one pack never waits
 * on another. A missing pack (null) is retried on the next call, so installing it later works without a restart.
 */
private class PackSlot<T : Any> {
    private val mutex = Mutex()

    @Volatile
    private var value: T? = null

    suspend fun get(open: suspend () -> T?): T? {
        value?.let { return it }
        return mutex.withLock { value ?: open()?.also { value = it } }
    }
}

/**
 * Composition root both apps hold one instance of.
 * Swift: `AppGraph(platform: PlatformServices())`; Android: `AppGraph(PlatformServices(applicationContext))`.
 */

/** How many kana lessons Today looks ahead when the kana course is needed (the course has 30). */
private const val KANA_LESSONS_AHEAD = 30

class AppGraph(val platform: PlatformServices) {

    val packs = PackInstaller(platform)

    /**
     * Pre-rendered VOICEVOX audio (BRIEF_V2 §5.6, rule 20): `audio.clip(AudioKeys.exam(id, line))` is a file to
     * play, or null to fall back to system TTS. Packs are downloaded or picked, never required (D-095..D-097).
     */
    val audio: AudioPackRepository by lazy { AudioPackRepository(platform) }

    private val userDriver by lazy { platform.userDatabaseDriver() }
    val userDatabase: TsumugiDatabase by lazy { TsumugiDatabase(userDriver) }
    val device: DeviceState by lazy { DeviceState(userDatabase, platform.secrets) }
    val settings: SettingsRepository by lazy { SettingsRepository(userDatabase) }
    /** Per-device configuration that never syncs (AI engines, endpoints, audio engine; CLAUDE.md rule 16). */
    val deviceSettings: DeviceSettings by lazy { DeviceSettings(userDatabase) }
    val srs: SrsRepository by lazy { SrsRepository(userDatabase, device.deviceId) }
    val stats: StatsService by lazy { StatsService(userDatabase, srs, settings) }
    val imports: ImportService by lazy { ImportService(this) }
    val reminders: ReminderPlanner by lazy { ReminderPlanner(userDatabase, settings) }
    val collection: CollectionService by lazy { CollectionService(userDatabase, srs, { path() }) }
    val reader: ReaderService by lazy { ReaderService(this) }
    val onboarding: Onboarding by lazy { Onboarding(settings) { path() } }
    val pathProgress: PathProgressStore by lazy { PathProgressStore(userDatabase) }

    /** On-device / self-hosted AI engines (BRIEF §7). Apps set `ai.llmBridge` / `ai.sttBridge` at startup. */
    val ai: AiService by lazy { AiService(platform, deviceSettings) }
    val pronunciation: PronunciationService by lazy { PronunciationService({ analyzer() }, { dictionary() }) }

    /** Background work that outlives a screen (card rebuilds). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Optional self-hostable sync (BRIEF §8). Nothing syncs until the learner signs in. */
    val syncAccount: SyncAccount by lazy { SyncAccount(userDatabase, platform.secrets, { platform.httpEngine() }) }
    private val syncLock = Mutex()
    private var syncEngine: SyncEngine? = null

    /** The sync engine for the signed-in account, or null when sync isn't set up. */
    @Throws(Exception::class)
    suspend fun sync(): SyncEngine? {
        val client = syncAccount.client() ?: return null
        val srs = configuredSrs()
        return syncLock.withLock {
            (syncEngine ?: SyncEngine(userDriver, userDatabase, srs, device.deviceId, client).also { engine ->
                // Synced settings are applied inside the engine; scheduler settings need the app to react.
                engine.onSettingsChanged = { keys -> settingsChanged(keys) }
                syncEngine = engine
            }).also { it.sealer = syncAccount.sealer }
        }
    }

    /** Syncs now if configured; failures are reported through [SyncEngine.status], never thrown at callers. */
    @Throws(Exception::class)
    suspend fun syncIfConfigured() {
        runCatching { sync()?.sync() }
    }

    /** Call after signing out so a new account gets a fresh engine. */
    fun resetSync() {
        syncEngine = null
    }
    private val planner: TodayPlanner by lazy { TodayPlanner(userDatabase, settings) }
    private val todayCandidates by lazy {
        TodayCandidateSource(userDatabase, srs, { reader.documents() }, { practice() }, { grammar() }, { dictionary() != null })
    }

    /** Today's plan (BRIEF §5.6, BRIEF_V2 G-01) from the current queue, path, grammar and the installed packs. */
    @Throws(Exception::class)
    suspend fun today(): TodayPlan {
        val srs = configuredSrs()
        val grammarLeft = grammar()?.lessonQueue(3)?.size ?: 0
        val status = deckLessons.adjust(path()?.status()) // deck lessons count as lessons (D-154)
        planner.difficulty = coverage.immersionDifficulty() // §6.4 score (D-155)
        val kana = kana()
        val kanaLessons = if (kana.needed(settings)) kana.lessonQueue(settings, KANA_LESSONS_AHEAD).size else 0
        return planner.plan(srs.dueCount(), status, grammarLeft, todayCandidates.collect(status?.currentLevel), kanaLessons = kanaLessons)
    }

    /** Records a finished Today block (weekly challenges count these; G-01/G-11). */
    @Throws(Exception::class)
    suspend fun markTodayBlockDone(kind: TodayBlockKind) = planner.markDone(kind)

    /** Stored conversations: rolling level estimate, recurring errors, weekly patterns (G-02). */
    val conversations: ConversationService by lazy { ConversationService(userDatabase, device.deviceId) }

    /** Free talk at the learner's rolling level (G-02); `start()` returns null without a model. */
    @Throws(Exception::class)
    suspend fun freeTalk(topic: String? = null): FreeTalkSession {
        val fallback = path()?.status()?.currentLevel?.let { LearnerLevel.jlptForPathLevel(it) } ?: 4
        return FreeTalkSession(ai.gateway(), conversations, conversations.partnerLevel(fallback), topic)
    }

    /** Minimal pairs on FSRS (G-06), or null without the practice pack. */
    @Throws(Exception::class)
    suspend fun minimalPairDrill(): MinimalPairService? = practice()?.let { MinimalPairService(it, configuredSrs()) }

    /** The opt-in leaderboard on the learner's sync server (G-11); off by default. */
    val leaderboard: LeaderboardService by lazy { LeaderboardService({ syncAccount.client() }, { syncAccount.e2eEnabled }, settings) }

    private val tokenizerSlot = PackSlot<LatticeTokenizer>()
    private val writingSlot = PackSlot<WritingService>()
    private val practiceSlot = PackSlot<PracticeRepository>()
    private val examSlot = PackSlot<ExamService>()
    private val dictionarySlot = PackSlot<DictionaryRepository>()
    private val pathSlot = PackSlot<PathService>()
    private val grammarSlot = PackSlot<GrammarService>()

    /** The IPADIC lattice analyzer (BRIEF §5.2), or null when the tokenizer pack isn't installed. */
    @Throws(Exception::class)
    suspend fun analyzer(): MorphologicalAnalyzer? = tokenizerSlot.get {
        openPack(PackInstaller.TOKENIZER) {
            LatticeTokenizer(TokenizerDatabase(platform.packDriver(TokenizerDatabase.Schema, PackInstaller.TOKENIZER)))
        }
    }

    /** Writing practice and handwriting search, or null without the dictionary pack (it holds KanjiVG). */
    @Throws(Exception::class)
    suspend fun writing(): WritingService? {
        val dictionary = dictionary() ?: return null
        val srs = configuredSrs()
        return writingSlot.get { WritingService(dictionary, srs) }
    }

    /** Next grammar lesson batch (1–3 points) or empty when the pack is missing or everything is learned. */
    @Throws(Exception::class)
    suspend fun grammarLessons(): List<GrammarPoint> = grammar()?.lessonQueue(
        (settings.int(SettingsRepository.DAILY_BUDGET_MINUTES, TodayPlanner.DEFAULT_BUDGET) / 20).coerceIn(1, 3),
        settings.get(SettingsRepository.GRAMMAR_PATH)?.takeIf { it.isNotBlank() && it != "jlpt" },
    ).orEmpty()

    /** Speaking/listening practice pack (scenarios, OPI banks, dialogues, minimal pairs), or null when missing. */
    @Throws(Exception::class)
    suspend fun practice(): PracticeRepository? = practiceSlot.get {
        openPack(PackInstaller.PRACTICE) {
            PracticeRepository(PracticeDatabase(platform.packDriver(PracticeDatabase.Schema, PackInstaller.PRACTICE)))
        }
    }

    /** Exam simulators. Works without the exam pack too (imported banks, history), so never null. */
    @Throws(Exception::class)
    suspend fun exams(): ExamService = examSlot.get {
        val pack = openPack(PackInstaller.EXAM) { ExamDatabase(platform.packDriver(ExamDatabase.Schema, PackInstaller.EXAM)) }
        configuredSrs() // "add missed items to SRS" schedules with the learner's settings
        ExamService(pack, userDatabase, device.deviceId, { grammar() }, { dictionary() }, collection)
    }!!

    /** A role-play for [scenarioId] with the configured model (or scripted turns), or null without the pack. */
    @Throws(Exception::class)
    suspend fun roleplay(scenarioId: String): RoleplaySession? {
        val practice = practice() ?: return null
        val scenario = practice.scenario(scenarioId) ?: return null
        return RoleplaySession(scenario, practice.scriptedTurns(scenarioId), ai.gateway())
    }

    /** An OPI practice interview starting at [startLevel]; scripted banks come from the practice pack. */
    @Throws(Exception::class)
    suspend fun opi(startLevel: IlrLevel = IlrLevel.L1): OpiSession? {
        val practice = practice() ?: return null
        val banks = IlrLevel.lowerRange.associateWith { practice.opiBank(it.label) }.filterValues { it.questions.isNotEmpty() }
        if (banks.isEmpty()) return null
        return OpiSession(banks, ai.gateway(), startLevel)
    }

    /** A 25-minute speaking session at the learner's JLPT level (from settings, default N4). */
    @Throws(Exception::class)
    suspend fun pomodoro(jlpt: Int = 4): PomodoroSession? = PomodoroSession.build(practice(), jlpt)

    /**
     * The dictionary, installing the bundled pack on first use. Returns null when no dictionary pack is
     * available, so screens can show an honest "dictionary not installed" state.
     */
    @Throws(Exception::class)
    suspend fun dictionary(): DictionaryRepository? = dictionarySlot.get {
        openPack(PackInstaller.DICTIONARY) {
            DictionaryRepository(DictionaryDatabase(platform.packDriver(DictionaryDatabase.Schema, PackInstaller.DICTIONARY)))
        }
    }

    /** The 60-level kanji path, or null when the path pack isn't installed. */
    @Throws(Exception::class)
    suspend fun path(): PathService? {
        val srs = configuredSrs()
        return pathSlot.get {
            openPack(PackInstaller.KANJI_PATH) {
                PathService(PathDatabase(platform.packDriver(PathDatabase.Schema, PackInstaller.KANJI_PATH)), srs, settings, pathProgress)
            }
        }
    }

    private val schedulerLock = Mutex()

    @Volatile
    private var schedulerLoaded = false

    /** SRS repository with the user's scheduler settings (fitted FSRS weights, desired retention) applied. */
    @Throws(Exception::class)
    suspend fun configuredSrs(): SrsRepository {
        if (!schedulerLoaded) {
            schedulerLock.withLock {
                if (!schedulerLoaded) {
                    srs.scheduler = FsrsScheduler(schedulerParameters())
                    schedulerLoaded = true
                }
            }
        }
        return srs
    }

    /** The grammar pack service, or null when the grammar pack isn't installed. */
    @Throws(Exception::class)
    suspend fun grammar(): GrammarService? {
        val srs = configuredSrs()
        return grammarSlot.get {
            openPack(PackInstaller.GRAMMAR) {
                GrammarService(GrammarDatabase(platform.packDriver(GrammarDatabase.Schema, PackInstaller.GRAMMAR)), srs) { dictionary() }
            }?.also { it.syncExampleAvailability() } // points without examples never count as due (F-20)
        }
    }

    @Throws(Exception::class)
    suspend fun startReviews(limit: Int = 500): ReviewSession = ReviewSession.start(
        configuredSrs(), grammar(), limit,
        grammarVariety = settings.bool(SettingsRepository.GRAMMAR_REVIEW_VARIETY, true),
        gateway = ai.gateway(),
    )

    @Throws(Exception::class)
    suspend fun startLessons(): LessonSession? {
        deckLessons.startSession(path(), settings.lessonBatchSize())?.let { return it } // an active deck (D-154)
        val path = path() ?: return null
        val batch = path.lessonQueue(settings.lessonBatchSize())
        return if (batch.isEmpty()) null else LessonSession(path, batch)
    }

    private suspend fun schedulerParameters(): FsrsParameters {
        val retention = settings.desiredRetention()
        val weights = settings.get(SettingsRepository.FSRS_WEIGHTS)
            ?.let { runCatching { Json.decodeFromString<List<Double>>(it) }.getOrNull() }
            ?: FsrsParameters.DEFAULT_WEIGHTS
        return runCatching { FsrsParameters(weights = weights, desiredRetention = retention) }
            .getOrElse { FsrsParameters(desiredRetention = retention) }
    }

    private suspend fun <T> openPack(file: String, open: suspend () -> T): T? =
        when (packs.ensureInstalled(file)) {
            is PackStatus.Installed -> open()
            PackStatus.Missing -> null
        }

    /** Call after changing scheduler settings so the next review uses them. */
    fun reloadScheduler() {
        schedulerLoaded = false
    }

    // --- FSRS weights (BRIEF_V2 F-32) ------------------------------------------------------------------------

    private val _recompute = MutableStateFlow<RecomputeProgress?>(null)

    /** The background card rebuild after new FSRS weights, or null when none has run. */
    val recomputeProgress: StateFlow<RecomputeProgress?> = _recompute.asStateFlow()
    private var recomputeJob: Job? = null

    /** Stores fitted FSRS weights (e.g. from the optimizer), then reloads the scheduler and rebuilds every card. */
    @Throws(Exception::class)
    suspend fun setFsrsWeights(weights: List<Double>) {
        FsrsParameters(weights = weights) // validates bounds before anything is stored
        settings.put(SettingsRepository.FSRS_WEIGHTS, Json.encodeToString(weights))
        settingsChanged(setOf(SettingsRepository.FSRS_WEIGHTS))
    }

    /** Reacts to changed synced settings, local or pulled by sync. */
    private fun settingsChanged(keys: Set<String>) {
        if (SettingsRepository.FSRS_WEIGHTS in keys || SettingsRepository.DESIRED_RETENTION in keys) reloadScheduler()
        if (SettingsRepository.FSRS_WEIGHTS in keys) recomputeAllInBackground()
    }

    // --- Phase 10 shared features (BRIEF_V2 G-03…G-16, DECISIONS D-110…D-119) ------------------------------

    /** The learner's recordings (G-03): files under dataDir/recordings, excluded from backups. */
    val recordings: RecordingStore by lazy { RecordingStore(userDatabase, platform.fileSystem, platform.dataDir, device.deviceId) }

    /** The learner's pictures for personal cards (G-12). */
    val images: ImageStore by lazy { ImageStore(userDatabase, platform.fileSystem, platform.dataDir, device.deviceId) }

    /** Opt-in recordings/pictures sync through the server's blob store (off by default, per device). */
    val recordingSync: RecordingSync by lazy {
        RecordingSync(
            userDatabase, platform.fileSystem, recordings, images, deviceSettings, device.deviceId,
            blobs = { syncAccount.client() },
            serverDeviceId = { userDatabase.metaQueries.get(SyncAccount.SERVER_DEVICE_ID).executeAsOneOrNull() },
            sealer = { syncAccount.sealer },
        )
    }

    /** Personal (Fluent Forever) cards and self-recorded audio sides (G-03, G-12). */
    val personalCards: PersonalCards by lazy { PersonalCards(userDatabase, srs, recordings, images) }

    /** Whisper subtitles for media, cached by content key (G-04). */
    val subtitles: SubtitleGenerator by lazy { SubtitleGenerator(userDatabase, { ai.recognizer() }) }

    /** "Save clip to SRS" (G-04). */
    val clips: ClipService by lazy { ClipService(userDatabase, srs, recordings) }

    /** Podcast feeds, episodes and download bookkeeping (G-04). */
    val podcasts: PodcastService by lazy {
        val web = UrlImporter(platform.httpEngine())
        PodcastService(userDatabase, platform.fileSystem, platform.dataDir, { web.fetchText(it) })
    }

    private val kanaCourse: KanaCourse by lazy { KanaCourse(srs) { settings.bool(SettingsRepository.WRITING_CARDS, false) } }

    /** The kana course (G-13), with the learner's scheduler loaded. Today calls `kana().needed(settings)`. */
    @Throws(Exception::class)
    suspend fun kana(): KanaCourse {
        configuredSrs()
        return kanaCourse
    }

    /** CSV of the whole review log (G-10). */
    val reviewCsv: ReviewCsvExporter by lazy { ReviewCsvExporter(userDatabase) }

    /** JSON backup and merge-restore of synced data (G-10). */
    val backup: BackupService by lazy { BackupService(userDriver, userDatabase, srs, device.deviceId) }

    /** Restores a backup file and applies changed scheduler settings. */
    @Throws(Exception::class)
    suspend fun restoreBackup(path: String, onProgress: (RestoreProgress) -> Unit = {}): RestoreResult {
        configuredSrs()
        return backup.restoreFrom(platform.fileSystem, path, onProgress).also { if (it.changedSettings.isNotEmpty()) settingsChanged(it.changedSettings) }
    }

    /** The study report model the platforms render as PDF (G-10). */
    @Throws(Exception::class)
    suspend fun studyReport(periodDays: Int = 30): StudyReport =
        StudyReportBuilder(userDatabase, stats, { pathProgress.progress().passedLevel }, { exams().history(limit = 100) }).build(periodDays)

    /** Notion push (G-09); the token is in the keychain. */
    val notion: NotionExport by lazy { NotionExport(userDatabase, platform.secrets, { NotionClient(it, platform.httpEngine()) }) }

    /** Pushes the last [days] days of totals to the Notion stats database. */
    @Throws(Exception::class)
    suspend fun pushStatsToNotion(days: Int = 30, onProgress: (Int, Int) -> Unit = { _, _ -> }): NotionPushResult {
        val since = kotlin.time.Clock.System.now().toEpochMilliseconds() - days.coerceIn(1, 3650) * 86_400_000L
        val totals = DailyTotals.since(userDatabase, since, kotlinx.datetime.TimeZone.currentSystemDefault())
        val streaks = DailyTotals.streaks(totals)
        return notion.pushStats(totals.map { NotionDayStats(it.date.toString(), it.reviews, it.accuracy, streaks[it.date] ?: 0, it.minutes) }, onProgress)
    }

    /** Pushes items (all started items when [itemIds] is empty) to the Notion items database. */
    @Throws(Exception::class)
    suspend fun pushItemsToNotion(itemIds: List<String> = emptyList(), onProgress: (Int, Int) -> Unit = { _, _ -> }): NotionPushResult {
        val stages = srs.stages()
        val ids = itemIds.ifEmpty { stages.keys.toList() }
        return notion.pushItems(srs.items(ids).values.toList(), stages, onProgress)
    }

    /** AnkiConnect push of mined cards to desktop Anki (G-09); clips carry their cut audio. */
    val ankiConnect: AnkiConnectPush by lazy {
        AnkiConnectPush(deviceSettings, platform.secrets, { platform.httpEngine() }) { item -> clipAudio(item) }
    }

    /** A clip item's cut audio as base64 + file name for AnkiConnect, or null. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun clipAudio(item: StudyItem): Pair<String, String>? {
        val clip = clips.contextOf(item.context) ?: return null
        val rec = recordings.recordingsFor(RecordingKind.CLIP, clip.clipId).firstOrNull() ?: return null
        if (!recordings.fileExists(rec)) return null
        val bytes = kotlinx.coroutines.withContext(Dispatchers.Default) { platform.fileSystem.read(recordings.pathOf(rec).toPath()) { readByteArray() } }
        return kotlin.io.encoding.Base64.encode(bytes) to "tsumugi-${rec.fileName}"
    }

    /** Items the learner mined themselves (reader words, clips, personal cards), newest last, for the Anki push. */
    @Throws(Exception::class)
    suspend fun minedItems(): List<StudyItem> =
        srs.items(srs.allItemIds()).values.filter { it.source == ItemSource.USER }

    private var reviewSources: List<ReviewSource>? = null

    /** The in-app content review over the installed packs (G-16; the UI hides it behind a developer toggle). */
    @Throws(Exception::class)
    suspend fun contentReview(): ContentReviewService = ContentReviewService(userDatabase, {
        reviewSources ?: buildList {
            openPack(PackInstaller.GRAMMAR) { platform.packDriver(GrammarDatabase.Schema, PackInstaller.GRAMMAR) }?.let { add(GrammarReviewSource(it)) }
            openPack(PackInstaller.EXAM) { platform.packDriver(ExamDatabase.Schema, PackInstaller.EXAM) }?.let { add(ExamReviewSource(it)) }
            openPack(PackInstaller.PRACTICE) { platform.packDriver(PracticeDatabase.Schema, PackInstaller.PRACTICE) }?.let { add(PracticeReviewSource(it)) }
            add(KanaMnemonicReviewSource)
        }.also { reviewSources = it }
    })

    // --- Phase 11: media decks, coverage, known words, difficulty, 1T (BRIEF_V2 §6.1/§6.4/§6.11, D-150…D-159) ------

    /** What the learner knows (SRS Guru+ and marked words), cached until a review, import, sync or mark. */
    val knowledge: LearnerKnowledge by lazy { LearnerKnowledge(userDatabase, srs) }

    private val textProfiler: TextProfiler by lazy {
        TextProfiler({ reader.analyzer() }, { ids -> dictionary()?.wordStats(ids).orEmpty() })
    }

    /** Coverage overlay, library sort by coverage, §6.4 difficulty and 1T sentences. */
    val coverage: CoverageService by lazy {
        CoverageService(
            knowledge, textProfiler, TextProfileStore(userDatabase), { reader.documents() }, { reader.document(it) },
            mine = { entryId, sentence -> dictionary()?.entry(entryId)?.entry?.let { collection.addToReviews(it, sentence) } },
        )
    }

    /** "Mark known" and the onboarding "I know these" frequency bands. */
    val knownWords: KnownWords by lazy { KnownWords(userDatabase, knowledge, dictionary = { dictionary() }) }

    /** Media decks from documents, EPUBs, subtitles and text, plus the Core frequency decks. */
    val decks: MediaDeckService by lazy {
        MediaDeckService(
            userDatabase, knowledge, textProfiler, coverage, { dictionary() }, { reader.document(it) },
            { path -> kotlinx.coroutines.withContext(Dispatchers.Default) { platform.fileSystem.read(path.toPath()) { readByteArray() } } },
        )
    }

    /** Lessons from a deck, interleaved with the kanji path (a synced setting). */
    val deckLessons: DeckLessons by lazy {
        DeckLessons(userDatabase, settings, knowledge, { dictionary() }) { entry, context -> configuredSrs(); collection.addToReviews(entry, context) }
    }

    /** Rebuilds every card from its reviews with the current scheduler, reporting [recomputeProgress]. */
    fun recomputeAllInBackground(): Job {
        recomputeJob?.cancel()
        return background.launch {
            val srs = configuredSrs()
            srs.recomputeAll { done, total -> _recompute.value = RecomputeProgress(done, total, running = done < total) }
            _recompute.value = _recompute.value?.copy(running = false)
        }.also { recomputeJob = it }
    }
}
