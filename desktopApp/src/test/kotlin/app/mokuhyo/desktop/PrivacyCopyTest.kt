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
import app.mokuhyo.desktop.ui.SettingsScreen
import app.mokuhyo.net.NetworkPolicy
import app.mokuhyo.platform.AppDirs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QC Q8: Settings → Privacy & updates (network enabled) and docs/SECURITY_PROFILE.md describe every connection the app
 * can make — including prompts and answers sent to a home-network Ollama server and Lexicon URL downloads — and never
 * claim that optional LAN inference stays on this computer.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PrivacyCopyTest {
    /** The implemented network capabilities and the words each description must carry, on screen and in the profile. */
    private val capabilities = mapOf(
        "model download" to listOf("Model download"),
        "automatic update check, off by default" to listOf("Update check", "off by default", "api.github.com"),
        "manual update check" to listOf("Check now"),
        "Ollama detection on this computer or the home network" to listOf("Look for Ollama", "home network"),
        "LAN model inference sends prompts and answers" to listOf("Ollama model use", "prompts", "answers", "leave this computer"),
        "Lexicon update by URL" to listOf("Lexicon update by URL", "Download"),
        "browser links" to listOf("browser", "This month"),
    )

    @Test
    fun privacyAndSecurityProfileDescribeEveryNetworkCapability() {
        System.setProperty("java.awt.headless", "true")
        NetworkPolicy.disabled = false
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-q8").toFile()))
        val scene = ImageComposeScene(1280, 2400, Density(1f)) { MokuhyoTheme { Column { SettingsScreen(app) } } }
        try {
            scene.render(0)
            fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            fun texts() = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            var tab: SemanticsNode? = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Privacy & updates" } }
            while (tab != null && tab.config.getOrNull(SemanticsActions.OnClick) == null) tab = tab.parent
            assertNotNull(tab).config[SemanticsActions.OnClick].action!!.invoke()
            repeat(4) { scene.render(it * 16_000_000L + 16_000_000L) }
            val privacy = texts().joinToString("\n")
            assertTrue("works offline" in privacy, privacy)
            val profile = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/SECURITY_PROFILE.md") }.first { it.isFile }.readText()
            for ((capability, words) in capabilities) {
                for (w in words) {
                    assertTrue(privacy.contains(w, ignoreCase = true), "Privacy screen: $capability needs '$w':\n$privacy")
                    assertTrue(profile.contains(w, ignoreCase = true), "SECURITY_PROFILE.md: $capability needs '$w'")
                }
            }
            // No claim that data never leaves except exports, or that Ollama is localhost-only.
            assertFalse("Stored only on this computer" in privacy, privacy)
            assertFalse("localhost:11434 only" in profile)
            assertTrue("Ollama server" in profile.substringAfter("## Data on the computer").substringBefore("## "), "data section names the LAN server")
        } finally {
            scene.close()
            app.close()
        }
    }
}
