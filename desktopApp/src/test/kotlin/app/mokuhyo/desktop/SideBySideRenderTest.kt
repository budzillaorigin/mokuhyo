package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.lexicon.CompareTab
import app.mokuhyo.lexicon.Track
import app.mokuhyo.lexicon.TrackTerm
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** BRIEF_PHASE8 N-13 gate: the side-by-side screen renders for 1, 2 and 3 enabled languages. */
class SideBySideRenderTest {
    @Test
    fun rendersForOneTwoThreeLanguages() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-sbs").toFile()))
        val track = Track(id = "cuas-base-defense", lang = "ja", title = "t", version = "1", terms = listOf(
            TrackTerm(id = "cuas-001", domain = "cuas", termEn = "unmanned aircraft system", definitionEn = "d", term = "無人航空機システム", termKind = "calque")))
        try {
            listOf(listOf("ja"), listOf("ja", "es"), listOf("ja", "es", "ar")).forEach { langs ->
                val scene = ImageComposeScene(1000, 900, Density(1f)) { MokuhyoTheme { Column { CompareTab(app, track, langs) } } }
                val img = scene.render(0)
                scene.close()
                assertEquals(1000, img.width, langs.toString())
            }
        } finally {
            app.close()
        }
    }
}
