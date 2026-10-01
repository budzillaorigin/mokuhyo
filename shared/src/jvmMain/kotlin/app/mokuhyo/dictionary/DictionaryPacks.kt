package app.mokuhyo.dictionary

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.mokuhyo.dictionary.db.DictionaryDatabase
import app.mokuhyo.lang.DictionaryPack
import java.io.File
import java.util.Properties

/** Opens built dictionary packs (`content/packs/<lang>/dictionary.sqlite`) read-only over JDBC SQLite. */
object DictionaryPacks {
    /** SQLITE_OPEN_READONLY: the pack is never written, and a missing file fails instead of being created. */
    private const val OPEN_READONLY = "1"

    /**
     * Opens [file] read-only. The pack's language comes from its `meta.language` row unless [language] is given.
     * Throws [IllegalArgumentException] for a missing file or a file without the dictionary schema.
     */
    fun open(file: File, language: String? = null): DictionaryPack {
        require(file.isFile) { "dictionary pack not found: $file" }
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${file.absolutePath}",
            Properties().apply { put("open_mode", OPEN_READONLY) },
        )
        val db = DictionaryDatabase(driver)
        val lang = language ?: runCatching {
            db.dictionaryQueries.allMeta().executeAsList().firstOrNull { it.key == "language" }?.value_
        }.getOrElse { e ->
            driver.close()
            throw IllegalArgumentException("$file is not a dictionary pack", e)
        } ?: run {
            driver.close()
            throw IllegalArgumentException("$file has no meta.language")
        }
        return SqlDictionaryPack(db, lang)
    }
}
