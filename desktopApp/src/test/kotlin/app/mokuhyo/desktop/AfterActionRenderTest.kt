package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.speaking.AfterActionBriefView
import app.mokuhyo.opi.AfterActionBrief
import app.mokuhyo.opi.PragmaticFlag
import app.mokuhyo.opi.TopicTurn
import app.mokuhyo.opi.TurnFeedbackRecord
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** BRIEF_PHASE8 C-11 gate: the After Action Brief renders on fixtures (every section, several scripts). */
class AfterActionRenderTest {
    private fun brief(lang: String, said: String) = AfterActionBrief(
        mode = "after_action", activity = "persona", title = "Drone sighting report", persona = "Col. Example (Senior counterpart)",
        durationSec = 312, turns = 4, levelTrack = listOf("1", "1+", "1+", "2"),
        nextSteps = listOf("Address officers by rank.", "Report bearing and range in one sentence.", "Use past tense for what you saw."),
        records = listOf(TurnFeedbackRecord(1, said, said + "!", listOf(TopicTurn.Change("a", "b", "agreement")), said, listOf(TopicTurn.Vocab("x", "y")),
            "1+", listOf(PragmaticFlag("register", "high", said, "Too casual for a colonel.", said)), "fake", "OK")),
        patterns = AfterActionBrief.Patterns(listOf("a → b (2×)"), listOf("casual address"), listOf("avoided the past tense")),
        fluency = AfterActionBrief.FluencyStats(61, 88, 23, 2),
        cultural = listOf(AfterActionBrief.CulturalGroup("rank", "x-card-rank-1", "Rank before name", listOf(PragmaticFlag("register", "high", said, "Too casual.", said)))),
        engine = "fake",
    )

    @Test
    fun rendersOnFixtures() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-aab").toFile()))
        try {
            listOf("es" to "Oye, vi un dron.", "ja" to "おい、ドローンを見た。", "ar" to "رأيت طائرة مسيرة.").forEach { (lang, said) ->
                val scene = ImageComposeScene(1100, 2400, Density(1f)) { MokuhyoTheme { Column { AfterActionBriefView(app, lang, brief(lang, said), "conv-$lang") } } }
                val image = scene.render(0)
                scene.close()
                assertTrue(image.width == 1100 && image.height == 2400, lang)
            }
        } finally {
            app.close()
        }
    }
}
