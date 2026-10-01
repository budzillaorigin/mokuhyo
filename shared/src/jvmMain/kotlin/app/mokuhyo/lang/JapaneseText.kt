package app.mokuhyo.lang

import app.mokuhyo.lang.ja.Kana
import app.mokuhyo.lang.ja.tokenizer.LatticeTokenizer
import app.mokuhyo.lang.ja.tokenizer.db.TokenizerDatabase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties

/**
 * Japanese segmentation with the lattice tokenizer over the mecab-ipadic pack (`packs/ja/tokenizer.sqlite`), so
 * tokens carry their dictionary form and reading (furigana). Falls back to ICU when the pack isn't installed.
 */
class JapaneseText(pack: File?) {
    private val tokenizer: LatticeTokenizer? = pack?.takeIf { it.isFile }?.let { f ->
        val driver = JdbcSqliteDriver("jdbc:sqlite:${f.absolutePath}", Properties().apply { put("open_mode", "1") })
        LatticeTokenizer(TokenizerDatabase(driver))
    }
    private val icu = IcuSegmenter("ja")

    val usesLattice: Boolean get() = tokenizer != null

    fun segment(text: String): List<Token> {
        val t = tokenizer ?: return icu.segment(text)
        val morphemes = runBlocking { t.analyze(text) }
        return morphemes.map { m ->
            val word = m.pos.firstOrNull() != "記号" && m.surface.any { it.isLetterOrDigit() }
            Token(
                text = m.surface, start = m.start, end = m.end, isWord = word,
                lemmaHint = m.baseForm.takeIf { it.isNotBlank() && it != m.surface },
                reading = m.reading?.let(Kana::toHiragana),
            )
        }
    }

    /** Furigana: the reading for tokens that contain kanji. */
    fun ruby(token: Token): String? = token.reading?.takeIf { Kana.containsKanji(token.text) && it != token.text }
}
