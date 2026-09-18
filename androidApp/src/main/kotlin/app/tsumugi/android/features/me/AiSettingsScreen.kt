package app.tsumugi.android.features.me

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.ai.DownloadProgress
import app.tsumugi.ai.ModelInfo
import app.tsumugi.ai.ModelKind
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.ui.Tag
import app.tsumugi.speaking.AiConfig
import app.tsumugi.speaking.LlmEngine
import app.tsumugi.speaking.SttEngine
import app.tsumugi.speaking.TtsEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Holds model downloads so they keep going across rotation and while the learner looks at other screens. */
class AiSettingsViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as TsumugiApplication).graph
    val progress = mutableStateMapOf<String, DownloadProgress>()
    private val jobs = mutableMapOf<String, Job>()
    /** Bumped when files change on disk (download finished, deleted) so sizes refresh. */
    var diskVersion by mutableStateOf(0)
        private set

    fun isDownloading(model: ModelInfo) = jobs[model.id]?.isActive == true

    fun download(model: ModelInfo) {
        val manager = graph.ai.models ?: return
        if (isDownloading(model)) return
        jobs[model.id] = viewModelScope.launch {
            try {
                manager.download(model).collect { progress[model.id] = it }
            } finally {
                if (jobs[model.id] === coroutineContext[Job]) jobs.remove(model.id)
                diskVersion++
            }
        }
    }

    /** Pauses: the partial file stays, and the next download resumes from it. */
    fun cancel(model: ModelInfo) {
        jobs.remove(model.id)?.cancel()
        progress.remove(model.id)
        diskVersion++
    }

    fun delete(model: ModelInfo) {
        cancel(model)
        graph.ai.models?.delete(model)
        diskVersion++
    }
}

