package app.tsumugi.study.games

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.ItemKind
import app.tsumugi.srs.CardState
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** One stored round. */
data class GameScoreRow(val game: GameKind, val score: Int, val correct: Int, val total: Int, val bestStreak: Int, val day: String, val playedAt: Long)

/**
 * Finished rounds of Reflex and Atom (`game_score`, union sync, D-286). The weekly challenge sums [weekPoints]; the game
 * screens show [best] and [recent].
 */
class GameScores(
    private val db: TsumugiDatabase,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val q get() = db.gamesQueries

    /** Stores a finished round (rounds with no answers aren't stored) and returns its id, or null. */
    @Throws(Exception::class)
    suspend fun record(result: GameResult): String? = withContext(Dispatchers.IO) {
        if (result.total == 0) return@withContext null
        val now = clock.now()
        val id = Uuid.random().toString()
        q.insertGameScore(
            id, result.game.name, result.score.toLong(), result.correct.toLong(), result.total.toLong(), result.bestStreak.toLong(),
            result.durationMs, now.toLocalDateTime(zone()).date.toString(), now.toEpochMilliseconds(), deviceId,
        )
        id
    }

    /** The best score ever in [game] (0 before the first round). */
    @Throws(Exception::class)
    suspend fun best(game: GameKind): Int = withContext(Dispatchers.IO) { q.bestGameScore(game.name).executeAsOne().toInt() }

    @Throws(Exception::class)
    suspend fun recent(game: GameKind, limit: Int = 20): List<GameScoreRow> = withContext(Dispatchers.IO) {
        q.recentGameScores(game.name, limit.toLong()).executeAsList().map {
            GameScoreRow(GameKind.valueOf(it.game), it.score.toInt(), it.correct.toInt(), it.total.toInt(), it.best_streak.toInt(), it.day, it.played_at)
        }
    }

    /** Points scored in both games in the ISO week (Monday to Sunday) containing [today]. */
    @Throws(Exception::class)
    suspend fun weekPoints(today: LocalDate = clock.now().toLocalDateTime(zone()).date): Int = withContext(Dispatchers.IO) {
        weekPoints(db, today)
    }

    companion object {
        /** Sum of scores from Monday to Sunday of [today]'s week (also used by the weekly challenge). */
        internal fun weekPoints(db: TsumugiDatabase, today: LocalDate): Int {
            var monday = today
            while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.minus(DatePeriod(days = 1))
            return db.gamesQueries.gameScoresBetween(monday.toString(), monday.plus(DatePeriod(days = 6)).toString())
                .executeAsList().sumOf { it.score }.toInt()
        }
    }
}

/**
 * Where the games get their words: the learner's started vocabulary first (so play reinforces what they study), topped
 * up from the dictionary's frequency list when they know fewer than [minOwn] words. Empty without either, and the game
 * screen says so.
 */
class GameWordSource(
    private val srs: SrsRepository,
    private val dictionary: suspend () -> DictionaryRepository?,
    private val minOwn: Int = 40,
) {
    @Throws(Exception::class)
    suspend fun words(limit: Int = GameWords.DEFAULT_POOL, random: Random = Random.Default): List<GameWord> {
        val started = srs.cardsOfKind(ItemKind.VOCAB).filter { it.fsrs.state != CardState.NEW }.map { it.itemId }.distinct()
        val own = srs.items(started).values.mapNotNull { it.toGameWord() }.shuffled(random)
        if (own.size >= minOwn) return own.take(limit)
        val dict = dictionary() ?: return own
        val common = dict.frequencyWordsUpTo(COMMON_WORDS).map { it.entryId }
        val summaries = dict.summaries(common.shuffled(random).take(limit))
        val extra = summaries.map { GameWord("jmdict:${it.id}", it.headword, it.reading, it.glossPreview.split(';').take(2).joinToString(";").trim()) }
        return GameWords.pool(own, extra, limit)
    }

    private fun app.tsumugi.srs.StudyItem.toGameWord(): GameWord? {
        val reading = reading ?: acceptedReadings.firstOrNull() ?: return null
        val meaning = meanings.take(2).joinToString("; ").ifBlank { return null }
        return GameWord(id, primaryText, reading, meaning)
    }

    companion object {
        const val COMMON_WORDS = 1500
    }
}
