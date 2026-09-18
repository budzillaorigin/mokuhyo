package app.tsumugi.grammar

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.FakeModel
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.Stage
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.grammar.db.Grammar_example
import app.tsumugi.grammar.db.Grammar_pattern
import app.tsumugi.grammar.db.Grammar_point
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
import app.tsumugi.study.AnswerMode
import app.tsumugi.study.ReviewSession
import app.tsumugi.study.ReviewState
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** BRIEF_V2 G-05: fill-in-with-hint, meaning recognition, graded production, and textbook-order paths. */
class GrammarReviewTypesTest {

    private val clock = TestClock()
    private val userDb = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(userDb, "device", clock)

    /** Points shaped like build_grammar.py output. Test data only. */
    private val pack = GrammarDatabase(inMemoryDriver(GrammarDatabase.Schema)).also { db ->
        val q = db.grammarQueries
        q.insertPoint(Grammar_point("n4-teoku", 4, 1, "〜ておく", "Verb て-form + おく", "do in advance", "", "[]", "[]", """{"genki":"L15"}""", "llm"))
        q.insertPoint(Grammar_point("n5-tai", 5, 1, "〜たい", "Verb stem + たい", "want to", "", "[]", "[]", """{"genki":"L11"}""", "llm"))
        q.insertPoint(Grammar_point("n5-kara", 5, 2, "〜から", "Sentence + から", "because", "", "[]", "[]", "{}", "llm"))
        q.insertPoint(Grammar_point("n5-mashou", 5, 3, "〜ましょう", "Verb stem + ましょう", "let's", "", "[]", "[]", """{"genki":"L5"}""", "llm"))
        q.insertPattern(Grammar_pattern("n4-teoku", 0, "[てで](?:おく|おき|おい|おか|おこ|おけ)"))
        q.insertPattern(Grammar_pattern("n5-tai", 0, "たい|たく|たかっ"))
        q.insertExample(Grammar_example("n4-teoku", 0, "切符を買っておいた。", "I bought the ticket in advance.", 5, 9, "tatoeba", 1))
        q.insertExample(Grammar_example("n5-tai", 0, "水が飲みたい。", "I want to drink water.", 4, 6, "tatoeba", 2))
        q.insertExample(Grammar_example("n5-kara", 0, "寒いから、帰ります。", "It's cold, so I'll go home.", 2, 4, "tatoeba", 3))
        q.insertExample(Grammar_example("n5-mashou", 0, "行きましょう。", "Let's go.", 2, 6, "tatoeba", 4))
    }
    private val grammar = GrammarService(pack, srs) { null }

    private suspend fun exerciseOf(kind: ExerciseKind, stage: Stage?): GrammarExercise {
        repeat(200) { seed ->
            val ex = grammar.exercise("n4-teoku", Random(seed), stage, variety = true)!!
            if (ex.kind == kind) return ex
        }
        error("no $kind exercise for $stage")
    }

    @Test
    fun exerciseKindsFollowTheStage() = runTest {
        val young = (0 until 60).map { grammar.exercise("n4-teoku", Random(it), Stage.APPRENTICE, variety = true)!!.kind }.toSet()
        assertEquals(setOf(ExerciseKind.FILL_HINT, ExerciseKind.MEANING_CHOICE, ExerciseKind.CLOZE), young)
        val mature = (0 until 60).map { grammar.exercise("n4-teoku", Random(it), Stage.MASTER, variety = true)!!.kind }.toSet()
        assertTrue(ExerciseKind.PRODUCTION in mature)
        assertTrue(ExerciseKind.FILL_HINT !in mature && ExerciseKind.MEANING_CHOICE !in mature)
        val plain = (0 until 30).map { grammar.exercise("n4-teoku", Random(it))!!.kind }.toSet()
        assertEquals(setOf(ExerciseKind.CLOZE), plain, "without variety (and without a dictionary): the v1 cloze")
    }

    @Test
    fun fillInWithHintShowsThePoint() = runTest {
        val ex = exerciseOf(ExerciseKind.FILL_HINT, Stage.APPRENTICE)
        assertEquals("〜ておく (Verb て-form + おく): do in advance", ex.pointHint)
        assertEquals(Verdict.CORRECT, grammar.check(ex, "ておいた").verdict)
        assertEquals(Verdict.CLOSE, grammar.check(ex, "ておきました").verdict)
    }

    @Test
    fun meaningChoiceHasFourOptionsAndChecksIndexOrText() = runTest {
        val ex = exerciseOf(ExerciseKind.MEANING_CHOICE, Stage.APPRENTICE)
        assertEquals(4, ex.choices.size)
        assertEquals(setOf("do in advance", "want to", "because", "let's"), ex.choices.toSet())
        assertEquals("切符を買っ【ておいた】。", ex.marked)
        assertEquals(Verdict.CORRECT, grammar.check(ex, ex.correctChoice.toString()).verdict)
        assertEquals(Verdict.CORRECT, grammar.check(ex, "do in advance").verdict)
        val wrong = (0 until 4).first { it != ex.correctChoice }
        assertEquals(Verdict.WRONG, grammar.check(ex, wrong.toString()).verdict)
    }

