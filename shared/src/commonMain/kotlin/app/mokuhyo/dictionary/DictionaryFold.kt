package app.mokuhyo.dictionary

import app.mokuhyo.platform.normalizeNfc
import app.mokuhyo.platform.normalizeNfd

/**
 * Fuzzy-lookup key of a dictionary pack's `fold` table. Mirror of `tools/packs/dict_fold.py`; both are tested
 * against `shared/src/commonTest/resources/dictionary/fold_vectors.json`, so the keys the builder writes are the
 * keys the app asks for. Never a blanket NFKC: only the listed width characters are folded.
 *
 * 1. NFC. 2. Width: full-width ASCII → ASCII, ideographic space → space, half-width katakana → full-width.
 * 3. Lowercase, ß → ss, curly apostrophes → '. 4. NFD, drop non-spacing marks except kana voicing marks and the
 * breve on a Cyrillic base (й), NFC (accents, stress, ё → е, tashkeel, hamza seats, pinyin tones).
 * 5. ja katakana → hiragana; ar/fa drop tatweel and ZWNJ, unify yeh and kaf; zh drop whitespace.
 * 6. Collapse whitespace, trim.
 */
object DictionaryFold {
    private const val HALF_WIDTH_KANA =
        "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨ" +
            "ラリルレロワン゙゚"
    private val whitespace = Regex("\\s+")

    fun fold(text: String, language: String): String {
        var s = width(normalizeNfc(text))
        s = s.lowercase().replace("ß", "ss").replace('’', '\'').replace('‘', '\'')
        s = stripMarks(s)
        s = when {
            language == "ja" -> buildString(s.length) {
                for (c in s) append(if (c in 'ァ'..'ヶ' || c == 'ヽ' || c == 'ヾ') c - 0x60 else c)
            }
            language == "ar" || language == "fa" -> s.replace("ـ", "").replace("‌", "")
                .replace('ى', 'ي').replace('ی', 'ي').replace('ک', 'ك')
            language.startsWith("zh") -> s.replace(whitespace, "")
            else -> s
        }
        return s.replace(whitespace, " ").trim()
    }

    private fun width(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c.code) {
                in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar())
                0x3000 -> append(' ')
                in 0xFF61..0xFF9F -> append(HALF_WIDTH_KANA[c.code - 0xFF61])
                else -> append(c)
            }
        }
    }

    private fun stripMarks(s: String): String {
        val out = StringBuilder(s.length)
        var base = ' '
        for (c in normalizeNfd(s)) {
            if (c.category == CharCategory.NON_SPACING_MARK) {
                if (c == '゙' || c == '゚' || (c == '̆' && base in 'Ѐ'..'ӿ')) out.append(c)
                continue
            }
            base = c
            out.append(c)
        }
        return normalizeNfc(out.toString())
    }
}
