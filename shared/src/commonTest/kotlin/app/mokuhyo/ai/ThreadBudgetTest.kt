package app.mokuhyo.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadBudgetTest {
    @Test
    fun criticalPathWorkNeverOversubscribesTheCores() {
        for (cores in 1..32) {
            val c = ThreadBudget.caps(cores)
            val budget = if (cores >= 6) cores - 1 else cores
            assertTrue(c.llm + c.tts <= budget.coerceAtLeast(2), "$cores cores: model + voice ($c)")
            assertTrue(c.asr + c.tts <= budget.coerceAtLeast(2), "$cores cores: recognition + voice ($c)")
            assertTrue(c.llm >= 1 && c.asr >= 1 && c.tts >= 1)
        }
        assertEquals(ThreadCaps(llm = 3, asr = 3, tts = 1), ThreadBudget.caps(4), "a 4-core laptop keeps 3 threads for the model")
        assertEquals(ThreadCaps(llm = 6, asr = 6, tts = 1), ThreadBudget.caps(8), "one core left for audio and the UI")
    }

    @Test
    fun overridesWinAndBlankMeansAutomatic() {
        assertEquals(ThreadCaps(2, 3, 1), ThreadBudget.caps(8, ThreadCaps(llm = 2, asr = 0, tts = 0)).copy(asr = 3))
        assertEquals(ThreadBudget.caps(8), ThreadBudget.caps(8, ThreadCaps(0, 0, 0)))
        assertEquals(64, ThreadBudget.caps(8, ThreadCaps(500, 0, 0)).llm)
    }
}
