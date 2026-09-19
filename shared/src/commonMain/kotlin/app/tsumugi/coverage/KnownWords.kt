package app.tsumugi.coverage

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** A word marked known or not known (`known_word`). */
data class KnownWordRow(val entryId: Long, val text: String, val known: Boolean, val source: String, val updatedAt: Long)

/** One word of the frequency list for the "I know these" flow, with the learner's current state. */
data class FrequencyWord(
    val ord: Int,
    val entryId: Long,
    val headword: String,
    val reading: String,
    val gloss: String,
    val state: WordState,
)

/** A page of the "I know these" flow; pass [nextAfter] to get the next page. */
data class FrequencyBatch(val words: List<FrequencyWord>, val nextAfter: Int, val exhausted: Boolean)

/** How much of one frequency band (words [from]..[to]) the learner knows. */
data class FrequencyBand(val from: Int, val to: Int, val known: Int, val learning: Int, val total: Int)

/**
 * Words the learner knows without an SRS item (BRIEF_V2 §6.1 "known-words import", jpdb's "mark known"): marks from
 * the reader, and the onboarding flow that walks the frequency list in batches so an intermediate learner isn't
 * drilled on 猫. Imports need nothing here: WaniKani, Anki and Bunpro create SRS items, and a Guru+ item already
 * counts as known ([KnowledgeSnapshot]; D-151).
 */
class KnownWords(
    private val db: TsumugiDatabase,
    private val knowledge: LearnerKnowledge,
    private val dictionary: suspend () -> DictionaryRepository?,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.decksQueries

    /** Marks [entryIds] known (synced; LWW per word). */
    @Throws(Exception::class)
    suspend fun markKnown(entryIds: List<Long>, source: String = SOURCE_MANUAL) = set(entryIds, true, source)

    /** Marks [entryIds] not known (undoes a mark; words the learner has in SRS keep their SRS state). */
    @Throws(Exception::class)
    suspend fun markUnknown(entryIds: List<Long>, source: String = SOURCE_MANUAL) = set(entryIds, false, source)

    private suspend fun set(entryIds: List<Long>, known: Boolean, source: String) {
        if (entryIds.isEmpty()) return
        val ids = entryIds.distinct()
        val heads = dictionary()?.summaries(ids)?.associate { it.id to it.headword }.orEmpty()
        withContext(Dispatchers.IO) {
            val now = clock.now().toEpochMilliseconds()
            val flag = if (known) 1L else 0L
            db.transaction {
                for (id in ids) {
                    val text = heads[id].orEmpty()
                    q.insertKnownIfAbsent(id, text, flag, source, now)
                    q.updateKnown(text, flag, source, now, id, flag)
                }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun rows(): List<KnownWordRow> = withContext(Dispatchers.IO) {
        q.knownWordRows().executeAsList().map { KnownWordRow(it.entry_id, it.text, it.known == 1L, it.source, it.updated_at) }
    }

    /** Words marked known (not counting SRS items). */
    @Throws(Exception::class)
    suspend fun count(): Int = withContext(Dispatchers.IO) { q.knownCount().executeAsOne().toInt() }

    /** The learner's state for one word (SRS or mark). */
    @Throws(Exception::class)
    suspend fun state(entryId: Long): WordState = knowledge.snapshot().word(entryId)

    /**
     * The next [size] words of the frequency list after position [afterOrd] that the learner doesn't know yet (SRS
     * Guru+ and marked words are skipped), for the bulk "I know these" review. Empty and exhausted when the
     * dictionary pack has no frequency list.
     */
    @Throws(Exception::class)
    suspend fun frequencyBatch(afterOrd: Int = 0, size: Int = DEFAULT_BATCH): FrequencyBatch {
        val dict = dictionary() ?: return FrequencyBatch(emptyList(), afterOrd, exhausted = true)
        val snapshot = knowledge.snapshot()
        val picked = ArrayList<Pair<Int, Long>>()
        var cursor = afterOrd
        var exhausted = false
        while (picked.size < size) {
            val page = dict.frequencyWords(cursor, PAGE)
            if (page.isEmpty()) { exhausted = true; break }
            for (f in page) {
                cursor = f.ord
                if (snapshot.word(f.entryId) == WordState.KNOWN) continue
                picked += f.ord to f.entryId
                if (picked.size == size) break
            }
        }
        val summaries = dict.summaries(picked.map { it.second }).associateBy { it.id }
        val words = picked.mapNotNull { (ord, id) ->
            val s = summaries[id] ?: return@mapNotNull null
            FrequencyWord(ord, id, s.headword, s.reading, s.glossPreview, snapshot.word(id, s.headword))
        }
        return FrequencyBatch(words, cursor, exhausted)
    }

    /** Known/learning counts per band of [bandSize] words over the first [upTo] words of the frequency list. */
    @Throws(Exception::class)
    suspend fun bands(bandSize: Int = 1000, upTo: Int = 10_000): List<FrequencyBand> {
        val dict = dictionary() ?: return emptyList()
        val snapshot = knowledge.snapshot()
        val words = dict.frequencyWordsUpTo(upTo)
        return words.groupBy { (it.ord - 1) / bandSize }.entries.sortedBy { it.key }.map { (band, ws) ->
            FrequencyBand(
                from = band * bandSize + 1, to = band * bandSize + ws.size,
                known = ws.count { snapshot.word(it.entryId) == WordState.KNOWN },
                learning = ws.count { snapshot.word(it.entryId) == WordState.LEARNING },
                total = ws.size,
            )
        }
    }

    companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_ONBOARDING = "ONBOARDING"
        const val SOURCE_IMPORT = "IMPORT"
        const val DEFAULT_BATCH = 50
        private const val PAGE = 200
    }
}