    @Test
    fun productionRuleChecks() = runTest {
        val ex = exerciseOf(ExerciseKind.PRODUCTION, Stage.MASTER)
        val good = grammar.productionChecks(ex, "切符を買っておきました。")
        assertEquals(true, good.constructionFound)
        assertEquals(true, good.formConsistent)
        assertEquals(false, grammar.productionChecks(ex, "切符を買いました。").constructionFound)
        assertTrue(grammar.productionChecks(ex, "kippu wo katte oita").japanese, "romaji converts")
        assertTrue(grammar.productionChecks(ex, "切符を買っておいた").matchesModel)
    }

    @Test
    fun productionIsModelGradedAndCappedByRuleChecks() = runTest {
        val ex = exerciseOf(ExerciseKind.PRODUCTION, Stage.MASTER)
        val full = """{"meaning":2,"grammar":2,"form":2,"corrected":"","feedback":"Correct use of ておく in the polite past."}"""
        val graded = grammar.gradeProduction(ex, "切符を買っておきました。", AiGateway({ FakeModel(full) }))
        assertEquals(Verdict.CORRECT, graded.verdict)
        assertEquals("切符を買っておいた。", graded.modelAnswer, "the model answer is always there")
        assertEquals("fake engine", graded.engine)

        // The model is generous, but ておく isn't in the answer: CLOSE at best.
        val generous = """{"meaning":2,"grammar":1,"form":2,"corrected":"切符を買っておきました。","feedback":"Fine, but it misses the in-advance nuance."}"""
        val capped = grammar.gradeProduction(ex, "切符を買いました。", AiGateway({ FakeModel(generous) }))
        assertEquals(Verdict.CLOSE, capped.verdict)

        val none = grammar.gradeProduction(ex, "切符を買いました。", AiGateway({ null }))
        assertTrue(none.selfGrade)
        assertEquals("切符を買っておいた。", none.modelAnswer)
        assertNull(none.rubric)
        assertEquals(Verdict.WRONG, grammar.gradeProduction(ex, "ticket bought", null).verdict, "no Japanese at all")
    }

    @Test
    fun productionInAReviewSelfGradesWithoutAModel() = runTest {
        grammar.learn(grammar.points(4).map { it.point })
        clock.advance(10.minutes)
        val card = srs.card(SrsRepository.cardId("g:n4-teoku", app.tsumugi.domain.CardDirection.CLOZE))!!
        val ex = exerciseOf(ExerciseKind.PRODUCTION, Stage.MASTER)
        val items = srs.items(listOf(card.itemId))
        val session = ReviewSession(srs, listOf(card), items, clock, Random(1), mapOf(card.id to ex), grammar, AiGateway({ null }))
        val asking = assertIs<ReviewState.Asking>(session.state.value)
        assertEquals(AnswerMode.PRODUCTION, asking.prompt.mode)
        assertEquals("I bought the ticket in advance.", asking.prompt.question)
        session.submit("切符を買いました。")
        val revealed = assertIs<ReviewState.Revealed>(session.state.value)
        assertEquals("切符を買いました。", revealed.given)
        assertEquals("切符を買っておいた。", revealed.production!!.modelAnswer)
        session.grade(Rating.HARD)
        assertEquals("切符を買いました。", userDb.srsQueries.reviewsForCard(card.id).executeAsList().last().answer_text)
    }

    @Test
    fun productionInAReviewWithAModelIsAnswered() = runTest {
        grammar.learn(grammar.points(4).map { it.point })
        clock.advance(10.minutes)
        val card = srs.card(SrsRepository.cardId("g:n4-teoku", app.tsumugi.domain.CardDirection.CLOZE))!!
        val ex = exerciseOf(ExerciseKind.PRODUCTION, Stage.MASTER)
        val full = """{"meaning":2,"grammar":2,"form":2,"corrected":"","feedback":"Correct use of ておく in the polite past."}"""
        val session = ReviewSession(srs, listOf(card), srs.items(listOf(card.itemId)), clock, Random(1), mapOf(card.id to ex), grammar, AiGateway({ FakeModel(full) }))
        session.submit("切符を買っておきました。")
        val answered = assertIs<ReviewState.Answered>(session.state.value)
        assertEquals(Verdict.CORRECT, answered.verdict)
        assertEquals(6, answered.production!!.rubric!!.total)
    }

    @Test
    fun textbookPathsAndLessonOrder() = runTest {
        val genki = grammar.path(GrammarService.GENKI)
        assertEquals(listOf("L5", "L11", "L15"), genki.map { it.chapter })
        assertEquals(listOf(5, 11, 15), genki.map { it.number })
        assertEquals("n5-mashou", genki.first().points.single().point.id)
        assertTrue(grammar.path(GrammarService.TOBIRA).isEmpty(), "no mapping: an empty path, not a made-up one")
        assertEquals(listOf("genki"), grammar.textbooks().map { it.id })

        assertEquals(listOf("n5-mashou", "n5-tai", "n4-teoku", "n5-kara"), grammar.lessonQueue(10, GrammarService.GENKI).map { it.id })
        assertEquals(listOf("n5-tai", "n5-kara", "n5-mashou", "n4-teoku"), grammar.lessonQueue(10).map { it.id })
        assertEquals(15, GrammarService.chapterNumber("L15"))
        assertEquals(3, GrammarService.chapterNumber("II-3"))
        assertEquals(Int.MAX_VALUE, GrammarService.chapterNumber("appendix"))
        assertNotNull(grammar.point("n4-teoku"))
    }
}
