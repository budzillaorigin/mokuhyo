package app.mokuhyo.desktop

import app.mokuhyo.ai.HardwareInfo
import app.mokuhyo.ai.ModelInfo
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.ModelManager
import app.mokuhyo.ai.ModelManifest
import app.mokuhyo.ai.Tier
import app.mokuhyo.ai.TierAdvisor
import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.lang.Languages
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.platform.HardwareProbe
import app.mokuhyo.platform.Os
import app.mokuhyo.platform.freeBytes
import app.mokuhyo.settings.Settings
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Everything the UI needs, created once at startup. Heavy pieces (native runtime, hardware probe) load lazily off the UI thread. */
class AppGraph(val dataDir: File = AppDirs.ensure()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = DatabaseFactory.open(File(dataDir, "db/mokuhyo.sqlite"))
    val db: MokuhyoDatabase = opened.second
    val settings = Settings(db)

    val learnerId: String = settings.get(Settings.Key.LEARNER_ID) ?: Uuid.random().toString().also { id ->
        db.userQueries.insertLearner(id, System.getProperty("user.name") ?: "Learner", Clock.System.now().toEpochMilliseconds())
        settings.put(Settings.Key.LEARNER_ID, id)
    }

    val manifest: ModelManifest = ModelManager.parseManifest(Resources.text("models/manifest.json"))
    val modelsDir = File(dataDir, "models")
    val models = ModelManager(FileSystem.SYSTEM, modelsDir.toOkioPath(), Java.create(), manifest, freeBytes = ::freeBytes)
    val downloads = DownloadCenter(scope, models)
    val runtime: NativeRuntime by lazy { NativeRuntime.create(settings.bool(Settings.Key.PREFER_CPU)) }

    private val _language = MutableStateFlow(settings.get(Settings.Key.CURRENT_LANGUAGE) ?: "ja")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _hardware = MutableStateFlow<HardwareInfo?>(null)
    val hardware: StateFlow<HardwareInfo?> = _hardware.asStateFlow()

    val firstRunDone: Boolean get() = settings.bool(Settings.Key.FIRST_RUN_DONE)

    /** Content packs: bundled in the installer, else installed into the data dir, else the dev checkout's content/packs. */
    val packsDir: File? = listOfNotNull(
        Resources.dir?.let { File(it, "packs") },
        File(dataDir, "packs"),
        Resources.repoDir?.let { File(it, "content/packs") },
    ).firstOrNull { d -> d.isDirectory && d.listFiles().orEmpty().any { it.isDirectory } }

    val speech: app.mokuhyo.lang.SpeechOutput get() = SpeechProvider.get(this)
    val languages = app.mokuhyo.lang.LanguageRegistry(packsDir, app.mokuhyo.lang.DictionaryOpeners.default) { speech }
    val history = app.mokuhyo.history.HistoryRepository(db)
    val reviews = app.mokuhyo.srs.ReviewService(db)
    private val examCache = java.util.concurrent.ConcurrentHashMap<String, Result<app.mokuhyo.exam.ExamContent?>>()

    /** The language's exam pack, parsed once (null when the pack isn't installed). Call off the UI thread. */
    fun exam(lang: String): app.mokuhyo.exam.ExamContent? = examCache.getOrPut(lang) {
        runCatching { packsDir?.let { File(it, "$lang/exam.json") }?.takeIf { it.isFile }?.let { app.mokuhyo.exam.ExamContent.parse(it.readText()) } }
    }.getOrNull()

    fun packFile(lang: String, relative: String): File? = packsDir?.let { File(File(it, lang), relative) }?.takeIf { it.isFile }

    fun setLanguage(code: String) {
        require(Languages.of(code) != null)
        settings.put(Settings.Key.CURRENT_LANGUAGE, code)
        _language.value = code
    }

    fun chosenLanguages(): List<String> =
        settings.get(Settings.Key.LANGUAGES)?.split(",")?.filter { Languages.of(it) != null }?.ifEmpty { null } ?: listOf(language.value)

    fun setChosenLanguages(codes: List<String>) {
        settings.put(Settings.Key.LANGUAGES, codes.joinToString(","))
        if (language.value !in codes && codes.isNotEmpty()) setLanguage(codes.first())
    }

    /** Probes the hardware once in the background (spawns small OS queries; may load the native library). */
    fun probeHardware() {
        if (_hardware.value != null) return
        scope.launch(Dispatchers.IO) { _hardware.value = HardwareProbe.probe(dataDir) { runtime.gpus() } }
    }

    val tier: Tier? get() = Tier.of(settings.get(Settings.Key.MODEL_TIER))

    /** The LLM the learner picked (tier default unless an alternate was chosen). */
    fun chosenModel(): ModelInfo? =
        settings.get(Settings.Key.MODEL_ID)?.let { models.model(it) } ?: tier?.let { TierAdvisor.defaultModel(it, manifest.models) }

    fun chooseTier(tier: Tier, model: ModelInfo? = null) {
        settings.put(Settings.Key.MODEL_TIER, tier.id)
        settings.put(Settings.Key.MODEL_ID, (model ?: TierAdvisor.defaultModel(tier, manifest.models))?.id ?: "")
    }

    /** A model file on disk: the bundled copy inside the app, else the downloaded one. */
    fun modelFile(model: ModelInfo): File? {
        Resources.bundledModel(model.files.first().name)?.let { return it }
        return models.modelPath(model)?.toFile()
    }

    fun sttModel(): ModelInfo? = settings.get(Settings.Key.STT_MODEL_ID)?.let { models.model(it) }?.takeIf { modelFile(it) != null }
        ?: manifest.models.filter { it.kind == ModelKind.STT }.firstOrNull { modelFile(it) != null }

    fun close() {
        opened.first.close()
    }

    companion object {
        val osLabel: String get() = "${Os.current.id}-${Os.arch}"
    }
}
