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
import app.tsumugi.settings.DeviceState
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.FsrsParameters
import app.tsumugi.srs.FsrsScheduler
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.LessonSession
import app.tsumugi.study.Onboarding
import app.tsumugi.study.ReminderPlanner
import app.tsumugi.study.ReviewSession
import app.tsumugi.study.StatsService
import app.tsumugi.study.TodayPlan
import app.tsumugi.study.TodayPlanner
import app.tsumugi.study.WritingService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Composition root both apps hold one instance of.
 * Swift: `AppGraph(platform: PlatformServices())`; Android: `AppGraph(PlatformServices(applicationContext))`.
 */
class AppGraph(val platform: PlatformServices) {

    val packs = PackInstaller(platform)

    private val userDriver by lazy { platform.userDatabaseDriver() }
    val userDatabase: TsumugiDatabase by lazy { TsumugiDatabase(userDriver) }
    val device: DeviceState by lazy { DeviceState(userDatabase) }
    val settings: SettingsRepository by lazy { SettingsRepository(userDatabase) }
    val srs: SrsRepository by lazy { SrsRepository(userDatabase, device.deviceId) }
    val stats: StatsService by lazy { StatsService(userDatabase, srs, settings) }
    val imports: ImportService by lazy { ImportService(this) }
    val reminders: ReminderPlanner by lazy { ReminderPlanner(userDatabase, settings) }
    val collection: CollectionService by lazy { CollectionService(userDatabase, srs, { path() }) }
    val reader: ReaderService by lazy { ReaderService(this) }
    val onboarding: Onboarding by lazy { Onboarding(settings) { path() } }

    /** On-device / self-hosted AI engines (BRIEF §7). Apps set `ai.llmBridge` / `ai.sttBridge` at startup. */
    val ai: AiService by lazy { AiService(platform, settings) }
    val pronunciation: PronunciationService by lazy { PronunciationService({ analyzer() }, { dictionary() }) }

    /** Optional self-hostable sync (BRIEF §8). Nothing syncs until the learner signs in. */
    val syncAccount: SyncAccount by lazy { SyncAccount(userDatabase, platform.secrets, { platform.httpEngine() }) }
    private var syncEngine: SyncEngine? = null

