package app.tsumugi.reader

import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.EntrySummary
import app.tsumugi.dictionary.Token
import app.tsumugi.domain.Stage
import app.tsumugi.jp.Furigana
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.jp.Kana
import app.tsumugi.jp.tokenizer.MorphologicalAnalyzer
import app.tsumugi.study.CollectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

enum class FuriganaMode {
    ALL,
    NONE,
    /** Only for words the learner doesn't know yet (not at Guru) and that are above their JLPT level. */
    UNKNOWN_ONLY,
}

data class ReaderToken(
    val surface: String,
    /** Offsets in the document body. */
    val start: Int,
    val end: Int,
    val entryId: Long?,
    val dictionaryForm: String?,
    /** Reading of the surface form (inflection applied), when known. */
    val reading: String?,
    val furigana: List<FuriganaSegment>,
    val jlpt: Int?,
    val isCommon: Boolean,
    /** The learner's stage for this word (null = never studied). */
    val stage: Stage?,
    val deinflection: List<String>,
) {
    val isWord: Boolean get() = entryId != null
    val known: Boolean get() = stage != null && stage >= Stage.GURU
    val hasKanji: Boolean get() = Kana.containsKanji(surface)

    /** Whether to draw ruby over this token. [learnerJlpt] is the learner's level (5 = N5); null = unknown. */
    fun showFurigana(mode: FuriganaMode, learnerJlpt: Int? = null): Boolean = when (mode) {
        FuriganaMode.NONE -> false
        FuriganaMode.ALL -> hasKanji
        FuriganaMode.UNKNOWN_ONLY -> hasKanji && !known && (learnerJlpt == null || jlpt == null || jlpt < learnerJlpt)
    }
}

data class ReaderSentence(
    val text: String,
    val start: Int,
    val end: Int,
    val tokens: List<ReaderToken>,
    /** Grammar pack points whose patterns occur in this sentence. */
    val grammarPointIds: List<String>,
)

data class ReaderParagraph(val start: Int, val end: Int, val sentences: List<ReaderSentence>)

data class ReaderAnalysis(
    val wordCount: Int,
    /** Share of words at Guru or above. */
    val knownRatio: Double,
    /** Share of words the learner has started at all. */
    val seenRatio: Double,
    /** Share of (content) words on the JLPT lists at each level or easier, keyed 5..1. */
    val jlptCoverage: Map<Int, Double>,
    /** Easiest level covering ≥ 85% of content words; 0 = harder than N1; null = no words. */
    val jlptEstimate: Int?,
    val ilrEstimate: String,
    val averageSentenceLength: Double,
    val kanjiDensity: Double,
    val rareWordRatio: Double,
) {
    val levelLabel: String? get() = levelLabel(jlptEstimate, ilrEstimate)
}

/**
 * Turns document text into tokens with furigana, known-word status and grammar hits, and estimates difficulty
 * (BRIEF §5.8). Tokenizing goes through [tokenize]: the app passes [LatticeReaderTokenizer] (F-26), and the
 * dictionary longest-match tokenizer only when the tokenizer pack is missing. All work runs on
 * [Dispatchers.IO]; [page] analyzes lazily, a few paragraphs at a time.
 */
