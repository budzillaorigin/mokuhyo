package app.tsumugi.android.features.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tsumugi.android.platform.SpeechInput
import app.tsumugi.android.platform.SpeechResult
import app.tsumugi.android.platform.Voices
import app.tsumugi.android.ui.japanese
import app.tsumugi.speech.PitchVerdict
import app.tsumugi.speech.PronunciationReport
import kotlinx.coroutines.launch

/**
 * The pronunciation panel (BRIEF §5.10): hear the target, record yourself, and see a heuristic analysis — a 0–100
 * composite with labeled sub-scores, per-word pitch marks (expected vs. observed) and the analyzer's caveats.
 * [initial] analyzes an existing recording right away (e.g. the learner's role-play turn).
 */
@Composable
fun PronunciationPanel(
    sentence: String,
    voices: Voices?,
    input: SpeechInput,
    modifier: Modifier = Modifier,
    initial: SpeechResult? = null,
    onReport: (PronunciationReport) -> Unit = {},
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var report by remember(sentence) { mutableStateOf<PronunciationReport?>(null) }
    var heard by remember(sentence) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(sentence) { mutableStateOf<String?>(null) }

    fun analyze(result: SpeechResult) {
        message = result.error
        heard = result.transcript.ifBlank { null }
        if (result.audio.isEmpty) {
            report = null
            message = listOfNotNull(
                result.error,
                "The system recognizer on this Android version listens by itself and doesn't share the audio, so pitch and " +
                    "fluency can't be scored. Choose on-device Whisper in Settings → AI & speech for the full analysis.",
            ).joinToString("\n")
            return
        }
        busy = true
        scope.launch {
            report = runCatching {
                graph.pronunciation.analyze(sentence, result.transcript.ifBlank { null }, result.audio.samples, result.analyzerSegments)
            }.onFailure { message = "Couldn't analyze the recording: ${it.message}" }.getOrNull()
            report?.let(onReport)
            busy = false
        }
    }
    LaunchedEffect(initial) { initial?.let(::analyze) }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pronunciation", style = MaterialTheme.typography.titleMedium)
            Text(sentence, style = MaterialTheme.typography.titleLarge.japanese())
            if (voices != null) {
                OutlinedButton(onClick = { scope.launch { voices.say(sentence) } }) { Text("▶ Hear it") }
            }
            MicButton(
                input,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !busy,
                onError = { message = it },
                onResult = ::analyze,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            heard?.let { Text("Heard: $it", style = MaterialTheme.typography.bodyMedium.japanese()) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            report?.let { ReportView(it) }
        }
    }
}

@Composable
fun ReportView(report: PronunciationReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(progress = { report.composite / 100f }, strokeWidth = 6.dp)
            Column {
                Text("${report.composite} / 100", style = MaterialTheme.typography.headlineSmall)
                Text("Heuristic estimate, not a phoneme-level assessment", style = MaterialTheme.typography.labelSmall)
            }
        }
        report.subScores.forEach { (key, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subScoreLabel(key), Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.weight(1f))
                Text(" $value", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (report.rateMoraPerSec > 0) {
            Text(
                "Speaking rate: ${"%.1f".format(report.rateMoraPerSec)} morae/s · long pauses: ${report.pauses.size}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (report.words.isNotEmpty()) {
            Text("Pitch by word (↑ rise, ↓ drop)", style = MaterialTheme.typography.titleSmall)
            report.words.forEach { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.reading, Modifier.width(96.dp), style = MaterialTheme.typography.bodyLarge.japanese())
                    Column(Modifier.weight(1f)) {
                        Text("expected ${w.expectedMarks.ifBlank { "?" }}", style = MaterialTheme.typography.bodySmall.japanese())
                        Text("you ${w.observedMarks.ifBlank { "unclear" }}", style = MaterialTheme.typography.bodySmall.japanese(), color = verdictColor(w.verdict))
                    }
                    Text(verdictLabel(w.verdict), style = MaterialTheme.typography.labelMedium, color = verdictColor(w.verdict))
                }
            }
        }
        report.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun subScoreLabel(key: String) = when (key) {
    "mora" -> "Mora accuracy"
    "pitch" -> "Pitch accent"
    "fluency" -> "Fluency"
    else -> key.replaceFirstChar { it.uppercase() }
}

private fun verdictLabel(v: PitchVerdict) = when (v) {
    PitchVerdict.MATCH -> "match"
    PitchVerdict.FLAT -> "too flat"
    PitchVerdict.WRONG_DROP -> "drop in wrong place"
    PitchVerdict.UNCLEAR -> "unclear"
    PitchVerdict.UNKNOWN_ACCENT -> "no accent data"
}

@Composable
private fun verdictColor(v: PitchVerdict): Color = when (v) {
    PitchVerdict.MATCH -> Color(0xFF2E7D32)
    PitchVerdict.FLAT -> Color(0xFFEF6C00)
    PitchVerdict.WRONG_DROP -> MaterialTheme.colorScheme.error
    PitchVerdict.UNCLEAR, PitchVerdict.UNKNOWN_ACCENT -> MaterialTheme.colorScheme.onSurfaceVariant
}
