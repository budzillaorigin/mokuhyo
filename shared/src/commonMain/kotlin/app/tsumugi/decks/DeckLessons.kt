package app.tsumugi.decks

import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.WordState
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.ItemKind
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathService
import app.tsumugi.srs.PathStatus
import app.tsumugi.study.LessonSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Where new lessons come from (synced setting [DeckLessons.MODE]). */
enum class DeckLessonMode {
    /** Kanji path only (the default). */
    OFF,

    /** Alternate deck words and path items in every batch. */
    INTERLEAVE,

    /** Deck words only; the path waits. */
    DECK_ONLY,
}

/** The active lesson deck and mode. [deckId] is a media deck id or a Core deck id ("core2k"). */
data class DeckLessonSettings(val deckId: String?, val mode: DeckLessonMode) {
    val active: Boolean get() = deckId != null && mode != DeckLessonMode.OFF
}

/**
 * Lessons drawn from a deck (BRIEF_V2 §6.1 "lessons draw from it instead of, or interleaved with, the kanji path";
 * D-154). The deck and mode are synced settings. Deck words become lesson items shaped like path items (so the same
 * lesson screens work) and join reviews through CollectionService.addToReviews with their media sentence as
 * context; a word that is also on the kanji path uses the path item. Words the learner knows or already studies are
 * skipped. Today counts deck lessons through [adjust] (no planner change).
 */
