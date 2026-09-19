package app.tsumugi.reader

import app.tsumugi.courses.ExplanationLanguage
import app.tsumugi.courses.Explanations
import app.tsumugi.courses.MonolingualSettings
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.grammar.GrammarService
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.grammar.db.Grammar_example
import app.tsumugi.grammar.db.Grammar_pattern
import app.tsumugi.grammar.db.Grammar_point
import app.tsumugi.grammar.db.Grammar_point_ja
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.16: constructions in reader sentences, with a one-line explanation and "practice this point". */
class ReaderGrammarTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val settings = SettingsRepository(db, clock)
    private val monolingual = MonolingualSettings(settings)

    private val pack = GrammarDatabase(inMemoryDriver(GrammarDatabase.Schema)).also { g ->
        val q = g.grammarQueries
        q.insertPoint(Grammar_point("n4-teoku", 4, 1, "〜ておく", "V て + おく", "Do in advance. Used for preparation.", "Ahead of time.", "[]", "[]", "{}", "llm"))
        q.insertPoint(Grammar_point("n5-tai", 5, 1, "〜たい", "Verb stem + たい", "want to", "Speaker's desire.", "[]", "[]", "{}", "verified"))
        q.insertPoint(Grammar_point("n2-sai", 2, 1, "〜際（に）", "V + 際に", "when; on the occasion of", "Formal とき.", "[]", "[]", "{}", "llm"))
        q.insertPattern(Grammar_pattern("n4-teoku", 0, "[てで](?:おく|おき|おい|おか|おこ|おけ)"))
        q.insertPattern(Grammar_pattern("n5-tai", 0, "たい|たく|たかっ"))
        q.insertPattern(Grammar_pattern("n2-sai", 0, "際に?"))
        q.insertJapanese(Grammar_point_ja("n2-sai", "〜するとき。〜の機会に。", "「〜とき」の改まった言い方。", "llm"))
        q.insertExample(Grammar_example("n4-teoku", 0, "切符を買っておいた。", "I bought the ticket in advance.", 5, 9, "tatoeba", 100))
    }
    private val grammar = GrammarService(pack, srs) { null }
    private val explanations = Explanations(db, monolingual, { grammar }, { null }, clock)
    private val readerGrammar = ReaderGrammar({ grammar }, explanations, srs)

    @Test
    fun constructionsWithOneLineExplanationsAndSpans() = runTest {
        val text = "切符を買っておいた。冷たい水が飲みたい。"
        // 冷たい is a dictionary word: its たい is not the 〜たい construction (F-39).
        val words = listOf(ReaderGrammar.WordSpan(10, 13, isWord = true, surface = "冷たい", dictionaryForm = "冷たい"))
        val found = readerGrammar.constructions(text, listOf("n5-tai", "n4-teoku"), words)
        assertEquals(listOf("n4-teoku", "n5-tai"), found.map { it.pointId }, "in the order they appear")
        val teoku = found[0]
        assertEquals("Do in advance.", teoku.explanation, "one line: the first sentence")
        assertEquals(listOf(5 until 8), teoku.spans)
        assertEquals(ExplanationLanguage.ENGLISH, teoku.language)
        assertTrue(teoku.aiGenerated)
        assertEquals(PracticeKind.ADD_TO_REVIEWS, teoku.practiceAction)
        assertTrue(teoku.hasExercises)
        val tai = found[1]
        assertEquals(listOf(17 until 19), tai.spans, "飲みたい only, not 冷たい")
        assertEquals(false, tai.aiGenerated)
        assertEquals(PracticeKind.ADD_TO_REVIEWS, tai.practiceAction)
        assertTrue(readerGrammar.constructions(text, emptyList()).isEmpty())
        assertTrue(ReaderGrammar({ null }, null, srs).constructions(text, listOf("n5-tai")).isEmpty(), "no grammar pack, nothing shown")
    }

    @Test
    fun explanationFollowsMonolingualMode() = runTest {
        monolingual.setEnabled(true)
        val sai = readerGrammar.constructions("出発の際に連絡します。", listOf("n2-sai")).single()
        assertEquals(ExplanationLanguage.JAPANESE, sai.language)
        assertEquals("〜するとき。", sai.explanation)
        assertTrue(sai.aiGenerated)
    }

    @Test
    fun practiceThisPoint() = runTest {
        val added = assertIs<GrammarPracticeResult.AddedToReviews>(readerGrammar.practice("n4-teoku"))
        assertEquals("g:n4-teoku", added.itemId)
        assertNotNull(added.exercise, "the first exercise comes with it")
        val detected = readerGrammar.constructions("買っておく", listOf("n4-teoku")).single()
        assertNotNull(detected.stage)
        assertEquals(PracticeKind.EXERCISE, detected.practiceAction)
        assertIs<GrammarPracticeResult.Exercise>(readerGrammar.practice("n4-teoku"))
        // A point without examples joins reviews but can't build an exercise.
        readerGrammar.practice("n5-tai")
        val none = assertIs<GrammarPracticeResult.Unavailable>(readerGrammar.practice("n5-tai"))
        assertEquals(ReaderGrammar.NO_EXAMPLES, none.reason)
        assertIs<GrammarPracticeResult.Unavailable>(readerGrammar.practice("nope"))
    }

    @Test
    fun oneLine() {
        assertEquals("短い。", ReaderGrammar.oneLine("短い。長い説明が続く。"))
        assertEquals(ReaderGrammar.MAX_LINE, ReaderGrammar.oneLine("x".repeat(200)).length)
        assertEquals("want to", ReaderGrammar.oneLine("  want to "))
    }
}
