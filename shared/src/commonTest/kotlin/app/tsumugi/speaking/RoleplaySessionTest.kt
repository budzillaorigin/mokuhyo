package app.tsumugi.speaking

import app.tsumugi.ai.AiException
import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.CompletionRequest
import app.tsumugi.ai.CompletionResult
import app.tsumugi.ai.ContextWindow
import app.tsumugi.ai.LanguageModel
import app.tsumugi.ai.Role
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.practice.Register
import app.tsumugi.practice.Scenario
import app.tsumugi.practice.ScriptedTurn
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-23: no scripted lines spliced into an AI conversation, and history trimmed to the model's window. */
class RoleplaySessionTest {
    private val scenario = Scenario(
        id = "cafe", titleEn = "At a café", titleJa = "カフェで", jlpt = 5, ilr = "1", category = "daily",
        setting = "A small café", learnerRole = "customer", partnerRole = "server", register = Register.POLITE,
        goals = listOf("Order a coffee"), vocabulary = emptyList(), phrases = emptyList(), systemPrompt = "", source = "llm",
    )
    private val scripted = listOf(
        ScriptedTurn("いらっしゃいませ。", "Welcome.", "greet", "こんにちは。"),
        ScriptedTurn("ご注文は？", "Your order?", "order", "コーヒーをください。"),
    )

    private fun reply(ja: String) = """{"reply":"$ja","translation":"Sure.","hint":"Say thanks.","goal_reached":false}"""

    /** Answers from [replies] in order; an exception is thrown; records every request. */
    private class ScriptedModel(private val replies: MutableList<Any>, override val contextSize: Int? = null) : LanguageModel {
        val requests = mutableListOf<CompletionRequest>()
        override val id = "m"
        override val isLocal = true
        override suspend fun complete(request: CompletionRequest): CompletionResult {
            requests += request
            return when (val r = replies.removeFirst()) {
                is Throwable -> throw r
                else -> CompletionResult(r.toString(), "fake", null)
            }
        }
    }

    @Test
    fun modelFailureMidConversationShowsABannerNotAScriptedLine() = runTest {
        val model = ScriptedModel(mutableListOf(reply("いらっしゃいませ。"), AiException("Prompt too long")))
        val session = RoleplaySession(scenario, scripted, AiGateway({ model }))
        val opening = assertNotNull(session.start())
        assertEquals("fake", opening.engine)
        assertEquals(false, session.isScripted)

        assertNull(session.reply("コーヒーをください。"))
        assertEquals("Prompt too long", session.modelFailure)
        // The learner's line stays; nothing from the script was spliced in.
        assertEquals(listOf(Speaker.PARTNER, Speaker.LEARNER), session.transcript.map { it.speaker })
        assertTrue(session.transcript.none { it.japanese == "ご注文は？" })
        assertFalse(session.goalReached)
    }

    @Test
    fun retryAfterFailureContinuesWithTheModel() = runTest {
        val model = ScriptedModel(mutableListOf(reply("いらっしゃいませ。"), AiException("timeout"), reply("かしこまりました。")))
        val session = RoleplaySession(scenario, scripted, AiGateway({ model }))
        session.start()
        assertNull(session.reply("コーヒーをください。"))
        val line = assertNotNull(session.retry())
        assertEquals("かしこまりました。", line.japanese)
        assertNull(session.modelFailure)
        assertEquals(listOf(Speaker.PARTNER, Speaker.LEARNER, Speaker.PARTNER), session.transcript.map { it.speaker })
    }

    @Test
    fun scriptedModeOnlyWithoutAModelFromTheStart() = runTest {
        val session = RoleplaySession(scenario, scripted, AiGateway({ null }))
        assertEquals("いらっしゃいませ。", session.start()?.japanese)
        assertEquals(true, session.isScripted)
        val next = assertNotNull(session.reply("こんにちは。"))
        assertEquals("ご注文は？", next.japanese)
        assertNull(next.engine)
    }

    @Test
    fun historyIsTrimmedToTheContextWindow() = runTest {
        val replies = MutableList<Any>(40) { reply("はい、そうですね。") }
        val model = ScriptedModel(replies, contextSize = 1_200)
        val session = RoleplaySession(scenario, scripted, AiGateway({ model }))
        session.start()
        repeat(30) { i -> session.reply("これは【$i】のとても長い発言です。" + "あ".repeat(40)) }
        val last = model.requests.last()
        val user = last.messages.last { it.role == Role.USER }.content
        assertTrue("【29】" in user, "the newest turn is kept")
        assertFalse("【0】" in user, "the oldest turns are dropped")
        val budget = ContextWindow.budget(1_200, 320)
        assertTrue(ContextWindow.estimateTokens(last.messages) <= budget, "prompt fits n_ctx − maxTokens − margin")
    }

    @Test
    fun fitLatestKeepsTheNewestItems() {
        val items = listOf(10, 10, 10, 10)
        assertEquals(listOf(10, 10), ContextWindow.fitLatest(items, fixedTokens = 5, budget = 25) { it })
        assertEquals(emptyList(), ContextWindow.fitLatest(items, fixedTokens = 30, budget = 25) { it })
        assertEquals(3, ContextWindow.estimateTokens("食べる"))
        assertEquals(2, ContextWindow.estimateTokens("abcd"))
    }
}
