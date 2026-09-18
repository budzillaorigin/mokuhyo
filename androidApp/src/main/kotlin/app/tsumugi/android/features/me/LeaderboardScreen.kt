package app.tsumugi.android.features.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import app.tsumugi.sync.LeaderboardPeriod
import app.tsumugi.sync.LeaderboardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Leaderboard (G-11, D-107): off by default. Nothing is sent to the sync server until the learner turns it on here;
 * end-to-end encrypted accounts can't take part (the server can't count their reviews).
 */
@Composable
fun LeaderboardScreen(onOpenSync: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var optedIn by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(LeaderboardPeriod.WEEK) }
    var state by remember { mutableStateOf<LeaderboardState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        busy = true
        try {
            optedIn = graph.leaderboard.isOptedIn()
            state = graph.leaderboard.load(period)
            (state as? LeaderboardState.Rows)?.displayName?.let { if (name.isBlank()) name = it }
            if (state is LeaderboardState.OptedOut) optedIn = false
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        } finally {
            busy = false
        }
    }
    LaunchedEffect(period) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.leaderboard_intro), style = MaterialTheme.typography.bodySmall)
        if (state != LeaderboardState.NotSignedIn && state != LeaderboardState.Encrypted) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.leaderboard_name)) }, singleLine = true)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = optedIn, role = Role.Switch, enabled = !busy) { on ->
                    scope.launch {
                        busy = true
                        try {
                            graph.leaderboard.setOptIn(on, name)
                            optedIn = on
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.readable()
                        } finally {
                            busy = false
                        }
                        load()
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.leaderboard_opt_in), Modifier.weight(1f))
                Switch(optedIn, onCheckedChange = null)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { load() } }) }
        when (val s = state) {
            LeaderboardState.NotSignedIn -> Notice(stringResource(R.string.leaderboard_not_signed_in), actionLabel = stringResource(R.string.title_sync), onAction = onOpenSync)
            LeaderboardState.OptedOut -> Notice(stringResource(R.string.leaderboard_opted_out))
            LeaderboardState.Encrypted -> Notice(stringResource(R.string.leaderboard_encrypted))
            is LeaderboardState.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { scope.launch { load() } })
            is LeaderboardState.Rows -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LeaderboardPeriod.entries.forEach { p ->
                        FilterChip(p == period, { period = p }, {
                            Text(stringResource(when (p) { LeaderboardPeriod.DAY -> R.string.leaderboard_day; LeaderboardPeriod.WEEK -> R.string.leaderboard_week; LeaderboardPeriod.MONTH -> R.string.leaderboard_month }))
                        })
                    }
                }
                if (s.rows.isEmpty()) Text(stringResource(R.string.leaderboard_empty))
                s.rows.forEachIndexed { i, row ->
                    ListItem(
                        headlineContent = { Text("${i + 1}. ${row.displayName}") },
                        supportingContent = { Text(stringResource(R.string.leaderboard_row, row.reviews, row.streak)) },
                    )
                }
            }
            null -> Unit
        }
    }
}