/** Settings → AI & speech (BRIEF §7): LLM engine, models, your own server, speech recognition and voices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen() {
    val vm: AiSettingsViewModel = viewModel()
    val graph = vm.graph
    val context = LocalContext.current
    var config by remember { mutableStateOf<AiConfig?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var keyEdited by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<Result<List<String>>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<ModelInfo?>(null) }

    LaunchedEffect(Unit) {
        config = graph.ai.config()
        apiKey = graph.ai.endpointKey.orEmpty()
    }
    // Debounced auto-save.
    LaunchedEffect(config, apiKey) {
        val c = config ?: return@LaunchedEffect
        delay(400)
        graph.ai.save(c)
        if (keyEdited) graph.ai.endpointKey = apiKey
        status = graph.ai.unavailableReason()
    }
    LaunchedEffect(vm.diskVersion) { status = config?.let { graph.ai.unavailableReason() } }

    val c = config
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        Text(
            "Everything runs on this phone by default. Optionally use your own server (Ollama, LM Studio, llama-server, vLLM). " +
                "No account, subscription or API key from a company is needed.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionTitle("Language model")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.llm == LlmEngine.NONE, { config = c.copy(llm = LlmEngine.NONE) }, { Text("Off") })
            FilterChip(c.llm == LlmEngine.LOCAL, { config = c.copy(llm = LlmEngine.LOCAL) }, { Text("On-device") })
            FilterChip(c.llm == LlmEngine.ENDPOINT, { config = c.copy(llm = LlmEngine.ENDPOINT) }, { Text("My server") })
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (c.llm == LlmEngine.NONE) {
            Text(
                "With no model, practice still works: role-plays follow scripts, the OPI uses question banks and a self-rating " +
                    "checklist, and explanations come from the packs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (c.llm == LlmEngine.LOCAL) {
            ModelList(vm, ModelKind.LLM, selected = c.localModelId, onSelect = { config = c.copy(localModelId = it) }, onDownload = { pendingDownload = it })
        }
        if (c.llm == LlmEngine.ENDPOINT) {
            OutlinedTextField(
                c.endpointUrl, { config = c.copy(endpointUrl = it) }, Modifier.fillMaxWidth(),
                label = { Text("Server URL") }, placeholder = { Text("http://<lan-ip>:11434/v1") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                apiKey, { apiKey = it; keyEdited = true }, Modifier.fillMaxWidth(),
                label = { Text("API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Stored in the Android Keystore and sent only to this server.") },
            )
            OutlinedTextField(c.endpointModel, { config = c.copy(endpointModel = it) }, Modifier.fillMaxWidth(), label = { Text("Model name") }, singleLine = true)
            Button(
                onClick = {
                    probing = true
                    vm.viewModelScope.launch {
                        probe = graph.ai.probeEndpoint(c.endpointUrl, apiKey.ifBlank { null })
                        probing = false
                    }
                },
                enabled = c.endpointUrl.isNotBlank() && !probing,
            ) { Text(if (probing) "Testing…" else "Test connection") }
            probe?.let { r ->
                r.onSuccess { models ->
                    Text("Connected. ${models.size} models — pick one:", style = MaterialTheme.typography.bodyMedium)
                    models.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { config = c.copy(endpointModel = m) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(c.endpointModel == m, { config = c.copy(endpointModel = m) })
                            Text(m)
                        }
                    }
                }
                r.onFailure { Text("Couldn't connect: ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        HorizontalDivider()
        SectionTitle("Speech recognition")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.stt == SttEngine.SYSTEM, { config = c.copy(stt = SttEngine.SYSTEM) }, { Text("System") })
            FilterChip(c.stt == SttEngine.WHISPER_LOCAL, { config = c.copy(stt = SttEngine.WHISPER_LOCAL) }, { Text("On-device Whisper") })
            FilterChip(c.stt == SttEngine.WHISPER_ENDPOINT, { config = c.copy(stt = SttEngine.WHISPER_ENDPOINT) }, { Text("Whisper server") })
        }
        when (c.stt) {
            SttEngine.SYSTEM -> Text(
                "Android's recognizer in Japanese, offline when the device has the Japanese pack. Recordings stay on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
            SttEngine.WHISPER_LOCAL -> {
                Text("Whisper runs on this phone; recordings never leave it.", style = MaterialTheme.typography.bodySmall)
                ModelList(vm, ModelKind.STT, selected = c.localSttModelId, onSelect = { config = c.copy(localSttModelId = it) }, onDownload = { pendingDownload = it })
            }
            SttEngine.WHISPER_ENDPOINT -> {
                OutlinedTextField(
                    c.sttEndpointUrl, { config = c.copy(sttEndpointUrl = it) }, Modifier.fillMaxWidth(),
                    label = { Text("Whisper server URL") }, placeholder = { Text("http://<lan-ip>:8000/v1") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("Recordings are sent only to this server (yours), using the API key above if set.", style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()
        SectionTitle("Voices")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.tts == TtsEngine.SYSTEM, { config = c.copy(tts = TtsEngine.SYSTEM) }, { Text("System") })
            FilterChip(c.tts == TtsEngine.VOICEVOX, { config = c.copy(tts = TtsEngine.VOICEVOX) }, { Text("VOICEVOX") })
        }
        if (c.tts == TtsEngine.SYSTEM) {
            Text("Android's text-to-speech. Install a Japanese voice in Android settings → Text-to-speech if none is available.", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(
                c.voicevoxUrl, { config = c.copy(voicevoxUrl = it) }, Modifier.fillMaxWidth(),
                label = { Text("VOICEVOX engine URL") }, placeholder = { Text("http://<lan-ip>:50021") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                c.voicevoxSpeaker.toString(), { v -> v.toIntOrNull()?.let { config = c.copy(voicevoxSpeaker = it) } }, Modifier.fillMaxWidth(),
                label = { Text("Speaker id") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("A free engine you run yourself. If it can't be reached, the system voice is used.", style = MaterialTheme.typography.bodySmall)
        }
    }

    pendingDownload?.let { model ->
        LaunchedEffect(model) {
            if (!isMetered(context)) {
                vm.download(model)
                pendingDownload = null
            }
        }
        if (isMetered(context)) {
            AlertDialog(
                onDismissRequest = { pendingDownload = null },
                title = { Text("Download on mobile data?") },
                text = { Text("${model.name} is ${gb(model.totalBytes)}. You're on a metered connection; Wi-Fi is recommended. You can pause and resume later.") },
                confirmButton = { TextButton(onClick = { vm.download(model); pendingDownload = null }) { Text("Download anyway") } },
                dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text("Wait for Wi-Fi") } },
            )
        }
    }
}

@Composable
private fun ModelList(vm: AiSettingsViewModel, kind: ModelKind, selected: String?, onSelect: (String) -> Unit, onDownload: (ModelInfo) -> Unit) {
    val manager = vm.graph.ai.models
    if (manager == null) {
        Notice("The model catalog (content/models/manifest.json) is missing from this build.")
        return
    }
    val ram = vm.graph.ai.deviceRamGb
    val recommended = manager.recommend(kind, ram)
    val models = manager.models(kind)
    Text(
        "Downloads come straight from the model host: no account or sign-in. Use Wi-Fi (models are large); an interrupted " +
            "download resumes where it stopped. This phone has about ${ram.toInt()} GB of RAM.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (models.isEmpty()) Text("No models of this kind in the catalog.")
    @Suppress("UNUSED_VARIABLE") val v = vm.diskVersion
    val effective = selected ?: recommended?.id
    models.forEach { m ->
        val installed = manager.isInstalled(m)
        val onDisk = manager.bytesOnDisk(m)
        val p = vm.progress[m.id]
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(effective == m.id, { onSelect(m.id) }, enabled = installed)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Text("${gb(m.totalBytes)} · needs ${m.minRamGb} GB RAM · ${m.license}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (m.id == recommended?.id) Tag("Recommended")
                }
                if (m.minRamGb > ram) Text("May not fit in this phone's memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                when {
                    vm.isDownloading(m) -> {
                        when (p) {
                            is DownloadProgress.Downloading -> {
                                LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text("${gb(p.bytesDone)} of ${gb(p.bytesTotal)}", style = MaterialTheme.typography.bodySmall)
                            }
                            is DownloadProgress.Verifying -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Verifying ${p.file}…", style = MaterialTheme.typography.bodySmall)
                            }
                            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = { vm.cancel(m) }) { Text("Pause") }
                    }
                    installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Installed · ${gb(onDisk)} on disk", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { vm.delete(m) }) { Text("Delete") }
                    }
                    else -> {
                        (p as? DownloadProgress.Failed)?.let {
                            Text("Download failed: ${it.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onDownload(m) }) { Text(if (onDisk > 0) "Resume (${gb(onDisk)} done)" else "Download") }
                            if (onDisk > 0) OutlinedButton(onClick = { vm.delete(m) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
    }
}

private fun gb(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

private fun isMetered(context: Context): Boolean =
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
