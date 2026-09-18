package app.tsumugi.android.features.me

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import app.tsumugi.exam.BankImportResult
import app.tsumugi.exam.ExamBankFile
import app.tsumugi.exam.ExamService
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
    var wkStatus by remember { mutableStateOf(context.getString(if (graph.imports.wanikani.isConnected()) R.string.import_wk_connected else R.string.import_wk_not_connected)) }

    fun run(label: String, block: suspend () -> String) {
        busy = true
        message = context.getString(R.string.status_working, label)
        scope.launch {
            message = runCatching { block() }.getOrElse { context.getString(R.string.status_failed, label, it.message ?: it::class.simpleName.orEmpty()) }
            busy = false
        }
    }

    val ankiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.import_anki_importing)) {
            val file = copyToCache(context, uri, "import.apkg")
            val r = graph.imports.importAnki(file.path)
            buildString {
                append(context.getString(R.string.import_anki_result, r.notes, r.cards, r.reviews))
                if (r.nihongoSharkNotes > 0) append(context.getString(R.string.import_anki_nihongoshark, r.nihongoSharkNotes))
                if (r.warnings.isNotEmpty()) append("\n" + r.warnings.take(5).joinToString("\n"))
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.import_exporting)) {
            val out = File(context.cacheDir, "export.apkg")
            graph.imports.exportAnki(out.path)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { stream -> out.inputStream().use { it.copyTo(stream) } }
            }
            context.getString(R.string.import_exported)
        }
    }
    val listPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.import_list_importing)) {
            val file = copyToCache(context, uri, "wordlist.txt")
            val r = graph.imports.importWordList(file.path, context.getString(R.string.import_list_default_name))
            context.getString(R.string.import_list_result, r.imported, r.resolved, r.skipped)
        }
    }

    val bunproPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.import_bunpro_importing)) {
            val file = copyToCache(context, uri, "bunpro.csv")
            val r = graph.imports.importBunpro(file.path) ?: return@run context.getString(R.string.grammar_pack_missing)
            context.getString(R.string.import_bunpro_result, r.matched, r.rows) +
                if (r.unmatched.isNotEmpty()) context.getString(R.string.import_bunpro_unmatched, r.unmatched.take(8).joinToString("、") + if (r.unmatched.size > 8) "…" else "") else ""
        }
    }

    var bankErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var banks by remember { mutableStateOf<List<ExamBankFile>>(emptyList()) }
    LaunchedEffect(Unit) { banks = graph.exams().userBanks() }
    val bankPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        bankErrors = emptyList()
        run(context.getString(R.string.import_bank_importing)) {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            val exams = graph.exams()
            when (val r = exams.importBank(text)) {
                is BankImportResult.Imported -> {
                    banks = exams.userBanks()
                    context.getString(R.string.import_bank_result, r.bank.removePrefix(ExamService.USER_PREFIX), r.items, r.passages)
                }
                is BankImportResult.Invalid -> {
                    bankErrors = r.errors
                    context.resources.getQuantityString(R.plurals.import_bank_invalid, r.errors.size, r.errors.size)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        bankErrors.take(50).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (bankErrors.size > 50) Text(stringResource(R.string.import_more_errors, bankErrors.size - 50), style = MaterialTheme.typography.bodySmall)

        Text("Anki", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.import_anki_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ankiPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.import_anki_button)) }
        OutlinedButton(onClick = { exportPicker.launch("tsumugi.apkg") }, enabled = !busy) { Text(stringResource(R.string.import_export_button)) }

        HorizontalDivider()
        Text(stringResource(R.string.title_word_lists), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.import_list_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { listPicker.launch(arrayOf("text/*", "*/*")) }, enabled = !busy) { Text(stringResource(R.string.import_list_button)) }

        HorizontalDivider()
        Text(stringResource(R.string.import_banks), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.import_banks_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { bankPicker.launch(arrayOf("application/json", "text/*", "*/*")) }, enabled = !busy) { Text(stringResource(R.string.import_bank_button)) }
        banks.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.import_bank_row, b.title, b.items.size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    run(context.getString(R.string.import_bank_removing)) {
                        val exams = graph.exams()
                        exams.deleteBank(b.bank)
                        banks = exams.userBanks()
                        context.getString(R.string.import_bank_removed, b.title)
                    }
                }, enabled = !busy) { Text(stringResource(R.string.action_remove)) }
            }
        }

        HorizontalDivider()
        Text("Bunpro", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.import_bunpro_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { bunproPicker.launch(arrayOf("text/*", "*/*")) }, enabled = !busy) { Text(stringResource(R.string.import_bunpro_button)) }

        HorizontalDivider()
        Text("WaniKani", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.import_wk_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.import_wk_status, wkStatus), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.import_wk_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = {
            run(context.getString(R.string.import_wk_connecting)) {
                val user = graph.imports.connectWaniKani(token)
                token = ""
                wkStatus = context.getString(R.string.import_wk_connected_as, user.username, user.level)
                wkStatus
            }
        }, enabled = !busy && token.isNotBlank()) { Text(stringResource(R.string.import_wk_connect)) }
        OutlinedButton(onClick = {
            run(context.getString(R.string.import_wk_importing)) {
                val r = graph.imports.importWaniKani { p -> message = p } ?: return@run context.getString(R.string.path_pack_missing)
                context.getString(R.string.import_wk_result, r.matchedToPath, r.wanikaniOnlyItems, r.reviewsImported, r.wanikaniLevel)
            }
        }, enabled = !busy && graph.imports.wanikani.isConnected()) { Text(stringResource(R.string.import_wk_import)) }
    }
}

private suspend fun copyToCache(context: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    file
}
