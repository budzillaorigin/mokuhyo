package app.mokuhyo.lang

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.text.Transliterator
import com.ibm.icu.util.ULocale

/**
 * ICU4J word segmentation (BRIEF §4): rule-based for space-delimited scripts, dictionary-based for Chinese,
 * Japanese and Thai. Thread-safe: a BreakIterator per call (they are cheap to clone).
 */
class IcuSegmenter(locale: String) {
    private val prototype: BreakIterator = BreakIterator.getWordInstance(ULocale.forLanguageTag(locale))

    fun segment(text: String): List<Token> {
        val it = prototype.clone() as BreakIterator
        it.setText(text)
        val out = ArrayList<Token>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            // ZWNJ (Persian) stays inside words; ICU already treats it as a word-internal joiner.
            val word = it.ruleStatus != BreakIterator.WORD_NONE || piece.any { c -> isWordChar(c) }
            if (piece.isNotBlank()) out += Token(piece, start, end, isWord = word && piece.any { c -> isWordChar(c) })
            start = end
            end = it.next()
        }
        return out
    }

    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c) ||
        UCharacter.hasBinaryProperty(c.code, UProperty.IDEOGRAPHIC) || c == '‌'
}

/** Romanization and script helpers from ICU's built-in transliterators (no extra data files). */
class IcuReadingAids(private val language: String, private val rubyFor: ((Token) -> String?)? = null) : ReadingAids {
    private val romanizer: Transliterator? = when (language) {
        "ru" -> Transliterator.getInstance("Russian-Latin/BGN")
        "zh-Hans" -> Transliterator.getInstance("Han-Latin")
        "ko" -> Transliterator.getInstance("Hangul-Latin")
        "ar" -> Transliterator.getInstance("Arabic-Latin")
        "fa" -> Transliterator.getInstance("Persian-Latin/BGN")
        "ja" -> Transliterator.getInstance("Hiragana-Latin; Katakana-Latin")
        else -> null
    }
    private val pinyin: Transliterator? = if (language == "zh-Hans") Transliterator.getInstance("Han-Latin") else null
    private val traditional: Transliterator? = if (language == "zh-Hans") Transliterator.getInstance("Simplified-Traditional") else null

    override fun ruby(token: Token): String? = when {
        rubyFor != null -> rubyFor(token)
        pinyin != null && token.isWord && token.text.any { UCharacter.hasBinaryProperty(it.code, UProperty.IDEOGRAPHIC) } ->
            synchronized(pinyin) { pinyin.transliterate(token.text) }
        else -> null
    }

    override fun romanize(text: String): String? = romanizer?.let { t -> synchronized(t) { t.transliterate(text) } }

    /** Traditional characters for a Simplified text (the zh "traditional toggle"). */
    fun toTraditional(text: String): String? = traditional?.let { t -> synchronized(t) { t.transliterate(text) } }

    override fun stripVowelMarks(text: String): String =
        if (language == "ar" || language == "fa") text.filterNot { it in 'ً'..'ٟ' || it == 'ٰ' } else text

    override val available: Set<ReadingAids.Aid>
        get() = buildSet {
            if (rubyFor != null || pinyin != null) add(ReadingAids.Aid.RUBY)
            if (romanizer != null) add(ReadingAids.Aid.ROMANIZATION)
            if (language == "ar") add(ReadingAids.Aid.VOWEL_MARKS)
            if (traditional != null) add(ReadingAids.Aid.TRADITIONAL)
        }
}
