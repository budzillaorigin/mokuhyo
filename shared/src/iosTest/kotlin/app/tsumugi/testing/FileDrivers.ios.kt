package app.tsumugi.testing

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tsumugi.integrations.anki.RawSqliteSchema
import okio.FileSystem
import okio.Path
import kotlin.random.Random

actual fun fileDriver(path: String): SqlDriver {
    val dir = path.substringBeforeLast('/')
    val name = path.substringAfterLast('/')
    return NativeSqliteDriver(RawSqliteSchema, name, onConfiguration = { config ->
        config.copy(extendedConfig = config.extendedConfig.copy(basePath = dir))
    })
}

actual val testFileSystem: FileSystem = FileSystem.SYSTEM

actual fun newTempDir(prefix: String): Path =
    (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "$prefix-${Random.nextLong().toULong()}").also { FileSystem.SYSTEM.createDirectories(it) }
