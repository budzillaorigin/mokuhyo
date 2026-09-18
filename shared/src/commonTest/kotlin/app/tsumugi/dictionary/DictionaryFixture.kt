package app.tsumugi.dictionary

import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.dictionary.db.DictionaryQueries
import app.tsumugi.dictionary.db.Entry
import app.tsumugi.dictionary.db.Entry_kana
import app.tsumugi.dictionary.db.Entry_kanji
import app.tsumugi.dictionary.db.Gloss_index
import app.tsumugi.dictionary.db.Kanji
import app.tsumugi.dictionary.db.Kanji_component
import app.tsumugi.dictionary.db.Kanji_word
import app.tsumugi.dictionary.db.Sentence
import app.tsumugi.dictionary.db.Sentence_word
import app.tsumugi.dictionary.db.Stroke
import app.tsumugi.jp.Kana
import app.tsumugi.testing.inMemoryDriver

/**
 * A tiny hand-written dictionary pack for tests, shaped exactly like the output of tools/packs/build_*.py
 * (same schema, same compact encodings). Test data only; never shipped.
 */
object DictionaryFixture {

    const val TABERU = 1358280L
    const val KAKU = 1352530L
    const val NEKO = 1467640L
    const val KANJI = 1315920L
    const val KANJI_FEELING = 1250530L
    const val IKU = 1578850L
    const val WATASHI = 1311110L
    const val WA = 2028920L
    const val GAKKOU = 1206900L
    const val NI = 2028990L
    const val SUSHI = 1320140L

    private data class Word(
        val id: Long,
        val kanji: String?,
        val kana: String,
        val pos: List<String>,
        val glosses: List<String>,
        val common: Boolean = true,
        val jlpt: Int? = 5,
        val rank: Long = 1000,
        val misc: List<String> = emptyList(),
    )

    private val words = listOf(
        Word(TABERU, "食べる", "たべる", listOf("v1", "vt"), listOf("to eat"), rank = 10),
        Word(KAKU, "書く", "かく", listOf("v5k", "vt"), listOf("to write", "to compose"), rank = 20),
        Word(NEKO, "猫", "ねこ", listOf("n"), listOf("cat"), rank = 30),
        Word(KANJI, "漢字", "かんじ", listOf("n"), listOf("kanji", "Chinese character"), jlpt = 4, rank = 40),
        Word(KANJI_FEELING, "感じ", "かんじ", listOf("n"), listOf("feeling", "sense", "impression"), jlpt = 3, rank = 35),
        Word(IKU, "行く", "いく", listOf("v5k-s", "vi"), listOf("to go", "to move"), rank = 5),
        Word(WATASHI, "私", "わたし", listOf("pn"), listOf("I", "me"), rank = 3),
        Word(WA, null, "は", listOf("prt"), listOf("indicates sentence topic"), rank = 1),
        Word(GAKKOU, "学校", "がっこう", listOf("n"), listOf("school"), rank = 50),
        Word(NI, null, "に", listOf("prt"), listOf("at", "in", "to (direction)"), rank = 2),
        Word(SUSHI, "寿司", "すし", listOf("n"), listOf("sushi"), common = true, jlpt = null, rank = 2_000),
    )

    fun create(): DictionaryDatabase {
        val db = DictionaryDatabase(inMemoryDriver(DictionaryDatabase.Schema))
        val q = db.dictionaryQueries
        q.insertMeta("pack_version", "test")
        for (w in words) {
            q.insertEntry(Entry(w.id, if (w.common) 1 else 0, w.rank, w.jlpt?.toLong()))
            w.kanji?.let { q.insertKanjiForm(Entry_kanji(w.id, 0, it, 1, "")) }
            q.insertKanaForm(Entry_kana(w.id, 0, w.kana, Kana.toHiragana(w.kana), 1, "", ""))
            q.insertSense(
                app.tsumugi.dictionary.db.Sense(
                    w.id, 0, json(w.pos), json(w.glosses), json(w.misc), "", "", "", "", "", "",
                ),
            )
            val terms = HashMap<String, Long>()
            for (g in w.glosses) {
                val lower = g.lowercase()
                terms["=$lower"] = 55
                Regex("[a-z0-9']+").findAll(lower).map { it.value }
                    .filter { it !in setOf("a", "an", "the", "to", "of", "in", "at") }
                    .forEach { terms[it] = 10 }
            }
            terms.forEach { (t, s) -> q.insertGloss(Gloss_index(t, w.id, s)) }
            w.kanji?.forEach { c -> if (Kana.isKanji(c.code)) q.insertKanjiWord(Kanji_word(c.toString(), w.id, w.rank)) }
        }
        q.insertFurigana(app.tsumugi.dictionary.db.Furigana("食べる", "たべる", "食=た|べる"))
        q.insertPitch(app.tsumugi.dictionary.db.Pitch("食べる", "たべる", "2"))
        q.insertPitch(app.tsumugi.dictionary.db.Pitch("猫", "ねこ", "1"))

        kanji(q, "食", 9, listOf("eat", "food"), listOf("ショク", "ジキ"), listOf("く.う", "た.べる"), listOf("人", "良"))
        kanji(q, "猫", 11, listOf("cat"), listOf("ビョウ"), listOf("ねこ"), listOf("犭", "艹", "田"))
        kanji(q, "田", 5, listOf("rice field"), listOf("デン"), listOf("た"), listOf("田"))
        kanji(q, "畑", 9, listOf("farm"), emptyList(), listOf("はた"), listOf("火", "田"))
        listOf("人" to 2, "良" to 7, "犭" to 3, "艹" to 3, "田" to 5, "火" to 4).forEach { (r, n) ->
            q.insertRadical(app.tsumugi.dictionary.db.Radical(r, n.toLong()))
        }
        q.insertStroke(Stroke("田", 1, "M22.5,29.5c1.2,1.2,1.8,2.9,1.9,4.4c0.5,11.3,1.2,39.1,1.5,50.5", "㇑"))
        q.insertStroke(Stroke("田", 2, "M25,31.9c10.9-1.3,48.9-4.4,56.4-4.9c3.4-0.2,5.1,1.3,4.9,4.1", "㇕"))

        q.insertSentence(Sentence(100, "猫が好きです。", "I like cats.", 5))
        q.insertSentenceWord(Sentence_word(NEKO, 100))
        q.insertSentence(Sentence(101, "寿司を食べる。", "I eat sushi.", null))
        q.insertSentenceWord(Sentence_word(TABERU, 101))
        return db
    }

    private fun kanji(
        q: DictionaryQueries, literal: String, strokes: Int, meanings: List<String>, on: List<String>,
        kun: List<String>, components: List<String>,
    ) {
        q.insertKanji(Kanji(literal, 1, strokes.toLong(), 100, 4, 5, null, null, json(meanings), json(on), json(kun), ""))
        components.forEach { q.insertComponent(Kanji_component(literal, it)) }
    }

    private fun json(values: List<String>): String =
        if (values.isEmpty()) "" else values.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }
}
