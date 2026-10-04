package app.mokuhyo.desktop.ui.speaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.platform.Os
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Vad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice input for every speaking mode (BRIEF_PHASE8 N-00b): record at the device's own rate, stop by itself after the
 * silence that ends an answer (or on Stop), say plainly when no audio arrives, and — in practice, unless turned off —
 * show the transcript to send, edit or retake before it goes to the partner. Interview tests pass [confirm] = false.
 */
@Composable
fun VoiceCapture(
    app: AppGraph,
    module: LanguageModule,
    confirm: Boolean,
    enabled: Boolean,
    onStatus: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    onSend: (String, ShortArray) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rec by remember { mutableStateOf<AudioIO.Recording?>(null) }
    var level by remember { mutableStateOf(0.0) }
    var pending by remember { mutableStateOf<Pair<String, ShortArray>?>(null) }
    var edit by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { rec?.runCatching { stop() } } }

    fun finish() {
        val r = rec ?: return
        rec = null
        level = 0.0
        val pcm = r.stop()
        onBusy(true)
        onStatus("Transcribing…")
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { app.recognizer()!!.transcribe(pcm, module.sttLanguage).text.trim() }.getOrDefault("")
            }
            onBusy(false)
            when {
                text.isBlank() -> onStatus("I didn't catch anything. Try again, or type your answer.")
                confirm -> { pending = text to pcm; edit = text; onStatus("Check what I heard, then send it — or edit it, or record again.") }
                else -> { onStatus(""); onSend(text, pcm) }
            }
        }
    }

    fun start() {
        pending = null
        val vad = Vad()
        rec = runCatching {
            AudioIO.record(app.settings.get(Settings.Key.INPUT_DEVICE), onLevel = { level = it }, vad = vad, onEvent = { e ->
                scope.launch {
                    when (e) {
                        Vad.Event.AUTO_STOP -> finish()
                        Vad.Event.NO_AUDIO -> {
                            rec?.runCatching { stop() }
                            rec = null
                            level = 0.0
                            onStatus(noAudioHint())
                        }
                        Vad.Event.SPEECH_START -> onStatus("Listening… it stops by itself when you finish.")
                    }
                }
            })
        }.onFailure { onStatus("Couldn't open the microphone: ${it.message}") }.getOrNull()
        if (rec != null) onStatus("Recording… speak now (it stops after a short silence, or press Stop).")
    }

    val p = pending
    if (p != null) {
        OutlinedTextField(edit, { edit = it }, Modifier.fillMaxWidth(), label = { Text("What I heard") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = edit.isNotBlank(), onClick = { pending = null; onStatus(""); onSend(edit.trim(), p.second) }) { Text("Send") }
            OutlinedButton(onClick = { pending = null; start() }) { Text("Record again") }
        }
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled || rec != null, onClick = { if (rec == null) start() else finish() }) {
                Text(if (rec != null) "■ Stop" else "● Record")
            }
        }
        if (rec != null) LinearProgressIndicator(progress = { (level * 4).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

/** "No audio detected" with where to look on this OS (macOS microphone permission, Windows privacy setting). */
fun noAudioHint(): String = "No audio detected — check your microphone. " + when (Os.current) {
    Os.MACOS -> "On a Mac: System Settings → Privacy & Security → Microphone → allow Mokuhyo, and pick the right input in Settings → Speech & audio."
    Os.WINDOWS -> "On Windows: Settings → Privacy & security → Microphone → let desktop apps use the microphone, and pick the right input in Settings → Speech & audio."
    Os.LINUX -> "Pick the right input in Settings → Speech & audio and check it isn't muted."
}
