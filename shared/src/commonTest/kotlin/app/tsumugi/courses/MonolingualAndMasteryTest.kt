package app.tsumugi.courses

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.FakeModel
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.grammar.GrammarService
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.grammar.db.Grammar_point
import app.tsumugi.grammar.db.Grammar_point_ja
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.SrsRepository
import app.tsumugi.sync.FakeSyncServer
import app.tsumugi.sync.SyncEngine
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.6: mastery checkboxes (D-231), the monolingual setting (D-232), Japanese explanations (D-233, D-234). */
class MonolingualAndMasteryTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val settings = SettingsRepository(db, clock)
    private val monolingual = MonolingualSettings(settings)

    private val pack = GrammarDatabase(inMemoryDriver(GrammarDatabase.Schema)).also { g ->
        val q = g.grammarQueries
        q.insertPoint(Grammar_point("n2-sai", 2, 1, "〜際（に）", "V + 際に", "when; on the occasion of", "Formal とき.", "[]", "[]", "{}", "llm"))
        q.insertPoint(Grammar_point("n2-ori", 2, 2, "〜折（に）", "V + 折に", "on the occasion of", "Literary.", "[]", "[]", "{}", "llm"))
        q.insertPoint(Grammar_point("n4-teoku", 4, 1, "〜ておく", "V て + おく", "do in advance", "Ahead of time.", "[]", "[]", "{}", "verified"))
        q.insertJapanese(Grammar_point_ja("n2-sai", "〜するとき。〜の機会に。", "「〜とき」の改まった言い方。", "llm"))
        q.insertJapanese(Grammar_point_ja("n4-teoku", "前もって〜する。", "あとのために準備する。", "verified"))
    }
    private val grammar = GrammarService(pack, SrsRepository(db, "dev", clock)) { null }

    @Test
    fun monolingualIsOffByDefaultAndStartsAtN2() = runTest {
        assertNull(monolingual.fromLevel())
        assertEquals(ExplanationLanguage.ENGLISH, monolingual.languageFor(1))
        monolingual.setEnabled(true)
        assertEquals(2, monolingual.fromLevel())
        assertEquals(ExplanationLanguage.JAPANESE, monolingual.languageFor(1))
        assertEquals(ExplanationLanguage.JAPANESE, monolingual.languageFor(2))
        assertEquals(ExplanationLanguage.ENGLISH, monolingual.languageFor(3))
        assertEquals(ExplanationLanguage.JAPANESE, monolingual.languageFor(null, learnerLevel = 2), "untagged content follows the learner")
        assertEquals(ExplanationLanguage.ENGLISH, monolingual.languageFor(null), "no level at all → English")
        monolingual.setFromLevel(4) // optionally earlier
        assertEquals(ExplanationLanguage.JAPANESE, monolingual.languageFor(4))
        monolingual.setEnabled(false)
        assertNull(monolingual.fromLevel())
        assertEquals("0", settings.get(SettingsRepository.MONOLINGUAL_FROM_LEVEL), "stored in the synced settings table")
    }

    @Test
    fun grammarExplanationFollowsTheSettingAndSaysWhenJapaneseIsMissing() = runTest {
        val explanations = Explanations(db, monolingual, { grammar }, { null }, clock)
        val sai = grammar.point("n2-sai")!!.point
        val ori = grammar.point("n2-ori")!!.point
        assertEquals(ExplanationLanguage.ENGLISH, explanations.grammar(sai).language)
        monolingual.setEnabled(true)
        val ja = explanations.grammar(sai)
        assertEquals(ExplanationLanguage.JAPANESE, ja.language)
        assertEquals("〜するとき。〜の機会に。", ja.meaning)
        assertTrue(ja.aiGenerated)
        val missing = explanations.grammar(ori)
        assertEquals(ExplanationLanguage.ENGLISH, missing.language)
        assertTrue(missing.japaneseMissing)
        val teoku = grammar.point("n4-teoku")!!.point
        assertEquals(ExplanationLanguage.ENGLISH, explanations.grammar(teoku).language, "N4 is easier than the N2 threshold")
        val forced = explanations.grammar(teoku, ExplanationLanguage.JAPANESE)
        assertEquals("前もって〜する。", forced.meaning)
        assertFalse(forced.aiGenerated, "reviewed text carries no badge")
    }

    @Test
    fun wordParaphraseIsGeneratedOnceThenCached() = runTest {
        val model = FakeModel("""{"paraphrase":"はっきりしない様子。","example":"曖昧な返事をした。","note":""}""")
        var gatewayCalls = 0
        val explanations = Explanations(db, monolingual, { grammar }, { gatewayCalls++; AiGateway({ model }) }, clock)
        val word = ParaphraseRequest("曖昧", "あいまい", listOf("vague", "ambiguous"), listOf("adj-na"), entryId = 1_000_000, jlpt = 1)

        assertEquals(ExplanationLanguage.ENGLISH, explanations.word(word).language, "mode off → JMdict glosses")
        monolingual.setEnabled(true)
        val cacheOnly = explanations.word(word, generate = false)
        assertEquals(ExplanationLanguage.ENGLISH, cacheOnly.language)
        assertEquals(Explanations.NOT_GENERATED, cacheOnly.unavailableReason)

        val first = explanations.word(word)
        assertEquals(ExplanationLanguage.JAPANESE, first.language)
        assertEquals("はっきりしない様子。", first.paraphrase)
        assertTrue(first.aiGenerated)
        assertFalse(first.cached)
        assertEquals("fake engine", first.engine)

        val second = explanations.word(word)
        assertTrue(second.cached)
        assertEquals("はっきりしない様子。", second.paraphrase)
        assertEquals(1, model.requests.size, "the model ran once")
        assertEquals(1, gatewayCalls)

        explanations.forgetParaphrase(word)
        val noModel = Explanations(db, monolingual, { grammar }, { null }, clock).word(word)
        assertEquals(ExplanationLanguage.ENGLISH, noModel.language)
        assertEquals("no AI model is set up", noModel.unavailableReason, "never a silent downgrade")
        assertEquals(0L, db.changeLogCount("ai_paraphrase"), "the cache is device-local")
    }

    @Test
    fun masteryIsIndependentOfSrsAndRecordsOnlyRealChanges() = runTest {
        val store = GrammarMasteryStore(db, clock)
        store.setMastered("n2-sai", true)
        store.setMastered("n2-sai", true)
        assertEquals(setOf("n2-sai"), store.masteredIds())
        assertEquals(1L, db.changeLogCount("grammar_mastery"))
        store.setMasteredAll(listOf("n2-sai", "n2-ori"), false)
        assertEquals(emptySet(), store.masteredIds())
        assertEquals(3L, db.changeLogCount("grammar_mastery")) // untick n2-sai, insert n2-ori
        assertTrue(db.srsQueries.allReviews().executeAsList().isEmpty(), "no SRS side effects")
    }

    @Test
    fun masteryAndTheSettingSyncLastWriterWins() = runTest {
        val server = FakeSyncServer()
        class Device(id: String) {
            val driver = inMemoryDriver(TsumugiDatabase.Schema)
            val db = TsumugiDatabase(driver)
            val engine = SyncEngine(driver, db, SrsRepository(db, id, clock), id, server, null, clock)
            val mastery = GrammarMasteryStore(db, clock)
            val mono = MonolingualSettings(SettingsRepository(db, clock))
        }
        val a = Device("device-a")
        val b = Device("device-b")
        a.mastery.setMastered("n2-sai", true)
        a.mono.setEnabled(true)
        a.engine.sync(); b.engine.sync()
        assertEquals(setOf("n2-sai"), b.mastery.masteredIds())
        assertEquals(2, b.mono.fromLevel())

        clock.advance(5.seconds)
        b.mastery.setMastered("n2-sai", false) // later untick wins over the earlier tick
        clock.advance(1.seconds)
        a.mastery.setMastered("n2-ori", true)
        b.engine.sync(); a.engine.sync(); b.engine.sync()
        for (d in listOf(a, b)) assertEquals(setOf("n2-ori"), d.mastery.masteredIds())
    }

    private fun TsumugiDatabase.changeLogCount(table: String): Long =
        syncQueries.unsyncedMarkers(Long.MAX_VALUE).executeAsList().count { it.table_name == table }.toLong()
}
