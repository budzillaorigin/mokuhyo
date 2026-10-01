package app.mokuhyo.srs

import app.mokuhyo.db.Card
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.db.Review
import app.mokuhyo.db.Review_item
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A due review card for the Review screen. */
data class ReviewCard(val item: Review_item, val due: Instant)

/**
 * The per-language FSRS review queue (BRIEF §2 Review): missed exam items, looked-up words, recurring speaking
 * errors. The review log is append-only; a card's state is always the replay of its live (non-tombstoned) reviews, so
 * undo is a tombstone plus a replay, and an imported bundle's reviews merge without conflicts (rule 5).
 */
class ReviewService(
    private val db: MokuhyoDatabase,
    private val scheduler: FsrsScheduler = FsrsScheduler(),
    private val clock: Clock = Clock.System,
) {
    enum class Kind { WORD, ITEM, ERROR }

    /** Adds an item unless the same (kind, ref) is already queued; returns its id either way. */
    fun add(learnerId: String, lang: String, kind: Kind, ref: String, front: String, back: String, context: String? = null): String {
        db.srsQueries.itemByRef(learnerId, lang, kind.name, ref).executeAsOneOrNull()?.let { return it.id }
        val id = Uuid.random().toString()
        val now = clock.now()
        db.transaction {
            db.srsQueries.insertItem(Review_item(id, learnerId, lang, kind.name, ref, front, back, context, now.toEpochMilliseconds(), null))
            save(id, FsrsCard(due = now))
        }
        return id
    }

    fun dueCount(learnerId: String, lang: String): Long = db.srsQueries.dueCount(learnerId, lang, clock.now().toEpochMilliseconds()).executeAsOne()

    fun total(learnerId: String, lang: String): Long = db.srsQueries.itemCount(learnerId, lang).executeAsOne()

    fun due(learnerId: String, lang: String, limit: Long = 50): List<ReviewCard> =
        db.srsQueries.dueItems(learnerId, lang, clock.now().toEpochMilliseconds(), limit).executeAsList().map {
            ReviewCard(Review_item(it.id, it.learnerId, it.lang, it.kind, it.ref, it.front, it.back, it.context, it.createdAt, it.deleted), Instant.fromEpochMilliseconds(it.due))
        }

    /** Appends a review and updates the card; returns the review id (for undo). */
    fun grade(itemId: String, rating: Rating, durationMs: Long = 0): String {
        val id = Uuid.random().toString()
        db.transaction {
            db.srsQueries.insertReview(Review(id, itemId, clock.now().toEpochMilliseconds(), rating.value.toLong(), durationMs, null))
            recompute(itemId)
        }
        return id
    }

    /** Undo = tombstone the review and replay the rest (never a physical delete). */
    fun undo(reviewId: String, itemId: String) = db.transaction {
        db.srsQueries.tombstoneReview(clock.now().toEpochMilliseconds(), reviewId)
        recompute(itemId)
    }

    /** The card state from the live review log (also used after importing a bundle). */
    fun recompute(itemId: String) {
        val item = db.srsQueries.itemsAll().executeAsList().firstOrNull { it.id == itemId } ?: return
        val reviews = db.srsQueries.reviewsFor(itemId).executeAsList().map { Instant.fromEpochMilliseconds(it.at) to Rating.entries.first { r -> r.value.toLong() == it.rating } }
        save(itemId, scheduler.replay(itemId, Instant.fromEpochMilliseconds(item.createdAt), reviews))
    }

    fun card(itemId: String): FsrsCard? = db.srsQueries.cardFor(itemId).executeAsOneOrNull()?.let {
        FsrsCard(CardState.valueOf(it.state), it.step?.toInt(), it.stability, it.difficulty, Instant.fromEpochMilliseconds(it.due),
            it.lastReview?.let(Instant::fromEpochMilliseconds), it.reps.toInt(), it.lapses.toInt())
    }

    /** Reviews done since [since] (the report's review stats). */
    fun reviewsSince(learnerId: String, lang: String, since: Instant): Long = db.srsQueries.reviewCountSince(learnerId, lang, since.toEpochMilliseconds()).executeAsOne()

    private fun save(itemId: String, c: FsrsCard) = db.srsQueries.upsertCard(
        Card(itemId, c.state.name, c.step?.toLong(), c.stability, c.difficulty, c.due.toEpochMilliseconds(), c.lastReview?.toEpochMilliseconds(), c.reps.toLong(), c.lapses.toLong()),
    )
}
