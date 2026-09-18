package app.tsumugi.study

import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.jp.Kana
import app.tsumugi.practice.MinimalPair
import app.tsumugi.practice.MinimalPairCategory
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.srs.CheckResult
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.StudyItem
import app.tsumugi.srs.Verdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock

/** One word of a stored minimal pair. [accent] is the Kanjium downstep (0 = heiban) when known. */
@Serializable
data class PairSide(val text: String, val reading: String, val accent: Int? = null, val gloss: String = "")

/**
 * The pair a MINIMAL_PAIR card drills, stored in the item's `context` as JSON so the card reviews without the practice
 * pack (and syncs with the item).
 */
@Serializable
data class MinimalPairCard(val pairId: Long, val category: String, val a: PairSide, val b: PairSide) {
    val categoryOrNull: MinimalPairCategory? get() = runCatching { MinimalPairCategory.valueOf(category) }.getOrNull()
}

/**
 * A minimal-pair review: the app plays [played] (the audio pack, or TTS from [PairSide.reading]; pitch pairs need the
 * pack or a pitch-capable voice), the learner picks A or B.
 */
data class MinimalPairPrompt(val pair: MinimalPairCard, val playA: Boolean) {
    val played: PairSide get() = if (playA) pair.a else pair.b
    val answer: String get() = played.text

    /** The two options, shown as buttons ("a" and "b"). */
    val question: String get() = "${pair.a.text} / ${pair.b.text}"

    /** Accepts "a"/"b", "0"/"1", or the word itself (text or reading; for pitch pairs the text tells them apart). */
    fun check(answer: String): CheckResult {
        val given = answer.trim()
        val pickedA = when {
            given.equals("a", true) || given == "0" -> true
            given.equals("b", true) || given == "1" -> false
            given == pair.a.text -> true
            given == pair.b.text -> false
            pair.a.reading != pair.b.reading && Kana.toHiragana(given) == Kana.toHiragana(pair.a.reading) -> true
            pair.a.reading != pair.b.reading && Kana.toHiragana(given) == Kana.toHiragana(pair.b.reading) -> false
            else -> null
        } ?: return CheckResult(Verdict.WRONG, this.answer)
        return CheckResult(if (pickedA == playA) Verdict.CORRECT else Verdict.WRONG, this.answer)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The prompt for a MINIMAL_PAIR item, or null when its context isn't a stored pair. */
        fun of(item: StudyItem, random: Random): MinimalPairPrompt? {
            val card = item.context?.let { runCatching { json.decodeFromString(MinimalPairCard.serializer(), it) }.getOrNull() } ?: return null
            return MinimalPairPrompt(card, random.nextBoolean())
        }

        internal fun encode(card: MinimalPairCard): String = json.encodeToString(MinimalPairCard.serializer(), card)
    }
}

/**
 * Minimal pairs on FSRS (BRIEF §5.9, BRIEF_V2 G-06). Starting the drill turns pack pairs into MINIMAL_PAIR items
 * ("mp:<pair id>", one LISTENING card each) and introduces them, a few new ones per drill; from then on they are
 * scheduled like every other card: they come due in normal reviews and in [session].
 */
class MinimalPairService(
    private val practice: PracticeRepository,
    private val srs: SrsRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Adds up to [newPairs] pairs of [category] (all categories when null, most common first) that aren't cards yet,
     * introduces them, and returns a review session over every due minimal-pair card (the new ones included).
     */
    @Throws(Exception::class)
    suspend fun startDrill(category: MinimalPairCategory? = null, newPairs: Int = DEFAULT_NEW, random: Random = Random.Default): ReviewSession {
        val existing = srs.cardsOfKind(ItemKind.MINIMAL_PAIR).map { it.itemId }.toSet()
        val fresh = practice.minimalPairs(category, limit = POOL).filter { itemId(it.id) !in existing }.take(newPairs)
        if (fresh.isNotEmpty()) {
            srs.addItems(fresh.map { it.toNewItem() })
            srs.introduce(fresh.map { SrsRepository.cardId(itemId(it.id), CardDirection.LISTENING) })
        }
        return session(random = random)
    }

    /** Due minimal-pair cards only (the drill screen), up to [limit]. */
    @Throws(Exception::class)
    suspend fun session(limit: Int = 50, random: Random = Random.Default): ReviewSession {
        val cards = srs.dueCards(DUE_SCAN, clock.now()).filter { it.itemId.startsWith(PREFIX) && it.direction == CardDirection.LISTENING }.take(limit)
        val items = srs.items(cards.map { it.itemId }.toSet())
        return ReviewSession(srs, cards, items, clock, random)
    }

    /** How many minimal-pair cards exist (0 = the drill was never started). */
    @Throws(Exception::class)
    suspend fun cardCount(): Int = srs.cardsOfKind(ItemKind.MINIMAL_PAIR).size

    companion object {
        const val PREFIX = "mp:"
        const val PACK_ID = "practice"
        const val DEFAULT_NEW = 5
        private const val POOL = 500
        private const val DUE_SCAN = 2000

        fun itemId(pairId: Long) = "$PREFIX$pairId"

        internal fun MinimalPair.toNewItem(): NewItem {
            val card = MinimalPairCard(id, category.name, PairSide(a.text, a.reading, a.accent, a.gloss), PairSide(b.text, b.reading, b.accent, b.gloss))
            return NewItem(
                id = itemId(id), kind = ItemKind.MINIMAL_PAIR, primaryText = "${a.text} / ${b.text}",
                reading = "${a.reading} / ${b.reading}", meanings = listOf(a.gloss, b.gloss).filter { it.isNotBlank() },
                acceptedReadings = emptyList(), source = ItemSource.PACK, directions = listOf(CardDirection.LISTENING),
                packId = PACK_ID, refId = id.toString(), context = MinimalPairPrompt.encode(card),
            )
        }
    }
}
