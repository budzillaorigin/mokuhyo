package app.mokuhyo.lang

import app.mokuhyo.lang.ja.Kana
import app.mokuhyo.platform.normalizeNfc
import app.mokuhyo.platform.normalizeNfd

/**
 * Comparison keys (BRIEF §4 `normalizeForCompare`): what a learner's typed answer or a tapped word is matched with.
 * Never used to store text (CLAUDE.md: store NFC, never blindly NFKC). Per language:
 * - all: NFC, trim, collapse spaces, case-fold;
 * - Latin and Cyrillic scripts: strip combining diacritics (é→e, ñ→n, ü→u, й→и); ru also ё→е;
 * - ar/fa: strip tashkeel, tatweel and superscript alef; unify alef forms, yeh/kaf variants, teh marbuta→heh (ar);
 * - ja: full-width ASCII → ASCII, half-width katakana → full-width, katakana → hiragana;
 * - zh/ko: full-width ASCII → ASCII only (Han and Hangul are compared as written).
 */
object Fold {
    private val combining = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    fun forCompare(text: String, language: String): String {
        var s = normalizeNfc(text).trim().replace(spaces, " ")
        s = widthFold(s)
        s = when (language) {
            "ar", "fa" -> arabic(s, persian = language == "fa")
            "ja" -> Kana.toHiragana(halfwidthKatakana(s))
            "zh-Hans", "ko" -> s
            "ru" -> stripMarks(s.replace('ё', 'е').replace('Ё', 'Е'))
            else -> stripMarks(s)
        }
        return s.lowercase()
    }

    /** é → e, ç → c, й → и, ß stays (it is a letter, not a mark). */
    fun stripMarks(s: String): String = normalizeNfc(normalizeNfd(s).replace(combining, ""))

    /** Full-width ASCII (Ａ, ０, ！) to ASCII; ideographic space to space. */
    fun widthFold(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '！'..'～' -> (c.code - 0xFEE0).toChar()
                '　' -> ' '
                else -> c
            },
        )
    }

    private val halfKana = "｡｢｣､･ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝﾞﾟ"
    private val fullKana = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン゛゜"

    /** Half-width katakana (ｶﾀｶﾅ, ｶﾞ) to full-width, combining voiced marks. */
    fun halfwidthKatakana(s: String): String {
        if (s.none { it in '｡'..'ﾟ' }) return s
        val mapped = buildString { for (c in s) { val i = halfKana.indexOf(c); append(if (i >= 0) fullKana[i] else c) } }
        return normalizeNfc(mapped.replace("゛", "゙").replace("゜", "゚"))
    }

    private const val TATWEEL = 'ـ'

    /** Arabic-script folding: drop harakat/tanwin/shadda/sukun (U+064B–065F), superscript alef, tatweel; unify letters. */
    fun arabic(s: String, persian: Boolean): String = buildString(s.length) {
        for (c in s) {
            when {
                c in 'ً'..'ٟ' || c == 'ٰ' || c == TATWEEL -> Unit
                c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' -> append('ا')
                c == 'ى' || c == 'ي' || c == 'ی' -> append(if (persian) 'ی' else 'ي')
                c == 'ك' || c == 'ک' -> append(if (persian) 'ک' else 'ك')
                c == 'ة' && !persian -> append('ه')
                c == 'ۀ' -> append('ه')
                c == '‌' && !persian -> Unit
                else -> append(c)
            }
        }
    }
}
