package app.tsumugi.dictionary

import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.jp.Conjugation
import app.tsumugi.jp.Conjugator
import app.tsumugi.jp.Deinflection
import app.tsumugi.jp.Deinflector
import app.tsumugi.jp.Furigana
import app.tsumugi.jp.Kana
import app.tsumugi.jp.Pitch
import app.tsumugi.jp.Romaji
import app.tsumugi.jp.WordClass
import app.tsumugi.platform.normalizeNfc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Offline dictionary over the read-only dictionary pack (JMdict, KANJIDIC2, KRADFILE, KanjiVG, Tatoeba…).
 * All lookups are local; nothing here touches the network.
 */
class DictionaryRepository(private val db: DictionaryDatabase) {

    private val q get() = db.dictionaryQueries

    // --- Search ----------------------------------------------------------------------------------------

    suspend fun search(rawQuery: String, limit: Int = 40): SearchResults = io {
        val query = normalizeNfc(rawQuery.trim())
        when {
            query.isEmpty() -> SearchResults.EMPTY
            Kana.isJapanese(query) -> searchJapanese(query, limit)
            else -> searchLatin(query, limit)
        }
    }

    private fun searchJapanese(query: String, limit: Int): SearchResults {
        val exact = exactHits(query)
        if (exact.isEmpty() && query.length > 1) {
            val tokens = tokenizeBlocking(query)
            if (tokens.count { it.entryId != null } > 1) {
                val summaries = summariesById(tokens.mapNotNull { it.entryId }.distinct())
                val hits = tokens.mapNotNull { t ->
                    t.entryId?.let { summaries[it] }?.let { e ->
                        SearchHit(e, if (t.deinflection.isEmpty()) MatchKind.EXACT else MatchKind.DEINFLECTED, t.deinflection)
                    }
                }.distinctBy { it.entry.id }
                return SearchResults(query, SearchMode.SENTENCE, hits, tokens)
            }
        }
        val prefix = prefixHits(query, limit)
        return SearchResults(query, SearchMode.JAPANESE, merge(exact, prefix).take(limit))
    }

    private fun searchLatin(query: String, limit: Int): SearchResults {
        val lower = query.lowercase()
        val kana = Romaji.finalize(Romaji.toHiragana(lower))
        val romajiExact = if (Kana.isAllKana(kana)) exactHits(kana) else emptyList()
        val english = englishHits(lower, limit)
        val romajiPrefix = if (Kana.isAllKana(kana)) prefixHits(kana, limit) else emptyList()
        val mode = if (romajiExact.isNotEmpty() && english.none { it.entry.id in romajiExact.map { h -> h.entry.id } }) {
            SearchMode.ROMAJI
        } else {
            SearchMode.ENGLISH
        }
        return SearchResults(query, mode, merge(romajiExact, english, romajiPrefix).take(limit))
    }

    /** Exact and deinflected matches for a Japanese string, most useful first. */
    private fun exactHits(text: String): List<SearchHit> {
        val matches = matchCandidates(Deinflector.deinflect(text))
        if (matches.isEmpty()) return emptyList()
        val summaries = summariesById(matches.keys.toList())
        return matches.values
            .sortedWith(compareBy({ it.deinflection.reasons.size }, { !it.isCommon }, { it.rank }))
            .mapNotNull { m ->
                summaries[m.entryId]?.let {
                    val kind = if (m.deinflection.reasons.isEmpty()) MatchKind.EXACT else MatchKind.DEINFLECTED
                    SearchHit(it, kind, m.deinflection.reasons)
                }
            }
    }

    private class Match(val entryId: Long, val deinflection: Deinflection, val isCommon: Boolean, val rank: Long)

    /**
     * Entries whose kanji or kana form equals a deinflection candidate and whose part of speech agrees with
     * the candidate's word class, keyed by entry id, each with the shortest-chain candidate that produced it.
     */
    private fun matchCandidates(candidates: List<Deinflection>): Map<Long, Match> {
        val terms = candidates.map { it.term }.distinct()
        val byTerm = HashMap<String, MutableSet<Long>>()
        q.formsExact(terms, terms.map(Kana::toHiragana).distinct()).executeAsList().forEach {
            byTerm.getOrPut(it.text) { mutableSetOf() } += it.entry_id
        }
        if (byTerm.isEmpty()) return emptyMap()
        fun idsFor(d: Deinflection) = byTerm[d.term].orEmpty() + byTerm[Kana.toHiragana(d.term)].orEmpty()

        // Part of speech is only needed to validate ids reached through an actual deinflection.
        val sorted = candidates.sortedBy { it.reasons.size }
        val needPos = sorted.filter { it.reasons.isNotEmpty() }.flatMap(::idsFor).distinct()
        val classes = HashMap<Long, MutableSet<WordClass>>()
        if (needPos.isNotEmpty()) {
            q.posFor(needPos).executeAsList().forEach { row ->
                PackCodec.strings(row.pos).mapNotNullTo(classes.getOrPut(row.entry_id) { mutableSetOf() }) {
                    WordClass.fromJmdictPos(it)
                }
            }
        }

        val chosen = LinkedHashMap<Long, Deinflection>()
        for (d in sorted) {
            for (id in idsFor(d)) {
                if (id in chosen) continue
                if (d.reasons.isEmpty() || classes[id].orEmpty().any { it in d.wordClasses }) chosen[id] = d
            }
        }
        if (chosen.isEmpty()) return emptyMap()
        val meta = q.entriesByIds(chosen.keys.toList()).executeAsList().associateBy { it.id }
        return chosen.entries.mapNotNull { (id, d) -> meta[id]?.let { id to Match(id, d, it.is_common != 0L, it.rank) } }.toMap()
    }

