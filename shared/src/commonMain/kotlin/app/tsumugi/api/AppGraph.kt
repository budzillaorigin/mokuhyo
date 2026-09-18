package app.tsumugi.api

import app.tsumugi.content.PackInstaller
import app.tsumugi.content.PackStatus
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
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
import app.tsumugi.settings.DeviceState
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.FsrsParameters
import app.tsumugi.srs.FsrsScheduler
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.LessonSession
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

    val userDatabase: TsumugiDatabase by lazy { TsumugiDatabase(platform.userDatabaseDriver()) }
    val device: DeviceState by lazy { DeviceState(userDatabase) }
    val settings: SettingsRepository by lazy { SettingsRepository(userDatabase) }
    val srs: SrsRepository by lazy { SrsRepository(userDatabase, device.deviceId) }
    val stats: StatsService by lazy { StatsService(userDatabase, srs, settings) }
    val imports: ImportService by lazy { ImportService(this) }
    val reminders: ReminderPlanner by lazy { ReminderPlanner(userDatabase, settings) }
    val collection: CollectionService by lazy { CollectionService(userDatabase, srs, { path() }) }
    val reader: ReaderService by lazy { ReaderService(this) }
    private val planner: TodayPlanner by lazy { TodayPlanner(userDatabase, settings) }

    /** Today's plan (BRIEF §5.6) from the current queue, path and grammar state. */
    suspend fun today(): TodayPlan {
        val srs = configuredSrs()
        val grammarLeft = grammar()?.lessonQueue(3)?.size ?: 0
        return planner.plan(srs.dueCount(), path()?.status(), grammarLeft)
    }

    private var writingService: WritingService? = null
    private var tokenizer: LatticeTokenizer? = null

    /** The IPADIC lattice analyzer (BRIEF §5.2), or null when the tokenizer pack isn't installed. */
    suspend fun analyzer(): MorphologicalAnalyzer? = lock.withLock {
        tokenizer ?: openPack(PackInstaller.TOKENIZER) {
            LatticeTokenizer(TokenizerDatabase(platform.packDriver(TokenizerDatabase.Schema, PackInstaller.TOKENIZER)))
        }?.also { tokenizer = it }
    }

    /** Writing practice and handwriting search, or null without the dictionary pack (it holds KanjiVG). */
    suspend fun writing(): WritingService? {
        val dictionary = dictionary() ?: return null
        val srs = configuredSrs()
        return lock.withLock { writingService ?: WritingService(dictionary, srs).also { writingService = it } }
    }

    /** Next grammar lesson batch (1–3 points) or empty when the pack is missing or everything is learned. */
    suspend fun grammarLessons(): List<GrammarPoint> = grammar()?.lessonQueue((settings.int(SettingsRepository.DAILY_BUDGET_MINUTES, TodayPlanner.DEFAULT_BUDGET) / 20).coerceIn(1, 3)).orEmpty()

    private val lock = Mutex()
    private var dictionaryRepository: DictionaryRepository? = null
    private var pathService: PathService? = null
    private var grammarService: GrammarService? = null
    private var schedulerLoaded = false

    /**
     * The dictionary, installing the bundled pack on first use. Returns null when no dictionary pack is
     * available, so screens can show an honest "dictionary not installed" state.
     */
    suspend fun dictionary(): DictionaryRepository? = lock.withLock {
        dictionaryRepository ?: openPack(PackInstaller.DICTIONARY) {
            DictionaryRepository(DictionaryDatabase(platform.packDriver(DictionaryDatabase.Schema, PackInstaller.DICTIONARY)))
        }?.also { dictionaryRepository = it }
    }

    /** The 60-level kanji path, or null when the path pack isn't installed. */
    suspend fun path(): PathService? {
        val srs = configuredSrs()
        return lock.withLock {
            pathService ?: openPack(PackInstaller.KANJI_PATH) {
                PathService(PathDatabase(platform.packDriver(PathDatabase.Schema, PackInstaller.KANJI_PATH)), srs, settings)
            }?.also { pathService = it }
        }
    }

    /** SRS repository with the user's scheduler settings (fitted FSRS weights, desired retention) applied. */
    suspend fun configuredSrs(): SrsRepository {
        if (!schedulerLoaded) {
            srs.scheduler = FsrsScheduler(schedulerParameters())
            schedulerLoaded = true
        }
        return srs
    }

    /** The grammar pack service, or null when the grammar pack isn't installed. */
    suspend fun grammar(): GrammarService? {
        val srs = configuredSrs()
        return lock.withLock {
            grammarService ?: openPack(PackInstaller.GRAMMAR) {
                GrammarService(GrammarDatabase(platform.packDriver(GrammarDatabase.Schema, PackInstaller.GRAMMAR)), srs) { dictionary() }
            }?.also { grammarService = it }
        }
    }

    suspend fun startReviews(limit: Int = 500): ReviewSession = ReviewSession.start(configuredSrs(), grammar(), limit)

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
