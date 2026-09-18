package app.tsumugi.srs

import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.RelationKind
import app.tsumugi.domain.Stage
import app.tsumugi.path.db.PathDatabase
import app.tsumugi.path.db.Path_item
import app.tsumugi.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** One radical, kanji or word of the 60-level path, as shipped in kanji-path.sqlite. */
data class PathItem(
    val id: String,
    val kind: ItemKind,
    val level: Int,
    val text: String,
    /** Glyph to show (differs from [text] for radicals RADKFILE writes with a stand-in kanji). */
    val display: String,
    val keyword: String,
    val meanings: List<String>,
    val readings: List<String>,
    val otherReadings: List<String>,
    val entryId: Long?,
    val heisig: Int?,
    val jlpt: Int?,
    val prerequisites: List<String>,
)

/** Everything the lesson and item screens show for one path item. */
data class PathItemDetail(
    val item: PathItem,
    /** Radicals of a kanji, or kanji of a word. */
    val components: List<PathItem>,
    /** Kanji that use this radical, or words that use this kanji. */
    val usedIn: List<PathItem>,
    val stage: Stage?,
    val myStory: String,
    val synonyms: List<String>,
)

/** One cell of the level grid. [stage] is null when the item hasn't been started. */
data class LevelEntry(val item: PathItem, val stage: Stage?)

data class StageCount(val stage: Stage, val count: Int)

data class PathStatus(
    val currentLevel: Int,
    val maxLevel: Int,
    /** Share of the current level's kanji at Guru or above. */
    val levelProgress: Double,
    val availableLessons: Int,
    val dueReviews: Int,
    val stageCounts: Map<Stage, Int>,
) {
    /** [stageCounts] for every stage in order, zeros included (list form for Swift). */
    val stages: List<StageCount> get() = Stage.entries.map { StageCount(it, stageCounts[it] ?: 0) }
}

/**
 * The WaniKani-style kanji path (BRIEF §5.4): which lessons are unlocked, taking lessons, and level progress.
 * Path content comes from the read-only pack; progress lives in [SrsRepository] under the same item ids.
 */
