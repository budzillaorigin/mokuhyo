package app.mokuhyo.desktop.ui

import app.mokuhyo.ai.LocalLlamaModel
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.ModelCheck
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import app.mokuhyo.lang.Languages
import app.mokuhyo.tts.OsVoice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.OllamaDetector
import app.mokuhyo.ai.OllamaModel
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.BuildInfo
import app.mokuhyo.desktop.Resources
import app.mokuhyo.net.NetworkPolicy
import okio.Path.Companion.toOkioPath
import app.mokuhyo.desktop.ui.lexicon.ImportLexicon
import app.mokuhyo.opi.CorrectionsMode
import app.mokuhyo.opi.SpeakingActivity
import androidx.compose.material3.FilterChip
import app.mokuhyo.desktop.ui.speaking.correctionsDefault
import app.mokuhyo.desktop.ui.speaking.settingKey
import app.mokuhyo.settings.Settings
import app.mokuhyo.update.UpdateResult
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.launch

private enum class SettingsTab(val title: String) { LANGUAGES("Languages"), AI("AI"), SPEECH("Speech & audio"), CONTENT("Content"), BACKUP("Backup"), PRIVACY("Privacy & updates"), ABOUT("About & licenses") }

@Composable
fun SettingsScreen(app: AppGraph) {
    var tab by remember { mutableStateOf(SettingsTab.LANGUAGES) }
    Page("Settings") {
        TabRow(selectedTabIndex = tab.ordinal) {
            SettingsTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) }) }
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            SettingsTab.LANGUAGES -> {
                val chosen = remember { mutableStateListOf<String>().apply { addAll(app.chosenLanguages()) } }
                LanguagePicker(chosen)
                OutlinedButton(onClick = { app.setChosenLanguages(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Save languages") }
            }
            SettingsTab.AI -> AiSettings(app)
            SettingsTab.SPEECH -> {
                SpeakingDefaults(app)
                SpeechModels(app)
                SystemVoices(app)
                AudioCheck(app)
            }
            SettingsTab.CONTENT -> {
                ImportLexicon(app)
                ExportSuggestions(app)
            }
            SettingsTab.BACKUP -> BackupSection(app)
            SettingsTab.PRIVACY -> PrivacySettings(app)
            SettingsTab.ABOUT -> About(app)
        }
    }
}

@Composable
private fun AiSettings(app: AppGraph) {
    var preferCpu by remember { mutableStateOf(app.settings.bool(Settings.Key.PREFER_CPU)) }
    SectionCard("AI engine") {
        Text("Engine: ${app.runtime.status}")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(preferCpu, onCheckedChange = {
                preferCpu = it
                app.settings.put(Settings.Key.PREFER_CPU, it.toString())
            })
            Text("Use the CPU even when a GPU is available (takes effect after a restart)")
        }
    }
    SectionCard("Model tier") { TierSettings(app) }
    TestModel(app)
    SideLoadModel(app)
    OllamaSection(app)
}

/**
 * "Test the model" (BRIEF_PHASE8 N-00b): one turn through the gateway with the error shown verbatim; which native
 * variant loaded; the model file's own name and its context limit; the last AI calls from the rolling log.
 */
