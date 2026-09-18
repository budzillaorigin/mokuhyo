package app.tsumugi.android.features.dictionary

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
@Composable
fun EntryActions(entry: DictionaryEntry) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var inReviews by remember(entry.id) { mutableStateOf<Boolean?>(null) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id) { inReviews = graph.collection.isInReviews(entry.id) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { scope.launch { graph.collection.addToReviews(entry); inReviews = true; note = "Added — first review in 10 minutes." } },
            enabled = inReviews == false,
        ) { Text(if (inReviews == true) "In reviews" else "Add to reviews") }
        OutlinedButton(onClick = { picking = true }) { Text("Add to list") }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (picking) {
        ListPicker(onDismiss = { picking = false }) { listId ->
            scope.launch {
                graph.collection.addToList(listId, entry.summary())
                note = "Added to list."
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
        title = { Text("Add to list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lists.forEach { l -> Text("${l.name} (${l.size})", Modifier.fillMaxWidth().clickable { onPick(l.id) }.padding(vertical = 8.dp)) }
                OutlinedTextField(newName, { newName = it }, label = { Text("New list") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { onPick(graph.collection.createList(newName)) } }, enabled = newName.isNotBlank()) { Text("Create & add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("New list") }, singleLine = true)
            Button(onClick = { scope.launch { graph.collection.createList(newName); newName = ""; refresh++ } }, enabled = newName.isNotBlank()) { Text("Create") }
        }
        if (lists.isEmpty()) Text("No word lists yet. Add words from the dictionary, or import an imiwa list from Me → Import.", Modifier.padding(16.dp))
        LazyColumn {
            items(lists, key = { it.id }) { l ->
                ListItem(modifier = Modifier.clickable { onOpen(l.id) }, headlineContent = { Text(l.name) }, supportingContent = { Text("${l.size} words") })
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
                    status = "Added $added words to reviews."
                }
            }) { Text("Add all to reviews") }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
        LazyColumn {
            items(entries, key = { it.ref }) { e ->
                ListItem(
                    modifier = Modifier.clickable(enabled = e.entryId != null) { e.entryId?.let(onOpenEntry) },
                    headlineContent = { Text(e.text, style = MaterialTheme.typography.titleMedium.japanese()) },
                    supportingContent = { Text("${e.reading} · ${e.gloss}", maxLines = 2, style = MaterialTheme.typography.bodyMedium.japanese()) },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { graph.collection.removeFromList(listId, e.ref); entries = graph.collection.entries(listId) } }) { Text("Remove") }
                    },
                )
            }
        }
    }
}
