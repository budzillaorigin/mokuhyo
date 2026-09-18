package app.tsumugi.srs

import app.tsumugi.db.Card
import app.tsumugi.db.Item
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.RelationKind
import app.tsumugi.domain.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** An item to add to the user's collection (from a pack lesson, an import, or sentence mining). */
data class NewItem(
    val id: String,
    val kind: ItemKind,
    val primaryText: String,
    val reading: String?,
    val meanings: List<String>,
    val acceptedReadings: List<String>,
    val source: ItemSource,
    val directions: List<CardDirection>,
    val level: Int? = null,
    val jlpt: Int? = null,
    val packId: String? = null,
    val refId: String? = null,
    val context: String? = null,
    /** (parent item id, relation) pairs this item points to, e.g. its kanji for vocab. */
    val relations: List<Pair<String, RelationKind>> = emptyList(),
)

/** A study item plus its user note, as the review UI needs it. */
data class StudyItem(
    val id: String,
    val kind: ItemKind,
    val primaryText: String,
    val reading: String?,
    val meanings: List<String>,
    val acceptedReadings: List<String>,
    val source: ItemSource,
    val level: Int?,
    val jlpt: Int?,
    val context: String?,
    val synonyms: List<String>,
    val myStory: String,
)

data class StudyCard(val id: String, val itemId: String, val direction: CardDirection, val fsrs: FsrsCard, val suspended: Boolean) {
    val stage: Stage? get() = Stage.of(fsrs.stability, fsrs.state != CardState.NEW)
}

/** A review imported from elsewhere (Anki revlog, WaniKani history). [externalId] makes re-imports idempotent. */
data class ImportedReview(val cardId: String, val at: Instant, val rating: Rating, val externalId: String, val source: String)

/**
 * Items, cards and the append-only review log (BRIEF §5.4, §8.2).
 *
 * Every answer is stored as an immutable [review] row and the card's FSRS state is updated from it. The card
 * state is only a cache: [recomputeCard] rebuilds it by replaying the card's reviews, which is how two devices
 * that reviewed offline converge. A lesson is recorded as a review with rating [INTRODUCED] (ignored by FSRS)
 * so "this card has been introduced" also survives a replay.
 */
