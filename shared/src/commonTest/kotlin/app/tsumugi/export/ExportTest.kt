package app.tsumugi.export

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.exam.AttemptScoring
import app.tsumugi.exam.AttemptSummary
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamMode
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.PathProgressStore
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.StatsService
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ExportTest {
    private val clock = TestClock()

    private inner class Device(val id: String) {
        val driver: SqlDriver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val srs = SrsRepository(db, id, clock)
        val settings = SettingsRepository(db, clock)
        val progress = PathProgressStore(db, clock)
        val backup = BackupService(driver, db, srs, id, clock)

        fun long(sql: String): Long = driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0)!! else 0L) }, 0).value
    }

    private val water = NewItem(
        "k:水", ItemKind.KANJI, "水", "すい", listOf("water"), listOf("すい", "みず"), ItemSource.PACK,
        listOf(CardDirection.MEANING, CardDirection.READING), level = 1,
    )
    private val cat = NewItem(
        "jmdict:1", ItemKind.VOCAB, "猫", "ねこ", listOf("cat, \"neko\""), listOf("ねこ"), ItemSource.USER,
        listOf(CardDirection.MEANING), context = "猫が好き,です。",
    )
    private val meaning = SrsRepository.cardId("k:水", CardDirection.MEANING)
    private val reading = SrsRepository.cardId("k:水", CardDirection.READING)
    private val catMeaning = SrsRepository.cardId("jmdict:1", CardDirection.MEANING)

    /** A device with a bit of everything: reviews, an undone (tombstoned) review, a suspended card, settings, progress. */
    private suspend fun populated(): Device {
        val a = Device("device-a")
        a.srs.addItems(listOf(water, cat))
        a.srs.introduce(listOf(meaning, reading, catMeaning))
        clock.advance(1.hours)
        a.srs.review(meaning, Rating.GOOD, 5.seconds, "water", true)
        clock.advance(1.hours)
        a.srs.review(reading, Rating.AGAIN, 7.seconds, "みず", false)
        // Pretend it synced, so the undo below must tombstone instead of deleting (rule 12).
        a.db.syncQueries.markSyncedUpTo(Long.MAX_VALUE)
        clock.advance(1.hours)
        val undone = a.srs.review(catMeaning, Rating.EASY, 2.seconds, "cat", true)
        a.db.syncQueries.markSyncedUpTo(Long.MAX_VALUE)
        a.srs.undo(undone)
        a.srs.setSuspended(reading, true)
        a.settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, "45")
        a.progress.recordPassed(3)
        a.db.userQueries.insertList("list-1", "Animals", 1, 1)
        return a
    }

    @Test
    fun jsonBackupRoundTripsIntoAnEmptyDevice() = runTest {
        val a = populated()
        val json = a.backup.exportJson()
        assertTrue(json.contains("\"format\":\"tsumugi-backup\""))

        val b = Device("device-b")
        val result = b.backup.restore(json)
        assertTrue(result.applied > 0)
        assertEquals(setOf(SettingsRepository.DAILY_BUDGET_MINUTES), result.changedSettings)

        // Same items, same live and tombstoned reviews, same FSRS state rebuilt from the log.
        assertEquals(a.srs.allItemIds().toSet(), b.srs.allItemIds().toSet())
        assertEquals(a.long("SELECT count(*) FROM review"), b.long("SELECT count(*) FROM review"))
        assertEquals(1L, b.long("SELECT count(*) FROM review WHERE deleted_at IS NOT NULL"), "the undo survives the restore")
        for (card in listOf(meaning, reading, catMeaning)) assertEquals(a.srs.card(card)!!.fsrs, b.srs.card(card)!!.fsrs, card)
        assertTrue(b.srs.card(reading)!!.suspended)
        assertEquals("45", b.settings.get(SettingsRepository.DAILY_BUDGET_MINUTES))
        assertEquals(3, b.progress.progress().passedLevel)
        assertEquals(listOf("Animals"), b.db.userQueries.lists().executeAsList().map { it.name })
        // Restored rows are recorded for sync like local edits.
        assertTrue(b.long("SELECT count(*) FROM change_log WHERE synced = 0") > 0)

        // Restoring the same file again changes nothing.
        val again = b.backup.restore(json)
        assertEquals(0, again.applied)
        // And the backup of B equals the backup of A, table by table.
        assertEquals(a.backup.snapshot().tables, b.backup.snapshot().tables)
    }

    @Test
    fun restoreMergesAndNeverLowersProgressOrDropsReviews() = runTest {
        val a = populated()
        val json = a.backup.exportJson()
        val b = Device("device-b")
        b.srs.addItems(listOf(water))
        b.srs.introduce(listOf(meaning))
        clock.advance(10.hours)
        b.srs.review(meaning, Rating.HARD, 3.seconds, "water", true)
        b.progress.recordPassed(7)
        val before = b.long("SELECT count(*) FROM review")

        b.backup.restore(json)
        assertEquals(7, b.progress.progress().passedLevel, "an older backup can't lower the level (rule 11)")
        assertEquals(before + a.long("SELECT count(*) FROM review"), b.long("SELECT count(*) FROM review"), "union of both logs")
    }

    @Test
    fun aForeignFileIsRejected() = runTest {
        val b = Device("device-b")
        assertFailsWith<BackupFormatException> { b.backup.restore("""{"hello":1}""") }
        assertFailsWith<BackupFormatException> {
            b.backup.restore("""{"format":"other","version":1,"schemaVersion":1,"exportedAt":0,"deviceId":"x","tables":{}}""")
        }
        assertFailsWith<BackupFormatException> {
            b.backup.restore("""{"format":"tsumugi-backup","version":99,"schemaVersion":1,"exportedAt":0,"deviceId":"x","tables":{}}""")
        }
    }

    @Test
    fun backupFilesRoundTripThroughTheFileSystem() = runTest {
        val a = populated()
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        val rows = a.backup.exportTo(fs, "/out/backup.json")
        assertTrue(rows > 5)
        val b = Device("device-b")
        val progress = ArrayList<RestoreProgress>()
        b.backup.restoreFrom(fs, "/out/backup.json") { progress += it }
        assertEquals(progress.last().total, progress.last().done)
        assertEquals(3, b.progress.progress().passedLevel)
    }

    @Test
    fun csvHasTheWholeLiveLogAndQuotesFields() = runTest {
        val a = populated()
        val exporter = ReviewCsvExporter(a.db)
        val rows = exporter.rows()
        assertEquals(a.long("SELECT count(*) FROM review WHERE deleted_at IS NULL").toInt(), rows.size, "tombstoned reviews are excluded")
        assertTrue(rows.none { it.cardId == catMeaning && it.rating == Rating.EASY.value.toLong() })
        assertEquals(rows.sortedBy { it.ts }, rows)

        val csv = exporter.csv(rows)
        val lines = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(ReviewCsvExporter.HEADER.joinToString(","), lines.first())
        assertEquals(rows.size + 1, lines.size)
        assertTrue(lines.any { it.contains(",水,") && it.contains(",MEANING,") })
        assertEquals("\"a,\"\"b\"\"\"", ReviewCsvExporter.field("a,\"b\""))

        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        assertEquals(rows.size, exporter.export(fs, "/out/reviews.csv"))
        assertEquals(csv, fs.read("/out/reviews.csv".toPath()) { readUtf8() })
    }

    @Test
    fun studyReportCollectsStatsLevelAndExamTrend() = runTest {
        val a = populated()
        val stats = StatsService(a.db, a.srs, a.settings, clock) { TimeZone.UTC }
        val exams = listOf(
            AttemptSummary("e2", ExamKind.JLPT, "N4", ExamMode.MOCK, clock.now(), "N4 · 120/180 · pass", AttemptScoring(total = 120, totalMax = 180, passed = true)),
            AttemptSummary("e1", ExamKind.JLPT, "N4", ExamMode.MOCK, clock.now() - 48.hours, "N4 · 80/180", AttemptScoring(total = 80, totalMax = 180, passed = false)),
        )
        val report = StudyReportBuilder(a.db, stats, { a.progress.progress().passedLevel }, { exams }, clock).build(30)
        assertEquals(3, report.pathLevel)
        assertEquals(2L, report.reviewsAllTime)
        assertEquals(2, report.reviewsInPeriod)
        assertEquals(listOf("e1", "e2").size, report.examTrend.size)
        assertEquals(80.0 / 180, report.examTrend.first().percent!!, 1e-9, "oldest first")
        assertEquals(true, report.examTrend.last().passed)
        assertEquals(1, report.accuracy.correct)
        assertEquals(2, report.accuracy.total)
        assertEquals(1L, report.itemsByKind[ItemKind.KANJI])
        assertEquals(30, report.daily.size)
        val titles = report.sections.map { it.title }
        assertTrue("Summary" in titles && "Exam trend" in titles, titles.toString())
        assertNotNull(report.sections.first().rows.firstOrNull { it.label == "Kanji path level passed" && it.value == "3" })

        val days = DailyTotals.since(a.db, 0, TimeZone.UTC)
        assertEquals(2, days.sumOf { it.reviews })
        assertEquals(0.5, days.single().accuracy)
        assertEquals(mapOf(days.single().date to 1), DailyTotals.streaks(days))
    }
}
