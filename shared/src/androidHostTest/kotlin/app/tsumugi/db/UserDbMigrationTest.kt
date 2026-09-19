package app.tsumugi.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.srs.PathProgressStore
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v1 → v2 user-database migration (1.sqm, DECISIONS D-040) on a copy of the real v1 schema snapshot, with the
 * kind of data the owner's test phone holds.
 */
class UserDbMigrationTest {

    private fun v1Copy(): SqlDriver {
        val snapshot = File("src/commonMain/sqldelight/databases/1.db")
        val copy = File.createTempFile("tsumugi-v1-", ".db").also { it.deleteOnExit() }
        snapshot.copyTo(copy, overwrite = true)
        return JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
    }

    private fun SqlDriver.exec(sql: String) = execute(null, sql, 0)

    private fun SqlDriver.long(sql: String): Long? =
        executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value

    @Test
    fun migratesV1DataOnce() = runTest {
        val driver = v1Copy()
        driver.exec("INSERT INTO item(id, kind, primary_text, meanings, source, created_at, updated_at) VALUES ('k:水', 'KANJI', '水', '[]', 'pack', 0, 0)")
        driver.exec("INSERT INTO card(id, item_id, direction, state, due, created_at, updated_at) VALUES ('k:水#MEANING', 'k:水', 'MEANING', 'REVIEW', 0, 0, 0)")
        // Two answers in one quarter-hour, one in the next day, plus a lesson introduction (not counted).
        driver.exec("INSERT INTO review(id, card_id, ts, rating, elapsed_ms, correct, device_id) VALUES ('r1', 'k:水#MEANING', 1000, 3, 4000, 1, 'd')")
        driver.exec("INSERT INTO review(id, card_id, ts, rating, elapsed_ms, correct, device_id) VALUES ('r2', 'k:水#MEANING', 2000, 1, 6000, 0, 'd')")
        driver.exec("INSERT INTO review(id, card_id, ts, rating, elapsed_ms, correct, device_id) VALUES ('r3', 'k:水#MEANING', 86400000, 3, 1000, NULL, 'd')")
        driver.exec("INSERT INTO review(id, card_id, ts, rating, elapsed_ms, correct, device_id) VALUES ('r0', 'k:水#MEANING', 500, 0, 0, NULL, 'd')")
        driver.exec("INSERT INTO setting(key, value, updated_at) VALUES ('ai.endpoint_url', 'http://<lan-ip>:8080', 5)")
        driver.exec("INSERT INTO setting(key, value, updated_at) VALUES ('ai.llm', 'ENDPOINT', 5)")
        driver.exec("INSERT INTO setting(key, value, updated_at) VALUES ('path.levelFloor', '7', 9)")
        driver.exec("INSERT INTO setting(key, value, updated_at) VALUES ('today.budgetMinutes', '40', 5)")
        val markersBefore = driver.long("SELECT count(*) FROM change_log")!!

        TsumugiDatabase.Schema.migrate(driver, 1, TsumugiDatabase.Schema.version)
        assertEquals(6L, TsumugiDatabase.Schema.version)
        val db = TsumugiDatabase(driver)

        // daily_stats backfilled from the log.
        val slots = db.srsQueries.statsSlotsSince(0).executeAsList()
        assertEquals(listOf(0L to 2L, 96L to 1L), slots.map { it.slot to it.reviews })
        assertEquals(1L, slots[0].correct)
        assertEquals(2L, slots[0].graded)
        assertEquals(10_000L, slots[0].elapsed_ms)

        // Device-local keys moved out of the synced table, without syncing a delete.
        val device = DeviceSettings(db)
        assertEquals("http://<lan-ip>:8080", device.get("ai.endpoint_url"))
        assertEquals("ENDPOINT", device.get("ai.llm"))
        assertNull(db.userQueries.settingValue("ai.endpoint_url").executeAsOneOrNull())
        assertEquals("40", db.userQueries.settingValue("today.budgetMinutes").executeAsOne())
        assertEquals(0L, driver.long("SELECT count(*) FROM change_log WHERE op = 'DELETE'"))

        // The old level floor is now the persisted passed level (and will sync).
        assertEquals(6, PathProgressStore(db).progress().passedLevel)
        assertEquals(markersBefore + 1, driver.long("SELECT count(*) FROM change_log"))

        // New columns and triggers work.
        db.srsQueries.tombstoneReview(99, "r2")
        assertEquals(1L, db.srsQueries.statsSlotsSince(0).executeAsList().first().reviews)
        assertEquals(0L, driver.long("SELECT applying FROM sync_state"))
        assertEquals(0L, driver.long("SELECT pushing FROM sync_state"))
        assertNull(db.srsQueries.cardById("k:水#MEANING").executeAsOne().blocked_reason)

        // v2 -> v3 (2.sqm): the device-local in-progress exam table (F-24).
        assertNull(db.examAttemptQueries.inProgress().executeAsOneOrNull())

        // v3 -> v4 (3.sqm): conversations, finished Today blocks and streak freezes, all union-synced (G-01/G-02/G-11).
        val before = driver.long("SELECT count(*) FROM change_log")!!
        db.conversationQueries.insertConversation("c1", "FREE_TALK", null, 0, 1, "N4", "[]", null, "[]", null, "d")
        db.studyQueries.markBlockDone("2026-09-18", "SHADOWING", 5)
        db.studyQueries.insertFreeze("2026-09-19", "FREEZE", 5)
        assertEquals(before + 3, driver.long("SELECT count(*) FROM change_log"))
        assertEquals(1, db.conversationQueries.recentConversations(10).executeAsList().size)

        // v4 -> v5 (4.sqm, D-110…D-118): device-local media tables, reader ruby, content-review verdicts; none syncs.
        assertTrue(db.mediaQueries.allRecordings().executeAsList().isEmpty())
        assertNull(db.readerQueries.questionsFor("x").executeAsOneOrNull())
        assertTrue(db.contentReviewQueries.allVerdicts().executeAsList().isEmpty())
        driver.exec("INSERT INTO recording(id, kind, file_name, mime, duration_ms, size_bytes, origin_device, created_at) VALUES ('r', 'FREE', 'r.m4a', 'audio/mp4', 1, 1, 'd', 0)")
        assertEquals(0L, driver.long("SELECT count(*) FROM change_log WHERE table_name = 'recording'"))

        // v5 -> v6 (5.sqm, D-160…D-169): the immersion log and reader annotations sync; the sentence bank, lyrics,
        // document extras and per-document word lists are device-local.
        db.immersionQueries.insertImmersion("i1", "2026-09-18", "MEDIA", "ACTIVE", 0, 600, null, null, "d", 0)
        db.immersionQueries.tombstoneImmersion(5, "i1")
        db.readerNotesQueries.putAnnotation("a1", "url:x", "HIGHLIGHT", 0, 1, "猫", "", null, null, 0, 0)
        assertEquals(2L, driver.long("SELECT count(*) FROM change_log WHERE table_name = 'immersion_session'"))
        assertEquals(1L, driver.long("SELECT count(*) FROM change_log WHERE table_name = 'reader_annotation'"))
        db.immersionQueries.putMediaIndex("m", "t", "VIDEO", null, "FILE", 0, 0)
        db.readerNotesQueries.insertDocVocab("doc", "text:猫|ねこ", "猫", "ねこ", "cat", null, "猫だ", 0, 1, 0, 0)
        db.immersionQueries.putSong("s", "t", null, "x", null, "NONE", "[]", 0, 0)
        assertEquals(3L, driver.long("SELECT count(*) FROM change_log WHERE table_name IN ('immersion_session', 'reader_annotation')"))
        assertEquals(0L, driver.long("SELECT count(*) FROM change_log WHERE table_name IN ('media_index', 'reader_doc_vocab', 'lyrics_song')"))
    }
}
