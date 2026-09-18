package app.tsumugi.jp.tokenizer

/**
 * One morpheme of analyzed text. [start]/[end] are UTF-16 offsets into the analyzed string.
 * [pos] holds the non-empty IPADIC part-of-speech levels, e.g. [名詞, 一般] or [動詞, 自立].
 * [reading]/[pronunciation] are katakana, null for unknown words.
 */
data class Morpheme(
    val surface: String,
    val start: Int,
    val end: Int,
    val pos: List<String>,
    val conjugationType: String?,
    val conjugationForm: String?,
    val baseForm: String,
    val reading: String?,
    val pronunciation: String?,
    val isUnknown: Boolean,
)

/**
 * Reading of the dictionary form (lemma) in hiragana, derived from the surface reading: the surface's kana tail
 * is swapped for the base form's (食べ/タベ + 食べる → たべる, 高かっ/タカカッ + 高い → たかい). 来る and する are
 * special-cased. Null when the shapes don't line up (better no pitch than the wrong one).
 */
fun Morpheme.baseReading(): String? {
    val read = reading?.let(app.tsumugi.jp.Kana::toHiragana) ?: return null
    if (surface == baseForm) return read
    when (baseForm) {
        "来る", "くる" -> return "くる"
        "する", "為る" -> return "する"
    }
    var p = 0
    while (p < surface.length && p < baseForm.length && surface[p] == baseForm[p]) p++
    if (p == 0) return null
    val surfaceTail = app.tsumugi.jp.Kana.toHiragana(surface.substring(p))
    val baseTail = app.tsumugi.jp.Kana.toHiragana(baseForm.substring(p))
    if (surfaceTail.isNotEmpty() && !app.tsumugi.jp.Kana.isAllKana(surfaceTail)) return null
    if (baseTail.isNotEmpty() && !app.tsumugi.jp.Kana.isAllKana(baseTail)) return null
    if (!read.endsWith(surfaceTail)) return null
    return read.dropLast(surfaceTail.length) + baseTail
}

/** Splits Japanese text into morphemes. Identical results on every platform (pure Kotlin + a content pack). */
interface MorphologicalAnalyzer {
    @Throws(Exception::class)
    suspend fun analyze(text: String): List<Morpheme>
}
