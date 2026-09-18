package app.tsumugi.android.features.me

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Anki .apkg import/export, imiwa word lists, and WaniKani (BRIEF §5.4, §9). */
@Composable
fun ImportScreen() {
    val context = LocalContext.current
    val graph = (context.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var wkStatus by remember { mutableStateOf(if (graph.imports.wanikani.isConnected()) "Connected" else "Not connected") }

    fun run(label: String, block: suspend () -> String) {
        busy = true
        message = "$label…"
        scope.launch {
            message = runCatching { block() }.getOrElse { "$label failed: ${it.message ?: it::class.simpleName}" }
            busy = false
        }
    }

    val ankiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Importing Anki deck") {
            val file = copyToCache(context, uri, "import.apkg")
            val r = graph.imports.importAnki(file.path)
            buildString {
                append("Imported ${r.notes} notes, ${r.cards} cards and ${r.reviews} reviews")
                if (r.nihongoSharkNotes > 0) append(" (${r.nihongoSharkNotes} NihongoShark kanji with your stories)")
                append(".")
                if (r.warnings.isNotEmpty()) append("\n" + r.warnings.take(5).joinToString("\n"))
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Exporting") {
            val out = File(context.cacheDir, "export.apkg")
            graph.imports.exportAnki(out.path)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { stream -> out.inputStream().use { it.copyTo(stream) } }
            }
            "Exported to the chosen file. Open it with Anki (desktop or AnkiDroid)."
        }
    }
    val listPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Importing word list") {
            val file = copyToCache(context, uri, "wordlist.txt")
            val r = graph.imports.importWordList(file.path, "Imported list")
            "Imported ${r.imported} words (${r.resolved} matched to the dictionary, ${r.skipped} skipped)."
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Text("Anki", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Import any .apkg (including NihongoShark decks: your myStory notes come along). Review history is kept.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ankiPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import .apkg") }
        OutlinedButton(onClick = { exportPicker.launch("tsumugi.apkg") }, enabled = !busy) { Text("Export everything to .apkg") }

        HorizontalDivider()
        Text("Word lists", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("imiwa? exports or any CSV/TSV of word, reading, meaning.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { listPicker.launch(arrayOf("text/*", "*/*")) }, enabled = !busy) { Text("Import word list") }

        HorizontalDivider()
        Text("WaniKani", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Paste a Personal Access Token (WaniKani → Settings → API tokens). It's stored in the Android Keystore and only " +
                "sent to api.wanikani.com. Progress maps onto the kanji path; mnemonics are never copied.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Status: $wkStatus", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("API v2 token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = {
            run("Connecting to WaniKani") {
                val user = graph.imports.connectWaniKani(token)
                token = ""
                wkStatus = "Connected as ${user.username} (level ${user.level})"
                wkStatus
            }
        }, enabled = !busy && token.isNotBlank()) { Text("Connect") }
        OutlinedButton(onClick = {
            run("Importing from WaniKani") {
                val r = graph.imports.importWaniKani { p -> message = p } ?: return@run "The kanji path pack isn't installed."
                "Imported: ${r.matchedToPath} items on the path, ${r.wanikaniOnlyItems} WaniKani-only, ${r.reviewsImported} reviews. " +
                    "Your WaniKani level is ${r.wanikaniLevel}."
            }
        }, enabled = !busy && graph.imports.wanikani.isConnected()) { Text("Import progress") }
    }
}

private suspend fun copyToCache(context: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    file
}