@Composable
private fun TestModel(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tail by remember { mutableStateOf(app.log.tail(8)) }
    SectionCard("Test the model") {
        Text("Native engine: ${app.runtime.status}", style = MaterialTheme.typography.bodySmall)
        val lm = app.languageModel()
        val local = lm as? LocalLlamaModel
        Text(
            when {
                lm == null -> "No model is set up."
                local != null -> "Model file: ${local.fileInfo?.name ?: lm.id} · context ${local.contextSize} tokens" +
                    (local.fileInfo?.contextLength?.let { " (trained for $it)" } ?: "")
                else -> "Model: ${lm.id}" + (lm.contextSize?.let { " · context $it tokens" } ?: "")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                result = null
                scope.launch {
                    val start = System.nanoTime()
                    val r = withContext(Dispatchers.Default) { app.gateway.run(ModelCheck(), "Spanish") }
                    val s = (System.nanoTime() - start) / 1_000_000_000.0
                    result = when (r) {
                        is AiResult.Ok -> "OK in ${"%.1f".format(s)} s — ${r.engine}: “${r.value.reply}”"
                        is AiResult.Fallback -> "Failed after ${"%.1f".format(s)} s: ${r.reason}"
                        is AiResult.Unavailable -> "Failed after ${"%.1f".format(s)} s: ${r.reason}"
                    }
                    tail = app.log.tail(8)
                    busy = false
                }
            }) { Text(if (busy) "Testing…" else "Test the model") }
            Text("One short turn through the same path every feature uses.", style = MaterialTheme.typography.bodySmall)
        }
        result?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        if (tail.isNotEmpty()) {
            Text("Recent AI calls (${app.log.file.absolutePath})", style = MaterialTheme.typography.labelMedium)
            tail.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Air-gapped install (BRIEF_PHASE8 N-11): copy a model file from a USB stick or share; size and SHA-256 are checked. */
@Composable
private fun SideLoadModel(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SectionCard("Install a model from a file (no network)") {
        Text("For computers without internet: copy the model file (from the model's source page, see docs/MODELS.md) onto this computer, " +
            "then choose it here. Mokuhyo checks its size and SHA-256 against its catalogue before installing.", style = MaterialTheme.typography.bodyMedium)
        app.manifest.models.filter { it.files.size == 1 && app.modelFile(it) == null }.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${m.name} — ${m.files.single().name} (${gb(m.totalBytes)})", modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !busy, onClick = {
                    val d = java.awt.FileDialog(null as java.awt.Frame?, "Choose ${m.files.single().name}", java.awt.FileDialog.LOAD)
                    d.isVisible = true
                    val name = d.file ?: return@OutlinedButton
                    busy = true
                    status = "Checking ${name}…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            app.models.installFromFile(m, java.io.File(d.directory, name).toOkioPath())
                        }
                        status = r.fold({ "${m.name} installed." }, { "Not installed: ${it.message}" })
                        busy = false
                    }
                }) { Text("Choose file…") }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OllamaSection(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<OllamaModel>?>(null) }
    var checked by remember { mutableStateOf(false) }
    var use by remember { mutableStateOf(app.settings.bool(Settings.Key.USE_OLLAMA)) }
    var model by remember { mutableStateOf(app.settings.get(Settings.Key.OLLAMA_MODEL)) }
    SectionCard("Use my Ollama (optional)") {
        Text("If you already run Ollama on this computer, Mokuhyo can use one of its models instead of its own. Off by default; only localhost is checked.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            scope.launch {
                found = OllamaDetector(Java.create()).detect()
                checked = true
            }
        }) { Text("Look for Ollama on this computer") }
        val list = found
        when {
            checked && list == null -> Text("No Ollama server answered at localhost:11434.")
            list != null -> {
                list.forEach { m ->
                    RadioRow(use && model == m.name, enabled = m.excludedReason == null, onSelect = {
                        model = m.name
                        use = true
                        app.settings.put(Settings.Key.OLLAMA_MODEL, m.name)
                        app.settings.put(Settings.Key.USE_OLLAMA, "true")
                    }) {
                        Text(m.name + (if (m.parameterSize.isNotEmpty()) " · ${m.parameterSize}" else ""))
                        m.excludedReason?.let { Text("  " + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (use) OutlinedButton(onClick = {
                    use = false
                    app.settings.put(Settings.Key.USE_OLLAMA, "false")
                }) { Text("Stop using Ollama") }
            }
        }
    }
}

/** Default corrections mode per speaking activity and AAB audio (BRIEF_PHASE8 §B.5); learner settings, so they travel in backups. */
@Composable
private fun SpeakingDefaults(app: AppGraph) {
    SectionCard("Speaking: corrections") {
        Text("How corrections appear by default. Interview tests always run as After action.", style = MaterialTheme.typography.bodyMedium)
        SpeakingActivity.entries.forEach { act ->
            var mode by remember { mutableStateOf(app.correctionsDefault(act)) }
            Text(act.title, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CorrectionsMode.entries.forEach { m ->
                    FilterChip(mode == m, {
                        mode = m
                        app.settings.put(settingKey(act), m.id)
                    }, label = { Text(m.title) })
                }
            }
        }
        var keep by remember { mutableStateOf(app.settings.bool(Settings.Key.AAB_KEEP_AUDIO, default = true)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(keep, onCheckedChange = { keep = it; app.settings.put(Settings.Key.AAB_KEEP_AUDIO, it.toString()) })
            Text("Keep my recorded turns for playback in the After Action Brief (recordings stay on this computer)")
        }
    }
}

@Composable
private fun SpeechModels(app: AppGraph) {
    val downloads by app.downloads.states.collectAsState()
    SectionCard("Speech recognition (Whisper)") {
        app.manifest.models.filter { it.kind == ModelKind.STT }.forEach { m ->
            Text("${m.name} — ${gb(m.totalBytes)}${if (m.bundled) " · included with the app" else ""}", style = MaterialTheme.typography.titleSmall)
            if (m.note.isNotEmpty()) Text(m.note, style = MaterialTheme.typography.bodySmall)
            if (!m.bundled || app.modelFile(m) == null) DownloadControls(app, m, downloads[m.id])
            if (app.modelFile(m) != null) {
                val active = app.sttModel()?.id == m.id
                OutlinedButton(enabled = !active, onClick = { app.settings.put(Settings.Key.STT_MODEL_ID, m.id) }) {
                    Text(if (active) "In use" else "Use this model")
                }
            }
        }
    }
}

/** The computer's own voices (BRIEF_PHASE8 N-00): what was found per language, the compact-voice hint, and a rescan. */
@Composable
private fun SystemVoices(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<OsVoice.Info>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { voices = withContext(Dispatchers.IO) { OsVoice.voices() } }
    SectionCard("This computer's voices") {
        if (!OsVoice.available) {
            Text("This system has no built-in voices Mokuhyo can use; the bundled voices speak where a language has one.")
            return@SectionCard
        }
        val all = voices
        if (all == null) Text("Looking for installed voices…")
        else Languages.all.forEach { l ->
            val found = OsVoice.voicesFor(l.code, all)
            Text("${l.nameEnglish}: " + (found.take(3).joinToString { it.name }.ifEmpty { "none installed" }), style = MaterialTheme.typography.bodySmall)
            OsVoice.compactOnlyHint(l.code, all)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    voices = withContext(Dispatchers.IO) { OsVoice.rescan() }
                    busy = false
                }
            }) { Text(if (busy) "Rescanning…" else "Rescan voices") }
            Text("After installing a voice in your system settings, rescan so Mokuhyo uses it without a restart.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PrivacySettings(app: AppGraph) {
    var auto by remember { mutableStateOf(app.settings.bool(Settings.Key.UPDATE_CHECK)) }
    SectionCard("Network") {
        if (NetworkPolicy.disabled) Text("Network access is turned off for this session (--no-network): downloads, the update check, " +
            "Ollama detection and lexicon URL imports all refuse to connect.", color = MaterialTheme.colorScheme.error)
        Text("Mokuhyo works offline. It goes online only to download a model you chose, to check for updates if you turn that on, and to look for Ollama on this computer when you ask.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(auto, onCheckedChange = {
                auto = it
                app.settings.put(Settings.Key.UPDATE_CHECK, it.toString())
            })
            Text("Check for updates automatically (GitHub Releases, once a day)")
        }
        val update by app.updates.state.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.updates.checkNow() }) { Text("Check now") }
            Text(
                when (val u = update) {
                    null -> "This is version ${BuildInfo.version}."
                    UpdateResult.UpToDate -> "You have the latest version (${BuildInfo.version})."
                    is UpdateResult.Available -> "Version ${u.version} is available: ${u.release.url}"
                    is UpdateResult.Failed -> "Couldn't check for updates: ${u.message}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    SectionCard("Your data") {
        Text("Stored only on this computer, in:")
        Text(app.dataDir.absolutePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun About(app: AppGraph) {
    SectionCard("Mokuhyo ${BuildInfo.version}") {
        Text("Free and open source (Apache-2.0). ${app.runtime.status}")
        Disclaimer()
    }
    var dev by remember { mutableStateOf(app.settings.bool(Settings.Key.DEVELOPER)) }
    SectionCard("Developer tools") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(dev, onCheckedChange = { dev = it; app.settings.put(Settings.Key.DEVELOPER, it.toString()) })
            Text("Content review (for people checking AI-drafted content)")
        }
    }
    if (dev) ContentReviewScreen(app)
    SectionCard("Third-party licenses") {
        Text(Resources.textOrNull("docs/LICENSES.md") ?: "docs/LICENSES.md is missing from this build.", fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}
