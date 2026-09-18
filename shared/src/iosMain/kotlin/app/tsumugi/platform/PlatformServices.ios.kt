package app.tsumugi.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.integrations.anki.RawSqliteSchema
import co.touchlab.sqliter.JournalMode
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.UIKit.UIDevice

@OptIn(ExperimentalForeignApi::class)
actual class PlatformServices {
    actual val platformName: String =
        "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"

    /**
     * `Library/Application Support/Tsumugi` — never Documents (F-15). Large re-creatable folders under it (packs,
     * models, tts) are marked excluded from backup by [excludeFromBackup] when they're created; the user database
     * lives elsewhere (SQLiter's own folder) and is backed up.
     */
    actual val dataDir: Path = run {
        val base: NSURL = NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        )!!
        val dir = base.path!!.toPath() / "Tsumugi"
        FileSystem.SYSTEM.createDirectories(dir)
        listOf("packs", "models", "tts").forEach { name ->
            val sub = dir / name
            if (FileSystem.SYSTEM.exists(sub)) excludeFromBackup(sub)
        }
        dir
    }

    actual val fileSystem: FileSystem = FileSystem.SYSTEM

    actual fun openBundled(fileName: String): Source? {
        val path = bundledPackPath(fileName) ?: return null
        return FileSystem.SYSTEM.source(path)
    }

    /** The app bundle is a plain, read-only folder: packs open in place, no copy (F-16, D-052). */
    actual fun bundledPackPath(fileName: String): Path? {
        val resources = NSBundle.mainBundle.resourcePath ?: return null
        val path = resources.toPath() / "packs" / fileName
        return if (FileSystem.SYSTEM.exists(path)) path else null
    }

    actual fun userDatabaseDriver(): SqlDriver = NativeSqliteDriver(TsumugiDatabase.Schema, "tsumugi.db")

    actual fun openSqlite(path: String): SqlDriver {
        val p = path.toPath()
        return NativeSqliteDriver(RawSqliteSchema, p.name, onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(basePath = p.parent.toString()))
        })
    }

    actual val secrets: Secrets by lazy { SecretStore() }

    actual fun httpEngine(): HttpClientEngine = Darwin.create()

    /**
     * Bundled packs open in place from the read-only app bundle; SQLite then opens them read-only by itself (the
     * file isn't writable). SQLiter 1.3 exposes neither open flags nor URI filenames, so `?immutable=1` isn't
     * reachable; `journalMode = DELETE` keeps SQLiter from asking for WAL, which would need `-wal`/`-shm` files next
     * to the pack (D-052). Downloaded packs open from `dataDir/packs` the same way.
     */
    actual fun packDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver {
        val dir = bundledPackPath(fileName)?.parent?.toString() ?: (dataDir / "packs").toString()
        return NativeSqliteDriver(schema, fileName, onConfiguration = { config ->
            config.copy(
                journalMode = JournalMode.DELETE,
                extendedConfig = config.extendedConfig.copy(basePath = dir),
            )
        })
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun freeBytes(path: Path): Long? {
    var p: Path? = path
    while (p != null && !FileSystem.SYSTEM.exists(p)) p = p.parent
    val existing = p?.toString() ?: return null
    // "Important usage" counts space iOS would free by purging caches: the figure Apple recommends for downloads.
    val important = NSURL.fileURLWithPath(existing)
        .resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
        ?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber
    if (important != null && important.longLongValue > 0) return important.longLongValue
    val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(existing, null)
    return (attrs?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
}

@OptIn(ExperimentalForeignApi::class)
actual fun excludeFromBackup(path: Path) {
    if (!FileSystem.SYSTEM.exists(path)) return
    NSURL.fileURLWithPath(path.toString()).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping()
