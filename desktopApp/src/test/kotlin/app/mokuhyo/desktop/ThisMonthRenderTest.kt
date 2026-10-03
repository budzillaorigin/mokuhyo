package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.exam.ThisMonth
import app.mokuhyo.desktop.ui.lexicon.LexiconScreen
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** BRIEF_PHASE8 C-09 gate "screen renders" (and the Lexicon screen, C-03/C-04) for several languages. */
class ThisMonthRenderTest {
    @Test
    fun rendersForSeveralLanguages() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-month").toFile()))
        try {
            listOf("es", "ja", "ar").forEach { lang ->
                app.setLanguage(lang)
                val scene = ImageComposeScene(1100, 1600, Density(1f)) {
                    MokuhyoTheme { Column { ThisMonth(app, app.languages.module(lang)); LexiconScreen(app) } }
                }
                val image = scene.render(0)
                scene.close()
                assertEquals(1100, image.width, lang)
            }
        } finally {
            app.close()
        }
    }
}
