package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.exam.AidState
import app.mokuhyo.desktop.ui.exam.ListeningPanel
import app.mokuhyo.exam.ExamForm
import app.mokuhyo.exam.ExamItem
import app.mokuhyo.exam.ExamKind
import app.mokuhyo.exam.ExamMode
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ExamSession
import app.mokuhyo.exam.FormItem
import app.mokuhyo.exam.FormSection
import app.mokuhyo.exam.PlayPolicy
import app.mokuhyo.exam.ScriptLine
import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceRotation
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * QC follow-up F1: a damaged recording (pack clip or cached render) reaches the listening recovery controls as
 * [PassageAudio.Result.Unavailable], or is replaced by the voice service, and never throws out of the screen.
 */
class PassageAudioLoadTest {
    private val voice = VoiceSpec("es_ES-test-medium", "es", "female", "piper", "CC0")

    private fun passage(audio: String?) = ExamPassage(
        "es-l-1", ExamKind.DLPT_LISTENING, "es", "1", "news", "Noticias", "", listOf(ScriptLine("A", "female", "Hola a todos.")), "llm", false, audio = audio,
    )

    /** A data dir whose packs dir holds `es/audio/<name>` with [bytes]. */
    private fun appWithClip(name: String, bytes: ByteArray): AppGraph {
        val dir = Files.createTempDirectory("mokuhyo-f1").toFile()
        File(dir, "packs/es/audio").mkdirs()
        File(dir, "packs/es/audio/$name").writeBytes(bytes)
        return AppGraph(AppDirs.ensure(dir), packsRoot = File(dir, "packs")).also {
            assertEquals(File(dir, "packs/es/audio/$name").canonicalFile, it.packFile("es", "audio/$name")?.canonicalFile,
                "the damaged-clip fixture must win even when the build has staged bundled packs")
        }
    }

