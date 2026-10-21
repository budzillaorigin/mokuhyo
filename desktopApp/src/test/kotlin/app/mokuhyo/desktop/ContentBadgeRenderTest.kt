package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.exam.ContentBadges
import app.mokuhyo.exam.ExamItem
import app.mokuhyo.exam.ExamKind
import app.mokuhyo.exam.ExamPassage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QC Q10: the Reading/Listening session badge says AI-generated content is unreviewed until a human verifies it, for
 * the question or its passage; verified content carries no such badge; the local-bank label stays separate.
 */
class ContentBadgeRenderTest {
    private fun passage(exam: ExamKind, source: String, verified: Boolean, bank: String = "shipped") =
        ExamPassage("p", exam, "es", "1", "news", "t", "b", emptyList(), source, verified, bank = bank)

    private fun item(exam: ExamKind, source: String, verified: Boolean) =
        ExamItem("i", "b", exam, "1", "detail", "p", "s", listOf("a", "b"), 0, "", emptyList(), emptyList(), source, verified)

    private data class Case(val name: String, val item: ExamItem, val passage: ExamPassage?, val expected: List<String>)

    private fun cases(exam: ExamKind) = listOf(
        Case("$exam unverified item", item(exam, "llm", false), passage(exam, "human", true), listOf("AI-generated · unreviewed")),
        Case("$exam unverified passage", item(exam, "human", true), passage(exam, "llm", false), listOf("AI-generated · unreviewed")),
        Case("$exam both verified", item(exam, "llm", true), passage(exam, "llm", true), emptyList()),
        Case("$exam no passage, verified", item(exam, "llm", true), null, emptyList()),
        Case("$exam local bank", item(exam, "llm", false), passage(exam, "llm", false, bank = "local"),
            listOf("AI-generated · unreviewed", "Generated on this computer")),
    )

    @Test
    fun badgeDisclosesUnreviewedAiContentOnly() {
        System.setProperty("java.awt.headless", "true")
        for (c in listOf(ExamKind.DLPT_READING, ExamKind.DLPT_LISTENING).flatMap { cases(it) }) {
            val scene = ImageComposeScene(800, 200, Density(1f)) { MokuhyoTheme { Row { ContentBadges(c.item, c.passage) } } }
            try {
                scene.render(0)
                val texts = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                assertEquals(c.expected, texts, c.name)
            } finally {
                scene.close()
            }
        }
    }
}
