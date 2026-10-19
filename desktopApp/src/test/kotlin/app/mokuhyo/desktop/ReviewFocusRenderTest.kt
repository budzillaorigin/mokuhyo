package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.ReviewScreen
import app.mokuhyo.platform.AppDirs
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse

/** QC follow-up (optional item): an empty Review queue rendered "FocusRequester is not initialized". */
class ReviewFocusRenderTest {
    @Test
    fun emptyQueueDoesNotAskForFocus() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-review").toFile()))
        val captured = ByteArrayOutputStream()
        val out = System.out
        val err = System.err
        try {
            app.setLanguage("es")
            System.setOut(PrintStream(captured, true))
            System.setErr(PrintStream(captured, true))
            val scene = ImageComposeScene(1100, 800, Density(1f)) { MokuhyoTheme { Column { ReviewScreen(app) } } }
            repeat(5) { scene.render(it * 16_000_000L) }
            scene.close()
        } finally {
            System.setOut(out)
            System.setErr(err)
            app.close()
        }
        val log = captured.toString()
        assertFalse("FocusRequester is not initialized" in log, log)
    }
}
