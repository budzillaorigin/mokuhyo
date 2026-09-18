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

/** Splits Japanese text into morphemes. Identical results on every platform (pure Kotlin + a content pack). */
interface MorphologicalAnalyzer {
    @Throws(Exception::class)
    suspend fun analyze(text: String): List<Morpheme>
}
