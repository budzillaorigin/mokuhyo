package app.tsumugi.api

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
class AppGraph(val platform: PlatformServices) {

    val packs = PackInstaller(platform)

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
        val status = path()?.status()
        return planner.plan(srs.dueCount(), status, grammarLeft, todayCandidates.collect(status?.currentLevel))
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
