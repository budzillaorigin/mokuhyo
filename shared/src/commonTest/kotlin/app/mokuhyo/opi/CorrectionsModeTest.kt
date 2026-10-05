package app.mokuhyo.opi

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.FakeModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIEF_PHASE8 C-11 gate: an identical turn produces identical correction records in Live and After-action, differing
 * only in when they surface; Off records nothing beyond the transcript; the interview feedback queue yields the same
 * record as a direct run and never runs while the interviewer is generating; the After Action Brief builds from records.
 */
class CorrectionsModeTest {
    private val turn = """{"reply":"Entendido, mi coronel. ¿Algo más?","reply_english":"Understood, colonel. Anything else?",
        "corrected":"Mi coronel, el dron está sobre la puerta norte.","changes":[{"from":"Oye","to":"Mi coronel","why":"Address a senior by rank."}],
        "rewrite":"Mi coronel, tenemos un dron sobre la puerta norte.","vocabulary":[{"word":"dron","meaning":"drone","example":"El dron vuela bajo."}],
        "turn_level":"1+","pragmatics":[{"kind":"register","severity":"high","what":"Oye","why":"Too casual for a senior officer.","better":"Mi coronel"}]}"""
    private val profile = OpiProfile("es", "usted with officers")
    private val topic = Topic("t", "military_operations", "Drone sighting", "¿Qué ve?")

    private suspend fun run(mode: CorrectionsMode): TopicSession {
        val s = TopicSession("es", profile, topic, AiGateway({ FakeModel(turn, turn) }), mode = mode) // reply, then critique
        s.say("Oye, el dron está sobre la puerta norte.")
        return s
    }

    /** BRIEF_PHASE8 N-00b: a failed critique degrades to "no feedback for this turn"; the partner's reply stands. */
    @Test
    fun critiqueFailureKeepsTheReply() = runTest {
        val fake = FakeModel(turn, "not json", "still not json")
        val s = TopicSession("es", profile, topic, AiGateway({ fake }), mode = CorrectionsMode.LIVE)
        val ex = assertNotNull(s.say("Oye, el dron está sobre la puerta norte."))
        assertEquals("Entendido, mi coronel. ¿Algo más?", ex.reply)
        assertNull(ex.corrected)
        assertTrue(ex.feedbackMissing!!.contains("failed checks"), ex.feedbackMissing)
        assertNull(s.lastError)
        assertEquals(2, s.transcript.size - 1, "the learner's turn and the reply are both in the transcript")
    }

    /** BRIEF_PHASE8 N-00b: when no reply comes, the session says why (the UI shows it instead of a generic line). */
    @Test
    fun replyFailureExposesTheReason() = runTest {
        val s = TopicSession("es", profile, topic, AiGateway({ FakeModel(IllegalStateException("couldn't load model: out of memory")) }))
        assertNull(s.say("Hola"))
        assertEquals("couldn't load model: out of memory", s.lastError)
        assertEquals(1, s.transcript.size, "the unanswered turn is not kept")
        val none = TopicSession("es", profile, topic, AiGateway({ null }))
        assertNull(none.say("Hola"))
        assertEquals("no AI model is set up", none.lastError)
    }

    @Test
    fun liveAndAfterActionRecordTheSame() = runTest {
        val live = run(CorrectionsMode.LIVE).records
        val aa = run(CorrectionsMode.AFTER_ACTION).records
        assertEquals(1, live.size)
        assertEquals(live, aa)
        assertEquals("register", live.single().pragmatics.single().kind)
        assertTrue(CorrectionsMode.LIVE.showsDuringSession && !CorrectionsMode.AFTER_ACTION.showsDuringSession)
    }

    @Test
    fun offRecordsNothing() = runTest {
        val model = FakeModel("""{"reply":"¿Y luego?","reply_english":"And then?"}""")
        val s = TopicSession("es", profile, topic, AiGateway({ model }), mode = CorrectionsMode.OFF)
        val ex = s.say("Vi un dron.")
        assertEquals("¿Y luego?", ex?.reply)
        assertTrue(s.records.isEmpty())
        assertTrue("partner_reply" in model.requests.single().messages.first().content || model.requests.single().messages.none { "pragmatics =" in it.content })
    }

