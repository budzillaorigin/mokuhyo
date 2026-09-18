package app.tsumugi.srs

import app.tsumugi.domain.ItemKind.KANJI
import app.tsumugi.domain.ItemKind.RADICAL
import app.tsumugi.domain.ItemKind.VOCAB
import app.tsumugi.domain.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnlockTreeTest {

    // Level 1: radicals 口 and 一, kanji 口 (needs r:口) and 日 (needs r:口, r:一), vocab 日本 needs k:日 and k:本 (level 2).
    private val nodes = listOf(
        PathNode("r:口", RADICAL, 1, emptyList()),
        PathNode("r:一", RADICAL, 1, emptyList()),
        PathNode("k:口", KANJI, 1, listOf("r:口")),
        PathNode("k:日", KANJI, 1, listOf("r:口", "r:一")),
        PathNode("v:口", VOCAB, 1, listOf("k:口")),
        PathNode("r:木", RADICAL, 2, emptyList()),
        PathNode("k:本", KANJI, 2, listOf("r:木", "r:一")),
        PathNode("v:日本", VOCAB, 2, listOf("k:日", "k:本")),
    )
    private val tree = UnlockTree(nodes)

    @Test
    fun startsWithOnlyRadicalsOfLevelOne() {
        val stages = emptyMap<String, Stage?>()
        assertEquals(1, tree.currentLevel(stages))
        assertEquals(setOf("r:口", "r:一"), tree.unlocked(stages, 1))
        assertEquals(listOf("r:口", "r:一"), tree.availableLessons(stages, 1).map { it.id })
    }

    @Test
    fun kanjiNeverUnlocksBeforeItsComponents() {
        // Apprentice radicals do not unlock anything.
        val apprentice = mapOf("r:口" to Stage.APPRENTICE, "r:一" to Stage.APPRENTICE)
        assertFalse("k:口" in tree.unlocked(apprentice, 1))

        val oneGuru = mapOf("r:口" to Stage.GURU, "r:一" to Stage.APPRENTICE)
        val unlocked = tree.unlocked(oneGuru, 1)
        assertTrue("k:口" in unlocked)
        assertFalse("k:日" in unlocked, "日 still waits for 一")
    }

    @Test
    fun vocabWaitsForAllItsKanjiAndForItsLevel() {
        val stages = mapOf(
            "r:口" to Stage.GURU, "r:一" to Stage.GURU, "k:口" to Stage.GURU, "k:日" to Stage.GURU,
        )
        assertTrue("v:口" in tree.unlocked(stages, 1))
        assertFalse("v:日本" in tree.unlocked(stages, 1), "level 2 item")
        assertFalse("v:日本" in tree.unlocked(stages, 2), "本 not yet Guru")
        assertTrue("v:日本" in tree.unlocked(stages + ("r:木" to Stage.GURU) + ("k:本" to Stage.MASTER), 2))
    }

    @Test
    fun levelAdvancesAtNinetyPercentOfKanji() {
        val half = mapOf("k:口" to Stage.GURU, "k:日" to Stage.APPRENTICE)
        assertEquals(1, tree.currentLevel(half))
        val all = mapOf("k:口" to Stage.GURU, "k:日" to Stage.BURNED)
        assertEquals(2, tree.currentLevel(all))
        assertEquals(0.5, tree.levelProgress(1, half))
    }

    @Test
    fun ninetyPercentThresholdIsInclusive() {
        val big = (1..10).map { PathNode("k:$it", KANJI, 1, emptyList()) } + PathNode("k:x", KANJI, 2, emptyList())
        val t = UnlockTree(big)
        val nine = (1..9).associate { "k:$it" to Stage.GURU }
        assertTrue(t.isPassed(1, nine))
        assertFalse(t.isPassed(1, nine - "k:9"))
    }

    @Test
    fun manualOverrides() {
        assertEquals(2, tree.currentLevel(emptyMap(), floor = 2), "skip level")
        assertTrue("k:本" in tree.unlocked(emptyMap(), 1, manual = setOf("k:本")), "manual unlock")
    }

    @Test
    fun rejectsPrerequisiteFromHigherLevel() {
        assertFailsWith<IllegalArgumentException> {
            UnlockTree(listOf(PathNode("r:x", RADICAL, 3, emptyList()), PathNode("k:x", KANJI, 2, listOf("r:x"))))
        }
    }

    @Test
    fun stageThresholds() {
        assertEquals(null, Stage.of(null, started = false))
        assertEquals(Stage.APPRENTICE, Stage.of(null, started = true))
        assertEquals(Stage.APPRENTICE, Stage.of(2.9, true))
        assertEquals(Stage.GURU, Stage.of(3.0, true))
        assertEquals(Stage.MASTER, Stage.of(30.0, true))
        assertEquals(Stage.ENLIGHTENED, Stage.of(90.0, true))
        assertEquals(Stage.BURNED, Stage.of(400.0, true))
    }

    /** BRIEF_V2 F-17: every started card has a stage, even with no or out-of-range stability. */
    @Test
    fun stageIsTotalForStartedCards() {
        assertEquals(Stage.APPRENTICE, Stage.of(null, true))
        assertEquals(Stage.APPRENTICE, Stage.of(Double.NaN, true))
        assertEquals(Stage.APPRENTICE, Stage.of(-1.0, true))
        assertEquals(null, Stage.of(5.0, false))
    }

    /** BRIEF_V2 F-28: within a level and kind, lessons follow the pack's order, not hash order. */
    @Test
    fun lessonOrderFollowsPackPosition() {
        val ids = listOf("r:山", "r:川", "r:口", "r:一", "r:人", "r:木", "r:水", "r:火", "r:土", "r:金", "r:月", "r:日", "r:目", "r:耳", "r:手", "r:足")
        val packOrder = ids.map { PathNode(it, RADICAL, 1, emptyList()) } + PathNode("k:林", KANJI, 1, listOf("r:木"))
        val t = UnlockTree(packOrder)
        assertEquals(ids, t.availableLessons(emptyMap(), 1).map { it.id })
        // Kind still comes before pack position: a kanji listed first in the pack comes after the radicals.
        val kanjiFirst = UnlockTree(listOf(PathNode("k:林", KANJI, 1, emptyList())) + ids.map { PathNode(it, RADICAL, 1, emptyList()) })
        assertEquals(ids + "k:林", kanjiFirst.availableLessons(emptyMap(), 1).map { it.id })
    }
}
