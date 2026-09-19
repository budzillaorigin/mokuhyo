package app.tsumugi.writing

/** CASUAL: plain form (普通体). POLITE: です/ます. FORMAL: keigo (尊敬語・謙譲語) or written である調. */
enum class SpeechRegister { CASUAL, POLITE, FORMAL }

/** One sentence of the draft with its register (null for fragments without a predicate) and the evidence. */
data class RegisterSentence(val start: Int, val end: Int, val text: String, val register: SpeechRegister?, val markers: List<String>)

data class RegisterReport(
    val sentences: List<RegisterSentence>,
    /** The most common register (ties go to the more formal one), null when no sentence was classified. */
    val dominant: SpeechRegister?,
    /** The register the sentences are checked against: the learner's target, else [dominant]. */
    val expected: SpeechRegister?,
    /** Classified sentences in another register than [expected]: the ones to look at. */
    val outliers: List<RegisterSentence>,
) {
    val counts: Map<SpeechRegister, Int> get() = sentences.mapNotNull { it.register }.groupingBy { it }.eachCount()
    val mixed: Boolean get() = counts.size > 1
}

/**
 * The writing studio's register check (BRIEF_V2 §6.13, D-275): rules, not a model, so it works offline and never
 * guesses. Each sentence is classified by its final predicate after sentence-final particles are stripped (です/ます
 * endings → POLITE; である/であった → FORMAL, written style; other predicates → CASUAL), then raised to FORMAL when it
 * contains keigo (いらっしゃる, 申す, おります, ございます …). Casual markers (じゃん, ちゃう, っす …) are reported as
 * evidence. Fragments (headings, lists, noun phrases without だ) aren't classified.
 */
object RegisterChecker {
    private const val TERMINATORS = "。！？!?\n"
    private const val TRAILING = "。！？!?、，,…‥・〜~ー」』）)】\"' 　"
    private val FINAL_PARTICLES = listOf("よね", "かしら", "のよ", "わよ", "よ", "ね", "な", "わ", "ぞ", "ぜ", "さ", "か", "の", "っけ")

    private val KEIGO = listOf(
        "いらっしゃ", "おっしゃ", "召し上が", "ご覧", "御覧", "存じ", "申し上げ", "申します", "申し", "参ります", "参りま",
        "伺", "うかが", "おります", "おりま", "ござい", "致し", "いたしま", "いたします", "拝見", "拝借", "差し上げ",
        "恐れ入", "恐縮", "くださいませ", "お越し", "なさいま", "賜",
    )
    private val POLITE_ENDINGS = listOf(
        "ませんでした", "ましょう", "ました", "ません", "ます", "でした", "でしょう", "です", "ください", "下さい", "まして",
    )
    private val WRITTEN_ENDINGS = listOf("であろう", "であった", "である", "ではない", "ではなかった", "であり")
    private val CASUAL_MARKERS = listOf("じゃん", "っす", "ちゃう", "ちゃった", "じゃない", "じゃなかった", "だよ", "だね", "だろ", "かな", "よな", "ねえ", "けど", "っけ", "んだ")

    fun check(text: String, target: SpeechRegister? = null): RegisterReport {
        val sentences = split(text).map { (s, e) -> classify(text.substring(s, e), s, e) }
        val counts = sentences.mapNotNull { it.register }.groupingBy { it }.eachCount()
        val dominant = counts.entries.maxWithOrNull(compareBy<Map.Entry<SpeechRegister, Int>> { it.value }.thenBy { it.key.ordinal })?.key
        val expected = target ?: dominant
        val outliers = if (expected == null) emptyList() else sentences.filter { it.register != null && it.register != expected }
        return RegisterReport(sentences, dominant, expected, outliers)
    }

    /** The register of one sentence (exposed for tests and the per-sentence chip). */
    fun classify(sentence: String, start: Int = 0, end: Int = start + sentence.length): RegisterSentence {
        val markers = ArrayList<String>()
        KEIGO.firstOrNull { it in sentence }?.let { markers += "keigo: $it" }
        CASUAL_MARKERS.filter { it in sentence }.take(2).forEach { markers += "casual: $it" }
        var core = sentence.trimEnd { it in TRAILING }
        // Strip quoted speech at the end is not ours to classify; strip final particles (up to two: よね, のよ …).
        repeat(2) {
            val p = FINAL_PARTICLES.firstOrNull { core.endsWith(it) && core.length > it.length }
            if (p != null) core = core.removeSuffix(p).trimEnd { it in TRAILING }
        }
        val register = when {
            core.isBlank() -> null
            POLITE_ENDINGS.any { core.endsWith(it) } -> {
                markers.add(0, "ending: ${POLITE_ENDINGS.first { core.endsWith(it) }}")
                if (markers.any { it.startsWith("keigo") }) SpeechRegister.FORMAL else SpeechRegister.POLITE
            }
            WRITTEN_ENDINGS.any { core.endsWith(it) } -> {
                markers.add(0, "ending: ${WRITTEN_ENDINGS.first { core.endsWith(it) }} (written style)")
                SpeechRegister.FORMAL
            }
            markers.any { it.startsWith("keigo") } -> SpeechRegister.FORMAL
            hasPredicateEnding(core) -> {
                markers.add(0, "ending: plain form")
                SpeechRegister.CASUAL
            }
            else -> null
        }
        return RegisterSentence(start, end, sentence, register, markers)
    }

    /** A plain-form predicate ends in a verb/adjective kana ending, だ/た/だった, or a casual marker. */
    private fun hasPredicateEnding(core: String): Boolean {
        val last = core.last()
        if (CASUAL_MARKERS.any { core.endsWith(it) }) return true
        if (core.endsWith("だ") || core.endsWith("た") || core.endsWith("ない") || core.endsWith("だった")) return true
        // Verb (う-row) and い-adjective endings, in hiragana, after a kanji or kana stem.
        return last in "うくぐすつぬぶむるい" && core.length >= 2
    }

    /** Sentence spans: ends after 。！？!? or a newline. */
    internal fun split(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in TERMINATORS) {
                var end = i + 1
                while (end < text.length && text[end] in "」』）)") end++
                if (text.substring(start, end).isNotBlank()) out += trim(text, start, end)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < text.length && text.substring(start).isNotBlank()) out += trim(text, start, text.length)
        return out
    }

    private fun trim(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && (text[s].isWhitespace() || text[s] == '　')) s++
        while (e > s && (text[e - 1].isWhitespace() || text[e - 1] == '　')) e--
        return s to e
    }
}