    @Test
    fun opiTestIsAlwaysAfterAction() {
        CorrectionsMode.entries.forEach { assertEquals(CorrectionsMode.AFTER_ACTION, CorrectionsMode.effective(it, opiTest = true)) }
        assertEquals(CorrectionsMode.OFF, CorrectionsMode.effective(CorrectionsMode.OFF, opiTest = false))
        assertEquals(CorrectionsMode.AFTER_ACTION, SpeakingActivity.OPI_PRACTICE.defaultMode)
    }

    private val feedback = """{"corrected":"Me llamo Ana.","changes":[{"from":"Yo llamo","to":"Me llamo","why":"Reflexive verb."}],
        "rewrite":"Me llamo Ana.","vocabulary":[],"turn_level":"1","pragmatics":[]}"""

    @Test
    fun feedbackQueueMatchesADirectRunAndWaitsForTheInterviewer() = runTest {
        val input = TurnFeedback.Input("es", "usted", "¿Cómo se llama?", "Yo llamo Ana.")
        val direct = (AiGateway({ FakeModel(feedback) }).run(TurnFeedback(), input) as app.mokuhyo.ai.AiResult.Ok).value
        val got = mutableListOf<TurnFeedbackRecord>()
        val queue = FeedbackQueue(AiGateway({ FakeModel(feedback) }), backgroundScope) { got += it }
        queue.foregroundBusy(true)
        queue.enqueue(1, input)
        testScheduler.advanceUntilIdle()
        assertTrue(got.isEmpty(), "no feedback while the interviewer is generating")
        queue.drain()
        val r = got.single()
        assertEquals(1, r.turnIndex)
        assertEquals(direct.corrected, r.corrected)
        assertEquals(direct.changes, r.changes)
        assertEquals(direct.turnLevel, r.turnLevel)
        queue.close()
    }

    @Test
    fun afterActionBriefBuildsFromRecords() = runTest {
        val s = run(CorrectionsMode.AFTER_ACTION)
        s.say("Oye, el dron está sobre la puerta norte.") // a second identical error → a recurring pattern
        val summary = """{"next_steps":["Address officers by rank.","Practise reporting a drone sighting.","Use usted throughout."],
            "grammar":[],"register":["Oye to a colonel"],"avoidance":["no past tense"]}"""
        val aab = AfterActionBuilder.build(AiGateway({ FakeModel(summary) }), "es", CorrectionsMode.AFTER_ACTION, SpeakingActivity.PERSONA, "Drone sighting",
            "Col. Ruiz", 95, s.transcript, s.records, s.levelTrack, null) { tag -> if (tag == "rank") "es-card-rank-1" to "Rank first" else null }
        assertEquals(3, aab.nextSteps.size)
        assertEquals(2, aab.records.size)
        assertEquals(listOf("rank"), aab.cultural.map { it.tag })
        assertEquals("Rank first", aab.cultural.single().cardTitle)
        assertEquals(listOf("no past tense"), aab.patterns.avoidance)
        val offline = AfterActionBuilder.build(null, "es", CorrectionsMode.AFTER_ACTION, SpeakingActivity.TOPIC, "t", null, 10, s.transcript, s.records,
            s.levelTrack, null) { null }
        assertTrue(offline.patterns.grammar.single().contains("2×"), "without a model the recurring errors become the patterns")
        assertTrue(offline.nextSteps.isNotEmpty())
        val off = AfterActionBuilder.build(null, "es", CorrectionsMode.OFF, SpeakingActivity.TOPIC, "t", null, 10, s.transcript, emptyList(), emptyList(), null) { null }
        assertTrue(off.records.isEmpty() && off.nextSteps.isEmpty())
    }
}
