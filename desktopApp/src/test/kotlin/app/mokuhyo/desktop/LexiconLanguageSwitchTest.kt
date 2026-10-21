package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.lexicon.LexiconScreen
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QC Q4: with Lexicon → Drills mounted, switching es → ar → ja shows each language's own drill, choices and progress,
 * and a missed drill queues its review under the language on screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LexiconLanguageSwitchTest {
    @Test
    fun drillsFollowTheSelectedLanguage() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-q4").toFile()))
        val langs = listOf("es", "ar", "ja")
        app.setLanguage("es")
        val scene = ImageComposeScene(1280, 1000, Density(1f)) { MokuhyoTheme { Column { LexiconScreen(app) } } }
        var frame = 0L
        fun pump() = repeat(6) { scene.render(frame++ * 16_000_000L) }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun texts() = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        fun click(label: String) {
            var n: SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == label } }
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            assertNotNull(n, "clickable $label in ${texts()}").config[SemanticsActions.OnClick].action!!.invoke()
            pump()
        }
        try {
            pump()
            click("Drills")
            val expected = langs.associateWith { app.reviews.total(app.learnerId, it) }.toMutableMap()
            for (lang in langs) {
                app.setLanguage(lang)
                pump()
                val track = assertNotNull(app.lexicon(lang), "$lang track")
                val meaning = track.drills.filter { it.kind == "meaning" }
                val shown = texts()
                // A prompt can equal another drill's choice ("radar"): the drill on screen is the one whose prompt and choices all show.
                val drill = meaning.firstOrNull { d -> d.prompt in shown && d.choices.all { it in shown } }
                assertNotNull(drill, "$lang: a $lang drill with its own choices is on screen: $shown")
                val own = meaning.flatMap { it.choices + it.prompt }.toSet()
                val others = langs.filter { it != lang }.flatMap { o -> app.lexicon(o)!!.drills.filter { it.kind == "meaning" }.map { it.prompt } } - own
                assertTrue(others.none { it in shown }, "$lang: no other language's drill remains: $shown")
                assertTrue(shown.any { it == "1 of ${meaning.size} · 0 right" }, "$lang: progress starts afresh: $shown")
                // Answer wrongly and continue: the miss is graded and queued under this language.
                click(drill.choices[(drill.answer + 1) % drill.choices.size])
                assertTrue(texts().any { it == "Not quite." }, "$lang graded: ${texts()}")
                val term = track.term(drill.termId)
                click(if (term != null) "Add to review and continue" else "Next")
                assertTrue(texts().any { it == "2 of ${meaning.size} · 0 right" }, "$lang advanced: ${texts()}")
                if (term != null) expected[lang] = expected.getValue(lang) + 1
                langs.forEach { l -> assertEquals(expected.getValue(l), app.reviews.total(app.learnerId, l), "$l queue after answering in $lang") }
                if (term != null) assertNotNull(app.db.srsQueries.itemByRef(app.learnerId, lang, "TERM", "term:${term.id}").executeAsOneOrNull())
            }
        } finally {
            scene.close()
            app.close()
        }
    }
}