class PathService(
    private val pack: PathDatabase,
    private val srs: SrsRepository,
    private val settings: SettingsRepository,
    private val store: PathProgressStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loadLock = Mutex()
    private var cache: Pair<List<PathItem>, UnlockTree>? = null

    init {
        // Record a passed level the moment an answer meets the pass criterion, before any later lapse (rule 11).
        srs.addReviewListener { card -> if (loaded().second.node(card.itemId)?.kind == ItemKind.KANJI) recordProgress() }
    }

    /** What the path shows right now, from persisted progress plus live stages. */
    private class Snapshot(
        val tree: UnlockTree,
        val stages: Map<String, Stage>,
        val currentLevel: Int,
        val available: List<PathNode>,
    )

    /**
     * The persisted level decides which levels are open: lessons come from `1..max(passed + 1, current)`, where
     * current only counts levels passed *now* on top of the persisted level, so a lapse can never close a level.
     * Newly passed levels and newly unlocked items are persisted on the way (BRIEF_V2 F-04, DECISIONS D-041).
     */
    private suspend fun snapshot(): Snapshot {
        val (_, tree) = loaded()
        store.importLegacyManualUnlocks(settings.get(SettingsRepository.PATH_MANUAL_UNLOCKS))
        val stages = srs.stages()
        var passed = store.progress().passedLevel
        val current = tree.currentLevel(stages, passed + 1)
        if (current - 1 > passed && store.recordPassed(current - 1)) passed = current - 1
        val through = maxOf(passed + 1, current).coerceAtMost(maxOf(1, tree.maxLevel))
        val persisted = store.unlocks()
        // Auto unlocks only count up to the open levels (after an explicit reset, higher ones close again).
        val kept = persisted.filter { it.manual || (tree.node(it.itemId)?.level ?: Int.MAX_VALUE) <= through }.map { it.itemId }.toSet()
        val unlocked = tree.unlocked(stages, through, kept)
        val known = persisted.map { it.itemId }.toSet()
        store.addUnlocks(unlocked.filter { it !in known && tree.node(it) != null }, manual = false)
        return Snapshot(tree, stages, through, tree.availableLessons(stages, through, kept))
    }

    /** Persists any level passed since the last check. Uses per-level stage queries, so it is cheap after each review. */
    @Throws(Exception::class)
    suspend fun recordProgress() {
        val (_, tree) = loaded()
        val passed = store.progress().passedLevel
        val highest = tree.highestPassed(passed + 1) { ids -> srs.stagesFor(ids) }
        if (highest > passed) store.recordPassed(highest)
    }

    /** The persisted level: highest level passed and when (0 = none yet). */
    @Throws(Exception::class)
    suspend fun progress(): PathProgress = store.progress()

    /**
     * Explicit, confirmed "reset to level N": the only way the level goes down. Lessons above [level] close again;
     * items already learned keep their reviews.
     */
    @Throws(Exception::class)
    suspend fun resetToLevel(level: Int) = store.resetTo(level)

    private suspend fun loaded(): Pair<List<PathItem>, UnlockTree> = loadLock.withLock {
        cache ?: withContext(Dispatchers.IO) {
            val prereqs = pack.pathQueries.allPrereqs().executeAsList().groupBy({ it.item_id }, { it.prereq_id })
            val items = pack.pathQueries.allItems().executeAsList().map { it.toPathItem(prereqs[it.id].orEmpty()) }
            val tree = UnlockTree(items.map { PathNode(it.id, it.kind, it.level, it.prerequisites) })
            (items to tree).also { cache = it }
        }
    }

    @Throws(Exception::class)
    suspend fun items(): List<PathItem> = loaded().first

    @Throws(Exception::class)
    suspend fun item(id: String): PathItem? = loaded().first.firstOrNull { it.id == id }

    @Throws(Exception::class)
    suspend fun status(): PathStatus {
        val s = snapshot()
        return PathStatus(
            currentLevel = s.currentLevel,
            maxLevel = s.tree.maxLevel,
            levelProgress = s.tree.levelProgress(s.currentLevel, s.stages),
            availableLessons = s.available.size,
            dueReviews = srs.dueCount(),
            stageCounts = s.stages.values.groupingBy { it }.eachCount(),
        )
    }

    /** Next lessons in path order (lower levels first; radicals → kanji → vocab; then pack order). */
    @Throws(Exception::class)
    suspend fun lessonQueue(limit: Int = Int.MAX_VALUE): List<PathItem> {
        val (items, _) = loaded()
        val byId = items.associateBy { it.id }
        return snapshot().available.take(limit).mapNotNull { byId[it.id] }
    }

    @Throws(Exception::class)
    suspend fun detail(id: String): PathItemDetail? {
        val (items, _) = loaded()
        val byId = items.associateBy { it.id }
        val item = byId[id] ?: return null
        val note = srs.note(id)
        return PathItemDetail(
            item = item,
            components = item.prerequisites.mapNotNull { byId[it] },
            usedIn = items.filter { id in it.prerequisites },
            stage = srs.stagesFor(listOf(id))[id],
            myStory = note.myStory,
            synonyms = note.synonyms,
        )
    }

    @Throws(Exception::class)
    suspend fun saveMyStory(itemId: String, story: String) = srs.saveNote(itemId, myStory = story)

    /** Items of one level with their current stage (null = not started), for the level grid. */
    @Throws(Exception::class)
    suspend fun level(level: Int): List<LevelEntry> {
        val (items, _) = loaded()
        val atLevel = items.filter { it.level == level }
        val stages = srs.stagesFor(atLevel.map { it.id })
        return atLevel.map { LevelEntry(it, stages[it.id]) }
    }

    /**
     * Lesson finished: the items join the user's collection and their cards enter the review queue. Kanji also
     * get a writing card when the learner turned writing cards on.
     */
    @Throws(Exception::class)
    suspend fun completeLessons(lessons: List<PathItem>) {
        val writing = settings.bool(SettingsRepository.WRITING_CARDS, false)
        fun directions(item: PathItem) = directionsFor(item.kind) + if (writing && item.kind == ItemKind.KANJI) listOf(CardDirection.WRITING) else emptyList()
        srs.addItems(lessons.map { it.toNewItem().copy(directions = directions(it)) })
        srs.introduce(lessons.flatMap { item -> directions(item).map { SrsRepository.cardId(item.id, it) } })
    }

    /** Skip ahead (onboarding, "I already know these"): levels below [level] count as passed. Never lowers the level. */
    @Throws(Exception::class)
    suspend fun skipToLevel(level: Int) {
        store.recordPassed(level - 1)
    }

    /** Unlocks one item by hand; a per-item row that syncs by set union. */
    @Throws(Exception::class)
    suspend fun unlockManually(itemId: String) = store.addUnlocks(listOf(itemId), manual = true)

    private fun PathItem.toNewItem() = NewItem(
        id = id,
        kind = kind,
        primaryText = text,
        reading = readings.firstOrNull(),
        meanings = meanings,
        acceptedReadings = readings,
        source = ItemSource.PACK,
        directions = directionsFor(kind),
        level = level,
        jlpt = jlpt,
        packId = PACK_ID,
        refId = entryId?.toString() ?: text,
        relations = prerequisites.map { it to RelationKind.COMPONENT },
    )

    private fun Path_item.toPathItem(prereqs: List<String>) = PathItem(
        id = id,
        kind = ItemKind.valueOf(kind),
        level = level.toInt(),
        text = text,
        display = display,
        keyword = keyword,
        meanings = json.decodeFromString(meanings),
        readings = json.decodeFromString(readings),
        otherReadings = json.decodeFromString(other_readings),
        entryId = entry_id,
        heisig = heisig?.toInt(),
        jlpt = jlpt?.toInt(),
        prerequisites = prereqs,
    )

    companion object {
        const val PACK_ID = "kanji-path"

        fun directionsFor(kind: ItemKind): List<CardDirection> = when (kind) {
            ItemKind.RADICAL -> listOf(CardDirection.MEANING)
            else -> listOf(CardDirection.MEANING, CardDirection.READING)
        }
    }
}