class DeckLessons(
    private val db: TsumugiDatabase,
    private val settings: SettingsRepository,
    private val knowledge: LearnerKnowledge,
    private val dictionary: suspend () -> DictionaryRepository?,
    /** Adds a word to reviews with its context sentence (CollectionService.addToReviews). */
    private val addToReviews: suspend (DictionaryEntry, String?) -> String,
) {
    @Throws(Exception::class)
    suspend fun settings(): DeckLessonSettings {
        val deck = settings.get(DECK)?.takeIf { it.isNotBlank() }
        val mode = settings.get(MODE)?.let { runCatching { DeckLessonMode.valueOf(it) }.getOrNull() } ?: DeckLessonMode.OFF
        return DeckLessonSettings(deck, mode)
    }

    /** "Create deck → study it": makes [deckId] the lesson source ([DeckLessonMode.INTERLEAVE] by default). */
    @Throws(Exception::class)
    suspend fun activate(deckId: String, mode: DeckLessonMode = DeckLessonMode.INTERLEAVE) {
        settings.put(DECK, deckId)
        settings.put(MODE, mode.name)
    }

    @Throws(Exception::class)
    suspend fun setMode(mode: DeckLessonMode) = settings.put(MODE, mode.name)

    @Throws(Exception::class)
    suspend fun deactivate() = settings.put(MODE, DeckLessonMode.OFF.name)

    /** Deck words still to learn (not known, not in SRS), in deck order. */
    @Throws(Exception::class)
    suspend fun remaining(limit: Int = Int.MAX_VALUE): List<DeckWord> {
        val s = settings()
        if (!s.active) return emptyList()
        val snapshot = knowledge.snapshot()
        return source(s.deckId!!).filter { snapshot.word(it.entryId, it.text) == WordState.UNKNOWN }.take(limit)
    }

    @Throws(Exception::class)
    suspend fun available(): Int = remaining().size

    /** The next [limit] deck words as lesson items (id `jmdict:<entry>`). */
    @Throws(Exception::class)
    suspend fun queue(limit: Int): List<PathItem> {
        val dict = dictionary() ?: return emptyList()
        return remaining(limit).mapNotNull { w -> dict.entry(w.entryId)?.entry?.let { toLessonItem(it) } }
    }

    /** Adds finished deck lesson items to reviews, each with its media sentence. */
    @Throws(Exception::class)
    suspend fun complete(items: List<PathItem>) {
        if (items.isEmpty()) return
        val dict = dictionary() ?: return
        val s = settings()
        val contexts = s.deckId?.let { id -> source(id).associate { it.entryId to it.context } }.orEmpty()
        for (item in items) {
            val entryId = item.entryId ?: continue
            val entry = dict.entry(entryId)?.entry ?: continue
            addToReviews(entry, contexts[entryId])
        }
    }

    /**
     * A lesson batch of [size] from the active deck, interleaved with the kanji path ([path] may be null without the
     * path pack), or null when no deck is active (the caller starts a plain path lesson) or nothing is left.
     */
    @Throws(Exception::class)
    suspend fun startSession(path: PathService?, size: Int, random: Random = Random.Default): LessonSession? {
        val s = settings()
        if (!s.active) return null
        val deckItems = queue(size)
        val pathItems = if (s.mode == DeckLessonMode.INTERLEAVE) path?.lessonQueue(size).orEmpty() else emptyList()
        val batch = interleave(pathItems, deckItems, size)
        if (batch.isEmpty()) return null
        val deckIds = deckItems.map { it.id }.toSet()
        return LessonSession(batch, random) { done ->
            val (fromDeck, fromPath) = done.partition { it.id in deckIds }
            if (fromPath.isNotEmpty()) path?.completeLessons(fromPath)
            complete(fromDeck)
        }
    }

    /**
     * [status] with deck lessons counted in `availableLessons`, for Today (interleave: path + deck; deck only: deck).
     */
    @Throws(Exception::class)
    suspend fun adjust(status: PathStatus?): PathStatus? {
        status ?: return null
        val s = settings()
        if (!s.active) return status
        val deck = available()
        return when (s.mode) {
            DeckLessonMode.INTERLEAVE -> status.copy(availableLessons = status.availableLessons + deck)
            DeckLessonMode.DECK_ONLY -> status.copy(availableLessons = deck)
            DeckLessonMode.OFF -> status
        }
    }

    private suspend fun source(deckId: String): List<DeckWord> {
        CoreDeck.byId(deckId)?.let { core ->
            val dict = dictionary() ?: return emptyList()
            val words = dict.frequencyWordsUpTo(core.size)
            val heads = dict.summaries(words.map { it.entryId }).associateBy { it.id }
            return words.map { f -> DeckWord(f.entryId, f.ord, heads[f.entryId]?.headword.orEmpty(), heads[f.entryId]?.reading, f.count, f.count.toDouble(), null, WordState.UNKNOWN) }
        }
        return withContext(Dispatchers.IO) { db.decksQueries.deckWords(deckId).executeAsList() }.map {
            DeckWord(it.entry_id, it.ord.toInt(), it.text, it.reading, it.count.toInt(), it.score, it.context, WordState.UNKNOWN)
        }
    }

    companion object {
        /** Synced settings: the lesson deck id and the [DeckLessonMode]. */
        const val DECK = "decks.lessonDeck"
        const val MODE = "decks.lessonMode"

        /** Alternates path and deck items (path first), then fills with whichever has more, up to [size]. */
        fun <T> interleave(a: List<T>, b: List<T>, size: Int): List<T> {
            val out = ArrayList<T>(size)
            var i = 0
            var j = 0
            while (out.size < size && (i < a.size || j < b.size)) {
                if (i < a.size && (out.size % 2 == 0 || j >= b.size)) out += a[i++] else if (j < b.size) out += b[j++]
            }
            return out
        }

        /** A dictionary word as a lesson item the path lesson screens can show. */
        fun toLessonItem(entry: DictionaryEntry): PathItem {
            val meanings = entry.senses.take(3).flatMap { it.glosses }.distinct().take(8)
            val readings = entry.kana.map { it.text }.distinct()
            return PathItem(
                id = "jmdict:${entry.id}", kind = ItemKind.VOCAB, level = 0, text = entry.headword, display = entry.headword,
                keyword = meanings.firstOrNull().orEmpty(), meanings = meanings, readings = readings, otherReadings = emptyList(),
                entryId = entry.id, heisig = null, jlpt = entry.jlpt, prerequisites = emptyList(),
            )
        }
    }
}