    private fun prefixHits(text: String, limit: Int): List<SearchHit> {
        // A single kana matches thousands of words; completions only start being useful from two.
        if (Kana.isAllKana(text) && text.length < MIN_KANA_PREFIX) return emptyList()
        val ids = if (Kana.isAllKana(text)) {
            val key = Kana.toHiragana(text)
            q.idsByKanaPrefix(key, key + PREFIX_END, limit.toLong()).executeAsList()
        } else {
            q.idsByKanjiPrefix(text, text + PREFIX_END, limit.toLong()).executeAsList()
        }
        return summariesInOrder(ids).map { SearchHit(it, MatchKind.PREFIX) }
    }

    private fun englishHits(query: String, limit: Int): List<SearchHit> {
        val words = WORD_RE.findAll(query).map { it.value }.filter { it !in STOPWORDS }.distinct().toList()
        val exactTerm = "=" + query.replace(Regex("\\s+"), " ").trim()
        if (words.isEmpty()) {
            val ids = q.idsByGlossTerms(listOf(exactTerm), 1, limit.toLong()).executeAsList().map { it.entry_id }
            return summariesInOrder(ids).map { SearchHit(it, MatchKind.ENGLISH) }
        }
        val rows = q.idsByGlossTerms(words + exactTerm, words.size.toLong(), limit.toLong()).executeAsList()
        return summariesInOrder(rows.map { it.entry_id }).map { SearchHit(it, MatchKind.ENGLISH) }
    }

    private fun merge(vararg lists: List<SearchHit>): List<SearchHit> {
        val seen = HashSet<Long>()
        return lists.flatMap { it }.filter { seen.add(it.entry.id) }
    }

    // --- Tokenizing (sentence mode, reader) ------------------------------------------------------------

    /** Greedy longest-match segmentation of Japanese text into dictionary words (deinflection-aware). */
    suspend fun tokenize(text: String): List<Token> = io { tokenizeBlocking(normalizeNfc(text)) }