class ReaderAnalyzer(
    private val tokenize: suspend (String) -> List<Token>,
    private val summaries: suspend (List<Long>) -> List<EntrySummary>,
    private val stages: suspend () -> Map<String, Stage>,
    private val grammarPatterns: suspend () -> List<Pair<String, Regex>> = { emptyList() },
) {
    constructor(
        dictionary: DictionaryRepository,
        stages: suspend () -> Map<String, Stage>,
        grammarPatterns: suspend () -> List<Pair<String, Regex>> = { emptyList() },
    ) : this({ dictionary.tokenize(it) }, { dictionary.summaries(it) }, stages, grammarPatterns)

    /** Lattice tokenization (the morphological analyzer) with glosses from [dictionary] (F-26). */
    constructor(
        analyzer: MorphologicalAnalyzer,
        dictionary: DictionaryRepository,
        stages: suspend () -> Map<String, Stage>,
        grammarPatterns: suspend () -> List<Pair<String, Regex>> = { emptyList() },
    ) : this(
        (LatticeReaderTokenizer(analyzer) { dictionary.entriesForLemmas(it) })::tokenize,
        { dictionary.summaries(it) },
        stages,
        grammarPatterns,
    )

    private var patternsCache: List<Pair<String, Regex>>? = null

    // --- Structure ---------------------------------------------------------------------------------------

    /** Paragraph ranges: one per non-empty line of the body. */
    fun paragraphs(body: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (line in body.split('\n')) {
            if (line.isNotBlank()) out += start until start + line.length
            start += line.length + 1
        }
        return out
    }

    /** Sentence ranges inside [range]: ends after 。！？!? (plus closing brackets) or at the range end. */
    fun sentences(body: String, range: IntRange): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = range.first
        var i = range.first
        while (i <= range.last) {
            if (body[i] in TERMINATORS) {
                var end = i + 1
                while (end <= range.last && body[end] in CLOSERS) end++
                out += start until end
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start <= range.last) out += start..range.last
        return out.filter { r -> body.substring(r.first, r.last + 1).isNotBlank() }
    }

    // --- Reading view ------------------------------------------------------------------------------------

    /** Paragraphs [from, from + count) fully analyzed, for lazy rendering. */
    @Throws(Exception::class)
    suspend fun page(body: String, from: Int, count: Int, ruby: List<RubyHint> = emptyList()): List<ReaderParagraph> =
        withContext(Dispatchers.IO) {
            val known = stages()
            paragraphs(body).drop(from).take(count).map { paragraph(body, it, ruby, known) }
        }

    @Throws(Exception::class)
    suspend fun paragraph(body: String, range: IntRange, ruby: List<RubyHint> = emptyList(), known: Map<String, Stage>? = null): ReaderParagraph =
        withContext(Dispatchers.IO) { paragraphBlocking(body, range, ruby, known) }

    private suspend fun paragraphBlocking(body: String, range: IntRange, ruby: List<RubyHint>, known: Map<String, Stage>?): ReaderParagraph {
        val stageMap = known ?: stages()
        val patterns = patterns()
        val sentenceRanges = sentences(body, range)
        val raw = sentenceRanges.map { r -> r to tokenize(body.substring(r.first, r.last + 1)) }
        val ids = raw.flatMap { (_, tokens) -> tokens.mapNotNull { it.entryId } }.distinct()
        val info = if (ids.isEmpty()) emptyMap() else summaries(ids).associateBy { it.id }
        val sentences = raw.map { (r, tokens) ->
            val text = body.substring(r.first, r.last + 1)
            val readerTokens = tokens.map { t ->
                val summary = t.entryId?.let { info[it] }
                val start = r.first + t.start
                val end = r.first + t.end
                val reading = t.surfaceReading ?: surfaceReading(t.surface, t.dictionaryForm, t.reading)
                val hints = ruby.filter { it.start >= start && it.start + it.base.length <= end }
                ReaderToken(
                    surface = t.surface, start = start, end = end, entryId = t.entryId, dictionaryForm = t.dictionaryForm,
                    reading = reading, furigana = furigana(t.surface, start, reading, hints), jlpt = summary?.jlpt,
                    isCommon = summary?.isCommon ?: false,
                    stage = t.entryId?.let { stageMap["jmdict:$it"] ?: stageMap["v:$it"] },
                    deinflection = t.deinflection,
                )
            }
            ReaderSentence(text, r.first, r.last + 1, readerTokens, grammarHits(text, tokens, patterns))
        }
        return ReaderParagraph(range.first, range.last + 1, sentences)
    }

    private suspend fun patterns(): List<Pair<String, Regex>> = patternsCache ?: grammarPatterns().also { patternsCache = it }

    // --- Difficulty --------------------------------------------------------------------------------------

    /**
     * Difficulty of a document, from its first [sampleChars] characters (long novels are sampled).
     *
     * JLPT: content words (dictionary words other than one-kana function words) are checked against the
     * unofficial JLPT lists; the estimate is the easiest level whose words (that level or easier) cover ≥ 85%.
     *
     * ILR (heuristic, BRIEF §5.8): three signals, each scaled to 0–3 and averaged —
     *  - average sentence length in Japanese characters: (chars − 10) / 15 (easy news ≈ 25 → 1, newspapers ≈ 55 → 3),
     *  - kanji density (kanji / Japanese characters): (density − 0.15) / 0.10 (children's text ≈ 0.15 → 0,
     *    easy news ≈ 0.25 → 1, newspapers ≈ 0.40 → 2.5),
     *  - abstract/rare vocabulary (content words neither on a JLPT list nor marked common): ratio / 0.07.
     * The average maps to 0+ (< 0.5), 1 (< 1.0), 1+ (< 1.5), 2 (< 2.0), 2+ (< 2.5), else 3. This is a rough
     * signal for picking texts, not a DLPT rating.
     */
    @Throws(Exception::class)
    suspend fun analyze(body: String, sampleChars: Int = DEFAULT_SAMPLE): ReaderAnalysis = analyzeWithProgress(body, sampleChars) {}

    /**
     * [analyze], page by page ([PAGE_PARAGRAPHS] paragraphs at a time) on [Dispatchers.IO], reporting the share of
     * the sample done (0..1) to [onProgress] after each page and checking for cancellation (CLAUDE.md rule 15).
     */
    @Throws(Exception::class)
    suspend fun analyzeWithProgress(body: String, sampleChars: Int = DEFAULT_SAMPLE, onProgress: (Double) -> Unit): ReaderAnalysis =
        withContext(Dispatchers.IO) { analyzeBlocking(body, sampleChars, onProgress) }

    private suspend fun analyzeBlocking(body: String, sampleChars: Int, onProgress: (Double) -> Unit): ReaderAnalysis {
        val sample = if (body.length <= sampleChars) body else body.substring(0, sampleChars).substringBeforeLast('\n', body.substring(0, sampleChars))
        val known = stages()
        val ranges = paragraphs(sample)
        val paragraphs = ArrayList<ReaderParagraph>(ranges.size)
        onProgress(0.0)
        for (page in ranges.chunked(PAGE_PARAGRAPHS)) {
            page.mapTo(paragraphs) { paragraphBlocking(sample, it, emptyList(), known) }
            onProgress(if (sample.isEmpty()) 1.0 else (page.last().last + 1).toDouble() / sample.length)
            yield()
        }
        onProgress(1.0)
        val sentences = paragraphs.flatMap { it.sentences }
        val words = sentences.flatMap { it.tokens }.filter { it.isWord }
        val content = words.filterNot { it.surface.length == 1 && Kana.isAllKana(it.surface) }

        val coverage = (5 downTo 1).associateWith { level ->
            if (content.isEmpty()) 0.0 else content.count { it.jlpt != null && it.jlpt >= level }.toDouble() / content.size
        }
        val jlpt = if (content.isEmpty()) null else (5 downTo 1).firstOrNull { (coverage[it] ?: 0.0) >= COVERAGE_TARGET } ?: 0

        val japanese = sample.count(Html::isJapanese)
        val kanji = sample.count { Kana.isKanji(it.code) }
        val avgLength = if (sentences.isEmpty()) 0.0 else sentences.sumOf { s -> s.text.count(Html::isJapanese) }.toDouble() / sentences.size
        val density = if (japanese == 0) 0.0 else kanji.toDouble() / japanese
        val rare = if (content.isEmpty()) 0.0 else content.count { it.jlpt == null && !it.isCommon }.toDouble() / content.size
        val score = listOf((avgLength - 10) / 15, (density - 0.15) / 0.10, rare / 0.07).map { it.coerceIn(0.0, 3.0) }.average()
        val ilr = ILR_BANDS.firstOrNull { score < it.first }?.second ?: "3"

        return ReaderAnalysis(
            wordCount = words.size,
            knownRatio = if (words.isEmpty()) 0.0 else words.count { it.known }.toDouble() / words.size,
            seenRatio = if (words.isEmpty()) 0.0 else words.count { it.stage != null }.toDouble() / words.size,
            jlptCoverage = coverage,
            jlptEstimate = jlpt,
            ilrEstimate = ilr,
            averageSentenceLength = (avgLength * 10).roundToInt() / 10.0,
            kanjiDensity = density,
            rareWordRatio = rare,
        )
    }

    /** Analyzes a stored document and saves the estimates on it. */
    @Throws(Exception::class)
    suspend fun analyzeAndSave(repo: ReaderRepository, document: ReaderDocument): ReaderAnalysis =
        analyze(document.body).also { repo.saveAnalysis(document.id, it) }

    // --- Sentence mining ----------------------------------------------------------------------------------

    /**
     * Adds the token's word to reviews with the sentence as its context (Yomikiri-style one-tap mining).
     * Returns the item id, or null for tokens that aren't dictionary words.
     */
    @Throws(Exception::class)
    suspend fun mine(
        token: ReaderToken,
        sentence: ReaderSentence,
        entry: suspend (Long) -> DictionaryEntry?,
        addToReviews: suspend (DictionaryEntry, String) -> String,
    ): String? {
        val id = token.entryId ?: return null
        val e = entry(id) ?: return null
        return addToReviews(e, sentence.text.trim())
    }

    /** [mine] with the app's dictionary and collection. */
    @Throws(Exception::class)
    suspend fun mine(token: ReaderToken, sentence: ReaderSentence, dictionary: DictionaryRepository, collection: CollectionService): String? =
        mine(token, sentence, { dictionary.entry(it)?.entry }, { e, context -> collection.addToReviews(e, context) })

    /**
     * Grammar points with at least one match in [text] that doesn't cut into a dictionary word (F-39), the same
     * rule tools/packs/build_grammar.py applies with `inside_larger_word`, here on token boundaries: は in おはよう
     * or たい in 冷たい is not a hit. Inflected tokens only protect their stem (行き of 行きました), so endings like
     * ました still match.
     */
    internal fun grammarHits(text: String, tokens: List<Token>, patterns: List<Pair<String, Regex>>): List<String> =
        patterns.filter { (_, re) ->
            re.findAll(text).any { m -> m.value.isNotEmpty() && !insideLargerWord(tokens, m.range.first, m.range.last + 1) }
        }.map { it.first }.distinct()

    companion object {
        const val DEFAULT_SAMPLE = 20_000

        /** Paragraphs analyzed between progress reports and cancellation checks. */
        const val PAGE_PARAGRAPHS = 20

        /** True when [start, end) starts or ends strictly inside a dictionary word's lexical part. */
        internal fun insideLargerWord(tokens: List<Token>, start: Int, end: Int): Boolean = tokens.any { t ->
            if (t.entryId == null) return@any false
            val lexemeEnd = t.start + lexemeLength(t)
            start in (t.start + 1) until lexemeEnd || end in (t.start + 1) until lexemeEnd
        }

        /** The part of a token that is the word itself: all of it, or the stem shared with its dictionary form. */
        private fun lexemeLength(t: Token): Int {
            val base = t.dictionaryForm
            if (t.deinflection.isEmpty() || base == null || base == t.surface) return t.surface.length
            var p = 0
            while (p < t.surface.length && p < base.length && t.surface[p] == base[p]) p++
            return p
        }
        private const val COVERAGE_TARGET = 0.85
        private const val TERMINATORS = "。！？!?"
        private const val CLOSERS = "」』）)】〉》\"'”’"
        private val ILR_BANDS = listOf(0.5 to "0+", 1.0 to "1", 1.5 to "1+", 2.0 to "2", 2.5 to "2+")

        /**
         * Reading of an inflected surface from its dictionary form's reading: the dictionary form's kana tail is
         * swapped for the surface's (食べた + たべる → たべた). 来る is special-cased (き/こ). Returns null rather
         * than a wrong reading when the shapes don't line up.
         */
        fun surfaceReading(surface: String, dictionaryForm: String?, reading: String?): String? {
            if (reading == null) return null
            if (dictionaryForm == null || surface == dictionaryForm) return reading
            var p = 0
            while (p < surface.length && p < dictionaryForm.length && surface[p] == dictionaryForm[p]) p++
            if (p == 0) return null
            if (dictionaryForm.endsWith("来る") && reading.endsWith("くる") && p == dictionaryForm.length - 1) {
                val next = surface.getOrNull(p)
                val ko = next != null && next in "なよられさ"
                return reading.dropLast(2) + (if (ko) "こ" else "き") + surface.substring(p)
            }
            val dictTail = Kana.toHiragana(dictionaryForm.substring(p))
            if (!Kana.isAllKana(dictTail) && dictTail.isNotEmpty()) return null
            val folded = Kana.toHiragana(reading)
            if (!folded.endsWith(dictTail)) return null
            return reading.dropLast(dictTail.length) + surface.substring(p)
        }

        /** Ruby segments for a token: source-supplied readings win, else alignment of the surface reading. */
        internal fun furigana(surface: String, start: Int, reading: String?, hints: List<RubyHint>): List<FuriganaSegment> {
            if (!Kana.containsKanji(surface)) return listOf(FuriganaSegment(surface))
            if (hints.isNotEmpty()) {
                val out = ArrayList<FuriganaSegment>()
                var i = 0
                for (h in hints.sortedBy { it.start }) {
                    val at = h.start - start
                    if (at < i) continue
                    if (at > i) out += FuriganaSegment(surface.substring(i, at))
                    out += FuriganaSegment(h.base, h.reading)
                    i = at + h.base.length
                }
                if (i < surface.length) out += FuriganaSegment(surface.substring(i))
                return out
            }
            if (reading == null) return listOf(FuriganaSegment(surface))
            return Furigana.align(surface, reading)
        }
    }
}
