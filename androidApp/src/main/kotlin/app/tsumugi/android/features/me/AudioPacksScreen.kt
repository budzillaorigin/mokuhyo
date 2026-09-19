package app.tsumugi.android.features.me

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import app.tsumugi.audio.AudioInstallProgress
import app.tsumugi.audio.AudioPackEntry
import app.tsumugi.audio.AudioSet
import app.tsumugi.audio.InstalledAudioPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.source

/** Device setting (rule 16): the base URL the learner downloads audio packs from. No default (D-096, D-180). */
const val AUDIO_PACKS_URL = "audio.packsUrl"

/**
 * Settings → Audio packs (BRIEF_V2 §5.6, rule 20, D-095/D-096): the installed pre-rendered sets with their sizes and
 * voice credits, install from a file (SAF) or from a URL the learner types, and remove. Every install shows its
 * phase and progress and can be cancelled; a failed or cancelled install leaves the previous version in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioPacksScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<InstalledAudioPack>?>(null) }
    var credits by remember { mutableStateOf<List<String>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<List<AudioPackEntry>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmRemove by remember { mutableStateOf<AudioSet?>(null) }
    val progress by graph.audio.progress.collectAsStateWithLifecycle()

    suspend fun reload() {
        installed = withContext(Dispatchers.IO) { graph.audio.installed() }
        credits = withContext(Dispatchers.IO) { graph.audio.credits() }
    }
    LaunchedEffect(Unit) {
        url = runCatching { graph.deviceSettings.get(AUDIO_PACKS_URL) }.getOrNull().orEmpty()
        reload()
    }

    fun run(label: String, block: suspend () -> InstalledAudioPack) {
        error = null
        message = null
        job = scope.launch {
            try {
                val pack = block()
                message = context.getString(R.string.audio_installed_ok, setLabel(context, pack.set), pack.clips)
                retry = null
            } catch (e: CancellationException) {
                message = context.getString(R.string.audio_install_cancelled)
            } catch (e: Exception) {
                error = context.getString(R.string.audio_install_failed, label, e.readable())
                retry = { run(label, block) }
            } finally {
                job = null
                runCatching { reload() }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = app.tsumugi.android.app.displayName(context, uri)
        // A picked file whose name matches the fetched manifest is verified against it (size + SHA-256).
        val expected = manifest?.firstOrNull { it.file == name }
        run(name) {
            val stream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException(context.getString(R.string.audio_cant_open))
            stream.source().use { graph.audio.installFrom(it, expected) }
        }
    }

    fun fetch() {
        val base = url.trim()
        error = null
        fetching = true
        scope.launch {
            try {
                graph.deviceSettings.put(AUDIO_PACKS_URL, base)
                manifest = graph.audio.fetchManifest(base).packs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                manifest = null
                error = context.getString(R.string.audio_manifest_failed, e.readable())
                retry = { fetch() }
            } finally {
                fetching = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.audio_intro), style = MaterialTheme.typography.bodyMedium)

        progress?.let { p -> InstallProgress(p, onCancel = { job?.cancel() }) }
        message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.tertiary) }
        error?.let { ErrorState(it, onRetry = { retry?.invoke() }) }

        Text(stringResource(R.string.audio_installed), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        val list = installed
        when {
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() -> Text(stringResource(R.string.audio_none_installed), style = MaterialTheme.typography.bodyMedium)
            else -> list.forEach { pack ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(setLabel(context, pack.set), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.audio_pack_info, pack.clips, megabytes(pack.bytesOnDisk), pack.version), style = MaterialTheme.typography.bodySmall)
                        if (pack.credits.isNotEmpty()) Text(pack.credits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { confirmRemove = pack.set }, enabled = job == null) { Text(stringResource(R.string.action_remove)) }
                    }
                }
            }
        }
        if (credits.isNotEmpty()) {
            Text(stringResource(R.string.audio_credits, credits.joinToString("、")), style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text(stringResource(R.string.audio_from_file), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.audio_from_file_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = job == null) {
            Text(stringResource(R.string.audio_pick_file))
        }

        HorizontalDivider()
        Text(stringResource(R.string.audio_from_url), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.audio_from_url_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            url, { url = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.audio_url)) },
            placeholder = { Text("https://…/packs") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Button(onClick = ::fetch, enabled = url.isNotBlank() && !fetching && job == null) { Text(stringResource(R.string.audio_fetch)) }
        if (fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
        manifest?.let { entries ->
            if (entries.isEmpty()) Text(stringResource(R.string.audio_manifest_empty))
            entries.forEach { entry ->
                val set = AudioSet.fromId(entry.set)
                val current = list?.firstOrNull { it.set == set }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(set?.let { setLabel(context, it) } ?: entry.set, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.audio_entry_info, entry.clips, megabytes(entry.bytes), entry.audioSeconds / 60), style = MaterialTheme.typography.bodySmall)
                        if (entry.credits.isNotEmpty()) Text(entry.credits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                set == null -> Text(stringResource(R.string.audio_unknown_set), style = MaterialTheme.typography.bodySmall)
                                current?.version == entry.version -> Text(stringResource(R.string.audio_up_to_date), style = MaterialTheme.typography.bodySmall)
                                else -> OutlinedButton(onClick = { run(entry.file) { graph.audio.download(url.trim(), entry) } }, enabled = job == null) {
                                    Text(stringResource(if (current == null) R.string.audio_download else R.string.audio_update))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { set ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            text = { Text(stringResource(R.string.audio_remove_confirm, setLabel(context, set))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    scope.launch {
                        runCatching { graph.audio.remove(set) }.onFailure { error = it.readable() }
                        reload()
                    }
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun InstallProgress(p: AudioInstallProgress, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val phase = stringResource(
                when (p.phase) {
                    AudioInstallProgress.Phase.DOWNLOADING -> R.string.audio_phase_downloading
                    AudioInstallProgress.Phase.COPYING -> R.string.audio_phase_copying
                    AudioInstallProgress.Phase.EXTRACTING -> R.string.audio_phase_extracting
                },
            )
            Text(stringResource(R.string.audio_progress, phase, p.set, megabytes(p.bytesDone), megabytes(p.bytesTotal)), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

private fun megabytes(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

fun setLabel(context: android.content.Context, set: AudioSet): String = context.getString(
    when (set) {
        AudioSet.EXAM -> R.string.audio_set_exam
        AudioSet.DIALOGUES -> R.string.audio_set_dialogues
        AudioSet.MINIMAL_PAIRS -> R.string.audio_set_pairs
        AudioSet.PITCH -> R.string.audio_set_pitch
        AudioSet.GRAMMAR -> R.string.audio_set_grammar
        AudioSet.READERS -> R.string.audio_set_readers
    },
)
