package app.mokuhyo.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.Destination
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.Screens
import app.mokuhyo.desktop.ui.Shell
import app.mokuhyo.platform.AppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QC follow-up (optional item): at 1024×700 pixels and 150 % scaling (about 683×467 logical) the navigation rail
 * clipped Settings below the window. Every destination must be on screen or reachable by scrolling the rail.
 */
class NavigationRailRenderTest {
    @Test
    fun allNineDestinationsAreReachableAtSmallHeights() {
        System.setProperty("java.awt.headless", "true")
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-rail").toFile()))
        try {
            val height = 700
            val scene = ImageComposeScene(1024, height, Density(1.5f)) { MokuhyoTheme { Shell(app, screens = Screens { _, _, _, _ -> }) } }
            try {
                var frame = 0L
                scene.render(frame++)
                Destination.entries.forEach { d ->
                    var item = assertNotNull(scene.item(d.title), d.title)
                    val icon = 24f * 1.5f
                    if (item.boundsInRoot.height < icon) {
                        // Cut off at the bottom (bounds are clipped to what shows): the rail must scroll it into view.
                        val rail = assertNotNull(scene.nodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }, "${d.title} is cut off and the rail can't scroll")
                        rail.config[SemanticsActions.ScrollBy].action!!(0f, height.toFloat())
                        repeat(60) { scene.render(frame++ * 16_000_000L) } // the scroll is animated
                        item = assertNotNull(scene.item(d.title))
                    }
                    // A squeezed-out item has empty bounds; a reachable one is its full icon size, inside the window.
                    val b = item.boundsInRoot
                    assertTrue(b.width >= icon && b.height >= icon && b.top >= 0f && b.bottom <= height, "${d.title} at $b")
                }
            } finally {
                scene.close()
            }
        } finally {
            app.close()
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }

    /** The rail item labelled [title] (its icon's content description), as laid out now. */
    private fun ImageComposeScene.item(title: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(title) == true }
}
