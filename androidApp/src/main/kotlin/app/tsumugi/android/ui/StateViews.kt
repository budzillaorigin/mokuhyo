package app.tsumugi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication

/**
 * F-33: what an async screen shows when its work failed, instead of an endless spinner. [onRetry] runs the work again.
 */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** Short text for a caught exception. */
fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()

/**
 * While cards are rebuilt after new FSRS weights (F-32, `AppGraph.recomputeProgress`), a banner with the progress.
 * Shown on the study screens whose numbers depend on it.
 */
@Composable
fun RecomputeBanner(modifier: Modifier = Modifier) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val progress by graph.recomputeProgress.collectAsStateWithLifecycle()
    val p = progress ?: return
    if (!p.running) return
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.recompute_banner, p.done, p.total), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
