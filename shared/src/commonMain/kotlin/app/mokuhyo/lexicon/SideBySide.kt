package app.mokuhyo.lexicon

/**
 * Side-by-side terms for multi-language linguists (BRIEF_PHASE8 N-13): one row per English term, with each enabled
 * language's term, its kind (native, calque, loanword, acronym), whether the partner force says it in English on the
 * radio, and whether the term is confirmed in an allied source. Terms are matched across languages by seed id.
 */
object SideBySide {
    data class Cell(val lang: String, val term: String, val kind: String, val radioEnglish: Boolean, val confirmed: Boolean, val badge: String?)

    data class Row(val id: String, val domain: String, val termEn: String, val cells: List<Cell?>)

    /** [tracks] in the learner's language order; null cells where a language lacks the term. */
    fun rows(tracks: List<Track>, domain: String? = null, query: String = ""): List<Row> {
        val first = tracks.firstOrNull() ?: return emptyList()
        val ids = LinkedHashSet<String>()
        tracks.forEach { t -> t.terms.forEach { ids += it.id } }
        val byLang = tracks.map { t -> t.terms.associateBy { it.id } }
        val q = query.trim().lowercase()
        return ids.mapNotNull { id ->
            val any = byLang.firstNotNullOfOrNull { it[id] } ?: return@mapNotNull null
            if (domain != null && any.domain != domain) return@mapNotNull null
            val cells = tracks.mapIndexed { i, t ->
                byLang[i][id]?.let { term -> Cell(t.lang, term.term, term.termKind, term.radioEnglish, term.termConfirmed, term.badgeLabel) }
            }
            if (q.isNotEmpty() && !any.termEn.lowercase().contains(q) && cells.none { it?.term?.lowercase()?.contains(q) == true }) return@mapNotNull null
            Row(id, any.domain, any.termEn, cells)
        }.sortedWith(compareBy({ first.term(it.id)?.priority ?: 3 }, { it.termEn.lowercase() }))
    }
}
