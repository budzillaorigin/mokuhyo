package app.mokuhyo.desktop

import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.Scripts
import java.awt.Font
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** BRIEF §9: every launch language renders with a bundled font, no missing glyphs (boxes). */
class FontCoverageTest {
    @Test
    fun bundledFontsCoverEveryLanguage() {
        val dir = Fonts.dir() ?: return println("SKIP fonts: run tools/release/stage_fonts.py --cache-only").also {
            if (System.getenv("MOKUHYO_REQUIRE_PACKS") == "1") throw AssertionError("fonts missing")
        }
        Languages.all.forEach { l ->
            val family = Scripts.of(l.code).fontFamily
            val file = dir.listFiles().orEmpty().first { f -> f.name.startsWith(family.replace(" ", "")) && f.extension == "ttf" }
            val font = Font.createFont(Font.TRUETYPE_FONT, file)
            val text = LangSmoke.sentences.getValue(l.code) + l.nameNative
            val missing = text.filter { !it.isWhitespace() && it != '‌' && !font.canDisplay(it) }
            assertEquals("", missing, "${l.code}: ${file.name} lacks glyphs for '$missing'")
        }
    }

    @Suppress("unused")
    private fun exists(f: File) = f.isFile
}
