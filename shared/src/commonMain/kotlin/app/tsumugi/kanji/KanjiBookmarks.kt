package app.tsumugi.kanji

import app.tsumugi.dictionary.KanjiInfo
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.jp.Kana
import app.tsumugi.srs.CardState
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository

/**
 * "Bookmark to SRS" from the kanji explorer (BRIEF_V2 §6.15). A kanji on the 60-level path becomes that path item (the
 * lesson completes, so progress isn't split); any other kanji becomes the learner's own item `k:<kanji>` (the id the
 * path, the reader and coverage already use for kanji) with meaning and reading cards, introduced right away.
 */
class KanjiBookmarks(private val srs: SrsRepository, private val path: suspend () -> PathService?) {

    /** Adds [info] to reviews; returns the item id. Adding a kanji twice is harmless. */
    @Throws(Exception::class)
    suspend fun bookmark(info: KanjiInfo): String {
        val id = itemId(info.literal)
        val pathService = path()
        pathService?.item(id)?.let {
            pathService.completeLessons(listOf(it))
            return id
        }
        srs.addItems(listOf(newItem(info)))
        srs.introduce(DIRECTIONS.map { SrsRepository.cardId(id, it) })
        return id
    }

    /** True when the kanji already has started cards (bookmarked, or learned on the path). */
    @Throws(Exception::class)
    suspend fun isBookmarked(literal: String): Boolean =
        srs.cardsForItems(listOf(itemId(literal))).any { it.fsrs.state != CardState.NEW }

    companion object {
        val DIRECTIONS = listOf(CardDirection.MEANING, CardDirection.READING)

        fun itemId(literal: String) = "k:$literal"

        /** The SRS item for a kanji outside the path: KANJIDIC2 meanings, on'yomi and kun'yomi as accepted readings. */
        internal fun newItem(info: KanjiInfo): NewItem {
            val readings = (info.onyomi.map(Kana::toHiragana) + info.kunyomi.map { kunStem(it) })
                .filter { it.isNotEmpty() }.distinct()
            return NewItem(
                id = itemId(info.literal), kind = ItemKind.KANJI, primaryText = info.literal, reading = readings.firstOrNull(),
                meanings = info.meanings.take(MAX_MEANINGS), acceptedReadings = readings, source = ItemSource.USER,
                directions = DIRECTIONS, jlpt = info.jlpt, refId = info.literal,
            )
        }

        /** KANJIDIC2 kun'yomi "きよ.い" / "あお-" → the kana a learner types: "きよい" / "あお". */
        internal fun kunStem(kun: String): String = kun.replace(".", "").replace("-", "")

        private const val MAX_MEANINGS = 6
    }
}
