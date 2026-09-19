package app.tsumugi.onomatopoeia

import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.db.Onomatopoeia
import app.tsumugi.dictionary.db.Onomatopoeia_theme
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.dictionary.db.Sentence
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.8 (DECISIONS D-235..D-237): the onomatopoeia module over the dictionary pack tables, and its quiz. */
class OnomatopoeiaTest {
    private fun word(id: Long, text: String, theme: String, feel: String, glosses: List<String>, type: OnomatopoeiaType = OnomatopoeiaType.GITAIGO, variants: List<String> = emptyList()) =
        OnomatopoeiaWord(id, id.toInt(), text, variants, type, theme, glosses, feel, "", emptyList(), aiGenerated = true)

    private val pool = listOf(
        word(1, "しとしと", "weather", "Soft, steady rain falling quietly for a long time.", listOf("drizzling", "softly"), OnomatopoeiaType.GIONGO),
        word(2, "ざあざあ", "weather", "Heavy rain pouring down loudly.", listOf("pouring", "torrential")),
        word(3, "ぽつぽつ", "weather", "The first few drops of rain.", listOf("in drops", "drizzling")), // shares "drizzling" with しとしと
        word(4, "いらいら", "feelings", "Irritation building up inside.", listOf("irritated"), OnomatopoeiaType.GIJOUGO),
        word(5, "わくわく", "feelings", "Excited anticipation, heart racing a little.", listOf("excited"), OnomatopoeiaType.GIJOUGO),
        word(6, "ずきずき", "pain", "A throbbing pain that pulses.", listOf("throbbing")),
        word(7, "きらきら", "appearance", "Something glittering and sparkling.", listOf("glittering")),
        word(8, "ぺこぺこ", "body", "An empty, hungry stomach.", listOf("starving")),
        word(9, "ごろごろ", "sounds", "", listOf("rumbling")), // no feel: gloss and examples only, never quizzed
        word(10, "シトシト", "weather", "Quiet rain written in katakana.", listOf("quietly")), // same reading as しとしと
    )

    @Test
    fun quizQuestionsHaveOneUnambiguousAnswer() {
        val questions = OnomatopoeiaQuiz.questions(pool, count = 20, random = Random(7))
        assertTrue(questions.isNotEmpty())
        for (q in questions) {
            assertEquals(OnomatopoeiaQuiz.CHOICES, q.choices.size)
            assertEquals(q.choices.size, q.choices.distinct().size)
            assertTrue(q.isCorrect(q.answer))
            assertEquals(q.target, q.options[q.answer])
            val others = q.options.filter { it != q.target }
            assertTrue(others.none { OnomatopoeiaQuiz.confusable(q.target, it) }, "no distractor shares a reading or gloss with ${q.target.text}")
            assertTrue(q.options.all { it.hasFeel })
            when (q.kind) {
                OnomatopoeiaQuizKind.WORD_FOR_SCENE -> {
                    assertEquals(q.target.feel, q.prompt)
                    assertEquals(q.target.text, q.choices[q.answer])
                }
                OnomatopoeiaQuizKind.SCENE_FOR_WORD -> {
                    assertEquals(q.target.text, q.prompt)
                    assertEquals(q.target.feel, q.choices[q.answer])
                }
            }
        }
        assertTrue(questions.none { it.target.text == "ごろごろ" }, "words without a feel line aren't quizzed")
        assertEquals(questions.size, questions.map { it.target.entryId }.distinct().size, "each target once")
        assertEquals(setOf(OnomatopoeiaQuizKind.WORD_FOR_SCENE, OnomatopoeiaQuizKind.SCENE_FOR_WORD), questions.map { it.kind }.toSet())
    }

    @Test
    fun readingVariantsAndSharedGlossesAreConfusable() {
        assertTrue(OnomatopoeiaQuiz.confusable(pool[0], pool[9]), "しとしと / シトシト fold to the same reading")
        assertTrue(OnomatopoeiaQuiz.confusable(pool[0], pool[2]), "both mean drizzling")
        assertFalse(OnomatopoeiaQuiz.confusable(pool[0], pool[1]))
        val q = assertNotNull(OnomatopoeiaQuiz.question(pool[0], pool, OnomatopoeiaQuizKind.WORD_FOR_SCENE, Random(1)))
        assertTrue(q.options.none { it.text == "シトシト" || it.text == "ぽつぽつ" })
    }

    @Test
    fun aSmallPoolGivesNoQuestionRatherThanABadOne() {
        assertNull(OnomatopoeiaQuiz.question(pool[0], pool.take(3), OnomatopoeiaQuizKind.SCENE_FOR_WORD, Random(1)))
        assertTrue(OnomatopoeiaQuiz.questions(pool, count = 5, random = Random(3), theme = "feelings").all { it.target.theme == "feelings" })
    }

    @Test
    fun repositoryReadsThePackTablesAndExamples() = runTest {
        val db = DictionaryFixture.create()
        val q = db.onomatopoeiaQueries
        q.insertOnomatopoeiaTheme(Onomatopoeia_theme("weather", 0, "Weather", "天気", "Rain.", "<svg/>"))
        q.insertOnomatopoeiaTheme(Onomatopoeia_theme("feelings", 1, "Feelings", "気持ち", "Nerves.", "<svg/>"))
        db.dictionaryQueries.insertSentence(Sentence(900, "雨がしとしと降っている。", "It's drizzling.", null))
        q.insertOnomatopoeia(Onomatopoeia(1, 1, "しとしと", "[\"シトシト\"]", "GIONGO", "weather", "[\"drizzling\"]", "Soft rain.", "静かに降る雨", "[900]", "llm"))
        q.insertOnomatopoeia(Onomatopoeia(2, 2, "いらいら", "[]", "GIJOUGO", "feelings", "[\"irritated\"]", "", "", "[]", "rule"))
        val repo = OnomatopoeiaRepository(db)

        assertTrue(repo.available())
        assertEquals(listOf("weather" to 1, "feelings" to 1), repo.themes().map { it.id to it.count })
        assertEquals(listOf("しとしと"), repo.words(withFeelOnly = true).map { it.text })
        assertEquals(listOf("いらいら"), repo.words(type = OnomatopoeiaType.GIJOUGO).map { it.text })
        assertEquals(listOf("しとしと"), repo.search("シトシト").map { it.text }, "kana folded")
        assertEquals(listOf("しとしと"), repo.search("drizz").map { it.text })
        val detail = assertNotNull(repo.detail(1))
        assertEquals("天気", detail.theme?.titleJa)
        assertEquals(listOf("雨がしとしと降っている。"), detail.examples.map { it.japanese })
        assertTrue(detail.word.aiGenerated)
        assertFalse(repo.detail(2)!!.word.aiGenerated, "rule-classified words carry no drafted text")
    }

    @Test
    fun packsWithoutTheTablesAreAnHonestEmptyState() = runTest {
        val driver = inMemoryDriver(DictionaryDatabase.Schema)
        driver.execute(null, "DROP TABLE onomatopoeia", 0)
        driver.execute(null, "DROP TABLE onomatopoeia_theme", 0)
        val repo = OnomatopoeiaRepository(DictionaryDatabase(driver))
        assertFalse(repo.available())
        assertTrue(repo.themes().isEmpty())
        assertTrue(repo.quiz(5, seed = 1).isEmpty())
    }
}
