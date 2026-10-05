package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * "Flag this item" / "Suggest a term" (BRIEF_PHASE8 N-10): a note saved on this computer only, in suggestions.json; it
 * travels in your backup and can be exported for the content curator. Nothing is sent anywhere.
 */
@Composable
fun SuggestButton(app: AppGraph, lang: String, type: String, targetKind: String, targetId: String, context: String = "", label: String? = null) {
    var open by remember(targetKind, targetId) { mutableStateOf(false) }
    var text by remember(targetKind, targetId) { mutableStateOf("") }
    var saved by remember(targetKind, targetId) { mutableStateOf(false) }
    if (!open) {
        TextButton(onClick = { open = true; saved = false }) { Text(label ?: if (type == "flag") "Flag this" else "Suggest a term") }
        return
    }
    Column {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = {
            Text(if (type == "flag") "What's wrong? (stays on this computer)" else "The term, what it means, and where you met it")
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = text.isNotBlank(), onClick = {
                runCatching { app.suggestions.add(lang, type, targetKind, targetId, text, context) }.onSuccess { saved = true; open = false; text = "" }
            }) { Text("Save") }
            TextButton(onClick = { open = false }) { Text("Cancel") }
        }
    }
    if (saved) Text("Saved on this computer. Export it from Settings → Content.", style = MaterialTheme.typography.bodySmall)
}

/** Settings → Content: export the learner's suggestions and flags for the curator. */
@Composable
fun ExportSuggestions(app: AppGraph) {
    var status by remember { mutableStateOf("") }
    SectionCard("Suggestions and flags") {
        val n = remember(status) { app.suggestions.all().size }
        Text("$n saved on this computer. They are in your backups; export them on their own to send to the content curator.")
        OutlinedButton(enabled = n > 0, onClick = {
            val d = FileDialog(null as Frame?, "Export suggestions", FileDialog.SAVE).apply { file = "mokuhyo-suggestions.json" }
            d.isVisible = true
            val name = d.file ?: return@OutlinedButton
            val out = File(d.directory, name)
            app.suggestions.exportTo(out)
            status = "Exported $n to ${out.name}. The curator runs: uv run python items/review.py suggestions ${out.name}"
        }) { Text("Export suggestions…") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
