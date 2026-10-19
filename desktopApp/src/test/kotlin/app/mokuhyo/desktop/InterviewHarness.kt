package app.mokuhyo.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import app.mokuhyo.ai.*
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.speaking.SpeakingScreen
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.settings.Settings
import io.ktor.client.engine.java.Java
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import java.lang.reflect.Proxy
import javax.sound.sampled.*
import javax.sound.sampled.spi.MixerProvider

/** A synthetic microphone, never a host device. Loaded only by the test runtime's JavaSound SPI. */
class InterviewMicrophone : MixerProvider() {
    companion object {
        val info = object : Mixer.Info("Mokuhyo regression input", "test", "synthetic PCM", "1") {}
        @Volatile var enabled = false
        @Volatile var stopDelayMs = 0L
        @Volatile var openDelayMs = 0L
        @Volatile var closed = 0
        @Volatile var opening = 0
    }
    override fun getMixerInfo(): Array<Mixer.Info> = if (enabled) arrayOf(info) else emptyArray()
    override fun getMixer(info: Mixer.Info?): Mixer {
        require(enabled && info == Companion.info)
        var running = true
        var format = AudioFormat(48000f, 16, 1, true, false)
        val line = proxy<TargetDataLine> { method, args -> when (method) {
            "open" -> { opening++; Thread.sleep(openDelayMs); format = args!![0] as AudioFormat; null }
            "getFormat" -> format
            "start" -> { running = true; null }
            "stop" -> { Thread.sleep(stopDelayMs); running = false; null }
            "close" -> { running = false; closed++; null }
            "read" -> {
                Thread.sleep(20)
                if (!running) 0 else {
                    val bytes = args!![0] as ByteArray
                    val offset = args[1] as Int
                    val length = args[2] as Int
                    repeat(length / 2) { i -> bytes[offset + i * 2] = 0; bytes[offset + i * 2 + 1] = (if (i % 20 < 10) 20 else -20).toByte() }
                    length
                }
            }
            else -> null
        } }
        return proxy { method, _ -> when (method) {
            "isLineSupported" -> true
            "getLine" -> line
            "getMixerInfo" -> Companion.info
            else -> null
        } }
    }
    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> call(m.name, args) } as T
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class InterviewHarness : AutoCloseable {
    val dir = Files.createTempDirectory("mokuhyo-interview-test").toFile()
    val app = AppGraph(AppDirs.ensure(dir))
    var scene: ImageComposeScene? = null
    var frame = 0L
    @Volatile var transcription: ((String?, String?) -> Unit)? = null
    @Volatile var cancelled = false
    init {
        System.setProperty("java.awt.headless", "true")
        app.setLanguage("es")
        app.settings.put(Settings.Key.CONFIRM_TRANSCRIPT, "false")
        app.settings.put(Settings.Key.CORRECTIONS_OPI, "off")
        app.settings.put(Settings.Key.INPUT_DEVICE, InterviewMicrophone.info.name)
        // No model weights: a one-byte installation fixture and a callback-only native bridge.
        val hash = "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"
        val model = ModelInfo("test-stt", "Test STT", ModelKind.STT, minRamGb = 0, license = "CC0",
            files = listOf(ModelFile("test-stt.bin", "https://invalid.example/test", hash, 1)))
        val manifest = ModelManifest(1, models = listOf(model))
        File(app.modelsDir, "test-stt").mkdirs()
        File(app.modelsDir, "test-stt/test-stt.bin").writeBytes(byteArrayOf(0))
        File(app.modelsDir, "test-stt/test-stt.bin.sha256").writeText(hash)
        replace("manifest", manifest)
        replace("models", ModelManager(FileSystem.SYSTEM, app.modelsDir.toOkioPath(), Java.create(), manifest))
        replace("runtime\$delegate", lazyOf(object : NativeRuntime {
            override val status = "test"
            override val available = true
            override val gpuActive = false
            override fun gpus() = emptyList<GpuInfo>()
            override fun llm(): LocalLlmBridge? = null
            override fun stt() = object : LocalSttBridge, CancellableSttBridge {
                override fun isLoaded() = true
                override fun load(modelPath: String, onDone: (String?) -> Unit) = onDone(null)
                override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) { transcription = onDone }
                override fun cancel() { cancelled = true }
            }
        }))
        InterviewMicrophone.enabled = true
        SpeechProvider.set(object : app.mokuhyo.lang.SpeechOutput {
            override fun voicesFor(language: String) = emptyList<app.mokuhyo.lang.VoiceSpec>()
            override suspend fun synthesize(text: String, language: String, voice: app.mokuhyo.lang.VoiceSpec?, speed: Double): app.mokuhyo.lang.SpokenAudio? = null
        })
    }
    fun replace(name: String, value: Any) { AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }.set(app, value) }
    fun show() { scene = ImageComposeScene(1200, 1800, Density(1f)) { MokuhyoTheme { SpeakingScreen(app) } } }
    fun nodes() = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
    fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    fun pump() { scene!!.render(frame++ * 16_000_000L); Thread.sleep(20) }
    fun until(message: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) { pump(); if (check()) return }
        error("$message; screen=${texts()}")
    }
    fun click(text: String) {
        until("Missing $text") { text in texts() }
        val node = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == text } }
        scene!!.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
        scene!!.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
        pump()
    }
    fun type(text: String) {
        until("text field missing") { nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null } }
        nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }.config[SemanticsActions.SetText].action!!(AnnotatedString(text))
        pump()
    }
    override fun close() {
        scene?.close()
        app.close()
        SpeechProvider.set(null)
        InterviewMicrophone.enabled = false
        InterviewMicrophone.stopDelayMs = 0
        InterviewMicrophone.openDelayMs = 0
        dir.deleteRecursively()
    }
}