class SrsRepository(
    private val db: TsumugiDatabase,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
    var scheduler: FsrsScheduler = FsrsScheduler(),
) {
    private val q get() = db.srsQueries
    private val json = Json { ignoreUnknownKeys = true }

    // --- Items and cards ----------------------------------------------------------------------------------

    /** Adds or refreshes items and creates their cards (as NEW, i.e. awaiting a lesson). Idempotent. */
    suspend fun addItems(items: List<NewItem>) = io {
        val now = clock.now().toEpochMilliseconds()
        db.transaction {
            for (it in items) {
                val meanings = encode(it.meanings)
                val readings = encode(it.acceptedReadings)
                q.insertItemIfAbsent(
                    it.id, it.kind.name, it.level?.toLong(), it.jlpt?.toLong(), null, it.primaryText, it.reading,
                    meanings, readings, it.source.code, it.packId, it.refId, it.context, now, now,
                )
                q.updateItem(
                    it.kind.name, it.level?.toLong(), it.jlpt?.toLong(), null, it.primaryText, it.reading, meanings,
                    readings, it.source.code, it.packId, it.refId, it.context, now, it.id,
                )
                for ((parent, kind) in it.relations) q.insertRelation(parent, it.id, kind.name)
                for (d in it.directions) q.insertCardIfAbsent(cardId(it.id, d), it.id, d.name, now, now, now)
            }
        }
    }

    /** Marks cards as introduced (lesson done): their first review becomes due after the first learning step. */
    suspend fun introduce(cardIds: List<String>) = io {
        val now = clock.now()
        db.transaction {
            for (id in cardIds) {
                q.insertReview(Uuid.random().toString(), id, now.toEpochMilliseconds(), INTRODUCED.toLong(), 0, null, null, deviceId, "lesson")
                writeCard(id, recompute(id))
            }
        }
    }

    suspend fun item(id: String): StudyItem? = io { q.itemById(id).executeAsOneOrNull()?.toStudyItem() }

    suspend fun items(ids: Collection<String>): Map<String, StudyItem> = io {
        ids.chunked(CHUNK).flatMap { q.itemsByIds(it).executeAsList() }.associate { it.id to it.toStudyItem() }
    }

    suspend fun cardsForItems(itemIds: Collection<String>): List<StudyCard> = io {
        itemIds.chunked(CHUNK).flatMap { q.cardsForItems(it).executeAsList() }.map { it.toStudyCard() }
    }

    suspend fun card(id: String): StudyCard? = io { q.cardById(id).executeAsOneOrNull()?.toStudyCard() }

    suspend fun dueCards(limit: Int = 500, now: Instant = clock.now()): List<StudyCard> = io {
        q.dueCards(now.toEpochMilliseconds(), limit.toLong()).executeAsList().map { it.toStudyCard() }
    }

    suspend fun dueCount(now: Instant = clock.now()): Int = io { q.dueCount(now.toEpochMilliseconds()).executeAsOne().toInt() }

    /** Cards still awaiting a lesson (imported NEW cards), in level order. */
    suspend fun newCards(limit: Int): List<StudyCard> = io { q.newCards(limit.toLong()).executeAsList().map { it.toStudyCard() } }

    suspend fun setSuspended(cardId: String, suspended: Boolean) = io {
        q.setSuspended(if (suspended) 1 else 0, clock.now().toEpochMilliseconds(), cardId)
    }

    /**
     * Stage per started item: the lowest stage among its answer cards (an item is Guru only when both its
     * meaning and reading are). Items with no introduced card are absent.
     */
    suspend fun stages(): Map<String, Stage> = io {
        q.allStartedCards().executeAsList()
            .groupBy { it.item_id }
            .mapValues { (_, cards) -> cards.mapNotNull { Stage.of(it.stability, true) }.min() }
    }

    // --- Reviews ------------------------------------------------------------------------------------------

    data class ReviewOutcome(val reviewId: String, val before: StudyCard, val after: StudyCard)

    suspend fun review(
        cardId: String,
        rating: Rating,
        elapsed: Duration = Duration.ZERO,
        answer: String? = null,
        correct: Boolean? = null,
        at: Instant = clock.now(),
    ): ReviewOutcome = io {
        db.transactionWithResult {
            val before = q.cardById(cardId).executeAsOne().toStudyCard()
            val index = q.reviewsForCard(cardId).executeAsList().count { it.rating != INTRODUCED.toLong() }
            val fsrs = scheduler.review(before.fsrs, rating, at, kotlin.random.Random(FsrsScheduler.fuzzSeed(cardId, index)))
            val id = Uuid.random().toString()
            q.insertReview(
                id, cardId, at.toEpochMilliseconds(), rating.value.toLong(), elapsed.inWholeMilliseconds, answer,
                correct?.let { if (it) 1L else 0L }, deviceId, "app",
            )
            writeCard(cardId, fsrs)
            ReviewOutcome(id, before, before.copy(fsrs = fsrs))
        }
    }

    /** Undo the last answer of a session: removes the (not yet synced) review and restores the card. */
    suspend fun undo(outcome: ReviewOutcome) = io {
        db.transaction {
            q.deleteReview(outcome.reviewId)
            writeCard(outcome.before.id, outcome.before.fsrs)
        }
    }

    /** Adds reviews from another system (idempotent by external id) and replays the affected cards. */
    suspend fun importReviews(reviews: List<ImportedReview>) = io {
        db.transaction {
            for (r in reviews) {
                q.insertReview(
                    "${r.source}:${r.externalId}", r.cardId, r.at.toEpochMilliseconds(), r.rating.value.toLong(), 0, null,
                    null, deviceId, r.source,
                )
            }
            for (cardId in reviews.map { it.cardId }.distinct()) writeCard(cardId, recompute(cardId))
        }
    }

    /** Rebuilds a card's FSRS state from its reviews (sync merge, parameter changes). */
    suspend fun recomputeCard(cardId: String) = io { db.transaction { writeCard(cardId, recompute(cardId)) } }

    /** Rebuilds every card, e.g. after the optimizer produced new weights. */
    suspend fun recomputeAll() = io {
        db.transaction {
            q.allReviews().executeAsList().map { it.card_id }.distinct().forEach { writeCard(it, recompute(it)) }
        }
    }

    suspend fun reviewLogForOptimizer(): List<ReviewLogEntry> = io {
        q.allReviews().executeAsList()
            .filter { it.rating in 1L..4L }
            .map { ReviewLogEntry(it.card_id, Rating.entries[it.rating.toInt() - 1], Instant.fromEpochMilliseconds(it.ts)) }
    }

    private fun recompute(cardId: String): FsrsCard {
        val card = q.cardById(cardId).executeAsOne()
        val created = Instant.fromEpochMilliseconds(card.created_at)
        val reviews = q.reviewsForCard(cardId).executeAsList()
        val graded = reviews.filter { it.rating in 1L..4L }
            .map { Instant.fromEpochMilliseconds(it.ts) to Rating.entries[it.rating.toInt() - 1] }
        if (graded.isNotEmpty()) return scheduler.replay(cardId, created, graded)
        val intro = reviews.firstOrNull { it.rating == INTRODUCED.toLong() } ?: return FsrsCard(due = created)
        val firstStep = scheduler.parameters.learningSteps.firstOrNull() ?: Duration.ZERO
        val at = Instant.fromEpochMilliseconds(intro.ts)
        return FsrsCard(state = CardState.LEARNING, step = 0, due = at + firstStep)
    }

    private fun writeCard(cardId: String, c: FsrsCard) {
        q.updateCardState(
            c.state.name, c.step?.toLong(), c.stability, c.difficulty, c.due.toEpochMilliseconds(),
            c.lastReview?.toEpochMilliseconds(), c.reps.toLong(), c.lapses.toLong(), clock.now().toEpochMilliseconds(), cardId,
        )
    }

    // --- Notes --------------------------------------------------------------------------------------------

    suspend fun saveNote(itemId: String, myStory: String? = null, synonyms: List<String>? = null) = io {
        val existing = q.noteFor(itemId).executeAsOneOrNull()
        q.putNote(
            itemId,
            myStory ?: existing?.my_story.orEmpty(),
            synonyms?.let(::encode) ?: existing?.synonyms ?: "[]",
            existing?.tags ?: "[]",
            existing?.mnemonic_image_path,
            existing?.user_audio_path,
            clock.now().toEpochMilliseconds(),
        )
    }

    // --- Mapping ------------------------------------------------------------------------------------------

    private fun Item.toStudyItem(): StudyItem {
        val note = q.noteFor(id).executeAsOneOrNull()
        return StudyItem(
            id = id,
            kind = ItemKind.valueOf(kind),
            primaryText = primary_text,
            reading = reading,
            meanings = decode(meanings),
            acceptedReadings = decode(accepted_readings),
            source = ItemSource.of(source),
            level = level?.toInt(),
            jlpt = jlpt?.toInt(),
            context = context,
            synonyms = note?.synonyms?.let(::decode).orEmpty(),
            myStory = note?.my_story.orEmpty(),
        )
    }

    private fun Card.toStudyCard() = StudyCard(
        id = id,
        itemId = item_id,
        direction = CardDirection.valueOf(direction),
        fsrs = FsrsCard(
            state = CardState.valueOf(state),
            step = step?.toInt(),
            stability = stability,
            difficulty = difficulty,
            due = Instant.fromEpochMilliseconds(due),
            lastReview = last_review?.let(Instant::fromEpochMilliseconds),
            reps = reps.toInt(),
            lapses = lapses.toInt(),
        ),
        suspended = suspended != 0L,
    )

    private fun encode(values: List<String>) = json.encodeToString(values)
    private fun decode(value: String): List<String> = if (value.isEmpty()) emptyList() else json.decodeFromString(value)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Review rating that records a lesson (introduction), not an answer. FSRS ignores it. */
        const val INTRODUCED = 0
        const val LEECH_LAPSES = 8
        private const val CHUNK = 500

        fun cardId(itemId: String, direction: CardDirection) = "$itemId#${direction.name}"
    }
}
