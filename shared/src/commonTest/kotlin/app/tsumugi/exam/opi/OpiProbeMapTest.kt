package app.tsumugi.exam.opi

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.practice.OpiBank
import app.tsumugi.practice.OpiDomain
import app.tsumugi.practice.OpiQuestion
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.tsumugi.practice.OpiPhase as BankPhase

/** DLI topic domains and the "level check → probe" visualizer data (BRIEF_V2 §6.16). */
class OpiProbeMapTest {

    /** Every level has one question per (phase, domain), so the rotation always has a fresh domain to pick. */
    private val banks: Map<IlrLevel, OpiBank> = IlrLevel.lowerRange.associateWith { level ->
        OpiBank(
            level.label,
            BankPhase.entries.flatMap { phase ->
                OpiDomain.entries.map { d -> OpiQuestion(phase, "${level.label}-${phase.name}-${d.name}？", "Q", "", "llm", d) }
            },
            listOf("statement"),
        )
    }

    private fun record(i: Int, phase: OpiPhase, target: IlrLevel, outcome: OpiTurnOutcome, after: IlrLevel = target, domain: OpiDomain? = null) =
        OpiTurnRecord(i, phase, "q$i", domain, target, target, answerLength = 10, levelAfter = after, outcome = outcome)

    @Test
    fun outcomesFollowTheTypicalAnswerLength() {
        val typical = OpiSession.TYPICAL_LENGTH.getValue(IlrLevel.L2) // 35
        assertEquals(OpiTurnOutcome.SUSTAINED, OpiTurnOutcome.judge(typical, IlrLevel.L2))
        assertEquals(OpiTurnOutcome.PARTIAL, OpiTurnOutcome.judge(typical / 2 + 1, IlrLevel.L2))
        assertEquals(OpiTurnOutcome.BREAKDOWN, OpiTurnOutcome.judge(typical / 2 - 1, IlrLevel.L2))
        assertEquals(OpiTurnOutcome.NOT_RATED, OpiTurnOutcome.judge(100, IlrLevel.L4), "no typical length above 3")
    }

    @Test
    fun mapFindsFloorCeilingAndBreakdowns() {
        val map = OpiProbeMap.from(
            listOf(
                record(0, OpiPhase.WARMUP, IlrLevel.L1, OpiTurnOutcome.NOT_RATED, domain = OpiDomain.PERSONAL),
                record(1, OpiPhase.LEVEL_CHECK, IlrLevel.L1, OpiTurnOutcome.SUSTAINED, IlrLevel.L1_PLUS, OpiDomain.FAMILY),
                record(2, OpiPhase.LEVEL_CHECK, IlrLevel.L1_PLUS, OpiTurnOutcome.SUSTAINED, IlrLevel.L2, OpiDomain.WORK),
                record(3, OpiPhase.PROBE, IlrLevel.L2_PLUS, OpiTurnOutcome.BREAKDOWN, IlrLevel.L1_PLUS, OpiDomain.ABSTRACT),
                record(4, OpiPhase.PROBE, IlrLevel.L2, OpiTurnOutcome.PARTIAL, IlrLevel.L1_PLUS, OpiDomain.HYPOTHETICAL),
            ),
        )
        assertEquals(IlrLevel.L1_PLUS, map.floor)
        assertEquals(IlrLevel.L2_PLUS, map.ceiling)
        assertEquals(listOf(3), map.breakdowns.map { it.index })
        assertEquals(listOf(3, 4), map.probes.map { it.index })
        assertEquals(listOf(1, 2), map.levelChecks.map { it.index })
        assertEquals(listOf(IlrLevel.L1, IlrLevel.L1_PLUS, IlrLevel.L2, IlrLevel.L2_PLUS), map.byLevel.map { it.level })
        assertEquals(OpiProbeMap.LevelTally(IlrLevel.L2, 0, 1, 0), map.byLevel.single { it.level == IlrLevel.L2 })
        assertEquals(IlrLevel.L1_PLUS, map.levelTrack.last().second)
        assertEquals(listOf(OpiDomain.CURRENT_EVENTS), map.missingDliDomains)
    }

    @Test
    fun emptyInterviewGivesAnEmptyMap() {
        val map = OpiProbeMap.from(emptyList())
        assertNull(map.floor)
        assertNull(map.ceiling)
        assertTrue(map.byLevel.isEmpty() && map.levelTrack.isEmpty())
        assertEquals(OpiDomain.dli, map.missingDliDomains)
    }

    @Test
    fun sessionLogsEveryTurnAndShowsWhereSpeechBrokeDown() = runTest {
        val session = OpiSession(banks, AiGateway({ null }), IlrLevel.L1, random = Random(5))
        val long = "週末はたいてい家族と一緒に近くの公園へ行って、散歩をしたり、お弁当を食べたりします。楽しいです。"
        while (true) {
            val line = session.next() ?: break
            // Strong level checks, then the probes break down.
            session.answer(if (line.phase == OpiPhase.PROBE) "えっと" else long)
        }
        val turns = session.turns
        assertEquals(OpiSession.PLAN.values.sum(), turns.size)
        assertTrue(turns.all { it.answerLength != null && it.levelAfter != null })
        assertTrue(turns.filter { it.phase == OpiPhase.PROBE }.all { it.outcome == OpiTurnOutcome.BREAKDOWN })
        assertTrue(turns.filter { it.phase == OpiPhase.WARMUP }.all { it.outcome == OpiTurnOutcome.NOT_RATED })
        assertTrue(turns.filter { it.isProbe }.all { it.targetLevel > it.levelBefore || it.levelBefore == IlrLevel.L3 })
        val map = session.probeMap()
        assertTrue(map.breakdowns.isNotEmpty())
        val floor = map.floor!!
        val ceiling = map.ceiling!!
        assertTrue(ceiling > IlrLevel.L1 && floor >= IlrLevel.L1, "floor $floor ceiling $ceiling")
    }

    @Test
    fun scriptedQuestionsRotateThroughDomains() = runTest {
        val session = OpiSession(banks, AiGateway({ null }), IlrLevel.L2, random = Random(9))
        while (session.next() != null) session.answer("はい、そうですね。")
        val domains = session.turns.mapNotNull { it.domain }
        assertEquals(session.turns.size, domains.size, "every bank question is tagged")
        val first = domains.take(OpiDomain.entries.size)
        assertEquals(first.size, first.toSet().size, "no domain repeats while unused ones remain: $domains")
    }
}
