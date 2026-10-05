package app.mokuhyo.standto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-12 gate: planner tests — 8–10 minutes, the band's clip, and no speaking turn without a model. */
class StandToTest {
    private val candidates = mapOf("1+" to listOf("es-dl-1p-news-001", "es-dl-1p-news-002"), "2" to listOf("es-dl-2-news-001"))

    @Test
    fun fullRecipeWithAModel() {
        val r = StandToPlanner.plan(hasModel = true, listeningLevel = "2", candidates = candidates, random = Random(1))
        assertEquals(listOf(StandToPlanner.StepKind.NUMBERS, StandToPlanner.StepKind.LEXICON, StandToPlanner.StepKind.LISTENING, StandToPlanner.StepKind.SPEAKING),
            r.steps.map { it.kind })
        assertEquals("es-dl-2-news-001", r.steps[2].passageId)
        assertEquals(3, r.steps[1].count)
        assertTrue(r.minutes in 8.0..10.0, "${r.minutes}")
    }

    @Test
    fun noSpeakingTurnWithoutAModel() {
        val r = StandToPlanner.plan(hasModel = false, listeningLevel = null, candidates = candidates, random = Random(1))
        assertTrue(r.steps.none { it.kind == StandToPlanner.StepKind.SPEAKING })
        assertEquals("1+", r.steps.single { it.kind == StandToPlanner.StepKind.LISTENING }.level, "no estimate yet → 1+")
        assertTrue(r.minutes in 8.0..10.0, "${r.minutes}")
    }

    @Test
    fun nearestBandWhenTheLearnersBandIsEmptyAndNoClipAtAll() {
        val r = StandToPlanner.plan(hasModel = true, listeningLevel = "3", candidates = candidates, random = Random(1))
        assertEquals("2", r.steps.single { it.kind == StandToPlanner.StepKind.LISTENING }.level)
        val none = StandToPlanner.plan(hasModel = true, listeningLevel = "2", candidates = emptyMap(), random = Random(1))
        assertNull(none.steps.firstOrNull { it.kind == StandToPlanner.StepKind.LISTENING })
        assertTrue(none.minutes in 8.0..10.0, "${none.minutes}")
        val noneNoModel = StandToPlanner.plan(hasModel = false, listeningLevel = null, candidates = emptyMap(), random = Random(1))
        assertTrue(noneNoModel.minutes in 8.0..10.0, "${noneNoModel.minutes}")
    }

    @Test
    fun weeklySummary() {
        val day = 86_400_000L
        val now = 20 * day + 5
        val recs = listOf(
            now to StandToResult(5, 6, 3, true, true, 9.0),
            now - 2 * day to StandToResult(4, 6, 3, false, false, 8.5),
            now - 9 * day to StandToResult(6, 6, 3, true, true, 9.0), // outside the week
        )
        val w = WeeklySummary.of(recs, now) { it / day }
        assertEquals(2, w.days)
        assertEquals(2, w.sessions)
        assertEquals(75, w.numbersAccuracy)
        assertEquals(6, w.termsReviewed)
        assertEquals(1 to 2, w.listeningRight to w.listeningTotal)
        assertEquals(17, w.minutes)
    }
}