    internal fun tokenizeBlocking(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!Kana.isJapanese(c.toString())) {
                var j = i + 1
                while (j < text.length && !Kana.isJapanese(text[j].toString())) j++
                tokens += Token(text.substring(i, j), i, j, null, null, null)
                i = j
                continue
            }
            val match = longestMatchAt(text, i)
            if (match == null) {
                tokens += Token(c.toString(), i, i + 1, null, null, null)
                i++
            } else {
                tokens += match
                i = match.end
            }
        }
        return tokens
    }

    private fun longestMatchAt(text: String, start: Int): Token? {
        var end = minOf(text.length, start + MAX_WORD_LENGTH)
        // Stop at the first non-Japanese character: words never span punctuation or Latin text.
        for (k in start until end) if (!Kana.isJapanese(text[k].toString())) { end = k; break }
        for (len in (end - start) downTo 1) {
            val surface = text.substring(start, start + len)
            val matches = matchCandidates(Deinflector.deinflect(surface))
            if (matches.isEmpty()) continue
            val best = matches.values.minWith(compareBy({ it.deinflection.reasons.size }, { !it.isCommon }, { it.rank }))
            val reading = summariesById(listOf(best.entryId))[best.entryId]?.reading
            return Token(surface, start, start + len, best.entryId, best.deinflection.term, reading, best.deinflection.reasons)
        }
        return null
    }

    // --- Entries ----------------------------------------------------------------------------------------

    suspend fun entry(id: Long): EntryDetail? = io {
        val entry = entries(listOf(id)).firstOrNull() ?: return@io null
        val headword = entry.headword
        val reading = entry.reading
        val furigana = q.furiganaFor(headword, reading).executeAsOneOrNull()?.let(PackCodec::furigana)
            ?: Furigana.align(headword, reading)
        val pitch = (q.pitchFor(headword, reading).executeAsOneOrNull() ?: q.pitchFor(reading, reading).executeAsOneOrNull())
            ?.let(Pitch::parse).orEmpty().map { Pitch.accent(reading, it) }
        val kanjiLiterals = headword.codePointStrings().filter { Kana.containsKanji(it) }.distinct()
        val kanji = kanjiInfo(kanjiLiterals)
        val sentences = q.sentencesForEntry(id, MAX_SENTENCES.toLong()).executeAsList()
            .map { ExampleSentence(it.id, it.ja, it.en, it.jlpt?.toInt()) }
        val conjugations = Conjugator.table(headword, entry.senses.firstOrNull()?.partsOfSpeech.orEmpty())
            .orEmpty().map { (c, text) -> ConjugatedForm(c, c.label, text) }
        EntryDetail(entry, furigana, pitch, kanji, sentences, conjugations)
    }

    suspend fun summaries(ids: List<Long>): List<EntrySummary> = io { summariesInOrder(ids) }

    private fun summariesInOrder(ids: List<Long>): List<EntrySummary> {
        val byId = summariesById(ids)
        return ids.mapNotNull { byId[it] }
    }

    /** Result-row data for many entries in a single query. */
    private fun summariesById(ids: List<Long>): Map<Long, EntrySummary> {
        if (ids.isEmpty()) return emptyMap()
        return q.summariesByIds(ids).executeAsList().associate { row ->
            val kana = row.kana.orEmpty()
            val usuallyKana = row.misc?.let { "uk" in PackCodec.strings(it) } == true
            val headword = row.kanji?.takeUnless { usuallyKana } ?: kana
            val preview = row.glosses.orEmpty().split(GLOSS_SEPARATOR).filter { it.isNotEmpty() }
                .joinToString("; ") { PackCodec.strings(it).joinToString(", ") }
            row.id to EntrySummary(row.id, headword, kana, preview, row.is_common != 0L, row.jlpt?.toInt())
        }
    }

    private fun entries(ids: List<Long>): List<DictionaryEntry> {
        if (ids.isEmpty()) return emptyList()
        val kanji = q.kanjiFormsFor(ids).executeAsList().groupBy { it.entry_id }
        val kana = q.kanaFormsFor(ids).executeAsList().groupBy { it.entry_id }
        val senses = q.sensesFor(ids).executeAsList().groupBy { it.entry_id }
        return q.entriesByIds(ids).executeAsList().map { e ->
            DictionaryEntry(
                id = e.id,
                kanji = kanji[e.id].orEmpty().map { KanjiForm(it.text, it.is_common != 0L, PackCodec.strings(it.tags)) },
                kana = kana[e.id].orEmpty().map {
                    KanaForm(it.text, it.is_common != 0L, PackCodec.strings(it.tags), PackCodec.strings(it.applies_to))
                },
                senses = senses[e.id].orEmpty().map {
                    Sense(
                        partsOfSpeech = PackCodec.strings(it.pos),
                        glosses = PackCodec.strings(it.glosses),
                        misc = PackCodec.strings(it.misc),
                        fields = PackCodec.strings(it.field_),
                        dialects = PackCodec.strings(it.dialect),
                        info = PackCodec.strings(it.info),
                        appliesKanji = PackCodec.strings(it.applies_kanji),
                        appliesKana = PackCodec.strings(it.applies_kana),
                        related = PackCodec.strings(it.related),
                    )
                },
                isCommon = e.is_common != 0L,
                jlpt = e.jlpt?.toInt(),
                rank = e.rank,
            )
        }
    }

    // --- Kanji ------------------------------------------------------------------------------------------

    suspend fun kanji(literal: String): KanjiDetail? = io {
        val info = kanjiInfo(listOf(literal)).firstOrNull() ?: return@io null
        val components = q.componentsOf(literal).executeAsList()
        val strokes = q.strokesFor(literal).executeAsList().map { KanjiStroke(it.ord.toInt(), it.path, it.type) }
        val words = summariesInOrder(q.wordsWithKanji(literal, MAX_KANJI_WORDS).executeAsList())
        KanjiDetail(info, components, strokes, words)
    }

    suspend fun strokes(literal: String): List<KanjiStroke> = io {
        q.strokesFor(literal).executeAsList().map { KanjiStroke(it.ord.toInt(), it.path, it.type) }
    }

    private fun kanjiInfo(literals: List<String>): List<KanjiInfo> {
        if (literals.isEmpty()) return emptyList()
        val rows = q.kanjiByLiteral(literals).executeAsList().associateBy { it.literal }
        return literals.mapNotNull { rows[it] }.map { it.toInfo() }
    }

    suspend fun radicals(): List<Radical> = io {
        q.allRadicals().executeAsList().map { Radical(it.radical, it.stroke_count.toInt(), it.display, it.name) }
    }

    /** Kanji containing every selected radical, plus the radicals that could still narrow the result. */
    suspend fun kanjiByRadicals(selected: Set<String>): RadicalSearchResult = io {
        if (selected.isEmpty()) return@io RadicalSearchResult(emptyList(), q.allRadicals().executeAsList().map { it.radical }.toSet())
        val kanji = q.kanjiWithAllRadicals(selected, selected.size.toLong()).executeAsList().map {
            KanjiInfo(
                it.literal, it.grade?.toInt(), it.stroke_count.toInt(), it.freq?.toInt(), it.jlpt_new?.toInt(),
                it.jlpt_old?.toInt(), it.heisig?.toInt(), it.heisig6?.toInt(), PackCodec.strings(it.meanings),
                PackCodec.strings(it.onyomi), PackCodec.strings(it.kunyomi), PackCodec.strings(it.nanori),
            )
        }
        val compatible = kanji.map { it.literal }.chunked(SQL_CHUNK)
            .flatMap { q.radicalsCooccurringWith(it).executeAsList() }
            .toSet()
        RadicalSearchResult(kanji, compatible)
    }

    suspend fun packInfo(): Map<String, String> = io {
        listOf("pack_version", "jmdict_version", "kanjidic2_version", "kanjivg_version", "tatoeba_sentences")
            .associateWith { q.metaValue(it).executeAsOneOrNull().orEmpty() }
    }

    private fun app.tsumugi.dictionary.db.Kanji.toInfo() = KanjiInfo(
        literal, grade?.toInt(), stroke_count.toInt(), freq?.toInt(), jlpt_new?.toInt(), jlpt_old?.toInt(),
        heisig?.toInt(), heisig6?.toInt(), PackCodec.strings(meanings), PackCodec.strings(onyomi),
        PackCodec.strings(kunyomi), PackCodec.strings(nanori),
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val PREFIX_END = "￿"
        const val MIN_KANA_PREFIX = 2
        const val MAX_WORD_LENGTH = 12
        const val MAX_SENTENCES = 12
        const val MAX_KANJI_WORDS = 40L
        const val SQL_CHUNK = 500
        const val GLOSS_SEPARATOR = '\u001F'
        val WORD_RE = Regex("[a-z0-9']+")
        // Must match STOPWORDS in tools/packs/build_dictionary.py.
        val STOPWORDS = setOf("a", "an", "the", "to", "of", "be", "or", "and", "in", "on", "at", "for", "with", "as", "by", "one's")
    }
}

