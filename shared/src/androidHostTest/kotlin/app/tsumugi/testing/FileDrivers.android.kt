package app.tsumugi.testing

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import okio.FileSystem
import okio.Path
import kotlin.random.Random

actual fun fileDriver(path: String): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$path")

actual val testFileSystem: FileSystem = FileSystem.SYSTEM

actual fun newTempDir(prefix: String): Path =
    (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "$prefix-${Random.nextLong().toULong()}").also { FileSystem.SYSTEM.createDirectories(it) }
