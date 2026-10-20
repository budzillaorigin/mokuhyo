package app.mokuhyo.speech

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.CompletionRequest
import app.mokuhyo.ai.CompletionResult
import app.mokuhyo.ai.LanguageModel
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.opi.BankQuestion
import app.mokuhyo.opi.OpiProfile
import app.mokuhyo.opi.OpiSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentenceStreamTest {
    /** Streams each reply in [pieces], [gapMs] apart (virtual time), then returns the whole text. */
    private class SlowModel(private val gapMs: Long, vararg replies: List<String>) : LanguageModel {
        private val queue = ArrayDeque(replies.toList())
        override val id = "slow"
        override val isLocal = true
        var lastTokenAt = -1L
        var cancelled = false
        var now: () -> Long = { 0 }
        override suspend fun complete(request: CompletionRequest): CompletionResult {
            val pieces = queue.removeFirst()
            try {
                for (p in pieces) {
                    delay(gapMs)
                    request.onToken?.invoke(p)
                    lastTokenAt = now()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            return CompletionResult(pieces.joinToString(""), "slow engine", null)
        }
    }

    private fun session(model: LanguageModel, bank: List<BankQuestion> = emptyList()) =
        OpiSession("es", OpiProfile("es", "usted"), bank, emptyList(), AiGateway({ model }), { it.split(' ').size }, IlrLevel.L1)

    private val good = listOf("{\"utter", "ance\":\"Muy ", "bien, gracias.", " ¿Y dónde ", "trabaja ", "usted ", "ahora?\"}")

    @Test
    fun theFirstSentencePlaysBeforeTheLastTokenArrives() = runTest {
        val model = SlowModel(200, good).also { m -> m.now = { testScheduler.currentTime } }
        val sentences = Channel<String>(2)
        val played = ArrayList<Pair<String, Long>>()
        val speaking = async {
            SpokenReply<String>({ s -> delay(50); s }, { a -> played += a to testScheduler.currentTime; delay(500); true }).speak(sentences)
        }
        val line = session(model).next(speak = { sentences.send(it) })!!
        sentences.close()
        val outcome = speaking.await()
        assertEquals(listOf("Muy bien, gracias.", "¿Y dónde trabaja usted ahora?"), played.map { it.first })
        assertTrue(played.first().second < model.lastTokenAt, "first sentence at ${played.first().second} ms, last token at ${model.lastTokenAt} ms")
        assertTrue(line.spokenByStream)
        assertEquals("Muy bien, gracias. ¿Y dónde trabaja usted ahora?", line.text)
        assertEquals(SpokenReply.Outcome(2, false), outcome)
    }

    @Test
    fun aReplyThatTurnsInvalidMidStreamNeverSpeaksTheBadPart() = runTest {
        // The second sentence switches to English: it must never reach speech, and nothing after it either.
        val model = SlowModel(10, listOf("{\"utterance\":\"¿Dónde vive usted? ", "Where do you work these days? ", "¿Le gusta?\"}"))
        val bank = listOf(BankQuestion("w1", "warmup", "1", "¿Cómo se llama usted?", "What is your name?", "personal"))
        val spoken = ArrayList<String>()
        val line = session(model, bank).next(speak = { spoken += it })!!
        assertEquals(listOf("¿Dónde vive usted?"), spoken)
        assertNull(line.engine, "the turn falls back")
        assertEquals("¿Cómo se llama usted?", line.text, "the guided question is what the learner answers")
        assertEquals(listOf("¿Dónde vive usted?"), line.streamed)
        assertFalse(line.spokenByStream, "the caller still has to say the fallback question")
        assertTrue(line.fallbackReason!!.contains("after 1 sentence"), line.fallbackReason)
        assertTrue(model.cancelled, "generation stopped as soon as the bad sentence was seen")
    }

    @Test
    fun aBadFirstSentenceIsRetriedBecauseNothingWasSpoken() = runTest {
        val model = SlowModel(10, listOf("{\"utterance\":\"Where do you live? ", "¿Y usted?\"}"), good)
        val spoken = ArrayList<String>()
        val line = session(model).next(speak = { spoken += it })!!
        assertEquals(listOf("Muy bien, gracias.", "¿Y dónde trabaja usted ahora?"), spoken)
        assertTrue(line.spokenByStream)
    }

    @Test
    fun cancelMidSentenceStopsAudioAndGeneration() = runTest {
        val model = SlowModel(300, good)
        val sentences = Channel<String>(2)
        val playing = CompletableDeferred<Unit>()
        val synthesized = ArrayList<String>()
        var stoppedPlaying = false
        val turn = launch {
            val speaking = async {
                SpokenReply<String>({ s -> synthesized += s; s }, { _ ->
                    playing.complete(Unit)
                    try { awaitCancellation() } finally { stoppedPlaying = true }
                }).speak(sentences)
            }
            try { session(model).next(speak = { sentences.send(it) }) } finally { sentences.close() }
            speaking.await()
        }
        playing.await()
        turn.cancel()
        turn.join()
        assertTrue(stoppedPlaying, "audio stopped")
        assertTrue(model.cancelled, "generation stopped")
        assertEquals(listOf("Muy bien, gracias."), synthesized, "nothing after the cancel was synthesized")
    }

    @Test
    fun anEngineThatDoesNotStreamStillSpeaksSentenceBySentence() = runTest {
        val model = object : LanguageModel {
            override val id = "endpoint"
            override val isLocal = false
            override suspend fun complete(request: CompletionRequest) = CompletionResult(good.joinToString(""), "endpoint", null)
        }
        val spoken = ArrayList<String>()
        assertTrue(session(model).next(speak = { spoken += it })!!.spokenByStream)
        assertEquals(2, spoken.size)
    }

    @Test
    fun jsonFieldDecodesEscapesAndSurrogatesSplitAcrossPieces() {
        val f = JsonStringField("utterance")
        val json = """{"note":"skip \"me\"","utterance":"Dijo \"hola\"\n¿Sí? 😀 中","x":1}"""
        val out = json.chunked(1).joinToString("") { f.feed(it) }
        assertEquals("Dijo \"hola\"\n¿Sí? 😀 中", out)
        assertTrue(f.closed)
        val g = JsonStringField("utterance")
        assertEquals("", g.feed("""{"utterance_x":"no","other":{"utterance":"nested"}}"""), "only the top-level key")
    }

    @Test
    fun punctuationSplitsEveryLaunchLanguage() {
        val cases = mapOf(
            "ja" to "はい、わかりました。お仕事は何ですか？",
            "zh-Hans" to "好的，谢谢。你在哪儿工作？",
            "ko" to "네, 알겠습니다. 어디에서 일하세요?",
            "ar" to "حسنا، شكرا. أين تعمل الآن؟",
            "fa" to "خیلی خوب. کجا کار می‌کنید؟",
            "ru" to "Хорошо, спасибо. Где вы работаете?",
            "es" to "¡Muy bien! ¿Dónde trabaja usted?",
            "fr" to "Très bien. Où travaillez-vous ?",
            "de" to "Sehr gut. Wo arbeiten Sie?",
            "pt-BR" to "Ótimo, obrigado. Onde você trabalha?",
            "id" to "Baik, terima kasih. Di mana Anda bekerja?",
        )
        cases.forEach { (lang, text) ->
            val ends = PunctuationSentences.ends(text, final = true)
            assertEquals(2, ends.size, "$lang: $ends")
            assertEquals(text.length, ends.last(), lang)
            // Not final: the second sentence isn't complete yet even though it ends with ?, nothing follows it.
            assertEquals(1, PunctuationSentences.ends(text, final = false).size, lang)
        }
        assertEquals(emptyList(), PunctuationSentences.ends("Cuesta 3.5 euros", final = false))
        assertEquals(listOf(5), PunctuationSentences.ends("「はい。」そう", final = false), "the closing quote stays with its sentence")
    }

    @Test
    fun sentenceChecksRunBeforeAnythingIsHandedOut() {
        val s = SentenceStream("reply", PunctuationSentences) { sentence, _ -> if ("mal" in sentence) "bad" else null }
        assertEquals(listOf("Bien."), s.feed("{\"reply\":\"Bien. Muy mal. Otra "))
        assertEquals("bad", s.problem)
        assertEquals(emptyList(), s.feed("cosa.\"}"))
        assertEquals(emptyList(), s.finish())
    }
}

class ReplyDeadlineTest {
    private class FirstSentenceAt(private val ms: Long) : LanguageModel {
        override val id = "slow"
        override val isLocal = true
        var cancelled = false
        override suspend fun complete(request: CompletionRequest): CompletionResult {
            try {
                delay(ms)
                request.onToken?.invoke("{\"utterance\":\"¿Y su familia? ")
                delay(ms)
                request.onToken?.invoke("¿Dónde vive?\"}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            return CompletionResult("{\"utterance\":\"¿Y su familia? ¿Dónde vive?\"}", "slow", null)
        }
    }

    private val bank = listOf(BankQuestion("w1", "warmup", "1", "¿Cómo se llama usted?", "What is your name?", "personal"))

    private fun session(model: LanguageModel, deadline: Long?, bank: List<BankQuestion> = this.bank) =
        OpiSession("es", OpiProfile("es", "usted"), bank, emptyList(), AiGateway({ model }), { it.split(' ').size }, IlrLevel.L1, replyDeadlineMs = deadline)

    @Test
    fun aMissedDeadlinePlaysAPhaseAppropriateScriptedQuestion() = runTest {
        val model = FirstSentenceAt(10_000)
        val spoken = ArrayList<String>()
        val line = session(model, 8_000).next(speak = { spoken += it })!!
        assertEquals(8_000, testScheduler.currentTime, "the learner waited no longer than the deadline")
        assertEquals("¿Cómo se llama usted?", line.text)
        assertNull(line.engine)
        assertEquals(FallbackKind.DEADLINE, FallbackKind.ofReason(line.fallbackReason))
        assertTrue(spoken.isEmpty() && model.cancelled, "nothing half-made was spoken and generation stopped")
    }

    @Test
    fun aFirstSentenceInTimeIsNotCutOffLater() = runTest {
        val line = session(FirstSentenceAt(5_000), 8_000).next(speak = {})!!
        assertTrue(line.spokenByStream, "the reply started within 8 s, so the rest may take longer")
        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test
    fun theDeadlineAlsoCoversTheNonStreamingPathAndOnlyAppliesWithAFallback() = runTest {
        val line = session(FirstSentenceAt(10_000), 8_000).next()!!
        assertEquals(FallbackKind.DEADLINE, FallbackKind.ofReason(line.fallbackReason))
        // An empty bank has nothing to say instead: the session waits for the model.
        assertNotNull(session(FirstSentenceAt(10_000), 8_000, bank = emptyList()).next(speak = {})!!.engine)
    }
}
