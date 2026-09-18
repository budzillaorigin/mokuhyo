package app.tsumugi.android.features.me

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
            stringResource(R.string.ai_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionTitle(stringResource(R.string.ai_llm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.llm == LlmEngine.NONE, { config = c.copy(llm = LlmEngine.NONE) }, { Text(stringResource(R.string.ai_off)) })
            FilterChip(c.llm == LlmEngine.LOCAL, { config = c.copy(llm = LlmEngine.LOCAL) }, { Text(stringResource(R.string.ai_on_device)) })
            FilterChip(c.llm == LlmEngine.ENDPOINT, { config = c.copy(llm = LlmEngine.ENDPOINT) }, { Text(stringResource(R.string.ai_my_server)) })
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (c.llm == LlmEngine.NONE) {
            Text(
                stringResource(R.string.ai_no_model_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (c.llm == LlmEngine.LOCAL) {
            ModelList(vm, ModelKind.LLM, selected = c.localModelId, onSelect = { config = c.copy(localModelId = it) }, onDownload = { pendingDownload = it })
        }
        if (c.llm == LlmEngine.ENDPOINT) {
            OutlinedTextField(
                c.endpointUrl, { config = c.copy(endpointUrl = it) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sync_server_url)) }, placeholder = { Text("http://<lan-ip>:11434/v1") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                apiKey, { apiKey = it; keyEdited = true }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_api_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.ai_api_key_hint)) },
            )
            OutlinedTextField(c.endpointModel, { config = c.copy(endpointModel = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai_model_name)) }, singleLine = true)
            Button(
                onClick = {
                    probing = true
                    vm.viewModelScope.launch {
                        probe = graph.ai.probeEndpoint(c.endpointUrl, apiKey.ifBlank { null })
                        probing = false
                    }
                },
                enabled = c.endpointUrl.isNotBlank() && !probing,
            ) { Text(stringResource(if (probing) R.string.ai_testing else R.string.ai_test)) }
            probe?.let { r ->
                r.onSuccess { models ->
                    Text(stringResource(R.string.ai_connected, models.size), style = MaterialTheme.typography.bodyMedium)
                    models.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = c.endpointModel == m, role = Role.RadioButton, onClick = { config = c.copy(endpointModel = m) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(c.endpointModel == m, onClick = null)
                            Text(m)
                        }
                    }
                }
                r.onFailure { Text(stringResource(R.string.ai_connect_failed, it.message.orEmpty()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.ai_stt))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.stt == SttEngine.SYSTEM, { config = c.copy(stt = SttEngine.SYSTEM) }, { Text(stringResource(R.string.ai_system)) })
            FilterChip(c.stt == SttEngine.WHISPER_LOCAL, { config = c.copy(stt = SttEngine.WHISPER_LOCAL) }, { Text(stringResource(R.string.ai_whisper_local)) })
            FilterChip(c.stt == SttEngine.WHISPER_ENDPOINT, { config = c.copy(stt = SttEngine.WHISPER_ENDPOINT) }, { Text(stringResource(R.string.ai_whisper_server)) })
        }
        when (c.stt) {
            SttEngine.SYSTEM -> Text(
                stringResource(R.string.ai_stt_system_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            SttEngine.WHISPER_LOCAL -> {
                Text(stringResource(R.string.ai_whisper_local_hint), style = MaterialTheme.typography.bodySmall)
                ModelList(vm, ModelKind.STT, selected = c.localSttModelId, onSelect = { config = c.copy(localSttModelId = it) }, onDownload = { pendingDownload = it })
            }
            SttEngine.WHISPER_ENDPOINT -> {
                OutlinedTextField(
                    c.sttEndpointUrl, { config = c.copy(sttEndpointUrl = it) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_whisper_url)) }, placeholder = { Text("http://<lan-ip>:8000/v1") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(stringResource(R.string.ai_whisper_server_hint), style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.ai_voices))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(c.tts == TtsEngine.SYSTEM, { config = c.copy(tts = TtsEngine.SYSTEM) }, { Text(stringResource(R.string.ai_system)) })
            FilterChip(c.tts == TtsEngine.VOICEVOX, { config = c.copy(tts = TtsEngine.VOICEVOX) }, { Text("VOICEVOX") })
        }
        if (c.tts == TtsEngine.SYSTEM) {
            Text(stringResource(R.string.ai_tts_system_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(
                c.voicevoxUrl, { config = c.copy(voicevoxUrl = it) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_voicevox_url)) }, placeholder = { Text("http://<lan-ip>:50021") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                c.voicevoxSpeaker.toString(), { v -> v.toIntOrNull()?.let { config = c.copy(voicevoxSpeaker = it) } }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_speaker_id)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(stringResource(R.string.ai_voicevox_hint), style = MaterialTheme.typography.bodySmall)
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
                title = { Text(stringResource(R.string.ai_metered_title)) },
                text = { Text(stringResource(R.string.ai_metered_text, model.name, gb(model.totalBytes))) },
                confirmButton = { TextButton(onClick = { vm.download(model); pendingDownload = null }) { Text(stringResource(R.string.ai_download_anyway)) } },
                dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text(stringResource(R.string.ai_wait_wifi)) } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelList(vm: AiSettingsViewModel, kind: ModelKind, selected: String?, onSelect: (String) -> Unit, onDownload: (ModelInfo) -> Unit) {
    val manager = vm.graph.ai.models
    if (manager == null) {
        Notice(stringResource(R.string.ai_catalog_missing))
        return
    }
    val ram = vm.graph.ai.deviceRamGb
    val recommended = manager.recommend(kind, ram)
    val models = manager.models(kind)
    Text(
        stringResource(R.string.ai_download_hint, ram.toInt()),
        style = MaterialTheme.typography.bodySmall,
    )
    if (models.isEmpty()) Text(stringResource(R.string.ai_no_models))
    @Suppress("UNUSED_VARIABLE") val v = vm.diskVersion
    val effective = selected ?: recommended?.id
    models.forEach { m ->
        val installed = manager.isInstalled(m)
        val onDisk = manager.bytesOnDisk(m)
        val p = vm.progress[m.id]
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.selectable(selected = effective == m.id, enabled = installed, role = Role.RadioButton, onClick = { onSelect(m.id) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(effective == m.id, onClick = null, enabled = installed)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.ai_model_row, gb(m.totalBytes), m.minRamGb.toString(), m.license), style = MaterialTheme.typography.bodySmall)
                    }
                    if (m.id == recommended?.id) Tag(stringResource(R.string.ai_recommended))
                }
                if (m.minRamGb > ram) Text(stringResource(R.string.ai_may_not_fit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                when {
                    vm.isDownloading(m) -> {
                        when (p) {
                            is DownloadProgress.Downloading -> {
                                LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.ai_progress, gb(p.bytesDone), gb(p.bytesTotal)), style = MaterialTheme.typography.bodySmall)
                            }
                            is DownloadProgress.Verifying -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(stringResource(R.string.ai_verifying, p.file), style = MaterialTheme.typography.bodySmall)
                            }
                            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = { vm.cancel(m) }) { Text(stringResource(R.string.ai_pause)) }
                    }
                    installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ai_installed, gb(onDisk)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { vm.delete(m) }) { Text(stringResource(R.string.action_delete)) }
                    }
                    else -> {
                        (p as? DownloadProgress.Failed)?.let {
                            Text(stringResource(R.string.ai_download_failed, it.message.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDownload(m) }) { Text(if (onDisk > 0) stringResource(R.string.ai_resume, gb(onDisk)) else stringResource(R.string.ai_download)) }
                            if (onDisk > 0) OutlinedButton(onClick = { vm.delete(m) }) { Text(stringResource(R.string.ai_discard)) }
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
