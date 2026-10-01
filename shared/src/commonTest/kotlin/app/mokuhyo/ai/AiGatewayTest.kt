package app.mokuhyo.ai

import app.mokuhyo.ai.prompts.CorrectSentence
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted model: each call pops the next reply. `null` hangs forever (for timeouts); an exception is thrown. */
class FakeModel(vararg replies: Any?) : LanguageModel {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<CompletionRequest>()
    override val id = "fake"
    override val isLocal = true

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        requests += request
        return when (val r = queue.removeFirst()) {
            null -> awaitCancellation()
            is Throwable -> throw r
            else -> CompletionResult(r.toString(), "fake engine", null)
        }
    }
}

class AiGatewayTest {
    private val task = CorrectSentence()
    private val input = CorrectSentence.Input("私は学校を行きます。")
    private val good = """{"is_correct":false,"corrected":"私は学校に行きます。","confidence":0.9,""" +
        """"edits":[{"original":"を","replacement":"に","reason":"行く takes に for a destination."}],"explanation":"Use に with 行く."}"""

    @Test
    fun okOnFirstValidAnswer() = runTest {
        val model = FakeModel(good)
        val result = AiGateway({ model }).run(task, input)
        assertIs<AiResult.Ok<CorrectSentence.Output>>(result)
        assertEquals("私は学校に行きます。", result.value.corrected)
        assertEquals("fake engine", result.engine)
        assertEquals(1, model.requests.size)
        assertEquals(task.schema, model.requests[0].jsonSchema)
        assertTrue(model.requests[0].messages[0].content.contains("JSON Schema"))
    }

    @Test
    fun retriesOnceOnInvalidJsonThenSucceeds() = runTest {
        val model = FakeModel("Sure! Here you go: {not json", "```json\n$good\n```")
        val result = AiGateway({ model }).run(task, input)
        assertIs<AiResult.Ok<*>>(result)
        assertEquals(2, model.requests.size)
        val retry = model.requests[1].messages
        assertEquals(Role.ASSISTANT, retry[retry.size - 2].role)
        assertTrue(retry.last().content.contains("not valid JSON"))
    }

    @Test
    fun givesUpAfterSecondFailure() = runTest {
        val rewrite = """{"is_correct":false,"corrected":"昨日は友達と映画館で映画を見ました。","confidence":0.9,""" +
            """"edits":[{"original":"全部","replacement":"映画","reason":"x"}],"explanation":"Rewrote it."}"""
        val model = FakeModel(rewrite, rewrite)
        val result = AiGateway({ model }).run(task, input)
        assertIs<AiResult.Unavailable>(result)
        assertTrue(result.reason.contains("changes too much"), result.reason)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun noRetryWhenDisabled() = runTest {
        val model = FakeModel("nope")
        val result = AiGateway({ model }, AiSettings(retryInvalid = false)).run(task, input)
        assertIs<AiResult.Unavailable>(result)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun timeoutUsesFallbackHook() = runTest {
        val fallback = CorrectSentence.Output(isCorrect = true, corrected = input.sentence, confidence = 0.0)
        val withHook = CorrectSentence { fallback }
        val result = AiGateway({ FakeModel(null) }, AiSettings(timeoutMs = 1_000)).run(withHook, input)
        assertIs<AiResult.Fallback<CorrectSentence.Output>>(result)
        assertEquals("the model took too long", result.reason)
        assertTrue(result.value.isUnsure)
    }

    @Test
    fun engineErrorsBecomeUnavailable() = runTest {
        val result = AiGateway({ FakeModel(AiException("can't reach server")) }).run(task, input)
        assertIs<AiResult.Unavailable>(result)
        assertEquals("can't reach server", result.reason)
    }

    @Test
    fun cancellationIsNeverRetried() = runTest {
        // F-10: an unload mid-generation is reported as cancelled; a retry would reload the model right after a
        // memory warning, so the gateway gives up at once.
        val model = FakeModel(AiCancelledException("cancelled: unloaded"), good)
        val result = AiGateway({ model }).run(task, input)
        assertIs<AiResult.Unavailable>(result)
        assertEquals(AiGateway.CANCELLED_REASON, result.reason)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun noModelConfigured() = runTest {
        val result = AiGateway({ null }).run(task, input)
        assertIs<AiResult.Unavailable>(result)
        assertNull(result.valueOrNull())
    }

    @Test
    fun dictionaryAcceptanceRejectsInventedWords() = runTest {
        val strict = ValidationContext(isKnownJapanese = { false })
        val result = AiGateway({ FakeModel(good, good) }, context = strict).run(task, input)
        assertIs<AiResult.Unavailable>(result)
        assertTrue(result.reason.contains("dictionary"))
    }

    @Test
    fun extractsOutermostObject() {
        assertEquals("""{"a":{"b":"}"}}""", AiGateway.extractJsonObject("""text {"a":{"b":"}"}} more {"c":1}"""))
        assertEquals("""{"q":"\"{"}""", AiGateway.extractJsonObject("""```json
{"q":"\"{"}
```"""))
        assertNull(AiGateway.extractJsonObject("no json here"))
        assertNull(AiGateway.extractJsonObject("""{"unterminated": 1"""))
    }

    @Test
    fun contractAddedAsSystemMessageWhenMissing() {
        val msgs = AiGateway.withJsonContract(listOf(ChatMessage(Role.USER, "hi")), JsonSchema.Obj(listOf("a" to JsonSchema.Bool)))
        assertEquals(Role.SYSTEM, msgs[0].role)
        assertTrue(msgs[0].content.endsWith("""{"type":"object","properties":{"a":{"type":"boolean"}},"required":["a"],"additionalProperties":false}"""))
    }
}
