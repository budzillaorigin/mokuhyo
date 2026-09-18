package app.tsumugi.android.features.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import app.tsumugi.integrations.ankiconnect.AnkiConnectConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings → Integrations (G-09, D-119): Notion push (token in the keystore, sent only to api.notion.com) and
 * AnkiConnect on the LAN (push mined cards to desktop Anki). Both are optional and online-only by nature.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntegrationsScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notionConnected by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var itemsDb by remember { mutableStateOf("") }
    var statsDb by remember { mutableStateOf("") }
    var ankiUrl by remember { mutableStateOf("") }
    var deck by remember { mutableStateOf(AnkiConnectConfig.DEFAULT_DECK) }
    var model by remember { mutableStateOf("Basic") }
    var front by remember { mutableStateOf("Front") }
    var back by remember { mutableStateOf("Back") }
    var ankiKey by remember { mutableStateOf("") }
    var ankiConfigured by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    suspend fun reload() {
        notionConnected = graph.notion.isConnected
        graph.notion.config()?.let { c -> itemsDb = c.itemsDatabaseId.orEmpty(); statsDb = c.statsDatabaseId.orEmpty() }
        graph.ankiConnect.config()?.let { c ->
            ankiConfigured = true
            ankiUrl = c.url; deck = c.deck; model = c.model; front = c.frontField; back = c.backField
        }
    }
    LaunchedEffect(Unit) { runCatching { reload() } }

    fun run(label: String, block: suspend () -> String) {
        status = context.getString(R.string.status_working, label)
        failure = null
        progress = null
        job = scope.launch {
            try {
                status = block()
                retry = null
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Exception) {
                status = null
                failure = context.getString(R.string.status_failed, label, e.readable())
                retry = { run(label, block) }
            } finally {
                progress = null
                job = null
            }
        }
    }
    val onProgress: (Int, Int) -> Unit = { done, total -> progress = done to total }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        status?.let { Text(it) }
        progress?.let { (done, total) ->
            LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text("$done / $total", style = MaterialTheme.typography.labelSmall)
        }
        if (job != null) TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
        failure?.let { ErrorState(it, onRetry = { retry?.invoke() }) }

        Text("Notion", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.notion_intro), style = MaterialTheme.typography.bodySmall)
        if (!notionConnected) {
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notion_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        } else {
            Text(stringResource(R.string.notion_connected), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(itemsDb, { itemsDb = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notion_items_db)) }, singleLine = true, enabled = !notionConnected)
        OutlinedTextField(statsDb, { statsDb = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notion_stats_db)) }, singleLine = true, enabled = !notionConnected)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!notionConnected) {
                Button(
                    onClick = {
                        run(context.getString(R.string.notion_connecting)) {
                            val problems = graph.notion.connect(token, itemsDb, statsDb)
                            reload()
                            if (problems.isEmpty()) {
                                token = ""
                                context.getString(R.string.notion_connected)
                            } else context.getString(R.string.notion_problems, problems.joinToString("; "))
                        }
                    },
                    enabled = token.isNotBlank() && (itemsDb.isNotBlank() || statsDb.isNotBlank()) && job == null,
                ) { Text(stringResource(R.string.integrations_connect)) }
            } else {
                Button(onClick = {
                    run(context.getString(R.string.notion_pushing_items)) {
                        val r = graph.pushItemsToNotion(onProgress = onProgress)
                        context.getString(R.string.notion_result, r.created, r.updated, r.failed.size)
                    }
                }, enabled = itemsDb.isNotBlank() && job == null) { Text(stringResource(R.string.notion_push_items)) }
                OutlinedButton(onClick = {
                    run(context.getString(R.string.notion_pushing_stats)) {
                        val r = graph.pushStatsToNotion(30, onProgress)
                        context.getString(R.string.notion_result, r.created, r.updated, r.failed.size)
                    }
                }, enabled = statsDb.isNotBlank() && job == null) { Text(stringResource(R.string.notion_push_stats)) }
                TextButton(onClick = { scope.launch { graph.notion.disconnect(); reload() } }) { Text(stringResource(R.string.integrations_disconnect)) }
            }
        }

        HorizontalDivider()
        Text("AnkiConnect", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.anki_intro), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(ankiUrl, { ankiUrl = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.anki_host)) }, placeholder = { Text("<lan-ip>:8765") }, singleLine = true)
        OutlinedTextField(deck, { deck = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.anki_deck)) }, singleLine = true)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.anki_note_type)) }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(front, { front = it }, Modifier.fillMaxWidth(0.48f), label = { Text(stringResource(R.string.anki_front)) }, singleLine = true)
            OutlinedTextField(back, { back = it }, Modifier.fillMaxWidth(0.48f), label = { Text(stringResource(R.string.anki_back)) }, singleLine = true)
        }
        OutlinedTextField(ankiKey, { ankiKey = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.anki_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                run(context.getString(R.string.anki_checking)) {
                    graph.ankiConnect.configure(AnkiConnectConfig(ankiUrl.trim(), deck.trim(), model.trim(), front.trim(), back.trim()), ankiKey.ifBlank { null })
                    ankiKey = ""
                    reload()
                    context.getString(R.string.anki_saved)
                }
            }, enabled = ankiUrl.isNotBlank() && job == null) { Text(stringResource(R.string.integrations_connect)) }
            OutlinedButton(onClick = {
                run(context.getString(R.string.anki_pushing)) {
                    val items = graph.minedItems()
                    if (items.isEmpty()) return@run context.getString(R.string.anki_nothing)
                    val r = graph.ankiConnect.push(items, onProgress)
                    context.getString(R.string.anki_result, r.added, r.duplicates, r.failed.size)
                }
            }, enabled = ankiConfigured && job == null) { Text(stringResource(R.string.anki_push)) }
        }
    }
}
