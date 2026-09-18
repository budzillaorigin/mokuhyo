package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.dictionary.EntrySummary
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class WordListSummary(val id: String, val name: String, val size: Int)

data class WordListEntry(val ref: String, val text: String, val reading: String, val gloss: String) {
    /** JMdict id when the word resolved to a dictionary entry. */
    val entryId: Long? get() = ref.removePrefix("jmdict:").takeIf { ref.startsWith("jmdict:") }?.toLongOrNull()
}

/**
 * The learner's own collection outside the kanji path: words added from the dictionary (and later the reader),
 * and word lists (BRIEF §5.3 "Add to SRS", word lists; §5.8 sentence mining).
 */
class CollectionService(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val path: suspend () -> PathService?,
    private val clock: Clock = Clock.System,
) {
    private val lists get() = db.userQueries

    /**
     * Adds a dictionary word to reviews (meaning + reading cards), introduced right away since the learner is
     * looking at it. A word that is also on the kanji path uses the path item, so progress isn't split.
     * [context] is an optional example/mined sentence stored with the item.
     */
    @Throws(Exception::class)
    suspend fun addToReviews(entry: DictionaryEntry, context: String? = null): String {
        val pathItem = path()?.item("v:${entry.id}")
        if (pathItem != null) {
            path()?.completeLessons(listOf(pathItem))
            return pathItem.id
        }
        val id = itemId(entry.id)
        val meanings = entry.senses.take(3).flatMap { it.glosses }.distinct().take(8)
        val directions = listOf(CardDirection.MEANING, CardDirection.READING)
        srs.addItems(
            listOf(
                NewItem(
                    id = id, kind = ItemKind.VOCAB, primaryText = entry.headword, reading = entry.reading,
                    meanings = meanings, acceptedReadings = entry.kana.map { it.text }.distinct(), source = ItemSource.USER,
                    directions = directions, jlpt = entry.jlpt, refId = entry.id.toString(), context = context,
                ),
            ),
        )
        srs.introduce(directions.map { SrsRepository.cardId(id, it) })
        return id
    }

    /** True when the word is already studied, either as an added word or on the kanji path. */
    @Throws(Exception::class)
    suspend fun isInReviews(entryId: Long): Boolean =
        srs.cardsForItems(listOf(itemId(entryId), "v:$entryId")).any { it.fsrs.state != app.tsumugi.srs.CardState.NEW }

    // --- Word lists -----------------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun lists(): List<WordListSummary> = io {
        lists.lists().executeAsList().map { WordListSummary(it.id, it.name, it.size.toInt()) }
    }

    @Throws(Exception::class)
    suspend fun createList(name: String): String = io {
        val now = clock.now().toEpochMilliseconds()
        Uuid.random().toString().also { lists.insertList(it, name.trim().ifEmpty { "Word list" }, now, now) }
    }

    @Throws(Exception::class)
    suspend fun renameList(listId: String, name: String) = io { lists.renameList(name, clock.now().toEpochMilliseconds(), listId) }

    @Throws(Exception::class)
    suspend fun deleteList(listId: String) = io { lists.deleteList(clock.now().toEpochMilliseconds(), listId) }

    @Throws(Exception::class)
    suspend fun addToList(listId: String, entry: EntrySummary) = io {
        lists.putListEntry(listId, "jmdict:${entry.id}", entry.headword, entry.reading, entry.glossPreview, clock.now().toEpochMilliseconds())
    }

    @Throws(Exception::class)
    suspend fun removeFromList(listId: String, ref: String) = io { lists.removeListEntry(clock.now().toEpochMilliseconds(), listId, ref) }

    @Throws(Exception::class)
    suspend fun entries(listId: String): List<WordListEntry> = io {
        lists.listEntries(listId).executeAsList().map { WordListEntry(it.ref, it.text, it.reading, it.gloss) }
    }

    @Throws(Exception::class)
    suspend fun listsContaining(entryId: Long): List<String> = io { lists.listsContaining("jmdict:$entryId").executeAsList() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        fun itemId(entryId: Long) = "jmdict:$entryId"
    }
}
