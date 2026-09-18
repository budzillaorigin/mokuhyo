package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathItem
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingTest {

    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val settings = SettingsRepository(db, TestClock())
    private val onboarding = Onboarding(settings) { null }

    private fun q(band: Int) = PlacementQuestion(
        PathItem("k:$band-${band * 7}", ItemKind.KANJI, band * 5 + 1, "字", "字", "kw", emptyList(), emptyList(), emptyList(), null, null, null, emptyList()),
        band,
    )

    @Test
    fun beginnerStartsAtLevelOne() {
        val answers = (0 until 6).associate { q(it) to false }
        assertEquals(1, onboarding.suggestedLevel(answers))
    }

    @Test
    fun knowsFirstTwoBands() {
        val answers = mapOf(q(0) to true, q(1) to true, q(2) to false, q(3) to false)
        assertEquals(11, onboarding.suggestedLevel(answers))
    }

    @Test
    fun finishSavesChoices() = runTest {
        assertFalse(onboarding.isDone())
        onboarding.finish(LearningGoal.DLPT, 40, startLevel = 1)
        assertTrue(onboarding.isDone())
        assertEquals(LearningGoal.DLPT, onboarding.goal())
        assertEquals(40, settings.int(SettingsRepository.DAILY_BUDGET_MINUTES, 0))
    }
}
