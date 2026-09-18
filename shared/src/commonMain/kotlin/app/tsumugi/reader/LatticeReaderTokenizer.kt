package app.tsumugi.reader

import app.tsumugi.dictionary.Lemma
import app.tsumugi.dictionary.Token
import app.tsumugi.jp.Kana
import app.tsumugi.jp.tokenizer.Morpheme
import app.tsumugi.jp.tokenizer.MorphologicalAnalyzer
import app.tsumugi.jp.tokenizer.baseReading

/**
 * Reader tokens from the morphological analyzer (F-26): the same [app.tsumugi.jp.tokenizer.LatticeTokenizer] the
 * rest of the app uses, so the reader and pronunciation/listening agree on word boundaries. Each content morpheme
 * absorbs the auxiliaries (助動詞) and conjunctive て/で that inflect it, so 行き+まし+た reads as one tappable
 * word 行きました with dictionary form 行く. Glosses come from one batched dictionary lookup per sentence
 * ([lookup], usually [app.tsumugi.dictionary.DictionaryRepository.entriesForLemmas]). Text the analyzer skips
 * (spaces) becomes plain tokens, so tokens always tile the input.
 */
class LatticeReaderTokenizer(
    private val analyzer: MorphologicalAnalyzer,
    private val lookup: suspend (Collection<Lemma>) -> Map<Lemma, Long>,
) {
    @Throws(Exception::class)
    suspend fun tokenize(text: String): List<Token> {
        val groups = group(analyzer.analyze(text))
        val lemmas = groups.mapNotNull { it.lemma }.toSet()
        val ids = if (lemmas.isEmpty()) emptyMap() else lookup(lemmas)
        val out = ArrayList<Token>(groups.size)
        var at = 0
        for (g in groups) {
            if (g.start < at) continue // overlapping analyzer output: keep the first
            if (g.start > at) out += Token(text.substring(at, g.start), at, g.start, null, null, null)
            val head = g.morphemes.first()
            val entryId = g.lemma?.let(ids::get)
            val surfaceReading = g.morphemes.map { it.reading }.takeIf { r -> r.all { it != null } }
                ?.joinToString("") { Kana.toHiragana(it!!) }
            out += Token(
                surface = text.substring(g.start, g.end),
                start = g.start,
                end = g.end,
                entryId = entryId,
                dictionaryForm = head.baseForm.takeIf { entryId != null },
                reading = g.lemma?.reading,
                deinflection = g.morphemes.drop(1).map { it.baseForm },
                surfaceReading = surfaceReading,
            )
            at = g.end
        }
        if (at < text.length) out += Token(text.substring(at), at, text.length, null, null, null)
        return out
    }

    private class Group(val morphemes: MutableList<Morpheme>) {
        val start get() = morphemes.first().start
        val end get() = morphemes.last().end
        val lemma: Lemma? get() {
            val head = morphemes.first()
            if (head.isUnknown || head.pos.firstOrNull() == SYMBOL) return null
            return Lemma(head.baseForm, head.baseReading())
        }
    }

    private fun group(morphemes: List<Morpheme>): List<Group> {
        val out = ArrayList<Group>()
        for (m in morphemes) {
            val current = out.lastOrNull()
            val adjacent = current != null && current.end == m.start
            val headPos = current?.morphemes?.first()?.pos?.firstOrNull()
            val pos = m.pos.firstOrNull()
            val absorb = adjacent && headPos != SYMBOL && current!!.morphemes.first().isUnknown.not() && when {
                pos == AUXILIARY -> headPos in INFLECTING
                pos == PARTICLE && m.pos.getOrNull(1) == "接続助詞" && m.surface in CONJUNCTIVE -> headPos in INFLECTING
                else -> false
            }
            if (absorb) current!!.morphemes += m else out += Group(mutableListOf(m))
        }
        return out
    }

    private companion object {
        const val SYMBOL = "記号"
        const val AUXILIARY = "助動詞"
        const val PARTICLE = "助詞"
        val INFLECTING = setOf("動詞", "形容詞", "助動詞")
        val CONJUNCTIVE = setOf("て", "で")
    }
}