    /** The sync engine for the signed-in account, or null when sync isn't set up. */
    @Throws(Exception::class)
    suspend fun sync(): SyncEngine? {
        val client = syncAccount.client() ?: return null
        val srs = configuredSrs()
        return lock.withLock {
            (syncEngine ?: SyncEngine(userDriver, userDatabase, srs, device.deviceId, client).also { syncEngine = it })
                .also { it.sealer = syncAccount.sealer }
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

    /** Today's plan (BRIEF §5.6) from the current queue, path and grammar state. */
    @Throws(Exception::class)
    suspend fun today(): TodayPlan {
        val srs = configuredSrs()
        val grammarLeft = grammar()?.lessonQueue(3)?.size ?: 0
        return planner.plan(srs.dueCount(), path()?.status(), grammarLeft)
    }

    private var writingService: WritingService? = null
    private var tokenizer: LatticeTokenizer? = null

    /** The IPADIC lattice analyzer (BRIEF §5.2), or null when the tokenizer pack isn't installed. */
    @Throws(Exception::class)
    suspend fun analyzer(): MorphologicalAnalyzer? = lock.withLock {
        tokenizer ?: openPack(PackInstaller.TOKENIZER) {
            LatticeTokenizer(TokenizerDatabase(platform.packDriver(TokenizerDatabase.Schema, PackInstaller.TOKENIZER)))
        }?.also { tokenizer = it }
    }

    /** Writing practice and handwriting search, or null without the dictionary pack (it holds KanjiVG). */
    @Throws(Exception::class)
    suspend fun writing(): WritingService? {
        val dictionary = dictionary() ?: return null
        val srs = configuredSrs()
        return lock.withLock { writingService ?: WritingService(dictionary, srs).also { writingService = it } }
    }

    /** Next grammar lesson batch (1–3 points) or empty when the pack is missing or everything is learned. */
    @Throws(Exception::class)
    suspend fun grammarLessons(): List<GrammarPoint> = grammar()?.lessonQueue((settings.int(SettingsRepository.DAILY_BUDGET_MINUTES, TodayPlanner.DEFAULT_BUDGET) / 20).coerceIn(1, 3)).orEmpty()

    private var practiceRepository: PracticeRepository? = null
    private var examService: ExamService? = null

    /** Speaking/listening practice pack (scenarios, OPI banks, dialogues, minimal pairs), or null when missing. */
    @Throws(Exception::class)
    suspend fun practice(): PracticeRepository? = lock.withLock {
        practiceRepository ?: openPack(PackInstaller.PRACTICE) {
            PracticeRepository(PracticeDatabase(platform.packDriver(PracticeDatabase.Schema, PackInstaller.PRACTICE)))
        }?.also { practiceRepository = it }
    }

    /** Exam simulators. Works without the exam pack too (imported banks, history), so never null. */
    @Throws(Exception::class)
    suspend fun exams(): ExamService {
        lock.withLock { examService }?.let { return it }
        val pack = lock.withLock { openPack(PackInstaller.EXAM) { ExamDatabase(platform.packDriver(ExamDatabase.Schema, PackInstaller.EXAM)) } }
        configuredSrs() // "add missed items to SRS" schedules with the learner's settings
        return lock.withLock {
            examService ?: ExamService(pack, userDatabase, device.deviceId, { grammar() }, { dictionary() }, collection).also { examService = it }
        }
    }

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

    private val lock = Mutex()
    private var dictionaryRepository: DictionaryRepository? = null
    private var pathService: PathService? = null
    private var grammarService: GrammarService? = null
    private var schedulerLoaded = false

    /**
     * The dictionary, installing the bundled pack on first use. Returns null when no dictionary pack is
     * available, so screens can show an honest "dictionary not installed" state.
     */
    @Throws(Exception::class)
    suspend fun dictionary(): DictionaryRepository? = lock.withLock {
        dictionaryRepository ?: openPack(PackInstaller.DICTIONARY) {
            DictionaryRepository(DictionaryDatabase(platform.packDriver(DictionaryDatabase.Schema, PackInstaller.DICTIONARY)))
        }?.also { dictionaryRepository = it }
    }

    /** The 60-level kanji path, or null when the path pack isn't installed. */
    @Throws(Exception::class)
    suspend fun path(): PathService? {
        val srs = configuredSrs()
        return lock.withLock {
            pathService ?: openPack(PackInstaller.KANJI_PATH) {
                PathService(PathDatabase(platform.packDriver(PathDatabase.Schema, PackInstaller.KANJI_PATH)), srs, settings)
            }?.also { pathService = it }
        }
    }

    /** SRS repository with the user's scheduler settings (fitted FSRS weights, desired retention) applied. */
    @Throws(Exception::class)
    suspend fun configuredSrs(): SrsRepository {
        if (!schedulerLoaded) {
            srs.scheduler = FsrsScheduler(schedulerParameters())
            schedulerLoaded = true
        }
        return srs
    }

    /** The grammar pack service, or null when the grammar pack isn't installed. */
    @Throws(Exception::class)
    suspend fun grammar(): GrammarService? {
        val srs = configuredSrs()
        return lock.withLock {
            grammarService ?: openPack(PackInstaller.GRAMMAR) {
                GrammarService(GrammarDatabase(platform.packDriver(GrammarDatabase.Schema, PackInstaller.GRAMMAR)), srs) { dictionary() }
            }?.also { grammarService = it }
        }
    }

    @Throws(Exception::class)
    suspend fun startReviews(limit: Int = 500): ReviewSession = ReviewSession.start(configuredSrs(), grammar(), limit)

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

    private suspend fun <T> openPack(file: String, open: () -> T): T? =
        when (packs.ensureInstalled(file)) {
            is PackStatus.Installed -> open()
            PackStatus.Missing -> null
        }

    /** Call after changing scheduler settings so the next review uses them. */
    fun reloadScheduler() {
        schedulerLoaded = false
    }
}
