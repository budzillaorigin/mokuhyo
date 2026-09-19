package app.tsumugi.tracks

import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.WordState
import app.tsumugi.decks.DeckLessons
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathStatus
import app.tsumugi.study.LessonSession
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Interest and domain tracks for the learner (BRIEF_V2 §6.5, DECISIONS D-210…D-219).
 *
 * - **Selection** is a synced setting ([SELECTED], a JSON array of track ids in the order chosen), set in onboarding
 *   ([chooseInOnboarding]) and changed any time ([select], [deselect], [switchTo]).
 * - **Lessons:** the selected tracks' words (round-robin across tracks, each in study order, skipping words the learner
 *   knows or already studies) join Today's lesson block *alongside* the main path: [mixInto] gives them up to half of
 *   every batch and interleaves them with the path/deck batch (the same shape as [DeckLessons], D-154), and [adjust]
 *   counts them in Today's available lessons. Finished track words join reviews through CollectionService.addToReviews.
 * - **Can-do self-checks** for daily-life situations are a synced setting too ([CAN_DO]).
 */
class TrackService(
    private val settings: SettingsRepository,
    private val knowledge: LearnerKnowledge,
    private val repository: suspend () -> TrackRepository?,
    private val dictionary: suspend () -> DictionaryRepository?,
    /** Adds a word to reviews with an optional context sentence (CollectionService.addToReviews). */
    private val addToReviews: suspend (DictionaryEntry, String?) -> String,
) {
    private val json = Json

    // --- selection -------------------------------------------------------------------------------------------

    /** Selected track ids, in the order the learner chose them (unknown ids from a newer pack are kept). */
    @Throws(Exception::class)
    suspend fun selected(): List<String> =
        settings.get(SELECTED)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    /** Every track in the pack with the learner's selection and words left; empty without the pack. */
    @Throws(Exception::class)
    suspend fun tracks(): List<TrackSummary> {
        val repo = repository() ?: return emptyList()
        val chosen = selected().toSet()
        val snapshot = knowledge.snapshot()
        return repo.tracks().map { t ->
            TrackSummary(t, t.id in chosen, repo.words(t.id).count { snapshot.word(it.entryId, it.text) == WordState.UNKNOWN })
        }
    }

    /** The onboarding picker: all tracks, none preselected unless the learner already chose some. */
    @Throws(Exception::class)
    suspend fun onboardingOptions(): List<TrackSummary> = tracks()

    /** Saves the onboarding choice (replaces the selection; an empty list means "main path only"). */
    @Throws(Exception::class)
    suspend fun chooseInOnboarding(trackIds: List<String>) = save(trackIds.distinct())

    /** Adds [trackId] to the selection (kept after the ones already chosen). */
    @Throws(Exception::class)
    suspend fun select(trackId: String) {
        val now = selected()
        if (trackId !in now) save(now + trackId)
    }

    @Throws(Exception::class)
    suspend fun deselect(trackId: String) = save(selected() - trackId)

    /** Makes [trackId] the only selected track. */
    @Throws(Exception::class)
    suspend fun switchTo(trackId: String) = save(listOf(trackId))

    private suspend fun save(ids: List<String>) = settings.put(SELECTED, json.encodeToString(ids))

    // --- lessons ---------------------------------------------------------------------------------------------

    /** Track words still to learn (not known, not in SRS), round-robin across the selected tracks. */
    @Throws(Exception::class)
    suspend fun remaining(limit: Int = Int.MAX_VALUE): List<TrackWord> {
        val ids = selected()
        if (ids.isEmpty() || limit <= 0) return emptyList()
        val repo = repository() ?: return emptyList()
        val snapshot = knowledge.snapshot()
        val seen = HashSet<Long>()
        val perTrack = ids.map { id ->
            repo.words(id).filter { snapshot.word(it.entryId, it.text) == WordState.UNKNOWN }
        }
        return roundRobin(perTrack).filter { seen.add(it.entryId) }.take(limit).toList()
    }

    @Throws(Exception::class)
    suspend fun available(): Int = remaining().size

    /** The next [limit] track words as lesson items (id `jmdict:<entry>`, the same items deck lessons use). */
    @Throws(Exception::class)
    suspend fun queue(limit: Int): List<PathItem> {
        if (limit <= 0) return emptyList()
        val dict = dictionary() ?: return emptyList()
        return remaining(limit).mapNotNull { w -> dict.entry(w.entryId)?.entry?.let { DeckLessons.toLessonItem(it) } }
    }

    /** Adds finished track lesson items to reviews. */
    @Throws(Exception::class)
    suspend fun complete(items: List<PathItem>) {
        if (items.isEmpty()) return
        val dict = dictionary() ?: return
        for (item in items) {
            val entry = item.entryId?.let { dict.entry(it)?.entry } ?: continue
            addToReviews(entry, null)
        }
    }

    /**
     * Today's lesson batch with the selected tracks mixed in: [base] is the main path (or active deck) batch built for
     * `size - share(size)` items, or null when there is none. Track words take the rest (all of it when [base] is
     * null or short), interleaved path-first. Returns [base] unchanged when no track is selected or nothing is left,
     * and null only when neither has anything.
     */
    @Throws(Exception::class)
    suspend fun mixInto(base: LessonSession?, size: Int, random: Random = Random.Default): LessonSession? {
        if (selected().isEmpty()) return base
        val baseItems = base?.batch.orEmpty()
        val trackItems = queue(size - baseItems.size)
        if (trackItems.isEmpty()) return base
        val trackIds = trackItems.map { it.id }.toSet()
        val batch = DeckLessons.interleave(baseItems, trackItems, size)
        return LessonSession(batch, random) { done ->
            val (fromTracks, fromBase) = done.partition { it.id in trackIds }
            if (fromBase.isNotEmpty()) base?.completeItems(fromBase)
            complete(fromTracks)
        }
    }

    /** How many of a [size] lesson batch go to track words when a track is selected (up to half, at least one). */
    @Throws(Exception::class)
    suspend fun share(size: Int): Int = if (selected().isEmpty() || size <= 1) 0 else minOf(size / 2, available())

    /** [status] with track lessons counted in `availableLessons` (they come on top of the path's, D-213). */
    @Throws(Exception::class)
    suspend fun adjust(status: PathStatus?): PathStatus? {
        status ?: return null
        if (selected().isEmpty()) return status
        return status.copy(availableLessons = status.availableLessons + available())
    }

    // --- can-do self-checks ----------------------------------------------------------------------------------

    /** Can-do statements the learner has ticked ([TrackSituation.canDoId]). */
    @Throws(Exception::class)
    suspend fun canDoDone(): Set<String> =
        settings.get(CAN_DO)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty().toSet()

    @Throws(Exception::class)
    suspend fun setCanDo(canDoId: String, done: Boolean) {
        val now = canDoDone()
        val next = if (done) now + canDoId else now - canDoId
        if (next != now) settings.put(CAN_DO, json.encodeToString(next.sorted()))
    }

    companion object {
        /** Synced settings: selected track ids (JSON array) and ticked can-do statements (JSON array). */
        const val SELECTED = "tracks.selected"
        const val CAN_DO = "tracks.canDo"

        /** a0, b0, c0, a1, b1, … (lists of different lengths). */
        fun <T> roundRobin(lists: List<List<T>>): Sequence<T> = sequence {
            var i = 0
            while (lists.any { i < it.size }) {
                for (l in lists) if (i < l.size) yield(l[i])
                i++
            }
        }
    }
}
