package app.tsumugi.integrations.anki

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A no-op schema for opening foreign SQLite files (Anki collections) with a platform driver:
 * Android `AndroidSqliteDriver(RawSqliteSchema, context, path)`, iOS `NativeSqliteDriver(RawSqliteSchema, name, …)`.
 */
object RawSqliteSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Unit
}

data class AnkiTemplate(val ord: Int, val name: String, val qfmt: String, val afmt: String)

data class AnkiNotetype(
    val id: Long,
    val name: String,
    val isCloze: Boolean,
    val fields: List<String>,
    val templates: List<AnkiTemplate>,
    val css: String,
)

data class AnkiNote(val id: Long, val guid: String, val mid: Long, val mod: Long, val tags: String, val fields: List<String>)

data class AnkiCard(val id: Long, val nid: Long, val did: Long, val ord: Int, val type: Int, val queue: Int, val due: Long, val ivl: Int, val reps: Int, val lapses: Int)

data class AnkiRevlog(val id: Long, val cid: Long, val ease: Int, val ivl: Int, val lastIvl: Int, val factor: Int, val time: Int, val type: Int)

/** The parts of an Anki collection Tsumugi reads, from either the legacy (schema 11) or the modern (18) layout. */
data class AnkiCollection(
    val notetypes: Map<Long, AnkiNotetype>,
    val decks: Map<Long, String>,
    val notes: List<AnkiNote>,
    val cards: List<AnkiCard>,
    val revlog: List<AnkiRevlog>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(driver: SqlDriver): AnkiCollection {
            val tables = driver.rows("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(0)!! }.toSet()
            val modern = "notetypes" in tables
            val notetypes = if (modern) modernNotetypes(driver) else legacyNotetypes(driver)
            val decks = if ("decks" in tables && modern) {
                driver.rows("SELECT id, name FROM decks") { it.getLong(0)!! to it.getString(1)!!.replace("\u001F", "::") }.toMap()
            } else {
                legacyDecks(driver)
            }
            val notes = driver.rows("SELECT id, guid, mid, mod, tags, flds FROM notes ORDER BY id") {
                AnkiNote(it.getLong(0)!!, it.getString(1)!!, it.getLong(2)!!, it.getLong(3)!!, it.getString(4)!!, it.getString(5)!!.split('\u001F'))
            }
            val cards = driver.rows("SELECT id, nid, did, ord, type, queue, due, ivl, reps, lapses FROM cards ORDER BY id") {
                AnkiCard(
                    it.getLong(0)!!, it.getLong(1)!!, it.getLong(2)!!, it.getLong(3)!!.toInt(), it.getLong(4)!!.toInt(),
                    it.getLong(5)!!.toInt(), it.getLong(6)!!, it.getLong(7)!!.toInt(), it.getLong(8)!!.toInt(), it.getLong(9)!!.toInt(),
                )
            }
            val revlog = driver.rows("SELECT id, cid, ease, ivl, lastIvl, factor, time, type FROM revlog ORDER BY id") {
                AnkiRevlog(
                    it.getLong(0)!!, it.getLong(1)!!, it.getLong(2)!!.toInt(), it.getLong(3)!!.toInt(), it.getLong(4)!!.toInt(),
                    it.getLong(5)!!.toInt(), it.getLong(6)!!.toInt(), it.getLong(7)!!.toInt(),
                )
            }
            return AnkiCollection(notetypes, decks, notes, cards, revlog)
        }

        private fun legacyNotetypes(driver: SqlDriver): Map<Long, AnkiNotetype> {
            val models = driver.rows("SELECT models FROM col") { it.getString(0)!! }.firstOrNull().orEmpty()
            if (models.isBlank()) return emptyMap()
            return json.parseToJsonElement(models).jsonObject.values.map { it.jsonObject }.associate { m ->
                val id = m.getValue("id").jsonPrimitive.long
                id to AnkiNotetype(
                    id = id,
                    name = m.getValue("name").jsonPrimitive.content,
                    isCloze = (m["type"]?.jsonPrimitive?.int ?: 0) == 1,
                    fields = (m["flds"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content },
                    templates = (m["tmpls"] as? JsonArray).orEmpty().mapIndexed { i, t ->
                        val o = t.jsonObject
                        AnkiTemplate(
                            o["ord"]?.jsonPrimitive?.int ?: i,
                            o["name"]?.jsonPrimitive?.content.orEmpty(),
                            o["qfmt"]?.jsonPrimitive?.content.orEmpty(),
                            o["afmt"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    },
                    css = m["css"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }

        private fun legacyDecks(driver: SqlDriver): Map<Long, String> {
            val decks = driver.rows("SELECT decks FROM col") { it.getString(0)!! }.firstOrNull().orEmpty()
            if (decks.isBlank()) return emptyMap()
            return json.parseToJsonElement(decks).jsonObject.values.associate {
                it.jsonObject.getValue("id").jsonPrimitive.long to it.jsonObject.getValue("name").jsonPrimitive.content
            }
        }

        /**
         * Schema 18: notetypes(id, name, config), fields(ntid, ord, name), templates(ntid, ord, name, config).
         * NotetypeConfig field 1 = kind (1 = cloze), field 3 = css; TemplateConfig 1 = q_format, 2 = a_format.
         */
        private fun modernNotetypes(driver: SqlDriver): Map<Long, AnkiNotetype> {
            val fields = driver.rows("SELECT ntid, name FROM fields ORDER BY ntid, ord") { it.getLong(0)!! to it.getString(1)!! }
                .groupBy({ it.first }, { it.second })
            val templates = driver.rows("SELECT ntid, ord, name, config FROM templates ORDER BY ntid, ord") {
                var q = ""
                var a = ""
                it.getBytes(3)?.let { cfg ->
                    ProtoReader(cfg).forEachField { f, v ->
                        if (v is ByteArray && f == 1) q = v.decodeToString()
                        if (v is ByteArray && f == 2) a = v.decodeToString()
                    }
                }
                it.getLong(0)!! to AnkiTemplate(it.getLong(1)!!.toInt(), it.getString(2)!!, q, a)
            }.groupBy({ it.first }, { it.second })
            return driver.rows("SELECT id, name, config FROM notetypes") {
                var cloze = false
                var css = ""
                it.getBytes(2)?.let { cfg ->
                    ProtoReader(cfg).forEachField { f, v ->
                        if (f == 1 && v is Long) cloze = v == 1L
                        if (f == 3 && v is ByteArray) css = v.decodeToString()
                    }
                }
                val id = it.getLong(0)!!
                id to AnkiNotetype(id, it.getString(1)!!, cloze, fields[id].orEmpty(), templates[id].orEmpty(), css)
            }.toMap()
        }
    }
}

internal fun <T> SqlDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> =
    executeQuery(null, sql, { cursor ->
        val out = ArrayList<T>()
        while (cursor.next().value) out += map(cursor)
        QueryResult.Value(out)
    }, 0).value

internal fun SqlDriver.exec(sql: String, binder: (SqlPreparedStatement.() -> Unit)? = null, params: Int = 0) {
    execute(null, sql, params, binder)
}

/** Anki's legacy (schema 11) collection layout, which every current Anki version still imports. */
internal object AnkiSchema11 {
    val statements = listOf(
        """CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null,
           ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null,
           models text not null, decks text not null, dconf text not null, tags text not null)""",
        """CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null,
           usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null,
           flags integer not null, data text not null)""",
        """CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null,
           mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null,
           ivl integer not null, factor integer not null, reps integer not null, lapses integer not null,
           left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null)""",
        """CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null,
           ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)""",
        "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)",
        "CREATE INDEX ix_notes_usn ON notes (usn)",
        "CREATE INDEX ix_cards_usn ON cards (usn)",
        "CREATE INDEX ix_revlog_usn ON revlog (usn)",
        "CREATE INDEX ix_cards_nid ON cards (nid)",
        "CREATE INDEX ix_cards_sched ON cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid ON revlog (cid)",
        "CREATE INDEX ix_notes_csum ON notes (csum)",
    )
}
