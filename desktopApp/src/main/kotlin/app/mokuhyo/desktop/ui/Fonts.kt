package app.mokuhyo.desktop.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import app.mokuhyo.desktop.Resources
import app.mokuhyo.lang.Scripts
import java.io.File

/**
 * Bundled Noto fonts (BRIEF §9): target-language text always uses the font that covers its script, so CJK and
 * Arabic never fall back to boxes on a machine without those system fonts. Missing files (a dev run before
 * `tools/release/stage_fonts.py`) fall back to the platform default.
 */
object Fonts {
    private val files = mapOf(
        "Noto Sans" to "NotoSans-wdth-wght.ttf",
        "Noto Sans JP" to "NotoSansJP-wght.ttf",
        "Noto Sans SC" to "NotoSansSC-wght.ttf",
        "Noto Sans KR" to "NotoSansKR-wght.ttf",
        "Noto Naskh Arabic" to "NotoNaskhArabic-wght.ttf",
    )
    private val cache = HashMap<String, FontFamily>()

    fun dir(): File? = Resources.dir?.let { File(it, "fonts") }?.takeIf { it.isDirectory }
        ?: Resources.repoDir?.let { File(it, "tools/.cache/fonts") }?.takeIf { it.isDirectory }

    /** The family for text in [language]. */
    @Synchronized
    fun forLanguage(language: String): FontFamily {
        val family = Scripts.of(language).fontFamily
        return cache.getOrPut(family) {
            val file = files[family]?.let { name -> dir()?.let { File(it, name) } }?.takeIf { it.isFile }
            if (file == null) FontFamily.Default else FontFamily(Font(file))
        }
    }

    fun available(): Boolean = dir()?.let { d -> files.values.all { File(d, it).isFile } } == true
}
