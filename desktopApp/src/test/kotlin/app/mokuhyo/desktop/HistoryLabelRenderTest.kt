package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.HistoryList
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** QC follow-up F4: History's interview row carries the provisional qualifier its speaking estimate was saved with. */
class HistoryLabelRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun provisionalInterviewIsQualifiedInTheRowAndTheDetail() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-f4").toFile()))
        try {
            app.setLanguage("es")
            val id = app.conversations.newId()
            // The report's reproduction: a model-rated test and its speaking estimate saved with confidence 0.3, provisional.
            app.conversations.save(id, app.learnerId, "es", "OPI_TEST", null, 0,
                StoredConversation(listOf(Turn(Speaker.PARTNER, "¿Qué hiciste ayer?"), Turn(Speaker.LEARNER, "Fui al mercado."))),
                OpiRating("2", engine = "eurollm-9b"), listOf("2"), null)
            app.history.saveSpeakingEstimate(app.learnerId, "es", id, "2", 0.3, provisional = true)
            val scene = ImageComposeScene(1400, 1000, Density(1f)) { MokuhyoTheme { Column { HistoryList(app) } } }
            try {
                scene.render(0)
                val texts = scene.texts()
                assertTrue(texts.any { it == "1 answers · ILR 2* (AI-rated, provisional)" }, texts.toString())
                val open = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Open" } }.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, Offset(open.x, open.y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(open.x, open.y))
                scene.render(100_000_000)
                assertTrue(scene.texts().any { it == "Rating: ILR 2* (AI-rated, provisional)" }, scene.texts().toString())
                assertTrue(scene.texts().any { it.startsWith("* provisional") }, scene.texts().toString())
            } finally {
                scene.close()
            }
        } finally {
            app.close()
        }
    }

    private fun ImageComposeScene.texts(): List<String> = semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}
