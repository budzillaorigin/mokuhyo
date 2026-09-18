package app.tsumugi.integrations.wanikani

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.IntegrationKind
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaniKaniSyncTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val secrets = FakeSecrets()
    private val server = FakeWaniKani()
    private val sync = WaniKaniSync(db, srs, secrets, { token -> WaniKaniClient(token, server.engine, clock, { clock.advance(it) }) }, clock)

    private val path = listOf(
        PathItem("r:一", ItemKind.RADICAL, 1, "一", "一", "one", listOf("one"), emptyList(), emptyList(), null, null, null, emptyList()),
        PathItem("k:一", ItemKind.KANJI, 1, "一", "一", "one", listOf("one"), listOf("いち", "ひと"), emptyList(), null, 1, 5, listOf("r:一")),
        PathItem("v:100", ItemKind.VOCAB, 1, "一つ", "一つ", "one thing", listOf("one thing"), listOf("ひとつ"), emptyList(), 100, null, 5, listOf("k:一")),
    )

    /** Assignments returned for `updated_after` requests (incremental imports). */
    private var changedAssignments = ""

    init {
        server.json("/user", WkFixtures.user)
        server.routes["GET /subjects"] = { req ->
            val ids = req.url.parameters["ids"].orEmpty().split(",").filter { it.isNotEmpty() }.map { it.toLong() }
            ok(WkFixtures.collection(ids.mapNotNull { WkFixtures.subjects[it] }.joinToString(",")))
        }
        server.routes["GET /assignments"] = { req ->
            when {
                req.url.parameters["updated_after"] != null -> ok(WkFixtures.collection(changedAssignments, updatedAt = "2026-09-02T00:00:00.000000Z"))
                req.url.parameters["page_after_id"] == null ->
                    ok(WkFixtures.collection(WkFixtures.assignmentsPage1, "https://api.wanikani.com/v2/assignments?page_after_id=3&started=true"))
                else -> ok(WkFixtures.collection(WkFixtures.assignmentsPage2))
            }
        }
        server.routes["GET /review_statistics"] = { req ->
            ok(WkFixtures.collection(if (req.url.parameters["updated_after"] == null) WkFixtures.reviewStatistics else ""))
        }
        server.routes["GET /study_materials"] = { req ->
            ok(WkFixtures.collection(if (req.url.parameters["updated_after"] == null) WkFixtures.studyMaterials else ""))
        }
    }

    private fun reviewCount() = db.srsQueries.reviewCount().executeAsOne()

    @Test
    fun connectValidatesAndStoresTokenInSecrets() = runTest {
        val me = sync.connect("  my-token ")
        assertEquals("tester", me.username)
        assertEquals("my-token", secrets.values[WaniKaniSync.TOKEN_KEY])
        assertEquals(WaniKaniConfig("tester", 7, postReviews = false), sync.config())
        assertFalse(db.userQueries.integration(IntegrationKind.WANIKANI.name).executeAsOne().config.contains("my-token"), "token only in secrets")
    }

    @Test
    fun connectWithBadTokenStoresNothing() = runTest {
        server.routes["GET /user"] = { respond("{}", HttpStatusCode.Unauthorized) }
        assertFailsWith<WaniKaniException> { sync.connect("bad") }
        assertNull(secrets.get(WaniKaniSync.TOKEN_KEY))
        assertNull(sync.config())
    }

    @Test
    fun importMapsSubjectsAndSeedsStages() = runTest {
        sync.connect("t")
        val result = sync.import(path)

        assertEquals(7, result.wanikaniLevel)
        assertEquals(3, result.matchedToPath, "radical 一, kanji 一, vocab 一つ")
        assertEquals(2, result.wanikaniOnlyItems, "猫 and 子猫")
        assertEquals(1, result.skippedRadicals, "image-only radical")
        assertEquals(5, result.seededAssignments)

        val stages = srs.stages()
        assertEquals(Stage.GURU, stages["k:一"], "WK Guru")
        assertEquals(Stage.BURNED, stages["r:一"], "WK burned")
        assertEquals(Stage.APPRENTICE, stages["v:100"])
        assertEquals(Stage.MASTER, stages["wk:3000"])
        assertEquals(Stage.APPRENTICE, stages["wk:4000"])

        val cat = assertNotNull(srs.item("wk:3000"))
        assertEquals(ItemSource.WANIKANI, cat.source)
        assertEquals(listOf("Cat"), cat.meanings)
        assertEquals(listOf("ねこ"), cat.acceptedReadings)
        assertEquals(ItemSource.PACK, srs.item("k:一")!!.source, "path items keep our own content")
        assertEquals(listOf("one"), srs.item("k:一")!!.meanings)

        // Missed meaning answers (5/10) turn into seeded lapses on the meaning card only.
        assertTrue(srs.card("k:一#MEANING")!!.fsrs.lapses > 0)
        assertEquals(0, srs.card("k:一#READING")!!.fsrs.lapses)
    }

    @Test
    fun studyMaterialsBecomeTheUsersNotes() = runTest {
        sync.connect("t")
        val result = sync.import(path)
        assertEquals(1, result.studyMaterials)
        val kanji = srs.item("k:一")!!
        assertEquals(listOf("the one"), kanji.synonyms)
        assertTrue(kanji.myStory.contains("A single horizontal line"))
    }

    @Test
    fun mnemonicsAreNeverPersisted() = runTest {
        sync.connect("t")
        sync.import(path)
        val stored = listOf("r:一", "k:一", "v:100", "wk:3000", "wk:4000").mapNotNull { srs.item(it) }
        assertEquals(5, stored.size)
        for (item in stored) {
            val text = listOf(item.primaryText, item.reading, item.context, item.myStory, item.meanings, item.acceptedReadings, item.synonyms).toString()
            assertFalse(text.contains(WkFixtures.MNEMONIC), "mnemonic leaked into ${item.id}")
        }
        val row = db.userQueries.integration(IntegrationKind.WANIKANI.name).executeAsOne()
        assertFalse((row.config + row.cursor).contains(WkFixtures.MNEMONIC))
    }

    @Test
    fun reimportIsIncrementalAndIdempotent() = runTest {
        sync.connect("t")
        sync.import(path)
        val before = reviewCount()
        server.requests.clear()

        val again = sync.import(path)
        assertEquals(0, again.seededAssignments)
        assertEquals(before, reviewCount(), "nothing new")
        val assignmentsRequest: HttpRequestData = server.requestsTo("/assignments").single()
        assertEquals("2026-08-31T12:00:00.000000Z", assignmentsRequest.url.parameters["updated_after"])
        assertTrue(server.requestsTo("/subjects").isEmpty(), "subjects already mapped")
    }

    @Test
    fun stageChangeBecomesOneReviewPerCard() = runTest {
        sync.connect("t")
        sync.import(path)
        val before = reviewCount()
        changedAssignments = WkFixtures.assignment(1, 440, "kanji", 6, "2026-09-15T09:00:00.000000Z", updatedAt = "2026-09-01T08:00:00.000000Z")
        val result = sync.import(path)
        assertEquals(1, result.updatedAssignments)
        assertEquals(before + 2, reviewCount(), "meaning + reading")
        sync.import(path)
        assertEquals(before + 2, reviewCount(), "same change imported twice counts once")
    }

    @Test
    fun reviewPostingIsOptInQueuedAndStopsWhenReadOnly() = runTest {
        sync.connect("t")
        sync.import(path)
        assertFalse(sync.postReview(440, 0, 0), "two-way sync is off by default")
        assertEquals(0, sync.queuedReviewCount())

        sync.setPostReviews(true)
        assertTrue(sync.postReviewForItem("k:一", 1, 0))
        assertTrue(sync.postReview(3000, 0, 0))
        assertFalse(sync.postReviewForItem("v:does-not-exist", 0, 0))
        assertEquals(2, sync.queuedReviewCount())

        val bodies = ArrayList<String>()
        var posts = 0
        server.routes["POST /reviews"] = { req ->
            bodies += (req.body as TextContent).text
            if (posts++ == 0) respond("{}", HttpStatusCode.Created) else respond("{}", HttpStatusCode.Forbidden)
        }
        assertEquals(1, sync.flushQueue())
        assertTrue(bodies.first().contains("\"subject_id\":440") && bodies.first().contains("\"incorrect_meaning_answers\":1"))
        assertTrue(bodies.first().contains("\"created_at\""))
        assertEquals(1, sync.queuedReviewCount(), "the refused review stays queued")
        val cfg = sync.config()!!
        assertTrue(cfg.readOnly)
        assertFalse(cfg.postReviews)
        assertFalse(sync.postReview(440, 0, 0), "read-only tokens don't queue more")
    }

    @Test
    fun disconnectForgetsTokenAndState() = runTest {
        sync.connect("t")
        sync.import(path)
        sync.disconnect()
        assertFalse(sync.isConnected())
        assertNull(sync.config())
        assertTrue(db.wanikaniQueries.mappedSubjectIds().executeAsList().isEmpty())
    }
}
