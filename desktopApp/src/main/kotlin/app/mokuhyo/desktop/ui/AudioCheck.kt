package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.Voice
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Microphone level meter, a 3-second record-and-play-back test, and a test voice (BRIEF §9 first run). */
@Composable
fun AudioCheck(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf(emptyList<AudioIO.Device>()) }
    var input by remember { mutableStateOf(app.settings.get(Settings.Key.INPUT_DEVICE)) }
    var level by remember { mutableStateOf(0.0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val lang by app.language.collectAsState()
    LaunchedEffect(Unit) { inputs = withContext(Dispatchers.IO) { runCatching { AudioIO.inputDevices() }.getOrDefault(emptyList()) } }

    SectionCard("Microphone") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Input: ${input ?: "System default"}", Modifier.weight(1f))
            OutlinedButton(onClick = { menu = true }) { Text("Change") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("System default") }, onClick = { input = null; app.settings.remove(Settings.Key.INPUT_DEVICE); menu = false })
                inputs.forEach { d ->
                    DropdownMenuItem(text = { Text(d.name) }, onClick = { input = d.name; app.settings.put(Settings.Key.INPUT_DEVICE, d.name); menu = false })
                }
            }
        }
        LinearProgressIndicator(progress = { (level * 4).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    status = "Recording for 3 seconds — say something…"
                    val samples = withContext(Dispatchers.IO) {
                        runCatching {
                            val rec = AudioIO.record(input) { level = it }
                            Thread.sleep(3_000)
                            rec.stop()
                        }
                    }
                    level = 0.0
                    samples.onSuccess { s ->
                        status = "Playing it back…"
                        withContext(Dispatchers.IO) { runCatching { AudioIO.play(AudioIO.wav(s), app.settings.get(Settings.Key.OUTPUT_DEVICE)) } }
                        status = if (s.any { kotlin.math.abs(it.toInt()) > 800 }) "Microphone works." else "That was very quiet — check the input device or the OS microphone permission."
                    }.onFailure { status = "Couldn't open the microphone: ${it.message}. On macOS, allow Mokuhyo in System Settings → Privacy & Security → Microphone." }
                    busy = false
                }
            }) { Text("Test microphone") }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    status = "Speaking a test sentence…"
                    status = Voice.speakSample(app, lang) ?: "Test voice played."
                    busy = false
                }
            }) { Text("Play test voice") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
