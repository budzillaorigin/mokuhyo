package app.mokuhyo.lang.ja

/**
 * A candidate dictionary form for an inflected surface string.
 *
 * [reasons] lists the inflections in the order they apply **from the dictionary form outward**:
 * 食べさせられなかった → 食べる with `[causative, passive, negative, past]`.
 * Candidates are hypotheses; callers confirm them against the dictionary's part-of-speech tags.
 */
data class Deinflection(val term: String, val wordClasses: Set<WordClass>, val reasons: List<String>)

/** Rule-driven deinflector (table in [DeinflectRules]). Pure and allocation-light; safe to call per keystroke. */
object Deinflector {

    private const val MAX_DEPTH = 10
    private val allClasses = WordClass.entries.toSet()

    private class Candidate(val term: String, val conds: Set<Cond>?, val reasons: List<String>)

    /**
     * All plausible dictionary forms of [text], breadth-first so the shortest chain comes first.
     * The first result is always [text] itself with every word class and no reasons.
     */
    fun deinflect(text: String): List<Deinflection> {
        if (text.isEmpty()) return emptyList()
        val results = LinkedHashMap<Pair<String, Set<WordClass>>, Deinflection>()
        results[text to allClasses] = Deinflection(text, allClasses, emptyList())

        val seen = HashSet<Pair<String, Set<Cond>>>()
        var frontier = listOf(Candidate(text, null, emptyList()))
        repeat(MAX_DEPTH) {
            if (frontier.isEmpty()) return@repeat
            val next = ArrayList<Candidate>()
            for (c in frontier) {
                val rules = DeinflectRules.byLastChar[c.term.last()] ?: continue
                for (rule in rules) {
                    if (rule.exact) {
                        if (c.term != rule.from) continue
                    } else if (!c.term.endsWith(rule.from)) continue
                    if (c.conds != null && rule.condIn.none { it in c.conds }) continue
                    val term = c.term.dropLast(rule.from.length) + rule.to
                    if (term.isEmpty() || !seen.add(term to rule.condOut)) continue
                    val cand = Candidate(term, rule.condOut, listOf(rule.reason) + c.reasons)
                    next += cand
                    emit(results, cand)
                }
            }
            frontier = next
        }
        return results.values.toList()
    }

    private fun emit(results: MutableMap<Pair<String, Set<WordClass>>, Deinflection>, c: Candidate) {
        val classes = c.conds.orEmpty().mapNotNullTo(LinkedHashSet()) { it.wordClass }
        if (classes.isEmpty()) return
        results.getOrPut(c.term to classes) { Deinflection(c.term, classes, c.reasons) }
        // いい only survives in its plain form; every inflection is built on よい (よかった, よくない…).
        if (WordClass.ADJ_I in classes && c.term.endsWith("よい")) {
            val ii = c.term.dropLast(2) + "いい"
            results.getOrPut(ii to setOf(WordClass.ADJ_I)) { Deinflection(ii, setOf(WordClass.ADJ_I), c.reasons) }
        }
    }
}
