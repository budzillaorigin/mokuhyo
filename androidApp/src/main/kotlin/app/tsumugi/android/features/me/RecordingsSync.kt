package app.tsumugi.android.features.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import app.tsumugi.recordings.RecordingSyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Sync my recordings and pictures" (G-03, D-111): off by default, per device (rule 16). Uses the account's blob
 * store; with end-to-end encryption the files are sealed before upload.
 */
@Composable
fun RecordingsSyncSection() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<RecordingSyncProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { on = runCatching { graph.recordingSync.isEnabled() }.getOrDefault(false) }
    fun sync() {
        error = null
        message = null
        job = scope.launch {
            try {
                val r = graph.recordingSync.sync { p -> progress = p }
                message = context.getString(R.string.rec_sync_result, r.uploaded, r.downloaded, r.deleted) +
                    if (r.failures.isNotEmpty()) "\n" + r.failures.take(3).joinToString("\n") else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.readable()
            } finally {
                progress = null
                job = null
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch) { v ->
                on = v
                scope.launch { graph.recordingSync.setEnabled(v) }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rec_sync))
                Text(stringResource(R.string.rec_sync_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(on, onCheckedChange = null)
        }
        if (on) {
            if (job == null) {
                OutlinedButton(onClick = ::sync) { Text(stringResource(R.string.rec_sync_now)) }
            } else {
                progress?.let { p ->
                    Text("${p.phase.name.lowercase()} ${p.done}/${p.total}", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { ErrorState(it, onRetry = ::sync) }
    }
}
