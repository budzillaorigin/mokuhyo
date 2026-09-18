package app.tsumugi.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tsumugi.db.TsumugiDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.UIKit.UIDevice

@OptIn(ExperimentalForeignApi::class)
actual class PlatformServices {
    actual val platformName: String =
        "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"

    actual val dataDir: Path = run {
        val base: NSURL = NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        )!!
        val dir = base.path!!.toPath() / "Tsumugi"
        FileSystem.SYSTEM.createDirectories(dir)
        dir
    }

    actual val fileSystem: FileSystem = FileSystem.SYSTEM

    actual fun openBundled(fileName: String): Source? {
        val resources = NSBundle.mainBundle.resourcePath ?: return null
        val path = resources.toPath() / "packs" / fileName
        return if (FileSystem.SYSTEM.exists(path)) FileSystem.SYSTEM.source(path) else null
    }

    actual fun userDatabaseDriver(): SqlDriver = NativeSqliteDriver(TsumugiDatabase.Schema, "tsumugi.db")

    actual fun packDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver {
        val packsDir = (dataDir / "packs").toString()
        return NativeSqliteDriver(schema, fileName, onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(basePath = packsDir))
        })
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping()
