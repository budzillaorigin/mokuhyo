package app.mokuhyo.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/** Opens (creating or migrating) the learner database at [file]. WAL mode; foreign keys on. */
object DatabaseFactory {
    fun open(file: File): Pair<SqlDriver, MokuhyoDatabase> {
        file.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties().apply { put("foreign_keys", "true") })
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        migrate(driver)
        return driver to MokuhyoDatabase(driver)
    }

    fun inMemory(): Pair<SqlDriver, MokuhyoDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        migrate(driver)
        return driver to MokuhyoDatabase(driver)
    }

    private fun migrate(driver: SqlDriver) {
        val current = driver.executeQuery(null, "PRAGMA user_version", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
        }, 0).value
        val target = MokuhyoDatabase.Schema.version
        when {
            current == 0L -> MokuhyoDatabase.Schema.create(driver)
            current < target -> MokuhyoDatabase.Schema.migrate(driver, current, target)
            current > target -> error("This database was written by a newer Mokuhyo (schema $current > $target). Update the app.")
        }
        if (current != target) driver.execute(null, "PRAGMA user_version = $target", 0)
    }
}
