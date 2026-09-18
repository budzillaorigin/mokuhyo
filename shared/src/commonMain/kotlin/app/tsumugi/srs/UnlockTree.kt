package app.tsumugi.srs

import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage

/** One item of the 60-level path. [prerequisites] must all reach Guru before this item unlocks. */
data class PathNode(val id: String, val kind: ItemKind, val level: Int, val prerequisites: List<String>)

/**
 * WaniKani-style unlock rules over FSRS-backed stages (BRIEF §5.4):
 * - radicals of the current level (and below) are available immediately;
 * - a kanji unlocks when every radical it is built from has reached Guru;
 * - a vocabulary word unlocks when every kanji it is written with has reached Guru;
 * - the level advances when 90% of its kanji have reached Guru.
 * Nothing above the current level unlocks, except items the user unlocked by hand.
 */
class UnlockTree(nodes: List<PathNode>) {

    private val byId = nodes.associateBy { it.id }
    private val byLevel = nodes.groupBy { it.level }
    /** Pack position of each node (the order [nodes] came in: level, then the pack's `ord`). */
    private val position = nodes.withIndex().associate { (i, n) -> n.id to i }
    val maxLevel: Int = nodes.maxOfOrNull { it.level } ?: 0

    init {
        for (n in nodes) for (p in n.prerequisites) {
            val pre = byId[p] ?: continue
            require(pre.level <= n.level) { "${n.id} (level ${n.level}) depends on ${pre.id} (level ${pre.level})" }
        }
    }

    fun node(id: String): PathNode? = byId[id]

    fun nodesAt(level: Int): List<PathNode> = byLevel[level].orEmpty()

    /**
     * The level the learner is on. [stages] maps item id → current stage (null/absent = not started).
     * [floor] lets the user skip ahead (manual level override or "I already know these kanji").
     */
    fun currentLevel(stages: Map<String, Stage?>, floor: Int = 1): Int {
        var level = floor.coerceIn(1, maxOf(1, maxLevel))
        while (level < maxLevel && isPassed(level, stages)) level++
        return level
    }

    /** True when ≥ 90% of the level's kanji are at Guru or above (a level with no kanji passes trivially). */
    fun isPassed(level: Int, stages: Map<String, Stage?>): Boolean {
        val kanji = nodesAt(level).filter { it.kind == ItemKind.KANJI }
        if (kanji.isEmpty()) return true
        val guru = kanji.count { stages[it.id].atLeastGuru() }
        return guru * 100 >= kanji.size * PASS_PERCENT
    }

    /**
     * Items whose lessons may be taken now (includes ones already started): every item up to [throughLevel] whose
     * prerequisites are at Guru, plus [manual] (items unlocked earlier, persisted; see PathService).
     */
    fun unlocked(stages: Map<String, Stage?>, throughLevel: Int, manual: Set<String> = emptySet()): Set<String> {
        val out = LinkedHashSet<String>(manual)
        for (level in 1..throughLevel) {
            for (n in nodesAt(level)) {
                if (n.prerequisites.all { pre -> byId[pre] == null || stages[pre].atLeastGuru() }) out += n.id
            }
        }
        return out
    }

    /**
     * Unlocked items not yet started, in path order: lower levels first, radicals → kanji → vocab, then the pack's
     * own order within the level (BRIEF_V2 F-28).
     */
    fun availableLessons(stages: Map<String, Stage?>, throughLevel: Int, manual: Set<String> = emptySet()): List<PathNode> =
        unlocked(stages, throughLevel, manual)
            .filter { stages[it] == null }
            .mapNotNull { byId[it] }
            .sortedWith(compareBy({ it.level }, { KIND_ORDER.indexOf(it.kind) }, { position[it.id] ?: Int.MAX_VALUE }))

    /**
     * Levels passed in a row starting at [from], by the pass criterion ([isPassed]) against [stagesAt] (item
     * stages of one level, fetched per level). Returns the highest level passed, or `from - 1` when [from] isn't.
     * Never goes past the second-to-last level (the last level has nowhere to advance to).
     */
    suspend fun highestPassed(from: Int, stagesAt: suspend (List<String>) -> Map<String, Stage?>): Int {
        var level = from.coerceAtLeast(1)
        while (level < maxLevel && isPassed(level, stagesAt(nodesAt(level).filter { it.kind == ItemKind.KANJI }.map { it.id }))) level++
        return level - 1
    }

    /** Share of the level's kanji at Guru+ (0..1), for the level progress ring. */
    fun levelProgress(level: Int, stages: Map<String, Stage?>): Double {
        val kanji = nodesAt(level).filter { it.kind == ItemKind.KANJI }
        if (kanji.isEmpty()) return 1.0
        return kanji.count { stages[it.id].atLeastGuru() }.toDouble() / kanji.size
    }

    private fun Stage?.atLeastGuru() = this != null && this >= Stage.GURU

    private companion object {
        const val PASS_PERCENT = 90
        val KIND_ORDER = listOf(ItemKind.RADICAL, ItemKind.KANJI, ItemKind.VOCAB)
    }
}
