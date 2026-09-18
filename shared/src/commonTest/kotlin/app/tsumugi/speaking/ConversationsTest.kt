package app.tsumugi.speaking

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.FakeModel
import app.tsumugi.ai.prompts.CorrectSentence
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** BRIEF_V2 G-02: free talk, stored conversations, the level estimate, the error log and the weekly patterns. */
class ConversationsTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val conversations = ConversationService(db, "device", clock)

    private fun partner(reply: String, en: String = "A reply.") = """{"reply":"$reply","translation":"$en","topic":"daily life"}"""

    @Test
    fun freeTalkRunsThroughTheGatewayAndIsStoredWithCorrections() = runTest {
        val model = FakeModel(
            partner("こんにちは。今日は何をしましたか。"),
            partner("いいですね。何を食べましたか。"),
            // feedback on the learner line: correct_sentence, then natural_rewrite
            """{"is_correct":false,"corrected":"公園へ行きました。","confidence":0.9,"edits":[{"original":"行きます","replacement":"行きました","reason":"Past event."}],"explanation":"Use the past tense."}""",
            """{"rewrite":"公園に行ってきました。","notes":"More natural."}""",
        )
        val session = FreeTalkSession(AiGateway({ model }), conversations, "N4", clock = clock)
        assertEquals("こんにちは。今日は何をしましたか。", session.start()!!.ja)
        assertNotNull(session.reply("公園へ行きます。"))
        val fb = session.feedback(1)
        assertEquals("公園へ行きました。", fb.correction!!.corrected)
        assertTrue(model.requests.first().messages.first().content.contains("free conversation"))

        clock.advance(5.minutes)
        val record = assertNotNull(session.finish())
        assertEquals(ConversationMode.FREE_TALK, record.mode)
        assertNull(record.scenarioId)
        assertEquals("fake engine", record.engine)
        assertEquals(listOf(ErrorType.TENSE), record.errors.map { it.type })
        assertEquals(record, session.finish(), "stored once")

        val stored = assertNotNull(conversations.conversation(record.id))
        assertEquals(3, stored.turns.size)
        assertEquals("行きました", stored.turns[1].corrections!!.edits.single().replacement)
        assertEquals(1L, db.syncQueries.hasUnsyncedMarker("conversation", record.id).executeAsOne(), "conversations sync")
    }

    @Test
    fun freeTalkWithoutAModelSaysSo() = runTest {
        val session = FreeTalkSession(AiGateway({ null }), conversations, "N4", clock = clock)
        assertNull(session.start())
        assertEquals(FreeTalkSession.NO_MODEL, session.unavailable)
        assertNull(session.finish(), "nothing from the learner: nothing stored")
    }

    @Test
    fun errorClassifier() {
        assertEquals(ErrorType.PARTICLE, ErrorClassifier.classify("が", "を"))
        assertEquals(ErrorType.PARTICLE, ErrorClassifier.classify("学校に", "学校へ"))
        assertEquals(ErrorType.TENSE, ErrorClassifier.classify("食べます", "食べました"))
        assertEquals(ErrorType.POLITENESS, ErrorClassifier.classify("食べる", "食べます"))
        assertEquals(ErrorType.CONJUGATION, ErrorClassifier.classify("食べって", "食べて"))
        assertEquals(ErrorType.SPELLING, ErrorClassifier.classify("カンジ", "かんじ"))
        assertEquals(ErrorType.WORD_CHOICE, ErrorClassifier.classify("見る", "聞く"))
    }

    @Test
    fun levelEstimateNeedsThreeLinesAndRisesWithLongerAccurateSpeech() {
        fun learner(vararg lines: String, correct: Boolean? = null) = lines.map {
            ConversationTurn(
                ConversationTurn.LEARNER, it,
                corrections = correct?.let { ok -> CorrectSentence.Output(ok, it, 0.9, if (ok) emptyList() else listOf(CorrectSentence.Edit("は", "が", "x"))) },
            )
        }
        assertNull(LevelEstimator.estimate(learner("はい。", "いいえ。")))
        val beginner = assertNotNull(LevelEstimator.estimate(learner("はい。", "すしがすきです。", "いいえ。", correct = false)))
        val advanced = assertNotNull(
            LevelEstimator.estimate(
                learner(
                    "先週の会議で提案された新しい計画について、私はいくつか懸念を持っています。",
                    "特に予算の配分が現実的ではないと思うので、もう一度検討する必要があるでしょう。",
                    "ただ、全体的な方向性には賛成しているので、細部を調整すれば実現できると考えています。",
                    correct = true,
                ),
            ),
        )
        assertEquals(5, beginner.jlpt)
        assertEquals("0+", beginner.ilr)
        assertTrue(advanced.score > 70, "${advanced.score}")
        assertTrue(advanced.jlpt <= 2)

        val rolling = assertNotNull(LevelEstimator.rolling(listOf(advanced, beginner)))
        assertTrue(rolling.level.score in beginner.score..advanced.score)
        assertTrue(rolling.level.score > (beginner.score + advanced.score) / 2, "newest weighs more")
    }

    @Test
    fun recurringErrorsAndWeeklyPatterns() = runTest {
        suspend fun talk(vararg edits: Pair<String, String>) {
            val turns = listOf(ConversationTurn(ConversationTurn.PARTNER, "どうでしたか。")) + edits.map { (a, b) ->
                ConversationTurn(ConversationTurn.LEARNER, "文$a", corrections = CorrectSentence.Output(false, "文$b", 0.9, listOf(CorrectSentence.Edit(a, b, "x"))))
            }
            conversations.save(ConversationMode.FREE_TALK, null, "N4", turns, clock.now().toEpochMilliseconds() - 600_000, "fake")
        }
        talk("が" to "を") // last week
        clock.advance(8.days)
        talk("が" to "を", "に" to "で", "食べます" to "食べました")
        clock.advance(1.days)
        talk("は" to "が")

        val errors = conversations.recurringErrors(days = 7)
        assertEquals(ErrorType.PARTICLE, errors.first().type)
        assertEquals(3, errors.first().count)
        assertEquals(1, errors.first().previousCount, "last week's particle slip")
        assertEquals(2, errors.first().trend)
        assertTrue(errors.any { it.type == ErrorType.TENSE })

        val week = conversations.weeklyPatterns()
        assertEquals(2, week.conversations)
        assertEquals(4, week.learnerTurns)
        assertEquals(20, week.minutes)
        assertEquals(ErrorType.PARTICLE, week.errors.first().type)
        assertTrue(!week.isEmpty)
    }

    @Test
    fun partnerLevelFollowsTheRollingEstimate() = runTest {
        assertEquals("N4", conversations.partnerLevel(4), "no conversations yet: the path's level")
        val long = "先週の会議で提案された新しい計画について、私はいくつか懸念を持っています。"
        val turns = List(4) { ConversationTurn(ConversationTurn.LEARNER, long) }
        conversations.save(ConversationMode.SCENARIO, "konbini", "N4", turns, 0, null)
        val level = assertNotNull(conversations.levelEstimate())
        assertEquals(1, level.conversations)
        assertEquals(level.level.jlptLabel, conversations.partnerLevel(4))
    }
}
