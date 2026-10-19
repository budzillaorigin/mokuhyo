package app.mokuhyo.desktop

import app.mokuhyo.ai.Sha256
import app.mokuhyo.opi.PromptAudioIndex
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.OggOpus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** N5: a prerendered prompt plays only when it is intact and still says the question; otherwise the caller uses the live voice. */
class PromptClipsTest {
    private val text = "¿Cómo se llama usted?"

    private fun withPack(clip: (File) -> Unit, body: suspend (AppGraph) -> Unit) {
        val root = Files.createTempDirectory("mokuhyo-prompts").toFile()
        val pack = File(root, "es").apply { mkdirs() }
        val ogg = OggOpus.encode(ShortArray(24_000) { (sin(it / 20.0) * 4_000).toInt().toShort() }, bitrate = 32_000, sampleRate = 24_000)
        val entry = PromptAudioIndex.Entry("es-q-1", "question", "warmup", "1", null, PromptAudioIndex.textHash(text), Sha256.hex(ogg), 1.0, ogg.size.toLong(), "piper", "es_ES-davefx-medium")
        File(pack, PromptAudioIndex.FILE).writeText(PromptAudioIndex(language = "es", prompts = listOf(entry)).toJson())
        val file = File(pack, entry.relativePath()).apply { parentFile.mkdirs(); writeBytes(ogg) }
        clip(file)
        PromptClips.forget()
        val app = AppGraph(AppDirs.ensure(Files.createTempDirectory("mokuhyo-prompts-data").toFile()), packsRoot = root)
        try { runBlocking { body(app) } } finally { app.close(); PromptClips.forget() }
    }

    @Test
    fun anIntactClipPlays() = withPack({}) { app -> assertNotNull(PromptClips.load(app, "es", "es-q-1", text)) }

    @Test
    fun aCorruptClipFallsBackWithoutThrowing() = withPack({ f -> f.writeBytes(f.readBytes().copyOf(200)) }) { app ->
        assertNull(PromptClips.load(app, "es", "es-q-1", text))
    }

    @Test
    fun aMissingClipFallsBack() = withPack({ f -> f.delete() }) { app -> assertNull(PromptClips.load(app, "es", "es-q-1", text)) }

    @Test
    fun aQuestionWhoseTextChangedDoesNotPlayItsOldClip() = withPack({}) { app ->
        assertNull(PromptClips.load(app, "es", "es-q-1", "¿Dónde vive usted?"))
        assertNull(PromptClips.load(app, "es", "unknown", text))
        assertNull(PromptClips.load(app, "fr", "es-q-1", text), "no index for the language")
    }
}
