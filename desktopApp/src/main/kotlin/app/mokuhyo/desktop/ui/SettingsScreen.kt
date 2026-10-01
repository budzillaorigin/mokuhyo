package app.mokuhyo.desktop.ui

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
import app.mokuhyo.settings.Settings
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.launch

private enum class SettingsTab(val title: String) { LANGUAGES("Languages"), AI("AI"), SPEECH("Speech & audio"), BACKUP("Backup"), PRIVACY("Privacy & updates"), ABOUT("About & licenses") }

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
                SpeechModels(app)
                AudioCheck(app)
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
    OllamaSection(app)
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.RadioButton(
                            selected = use && model == m.name, enabled = m.excludedReason == null,
                            onClick = {
                                model = m.name
                                use = true
                                app.settings.put(Settings.Key.OLLAMA_MODEL, m.name)
                                app.settings.put(Settings.Key.USE_OLLAMA, "true")
                            },
                        )
                        Text(m.name + (if (m.parameterSize.isNotEmpty()) " · ${m.parameterSize}" else ""))
                        m.excludedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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

@Composable
private fun PrivacySettings(app: AppGraph) {
    var auto by remember { mutableStateOf(app.settings.bool(Settings.Key.UPDATE_CHECK)) }
    SectionCard("Network") {
        Text("Mokuhyo works offline. It goes online only to download a model you chose, to check for updates if you turn that on, and to look for Ollama on this computer when you ask.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(auto, onCheckedChange = {
                auto = it
                app.settings.put(Settings.Key.UPDATE_CHECK, it.toString())
            })
            Text("Check for updates automatically (GitHub Releases, once a day)")
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
