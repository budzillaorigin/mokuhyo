package app.tsumugi.jp

/**
 * Kana/kanji classification and script folding.
 *
 * [toHiragana] is the search-key fold and must stay identical to `to_hiragana` in tools/packs/common.py:
 * only ァ(U+30A1)..ヶ(U+30F6) shift by 0x60; ー and everything else pass through.
 */
object Kana {
    private const val KATAKANA_FIRST = 'ァ'
    private const val KATAKANA_LAST = 'ヶ'
    private const val HIRAGANA_FIRST = 'ぁ'
    private const val HIRAGANA_LAST = 'ゖ'
    private const val OFFSET = 0x60

    fun toHiragana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in KATAKANA_FIRST..KATAKANA_LAST) c - OFFSET else c)
    }

    fun toKatakana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in HIRAGANA_FIRST..HIRAGANA_LAST) c + OFFSET else c)
    }

    /** ぁ..ゖ plus the hiragana iteration marks ゝゞ. */
    fun isHiragana(c: Char): Boolean = c in HIRAGANA_FIRST..HIRAGANA_LAST || c == 'ゝ' || c == 'ゞ'

    /** ァ..ヺ plus ヽヾ and the long-vowel mark ー. */
    fun isKatakana(c: Char): Boolean = c in 'ァ'..'ヺ' || c == 'ヽ' || c == 'ヾ' || c == 'ー'

    fun isKana(c: Char): Boolean = isHiragana(c) || isKatakana(c)

    fun isKanji(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2FFFF || cp == '々'.code || cp == '〆'.code

    fun containsKanji(s: String): Boolean = s.codePointList().any(::isKanji)

    fun isAllKana(s: String): Boolean = s.isNotEmpty() && s.all(::isKana)

    /** True if [s] contains any kana, kanji, or CJK punctuation/full-width form. */
    fun isJapanese(s: String): Boolean = s.codePointList().any { cp ->
        isKanji(cp) ||
            (cp <= 0xFFFF && isKana(cp.toChar())) ||
            cp in 0x3000..0x303F ||
            cp in 0xFF00..0xFFEF
    }
}

/** Code points of a string, pairing UTF-16 surrogates (needed for CJK Extension B+ kanji). */
internal fun String.codePointList(): List<Int> {
    val out = ArrayList<Int>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out += 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            i += 2
        } else {
            out += c.code
            i++
        }
    }
    return out
}
