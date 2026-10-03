package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.Os
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Where the bundled voice service lives and which Piper voices are installed (BRIEF §3.3).
 *
 * Layouts, first match wins:
 * 1. system property `mokuhyo.voices.dir` (or env `MOKUHYO_VOICES_DIR`): `<dir>/piper/`, `<dir>/manifest.json`,
 *    `<dir>/<voice id>/` — an explicit override, like `mokuhyo.native.dir` (D-011);
 * 2. the packaged app: `<compose.application.resources.dir>/voices/` with the same layout (staged by
 *    tools/release/stage_resources.py: piper/ from the OS-specific resources, voice files + manifest from common/);
 * 3. a dev checkout, found by walking up from the working directory: `voices/build/<os>-<arch>/piper/`,
 *    `voices/manifest.json`, `voices/models/<voice id>/` (voices/build.sh, tools/voices/manifest.py --fetch).
 */
class VoiceCatalog(
    /** Directory holding the piper executable, its libraries and espeak-ng-data; null when not found. */
    val piperDir: File?,
    /** Installed voices whose model and config files exist. */
    val voices: List<PiperVoice>,
    /** Where the catalog came from, for Settings and logs. */
    val source: String,
) {
    val piperExecutable: File?
        get() = piperDir?.let { File(it, if (Os.current == Os.WINDOWS) "piper.exe" else "piper") }?.takeIf { it.isFile }

    val espeakData: File? get() = piperDir?.let { File(it, "espeak-ng-data") }?.takeIf { it.isDirectory }

    /** True when Piper can run here: executable, espeak-ng data and at least one voice. */
    val usable: Boolean get() = piperExecutable != null && espeakData != null && voices.isNotEmpty()

    /** A bundled voice: [spec] as the app sees it, plus its model files. */
    class PiperVoice(
        val spec: VoiceSpec,
        val model: File,
        val config: File,
        /** The model's own `inference.length_scale` (speed 1.0). */
        val defaultLengthScale: Double,
        val quality: String,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val platform: String get() = "${Os.current.id}-${Os.arch}"

        /** Finds the voice service for this process (see the class comment for the order). */
        fun discover(): VoiceCatalog {
            val explicit = System.getProperty("mokuhyo.voices.dir") ?: System.getenv("MOKUHYO_VOICES_DIR")
            if (!explicit.isNullOrBlank()) return fromInstallDir(File(explicit), "mokuhyo.voices.dir")
            System.getProperty("compose.application.resources.dir")?.let { File(it, "voices") }?.takeIf { it.isDirectory }
                ?.let { return fromInstallDir(it, "app resources") }
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                val voices = File(dir, "voices")
                if (File(voices, "manifest.json").isFile) {
                    return load(
                        piperDir = File(voices, "build/$platform/piper"),
                        manifest = File(voices, "manifest.json"),
                        modelsDir = File(voices, "models"),
                        source = "dev checkout ${voices.path}",
                    )
                }
                dir = dir.parentFile
            }
            return VoiceCatalog(null, emptyList(), "not found")
        }

        /** The installed layout: `<dir>/piper/`, `<dir>/manifest.json`, `<dir>/<id>/`. */
        fun fromInstallDir(dir: File, source: String = dir.path): VoiceCatalog =
            load(File(dir, "piper"), File(dir, "manifest.json"), dir, source)

        fun load(piperDir: File?, manifest: File, modelsDir: File, source: String): VoiceCatalog {
            val entries = runCatching { json.decodeFromString<Manifest>(manifest.readText()).voices }.getOrDefault(emptyList())
            val voices = entries.filter { it.engine == "piper" }.mapNotNull { e ->
                val stem = e.model ?: e.id // speakers of one multi-speaker model share its files (BRIEF_PHASE8 N-07)
                val model = File(modelsDir, "$stem/$stem.onnx")
                val config = File(modelsDir, "$stem/$stem.onnx.json")
                if (!model.isFile || !config.isFile) return@mapNotNull null
                val lengthScale = runCatching {
                    json.parseToJsonElement(config.readText()).jsonObject["inference"]?.jsonObject
                        ?.get("length_scale")?.jsonPrimitive?.doubleOrNull
                }.getOrNull() ?: 1.0
                PiperVoice(
                    spec = VoiceSpec(id = e.id, language = e.language, gender = e.gender, engine = "piper", license = e.license, speaker = e.speaker),
                    model = model,
                    config = config,
                    defaultLengthScale = lengthScale,
                    quality = e.quality,
                )
            }
            return VoiceCatalog(piperDir?.takeIf { it.isDirectory }, voices, source)
        }
    }

    @Serializable
    internal data class Manifest(val voices: List<Entry> = emptyList())

    @Serializable
    internal data class Entry(
        val id: String,
        val language: String,
        val gender: String = "unknown",
        val engine: String = "piper",
        val speaker: Int? = null,
        val quality: String = "medium",
        val license: String = "",
        val model: String? = null,
    )
}
