package app.tsumugi.api

import app.tsumugi.content.PackInstaller
import app.tsumugi.content.PackStatus
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.path.db.PathDatabase
import app.tsumugi.platform.PlatformServices
import app.tsumugi.settings.DeviceState
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.FsrsParameters
import app.tsumugi.srs.FsrsScheduler
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.LessonSession
import app.tsumugi.study.ReviewSession
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

    private val lock = Mutex()
    private var dictionaryRepository: DictionaryRepository? = null
    private var pathService: PathService? = null
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
        val srs = srs()
        return lock.withLock {
            pathService ?: openPack(PackInstaller.KANJI_PATH) {
                PathService(PathDatabase(platform.packDriver(PathDatabase.Schema, PackInstaller.KANJI_PATH)), srs, settings)
            }?.also { pathService = it }
        }
    }

    /** SRS repository with the user's scheduler settings (fitted FSRS weights, desired retention) applied. */
    suspend fun srs(): SrsRepository {
        if (!schedulerLoaded) {
            srs.scheduler = FsrsScheduler(schedulerParameters())
            schedulerLoaded = true
        }
        return srs
    }

    suspend fun startReviews(limit: Int = 500): ReviewSession = ReviewSession.start(srs(), limit)

    suspend fun startLessons(): LessonSession? {
        val path = path() ?: return null
        val batch = path.lessonQueue(settings.int(SettingsRepository.LESSON_BATCH_SIZE, DEFAULT_LESSON_BATCH))
        return if (batch.isEmpty()) null else LessonSession(path, batch)
    }

    private suspend fun schedulerParameters(): FsrsParameters {
        val retention = settings.get(SettingsRepository.DESIRED_RETENTION)?.toDoubleOrNull() ?: 0.9
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

    companion object {
        const val DEFAULT_LESSON_BATCH = 5
    }
}
