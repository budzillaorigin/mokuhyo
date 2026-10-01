package app.mokuhyo.dictionary

import app.mokuhyo.dictionary.db.DictionaryDatabase
import app.mokuhyo.dictionary.db.Entry
import app.mokuhyo.lang.DictEntry
import app.mokuhyo.lang.DictionaryPack
import app.mokuhyo.lang.Example
import app.mokuhyo.lang.Sense
import app.mokuhyo.platform.normalizeNfc

/**
 * A [DictionaryPack] over the language-neutral SQLite schema of BRIEF §5.2 (`dictionary.sq`). Read-only.
 *
 * Lookup order, each stage ranked by frequencyRank (unranked last) and de-duplicated against earlier stages:
 * 1. EXACT: headword or reading equals the NFC query.
 * 2. LEMMA: the query is a known inflected/variant surface in the form index.
 * 3. FUZZY: the [DictionaryFold] key of the query equals a headword, reading or form (case, width, diacritics,
 *    tashkeel, ё/е, katakana/hiragana folded). The pack's `fold` table only holds keys that differ from their text;
 *    keys equal to their text are found through the entry and form indexes.
 * 4. PREFIX: headwords/readings (raw or folded) starting with the folded query.
 * Every stage is one to three indexed queries; senses and examples are fetched for all hits in two more.
 */
class SqlDictionaryPack(private val db: DictionaryDatabase, override val language: String) : DictionaryPack {
    private val q = db.dictionaryQueries

    override val meta: Map<String, String> by lazy {
        q.allMeta().executeAsList().associate { it.key to it.value_ }
    }

    override fun lookup(query: String, limit: Int): List<DictEntry> {
        val nfc = normalizeNfc(query).trim()
        if (nfc.isEmpty() || limit <= 0) return emptyList()
        val hits = LinkedHashMap<Long, Pair<Entry, DictEntry.Match>>()
        fun full() = hits.size >= limit
        fun add(rows: List<Entry>, match: DictEntry.Match) {
            for (row in rows) {
                if (full()) return
                if (row.id !in hits) hits[row.id] = row to match
            }
        }
        val n = limit.toLong()
        add(q.exact(nfc, n).executeAsList(), DictEntry.Match.EXACT)
        if (!full()) add(q.byForm(nfc, n).executeAsList(), DictEntry.Match.LEMMA)
        val key = DictionaryFold.fold(nfc, language)
        if (key.isEmpty()) return hydrate(hits.values.toList())
        if (!full()) {
            val fuzzy = buildList {
                if (key != nfc) {
                    addAll(q.exact(key, n).executeAsList())
                    addAll(q.byForm(key, n).executeAsList())
                }
                addAll(q.byFold(key, n) { id, lang, headword, reading, pos, rank, _ ->
                    Entry(id, lang, headword, reading, pos, rank)
                }.executeAsList())
            }
            add(fuzzy.distinctBy { it.id }.sortedWith(byRank), DictEntry.Match.FUZZY)
        }
        if (!full()) {
            val ids = q.prefixIds(key, prefixEnd(key), n + hits.size).executeAsList()
                .distinct().filter { it !in hits }.take(limit - hits.size)
            if (ids.isNotEmpty()) add(q.byIds(ids).executeAsList().sortedWith(byRank), DictEntry.Match.PREFIX)
        }
        return hydrate(hits.values.toList())
    }

    override fun entry(id: Long): DictEntry? =
        q.byId(id).executeAsOneOrNull()?.let { hydrate(listOf(it to DictEntry.Match.EXACT)).first() }

    override fun lemmasOf(surface: String): List<String> {
        val nfc = normalizeNfc(surface).trim()
        if (nfc.isEmpty()) return emptyList()
        val exact = q.byForm(nfc, LEMMA_LIMIT).executeAsList()
        if (exact.isNotEmpty()) return exact.map { it.headword }.distinct()
        // Sentence-initial capitals, missing accents or vowel marks: the form index under the folded key.
        val key = DictionaryFold.fold(nfc, language)
        if (key.isEmpty()) return emptyList()
        val folded = buildList {
            if (key != nfc) addAll(q.byForm(key, LEMMA_LIMIT).executeAsList())
            q.byFold(key, LEMMA_LIMIT).executeAsList().filter { it.kind == FOLD_KIND_FORM }.forEach {
                add(Entry(it.id, it.lang, it.headword, it.reading, it.pos, it.frequencyRank))
            }
        }
        return folded.distinctBy { it.id }.sortedWith(byRank).map { it.headword }.distinct()
    }

    private fun hydrate(rows: List<Pair<Entry, DictEntry.Match>>): List<DictEntry> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.first.id }
        val senses = q.sensesFor(ids).executeAsList().groupBy({ it.entryId }) {
            Sense(it.ord.toInt(), it.gloss_en, it.domain, it.register)
        }
        val examples = q.examplesFor(ids).executeAsList().groupBy({ it.entryId }) {
            Example(it.text, it.translation, it.source)
        }
        return rows.map { (e, match) ->
            DictEntry(
                id = e.id,
                headword = e.headword,
                reading = e.reading,
                pos = e.pos,
                frequencyRank = e.frequencyRank?.toInt(),
                senses = senses[e.id].orEmpty(),
                examples = examples[e.id].orEmpty(),
                match = match,
            )
        }
    }

    companion object {
        /** `fold.kind` of keys that come from the form index (0 = headword or reading). */
        const val FOLD_KIND_FORM = 1L
        private const val LEMMA_LIMIT = 20L

        private val byRank = compareBy<Entry>({ it.frequencyRank == null }, { it.frequencyRank }, { it.id })

        /** Smallest string greater than every string starting with [prefix] (SQLite compares UTF-8 bytes). */
        internal fun prefixEnd(prefix: String): String = prefix + "􏿿"
    }
}
