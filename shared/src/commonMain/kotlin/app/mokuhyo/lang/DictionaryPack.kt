package app.mokuhyo.lang

/**
 * A per-language dictionary pack with the language-neutral schema of BRIEF §5.2:
 * `entry(id, lang, headword, reading?, pos, frequencyRank?)`, `sense(entryId, ord, gloss_en, domain?, register?)`,
 * `form(surface, entryId, tags)`, `example(entryId, text, translation?, source)`.
 * Lookup order: exact → lemma (form index) → fuzzy (diacritic/case/width-insensitive) → prefix. Target < 5 ms.
 */
interface DictionaryPack {
    val language: String

    /** Best matches for [query] in lookup order, at most [limit]. */
    fun lookup(query: String, limit: Int = 10): List<DictEntry>

    fun entry(id: Long): DictEntry?

    /** Lemmas for an inflected surface form (empty when the form index doesn't know it). */
    fun lemmasOf(surface: String): List<String>

    /** Pack metadata: source, license, attribution, entry count, build date. */
    val meta: Map<String, String>
}

data class DictEntry(
    val id: Long,
    val headword: String,
    val reading: String?,
    val pos: String,
    val frequencyRank: Int?,
    val senses: List<Sense>,
    val examples: List<Example> = emptyList(),
    /** How this entry matched the query: EXACT, LEMMA, FUZZY or PREFIX. */
    val match: Match = Match.EXACT,
) {
    enum class Match { EXACT, LEMMA, FUZZY, PREFIX }

    /** First few glosses joined, for tap-to-define popups. */
    val shortGloss: String get() = senses.take(3).joinToString("; ") { it.glossEn }
}

data class Sense(val ord: Int, val glossEn: String, val domain: String? = null, val register: String? = null)

data class Example(val text: String, val translation: String?, val source: String)
