package app.mokuhyo.testing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.nio.file.Files

actual fun inMemoryDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { schema.create(it) }

actual fun fileDriver(path: String): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$path")

actual val testFileSystem: FileSystem = FileSystem.SYSTEM

actual fun newTempDir(prefix: String): Path = Files.createTempDirectory(prefix).toOkioPath()

actual val perfScale: Int = 1

/** A file under the repository root (found by walking up from the working directory). */
fun repoFile(relative: String): java.io.File {
    var dir: java.io.File? = java.io.File("").absoluteFile
    while (dir != null) {
        val f = java.io.File(dir, relative)
        if (f.exists()) return f
        dir = dir.parentFile
    }
    error("$relative not found above ${java.io.File("").absolutePath}")
}