/** Splits a string into code points as strings (keeps surrogate pairs together). */
internal fun String.codePointStrings(): List<String> {
    val out = ArrayList<String>(length)
    var i = 0
    while (i < length) {
        val n = if (this[i].isHighSurrogate() && i + 1 < length) 2 else 1
        out += substring(i, i + n)
        i += n
    }
    return out
}

val Conjugation.label: String
    get() = when (this) {
        Conjugation.NON_PAST -> "Non-past"
        Conjugation.NON_PAST_NEGATIVE -> "Negative"
        Conjugation.POLITE -> "Polite"
        Conjugation.POLITE_NEGATIVE -> "Polite negative"
        Conjugation.PAST -> "Past"
        Conjugation.PAST_NEGATIVE -> "Past negative"
        Conjugation.POLITE_PAST -> "Polite past"
        Conjugation.POLITE_PAST_NEGATIVE -> "Polite past negative"
        Conjugation.TE -> "Te-form"
        Conjugation.TE_NEGATIVE -> "Negative te-form"
        Conjugation.PROGRESSIVE -> "Progressive"
        Conjugation.POTENTIAL -> "Potential"
        Conjugation.PASSIVE -> "Passive"
        Conjugation.CAUSATIVE -> "Causative"
        Conjugation.CAUSATIVE_PASSIVE -> "Causative passive"
        Conjugation.CAUSATIVE_PASSIVE_SHORT -> "Causative passive (short)"
        Conjugation.VOLITIONAL -> "Volitional"
        Conjugation.POLITE_VOLITIONAL -> "Polite volitional"
        Conjugation.IMPERATIVE -> "Imperative"
        Conjugation.PROHIBITIVE -> "Prohibitive"
        Conjugation.CONDITIONAL_BA -> "Conditional (ば)"
        Conjugation.CONDITIONAL_TARA -> "Conditional (たら)"
        Conjugation.DESIRE_TAI -> "Want to (たい)"
        Conjugation.ADVERBIAL -> "Adverbial"
        Conjugation.ATTRIBUTIVE -> "Attributive"
        Conjugation.NOMINAL_SA -> "Noun (さ)"
    }
