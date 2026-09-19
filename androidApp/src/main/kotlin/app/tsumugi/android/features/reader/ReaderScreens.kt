package app.tsumugi.android.features.reader

import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import androidx.compose.material3.LinearProgressIndicator
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.localized
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.Speech
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.dictionary.EntrySummary
import app.tsumugi.reader.AozoraWork
import app.tsumugi.reader.FeedItem
import app.tsumugi.reader.FuriganaMode
import app.tsumugi.reader.ReaderDocument
import app.tsumugi.reader.ReaderDocumentSummary
import app.tsumugi.reader.ReaderFeed
import app.tsumugi.reader.ReaderParagraph
import app.tsumugi.reader.ReaderSentence
import app.tsumugi.reader.ReaderToken
import app.tsumugi.reader.LearnerLevel
import app.tsumugi.reader.TokenPitch
import app.tsumugi.reader.showFurigana
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FeedsScreen(onOpenDoc: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<ReaderFeed>>(emptyList()) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { feeds = graph.reader.feeds.feeds() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reader_feeds_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.reader_feed_url)) }, singleLine = true)
            Button(onClick = {
                scope.launch {
                    status = runCatching { val (_, list) = graph.reader.feeds.add(url.trim()); items = list; url = ""; feeds = graph.reader.feeds.feeds(); null }
                        .getOrElse { context.getString(R.string.reader_feed_failed, it.message.orEmpty()) }
                }
            }, enabled = url.isNotBlank()) { Text(stringResource(R.string.action_add)) }
        }
        status?.let { Text(it) }
        feeds.forEach { f ->
            Text(f.title, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { scope.launch { items = runCatching { graph.reader.feeds.items(f) }.getOrDefault(emptyList()) } }.padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleSmall)
        }
        LazyColumn {
            items(items) { item ->
                ListItem(
                    modifier = Modifier.clickable { scope.launch { runCatching { graph.reader.importFeedItem(item) }.onSuccess(onOpenDoc) } },
                    headlineContent = { JaText(item.title, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { item.published?.let { Text(it) } },
                )
            }
        }
    }
}

@Composable
fun AozoraScreen(onOpenDoc: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var works by remember { mutableStateOf<List<AozoraWork>?>(null) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reader_aozora_hint), style = MaterialTheme.typography.bodySmall)
        if (works == null) {
            Button(onClick = { status = context.getString(R.string.reader_aozora_downloading); scope.launch { works = runCatching { graph.reader.aozora.catalogue() }.getOrNull(); status = if (works == null) context.getString(R.string.reader_aozora_failed) else null } }) {
                Text(stringResource(R.string.reader_aozora_load))
            }
        }
        status?.let { Text(it) }
        works?.let { list ->
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reader_aozora_search)) }, singleLine = true)
            val shown = list.filter { query.isBlank() || query in it.title || query in it.author }.take(200)
            LazyColumn {
                items(shown, key = { it.id }) { w ->
                    ListItem(
                        modifier = Modifier.clickable { status = context.getString(R.string.status_working, context.getString(R.string.reader_importing)); scope.launch { runCatching { graph.reader.importAozora(w) }.onSuccess { status = null; onOpenDoc(it) }.onFailure { status = context.getString(R.string.status_failed, context.getString(R.string.reader_importing), it.message.orEmpty()) } } },
                        headlineContent = { JaText(w.title, style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { JaText(w.author, style = MaterialTheme.typography.bodyMedium) },
                    )
                }
            }
        }
    }
}