    private class FakeSpeech(val voices: List<VoiceSpec>, val speak: suspend (VoiceSpec) -> SpokenAudio?) : SpeechOutput {
        override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) = speak(voice ?: voices.first())
        override fun voicesFor(language: String) = voices
    }

    private fun tone() = AudioIO.wav(ShortArray(1600) { (it % 50 * 400).toShort() })

    private val brokenOgg = "OggS".toByteArray() + ByteArray(60)

    @AfterTest
    fun restoreSpeech() = SpeechProvider.set(null)

    @Test
    fun brokenOggWithNoVoiceIsUnavailableWithAnActionableReason() = runBlocking<Unit> {
        SpeechProvider.set(FakeSpeech(emptyList()) { null })
        val app = appWithClip("broken.ogg", brokenOgg)
        try {
            val r = assertIs<PassageAudio.Result.Unavailable>(PassageAudio(app).load(passage("audio/broken.ogg")))
            assertTrue("damaged" in r.reason && "Try again" in r.reason, r.reason)
        } finally {
            app.close()
        }
    }

    @Test
    fun brokenOggIsReplacedByTheVoiceServiceAndSaysSo() = runBlocking<Unit> {
        SpeechProvider.set(FakeSpeech(listOf(voice)) { SpokenAudio(tone(), it) })
        val app = appWithClip("broken.ogg", brokenOgg)
        try {
            val r = assertIs<PassageAudio.Result.Ready>(PassageAudio(app).load(passage("audio/broken.ogg")))
            assertTrue(r.source.startsWith("voice service") && "damaged" in r.source, r.source)
            assertTrue(playable(r.wav))
        } finally {
            app.close()
        }
    }

    @Test
    fun zeroLengthClipIsUnavailable() = runBlocking<Unit> {
        SpeechProvider.set(FakeSpeech(emptyList()) { null })
        val app = appWithClip("empty.ogg", ByteArray(0))
        try {
            assertIs<PassageAudio.Result.Unavailable>(PassageAudio(app).load(passage("audio/empty.ogg")))
        } finally {
            app.close()
        }
    }

    @Test
    fun corruptCachedRenderIsDeletedAndRenderedAgain() = runBlocking<Unit> {
        SpeechProvider.set(FakeSpeech(listOf(voice)) { SpokenAudio(tone(), it) })
        val app = appWithClip("unused.ogg", ByteArray(1))
        try {
            val p = passage(null)
            val cached = cacheFile(app, p)
            cached.parentFile.mkdirs()
            cached.writeBytes("RIFF".toByteArray() + ByteArray(40))
            val r = assertIs<PassageAudio.Result.Ready>(PassageAudio(app).load(p))
            assertEquals("voice service", r.source)
            assertTrue(playable(cached.readBytes()), "the damaged cache file was replaced by the new render")
        } finally {
            app.close()
        }
    }

    @Test
    fun corruptCachedRenderIsRemovedWhenNothingCanReplaceIt() = runBlocking<Unit> {
        SpeechProvider.set(FakeSpeech(listOf(voice)) { null })
        val app = appWithClip("unused.ogg", ByteArray(1))
        try {
            val p = passage(null)
            val cached = cacheFile(app, p)
            cached.parentFile.mkdirs()
            cached.writeBytes(ByteArray(0))
            assertIs<PassageAudio.Result.Unavailable>(PassageAudio(app).load(p))
            assertFalse(cached.exists())
        } finally {
            app.close()
        }
    }

    @Test
    fun cancellingTheLoadPropagatesCancellation() = runBlocking<Unit> {
        val rendering = CompletableDeferred<Unit>()
        SpeechProvider.set(FakeSpeech(listOf(voice)) { rendering.complete(Unit); awaitCancellation() })
        val app = appWithClip("broken.ogg", brokenOgg)
        try {
            val load = async { PassageAudio(app).load(passage("audio/broken.ogg")) }
            rendering.await()
            load.cancel()
            assertFailsWith<CancellationException> { load.await() }
            assertTrue(load.isCancelled)
        } finally {
            app.close()
        }
    }

    /** The Listening panel itself: a broken clip shows Try again and (in a test) Leave out, and costs no play. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun brokenClipReachesTheRecoveryControlsWithoutUsingAPlay() {
        System.setProperty("java.awt.headless", "true")
        SpeechProvider.set(FakeSpeech(emptyList()) { null })
        val app = appWithClip("broken.ogg", brokenOgg)
        try {
            val p = passage("audio/broken.ogg")
            val item = ExamItem("q1", "shipped", ExamKind.DLPT_LISTENING, "1", "main_idea", p.id, "¿De qué trata?", listOf("a", "b", "c", "d"), 0, "", emptyList(), emptyList(), "llm", false)
            val form = ExamForm(ExamKind.DLPT_LISTENING, "es", "1", ExamMode.SLICE_30,
                listOf(FormSection("Listening", 30, listOf(FormItem(item, p.id, "Main idea")), listening = true)), mapOf(p.id to p), emptyList())
            val session = ExamSession(form, PlayPolicy(plays = 1))
            val module = app.languages.module("es")
            val scene = ImageComposeScene(1000, 1200, Density(1f)) {
                MokuhyoTheme { Column { ListeningPanel(app, module, session, p, practice = false, transcriptVisible = false, aids = AidState(), onAids = {}) {} } }
            }
            try {
                val tryAgain = waitFor(scene) { it.textNode("Try again") }
                assertTrue(scene.texts().any { "damaged" in it }, scene.texts().toString())
                assertTrue(scene.texts().any { it == "Leave this recording out of the score" }, scene.texts().toString())
                // Retrying loads again and still costs nothing.
                val c = tryAgain.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, Offset(c.x, c.y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(c.x, c.y))
                waitFor(scene) { it.textNode("Try again") }
                assertEquals(0, session.playsOf(p.id))
                assertTrue(session.canPlayAudio(p.id))
            } finally {
                scene.close()
            }
        } finally {
            app.close()
        }
    }

    private fun playable(wav: ByteArray): Boolean = runCatching { AudioIO.toMonoPcm(wav).first.isNotEmpty() }.getOrDefault(false)

    private fun cacheFile(app: AppGraph, p: ExamPassage): File {
        val cast = VoiceRotation.assign(listOf(voice), p.script.map { it.speaker to it.voice }.distinctBy { it.first }, p.id)
        return File(app.dataDir, "cache/audio/es/${p.id}-${PassageAudio.cacheKey(p, cast)}.wav")
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }

    private fun ImageComposeScene.texts(): List<String> =
        nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ImageComposeScene.textNode(text: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }

    /** Renders frames until [find] returns a node (the panel loads its audio off the UI thread). */
    private fun waitFor(scene: ImageComposeScene, find: (ImageComposeScene) -> SemanticsNode?): SemanticsNode {
        val start = System.nanoTime()
        var frame = 0L
        while (System.nanoTime() - start < 10_000_000_000L) {
            scene.render(frame++ * 16_000_000L)
            find(scene)?.let { return it }
            Thread.sleep(20)
        }
        error("not shown within 10 s; on screen: ${scene.texts()}")
    }
}
