package app.tsumugi.api

import app.tsumugi.content.PackInstaller
import app.tsumugi.content.PackStatus
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.platform.PlatformServices
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Composition root both apps hold one instance of.
 * Swift: `AppGraph(platform: PlatformServices())`; Android: `AppGraph(PlatformServices(applicationContext))`.
 */
class AppGraph(val platform: PlatformServices) {

    val packs = PackInstaller(platform)

    val userDatabase: TsumugiDatabase by lazy { TsumugiDatabase(platform.userDatabaseDriver()) }

    private val dictionaryLock = Mutex()
    private var dictionaryRepository: DictionaryRepository? = null

    /**
     * The dictionary, installing the bundled pack on first use. Returns null when no dictionary pack is
     * available, so screens can show an honest "dictionary not installed" state.
     */
    suspend fun dictionary(): DictionaryRepository? = dictionaryLock.withLock {
        dictionaryRepository ?: when (packs.ensureInstalled(PackInstaller.DICTIONARY)) {
            is PackStatus.Installed -> DictionaryRepository(
                DictionaryDatabase(platform.packDriver(DictionaryDatabase.Schema, PackInstaller.DICTIONARY)),
            ).also { dictionaryRepository = it }
            PackStatus.Missing -> null
        }
    }
}
