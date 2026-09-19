package app.tsumugi.android.features.dictionary

import app.tsumugi.android.ui.JaText
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.japanese
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.study.WordListEntry
import app.tsumugi.study.WordListSummary
import kotlinx.coroutines.launch

/** "Add to reviews" and "Add to list" for a dictionary entry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryActions(entry: DictionaryEntry) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var inReviews by remember(entry.id) { mutableStateOf<Boolean?>(null) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id) { inReviews = graph.collection.isInReviews(entry.id) }
    val context = LocalContext.current

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { scope.launch { graph.collection.addToReviews(entry); inReviews = true; note = context.getString(R.string.collection_added) } },
            enabled = inReviews == false,
        ) { Text(stringResource(if (inReviews == true) R.string.collection_in_reviews else R.string.action_add_to_reviews)) }
        OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.collection_add_to_list)) }
        // §6.1 "mark known": counts for coverage and decks without an SRS item.
        app.tsumugi.android.features.decks.MarkKnownButton(entry.id)
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (picking) {
        ListPicker(onDismiss = { picking = false }) { listId ->
            scope.launch {
                graph.collection.addToList(listId, entry.summary())
                note = context.getString(R.string.collection_added_to_list)
            }
            picking = false
        }
    }
}

@Composable
private fun ListPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<WordListSummary>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { lists = graph.collection.lists() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collection_add_to_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lists.forEach { l -> Text("${l.name} (${l.size})", Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(l.id) }.padding(vertical = 12.dp)) }
                OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.collection_new_list)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { onPick(graph.collection.createList(newName)) } }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.collection_create_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun WordListsScreen(onOpen: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<WordListSummary>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(refresh) { lists = graph.collection.lists() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.collection_new_list)) }, singleLine = true)
            Button(onClick = { scope.launch { graph.collection.createList(newName); newName = ""; refresh++ } }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.action_create)) }
        }
        if (lists.isEmpty()) Text(stringResource(R.string.collection_empty), Modifier.padding(16.dp))
        LazyColumn {
            items(lists, key = { it.id }) { l ->
                ListItem(modifier = Modifier.clickable { onOpen(l.id) }, headlineContent = { Text(l.name) }, supportingContent = { Text(pluralStringResource(R.plurals.words_count, l.size, l.size)) })
            }
        }
    }
}

@Composable
fun WordListScreen(listId: String, onOpenEntry: (Long) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<WordListEntry>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(listId) { entries = graph.collection.entries(listId) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val dict = graph.dictionary() ?: return@launch
                    var added = 0
                    for (e in entries) {
                        val id = e.entryId ?: continue
                        if (graph.collection.isInReviews(id)) continue
                        dict.entry(id)?.let { graph.collection.addToReviews(it.entry); added++ }
                    }
                    status = context.resources.getQuantityString(R.plurals.collection_added_all, added, added)
                }
            }) { Text(stringResource(R.string.collection_add_all)) }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
        LazyColumn {
            items(entries, key = { it.ref }) { e ->
                ListItem(
                    modifier = Modifier.clickable(enabled = e.entryId != null) { e.entryId?.let(onOpenEntry) },
                    headlineContent = { JaText(e.text, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("${e.reading} · ${e.gloss}", maxLines = 2, style = MaterialTheme.typography.bodyMedium.japanese()) },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { graph.collection.removeFromList(listId, e.ref); entries = graph.collection.entries(listId) } }) { Text(stringResource(R.string.action_remove)) }
                    },
                )
            }
        }
    }
}
