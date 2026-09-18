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

data class PathStatus(
    val currentLevel: Int,
    val maxLevel: Int,
    /** Share of the current level's kanji at Guru or above. */
    val levelProgress: Double,
    val availableLessons: Int,
    val dueReviews: Int,
    val stageCounts: Map<Stage, Int>,
)

/**
 * The WaniKani-style kanji path (BRIEF §5.4): which lessons are unlocked, taking lessons, and level progress.
 * Path content comes from the read-only pack; progress lives in [SrsRepository] under the same item ids.
 */
class PathService(
    private val pack: PathDatabase,
    private val srs: SrsRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loadLock = Mutex()
    private var cache: Pair<List<PathItem>, UnlockTree>? = null

    private suspend fun loaded(): Pair<List<PathItem>, UnlockTree> = loadLock.withLock {
        cache ?: withContext(Dispatchers.IO) {
            val prereqs = pack.pathQueries.allPrereqs().executeAsList().groupBy({ it.item_id }, { it.prereq_id })
            val items = pack.pathQueries.allItems().executeAsList().map { it.toPathItem(prereqs[it.id].orEmpty()) }
            val tree = UnlockTree(items.map { PathNode(it.id, it.kind, it.level, it.prerequisites) })
            (items to tree).also { cache = it }
        }
    }

    suspend fun items(): List<PathItem> = loaded().first

    suspend fun item(id: String): PathItem? = loaded().first.firstOrNull { it.id == id }

    suspend fun status(): PathStatus {
        val (_, tree) = loaded()
        val stages = srs.stages()
        val level = tree.currentLevel(stages, levelFloor())
        return PathStatus(
            currentLevel = level,
            maxLevel = tree.maxLevel,
            levelProgress = tree.levelProgress(level, stages),
            availableLessons = tree.availableLessons(stages, level, manualUnlocks()).size,
            dueReviews = srs.dueCount(),
            stageCounts = stages.values.groupingBy { it }.eachCount(),
        )
    }

    /** Next lessons in path order (radicals → kanji → vocab, lower levels first). */
    suspend fun lessonQueue(limit: Int = Int.MAX_VALUE): List<PathItem> {
        val (items, tree) = loaded()
        val byId = items.associateBy { it.id }
        val stages = srs.stages()
        val level = tree.currentLevel(stages, levelFloor())
        return tree.availableLessons(stages, level, manualUnlocks()).take(limit).mapNotNull { byId[it.id] }
    }

    suspend fun detail(id: String): PathItemDetail? {
        val (items, _) = loaded()
        val byId = items.associateBy { it.id }
        val item = byId[id] ?: return null
        val note = srs.note(id)
        return PathItemDetail(
            item = item,
            components = item.prerequisites.mapNotNull { byId[it] },
            usedIn = items.filter { id in it.prerequisites },
            stage = srs.stages()[id],
            myStory = note.myStory,
            synonyms = note.synonyms,
        )
    }

    suspend fun saveMyStory(itemId: String, story: String) = srs.saveNote(itemId, myStory = story)

    /** Items of one level with their current stage (null = not started), for the level grid. */
    suspend fun level(level: Int): List<Pair<PathItem, Stage?>> {
        val (items, _) = loaded()
        val stages = srs.stages()
        return items.filter { it.level == level }.map { it to stages[it.id] }
    }

    /** Lesson finished: the items join the user's collection and their cards enter the review queue. */
    suspend fun completeLessons(lessons: List<PathItem>) {
        srs.addItems(lessons.map { it.toNewItem() })
        srs.introduce(lessons.flatMap { item -> directionsFor(item.kind).map { SrsRepository.cardId(item.id, it) } })
    }

    suspend fun skipToLevel(level: Int) = settings.put(SettingsRepository.PATH_LEVEL_FLOOR, level.toString())

    suspend fun unlockManually(itemId: String) {
        val current = manualUnlocks()
        settings.put(SettingsRepository.PATH_MANUAL_UNLOCKS, json.encodeToString((current + itemId).toList()))
    }

    private suspend fun levelFloor() = settings.int(SettingsRepository.PATH_LEVEL_FLOOR, 1)

    private suspend fun manualUnlocks(): Set<String> =
        settings.get(SettingsRepository.PATH_MANUAL_UNLOCKS)?.let { json.decodeFromString<List<String>>(it).toSet() }.orEmpty()

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
