package app.mokuhyo.opi

import app.mokuhyo.exam.IlrLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class OpiProbeMapTest {
    private fun rec(i: Int, target: IlrLevel, outcome: OpiTurnOutcome, domain: String? = null) =
        OpiTurnRecord(i, OpiPhase.PROBE, "q$i", domain = domain, targetLevel = target, levelBefore = target, answer = "a", levelAfter = target, outcome = outcome)

    @Test
    fun floorCeilingAndTallies() {
        val map = OpiProbeMap.from(listOf(
            rec(0, IlrLevel.L1, OpiTurnOutcome.SUSTAINED, "work"),
            rec(1, IlrLevel.L2, OpiTurnOutcome.SUSTAINED, "family"),
            rec(2, IlrLevel.L2_PLUS, OpiTurnOutcome.BREAKDOWN, "abstract"),
            rec(3, IlrLevel.L2, OpiTurnOutcome.PARTIAL, "work"),
        ))
        assertEquals(IlrLevel.L2, map.floor)
        assertEquals(IlrLevel.L2_PLUS, map.ceiling)
        assertEquals(1, map.breakdowns.size)
        assertEquals(listOf("work", "family", "abstract"), map.domains)
        assertEquals(2, map.byLevel.first { it.level == IlrLevel.L2 }.total)
    }
}
