package app.mokuhyo.lang

/**
 * A [LanguageModule] assembled from parts: the platform supplies segmentation and reading aids (ICU on the JVM),
 * the pack supplies the dictionary, the voice service supplies voices. Lemmas come from the segmenter's hint, then
 * the dictionary's form index, then [extraLemmas] (e.g. Korean particle stripping), then the token itself.
 */
open class BaseLanguageModule(
    override val info: LanguageInfo,
    override val script: ScriptInfo,
    private val segmenter: (String) -> List<Token>,
    override val dictionary: DictionaryPack?,
    private val voiceList: () -> List<VoiceSpec>,
    override val readingAids: ReadingAids,
    private val extraLemmas: (String) -> List<String> = { emptyList() },
    override val numbers: app.mokuhyo.numbers.NumberGrammar? = null,
) : LanguageModule {
    override fun segment(text: String): List<Token> = segmenter(text)

    override fun lemma(token: Token): List<String> {
        val out = LinkedHashSet<String>()
        token.lemmaHint?.let { out += it }
        dictionary?.lemmasOf(token.text)?.let { out += it }
        if (out.isEmpty()) {
            val lower = token.text.lowercase()
            if (lower != token.text) dictionary?.lemmasOf(lower)?.let { out += it }
        }
        if (out.isEmpty()) out += extraLemmas(token.text)
        out += token.text
        return out.toList()
    }

    override fun normalizeForCompare(s: String): String = Fold.forCompare(s, code)

    override val voices: List<VoiceSpec> get() = voiceList()
}

/** Script facts per launch language (BRIEF §4 table). */
object Scripts {
    fun of(code: String): ScriptInfo = when (code) {
        "ja" -> ScriptInfo(Direction.LTR, needsSegmentation = true, hasSpaces = false, hasCase = false, fontFamily = "Noto Sans JP")
        "zh-Hans" -> ScriptInfo(Direction.LTR, needsSegmentation = true, hasSpaces = false, hasCase = false, fontFamily = "Noto Sans SC")
        "ko" -> ScriptInfo(Direction.LTR, needsSegmentation = false, hasSpaces = true, hasCase = false, fontFamily = "Noto Sans KR")
        "ar" -> ScriptInfo(Direction.RTL, needsSegmentation = false, hasSpaces = true, hasCase = false, fontFamily = "Noto Naskh Arabic")
        "fa" -> ScriptInfo(Direction.RTL, needsSegmentation = false, hasSpaces = true, hasCase = false, fontFamily = "Noto Naskh Arabic")
        else -> ScriptInfo(Direction.LTR, needsSegmentation = false, hasSpaces = true, hasCase = true, fontFamily = "Noto Sans")
    }
}

/**
 * Korean has no inflection table in the pack's form index for most particles: strip a trailing particle or common
 * ending so 학교에서 → 학교, 먹었습니다 → 먹 (+다). Candidates only; the dictionary decides.
 */
object KoreanParticles {
    private val particles = listOf(
        "에서부터", "으로부터", "에게서", "한테서", "께서는", "에서는", "으로는", "이라고", "라고", "부터", "까지", "에서",
        "에게", "한테", "으로", "께서", "처럼", "보다", "마다", "이나", "은", "는", "이", "가", "을", "를", "에", "로",
        "와", "과", "의", "도", "만", "나", "랑",
    )
    private val endings = listOf("었습니다", "았습니다", "였습니다", "습니다", "합니다", "했어요", "해요", "었어요", "았어요", "어요", "아요", "었다", "았다", "했다", "한다", "는다", "다")

    fun candidates(word: String): List<String> {
        val out = mutableListOf<String>()
        particles.firstOrNull { word.length > it.length && word.endsWith(it) }?.let { out += word.removeSuffix(it) }
        endings.firstOrNull { word.length > it.length && word.endsWith(it) }?.let { e ->
            val stem = word.removeSuffix(e)
            out += stem + "다"
            if (e.startsWith("했") || e.startsWith("합") || e.startsWith("해") || e == "한다") out += stem + "하다"
        }
        return out.distinct()
    }
}
