@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.exam.ListeningOutput
import app.mokuhyo.desktop.ui.exam.SessionPreview
import app.mokuhyo.exam.Skill
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QC Q9: in a Listening session the advertised Space shortcut runs the Play/Stop button's own command — one counted
 * play per start, Stop while playing, a failed start not counted — and a focused text field keeps its spaces. The
 * output is a controllable fixture (synthetic WAV, no device): this checks key dispatch and accounting, not sound.
 */
class ListeningSpaceKeyTest {
    @Test
    fun spaceStartsAndStopsPlaybackThroughTheButtonsCommand() {
        System.setProperty("java.awt.headless", "true")
        val fixture = VoiceSpec("fixture", "es", "female", "piper", "CC0")
        SpeechProvider.set(object : SpeechOutput {
            override fun voicesFor(language: String) = listOf(fixture)
            override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) =
                SpokenAudio(AudioIO.wav(ShortArray(1600) { (it % 50 * 400).toShort() }), voice ?: fixture)
        })
        val starts = AtomicInteger()
        val failNext = AtomicBoolean(false)
        val original = ListeningOutput.play
        ListeningOutput.play = { _, stop, started ->
            if (failNext.getAndSet(false)) throw IllegalStateException("fixture output unavailable")
            starts.incrementAndGet()
            started()
            while (!stop()) delay(10)
            false
        }
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-q9").toFile()))
        val scene = ImageComposeScene(1280, 1000, Density(1f)) { MokuhyoTheme { Column { SessionPreview(app, Skill.LISTENING, "0+", answer = false) } } }
        var frame = 0L
        fun pump(n: Int = 4) = repeat(n) { scene.render(frame++ * 16_000_000L); Thread.sleep(20) }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun texts() = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        fun until(what: String, ok: () -> Boolean) {
            repeat(200) { pump(1); if (ok()) return }
            error("$what: ${texts()}")
        }
        fun key(k: Key): Boolean = scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown)).also { scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp)); pump() }
        try {
            app.setLanguage("es")
            until("audio ready") { "▶ Play" in texts() }
            assertTrue(texts().any { it.endsWith("Space play/stop") }, "hint matches the button's Play/Stop: ${texts()}")

            // Space from the session's initial focus starts playback exactly once, through the counted path.
            assertTrue(key(Key.Spacebar), "Space is handled")
            until("playing") { "Stop" in texts() }
            assertEquals(1, starts.get())
            // Space again stops it; no second play is counted.
            assertTrue(key(Key.Spacebar))
            until("stopped") { "▶ Play again" in texts() }
            assertEquals(1, starts.get())

            // The button runs the same command.
            clickText(nodes(), "▶ Play again"); pump()
            until("playing by button") { "Stop" in texts() }
            assertTrue(key(Key.Spacebar))
            until("stopped by Space") { "▶ Play again" in texts() }
            assertEquals(2, starts.get())

            // A start that fails before any sound is not counted and says so.
            failNext.set(true)
            assertTrue(key(Key.Spacebar))
            until("start failure") { texts().any { it.startsWith("Couldn't start playback") } }
            assertEquals(2, starts.get())

            // Answering reveals the transcript and dictation; with the text field focused, Space is left to the field.
            assertTrue(key(Key.One))
            until("dictation") { "Dictation drill" in texts() }
            val field = nodes().first { it.config.getOrNull(SemanticsActions.RequestFocus) != null && it.config.getOrNull(SemanticsProperties.EditableText) != null }
            field.config[SemanticsActions.RequestFocus].action!!.invoke(); pump()
            key(Key.Spacebar)
            pump(10)
            assertFalse("Stop" in texts(), "no playback started while typing: ${texts()}")
            assertEquals(2, starts.get())
        } finally {
            scene.close()
            app.close()
            ListeningOutput.play = original
            SpeechProvider.set(null)
        }
    }

    private fun clickText(nodes: List<SemanticsNode>, label: String) {
        var n: SemanticsNode? = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == label } }
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        assertNotNull(n, label).config[SemanticsActions.OnClick].action!!.invoke()
    }
}
