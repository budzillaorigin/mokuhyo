package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.DISCLAIMER
import app.mokuhyo.desktop.ui.HistoryList
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.exam.ExamAssembler
import app.mokuhyo.exam.ExamBlueprint
import app.mokuhyo.exam.ExamItem
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ExamSession
import app.mokuhyo.exam.FormLength
import app.mokuhyo.exam.SectionBlueprint
import app.mokuhyo.exam.Skill
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QC Q3 (rule 7): every score History shows, in the list and in the opened detail, carries the complete
 * unofficial-practice notice at the minimum window size, alongside the existing provisional/self-rated qualifiers.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HistoryDisclaimerRenderTest {
    private enum class Case(val score: String, val qualifier: String?) {
        READING_TEST("ILR ", null),
        RATED_INTERVIEW("ILR 2* (AI-rated, provisional)", "* provisional"),
        SELF_RATED_INTERVIEW("ILR 1+", null),
    }

    private val levels = listOf("0+", "1", "1+", "2", "2+", "3")

    private fun seed(app: AppGraph, case: Case) {
        when (case) {
            Case.READING_TEST -> {
                val bp = SectionBlueprint(levels, levels.associateWith { 3 }).let { ExamBlueprint("es", reading = it, listening = it) }
                val passages = levels.flatMap { l -> (1..3).map { ExamPassage("$l-$it", Skill.READING.exam, "es", l, "news", "t", "b", emptyList(), "llm", false) } }.associateBy { it.id }
                val items = passages.values.flatMap { p -> (1..3).map { ExamItem("${p.id}-$it", "b", p.exam, p.level, "detail", p.id, "s", listOf("a", "b", "c", "d"), 0, "", emptyList(), emptyList(), "llm", false) } }
                val s = ExamSession(ExamAssembler.test(bp, Skill.READING, FormLength.FULL, items, passages, emptySet(), Random(1)))
                s.form.items.forEachIndexed { i, f -> s.goTo(i); s.choose(f.item.answer) }
                app.history.saveAttempt(app.learnerId, Skill.READING, s.submit())
            }
            Case.RATED_INTERVIEW, Case.SELF_RATED_INTERVIEW -> {
                val id = app.conversations.newId()
                val rating = if (case == Case.RATED_INTERVIEW) OpiRating("2", engine = "synthetic-fixture") else OpiRating("1+", selfRated = true)
                app.conversations.save(id, app.learnerId, "es", "OPI_TEST", null, 0,
                    StoredConversation(listOf(Turn(Speaker.PARTNER, "¿Qué hiciste ayer?"), Turn(Speaker.LEARNER, "Fui al mercado."))),
                    rating, listOf("2"), null)
                if (case == Case.RATED_INTERVIEW) app.history.saveSpeakingEstimate(app.learnerId, "es", id, "2", 0.3, provisional = true)
            }
        }
    }

    @Test
    fun everyHistoryScoreCarriesTheCompleteDisclaimer() {
        System.setProperty("java.awt.headless", "true")
        for (case in Case.entries) {
            val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-q3").toFile()))
            try {
                app.setLanguage("es")
                seed(app, case)
                val scene = ImageComposeScene(1024, 700, Density(1f)) { MokuhyoTheme { Column { HistoryList(app) } } }
                try {
                    scene.render(0)
                    assertTrue(scene.texts().any { it.contains(case.score) }, "$case row shows its score: ${scene.texts()}")
                    scene.assertDisclaimerVisible("$case list")
                    val open = scene.nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Open" } }.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, Offset(open.x, open.y))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(open.x, open.y))
                    scene.render(100_000_000)
                    assertTrue(scene.texts().any { it == "Hide" }, "$case detail opened")
                    scene.assertDisclaimerVisible("$case detail")
                    case.qualifier?.let { q -> assertTrue(scene.texts().any { it.startsWith(q) }, "$case keeps its qualifier: ${scene.texts()}") }
                } finally {
                    scene.close()
                }
            } finally {
                app.close()
            }
        }
    }

    private fun ImageComposeScene.assertDisclaimerVisible(where: String) {
        val notice = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == DISCLAIMER } }
        assertEquals(1, notice.size, "$where: one complete disclaimer, got ${texts()}")
        val b = notice.single().boundsInRoot
        assertTrue(b.top >= 0f && b.bottom <= 700f && b.width > 0f, "$where: disclaimer inside the 1024×700 window ($b)")
        assertTrue(DISCLAIMER.contains("Unofficial practice") && DISCLAIMER.contains("not an official rating") && DISCLAIMER.contains("Not affiliated with DLI, ACTFL, AFCLC or the LEAP program"))
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }

    private fun ImageComposeScene.texts(): List<String> = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}
