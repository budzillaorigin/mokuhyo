package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate

/**
 * Backup (BRIEF §8.2): one `.mokuhyo` file with everything — history, recordings, review queue, settings, packs list.
 * Import merges and never overwrites. Optional passphrase (Argon2id + XChaCha20-Poly1305).
 */
@Composable
fun BackupSection(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var step by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<File?>(null) }
    SectionCard("Back up everything to one file") {
        Text("History, recordings, review queue and learner settings go into a single .mokuhyo file. Model weights are not included; the other computer downloads them.")
        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Passphrase (optional, encrypts the file)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = progress == null, onClick = {
                val d = FileDialog(null as Frame?, "Save backup", FileDialog.SAVE).apply { file = "mokuhyo-${LocalDate.now()}.mokuhyo" }
                d.isVisible = true
                val name = d.file ?: return@Button
                val out = File(d.directory, if (name.endsWith(".mokuhyo")) name else "$name.mokuhyo")
                progress = 0f
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { app.backup.export(out, app.chosenLanguages(), app.installedPacks(), passphrase.ifBlank { null }, { p, s -> progress = p.toFloat(); step = s }) }
                    }
                    status = r.fold({ m -> "Saved ${out.name}: ${m.counts["attempt"]} attempts, ${m.counts["conversation"]} conversations, ${m.counts["recording"]} recordings." }, { "Backup failed: ${it.message}" })
                    progress = null
                }
            }) { Text("Export backup…") }
            OutlinedButton(enabled = progress == null, onClick = {
                val d = FileDialog(null as Frame?, "Import backup", FileDialog.LOAD)
                d.isVisible = true
                val name = d.file ?: return@OutlinedButton
                pendingImport = File(d.directory, name)
            }) { Text("Import backup…") }
        }
        pendingImport?.let { file ->
            val encrypted = remember(file) { runCatching { app.backup.isEncrypted(file) }.getOrElse { status = "Not a Mokuhyo backup: ${it.message}"; pendingImport = null; false } }
            Text("Import ${file.name}?" + if (encrypted) " It's encrypted: enter its passphrase above." else "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !encrypted || passphrase.isNotBlank(), onClick = {
                    progress = 0f
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { app.backup.importBundle(file, passphrase.ifBlank { null }, { p, s -> progress = p.toFloat(); step = s }) } }
                        status = r.fold({ rep ->
                            buildString {
                                append("Merged: ${rep.total} new records (${rep.added.filterValues { it > 0 }.entries.joinToString { "${it.value} ${it.key.replace('_', ' ')}" }.ifEmpty { "nothing new" }}), ${rep.recordingsCopied} recordings.")
                                if (rep.settingConflicts.isNotEmpty()) append(" Kept your settings for: ${rep.settingConflicts.joinToString { it.first }}.")
                                val missing = rep.missingPacks.filter { id -> app.manifest.models.firstOrNull { it.id == id }?.let { app.modelFile(it) == null } == true }
                                if (missing.isNotEmpty()) append(" The other computer used models you haven't downloaded: ${missing.joinToString()} (Settings → AI).")
                            }
                        }, { "Import failed: ${it.message}" })
                        progress = null
                        pendingImport = null
                    }
                }) { Text("Merge into my data") }
                OutlinedButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        }
        progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            Text(step, style = MaterialTheme.typography.bodySmall)
        }
        if (status.isNotEmpty()) Text(status)
    }
}
