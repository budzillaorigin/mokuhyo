package app.tsumugi.android.features.practice

import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
    val context = LocalContext.current

    fun analyze(result: SpeechResult) {
        message = result.error
        heard = result.transcript.ifBlank { null }
        if (result.audio.isEmpty) {
            report = null
            message = listOfNotNull(
                result.error,
                context.getString(R.string.pron_no_audio),
            ).joinToString("\n")
            return
        }
        busy = true
        scope.launch {
            report = runCatching {
                graph.pronunciation.analyze(sentence, result.transcript.ifBlank { null }, result.audio.samples, result.analyzerSegments)
            }.onFailure { message = context.getString(R.string.pron_failed, it.message.orEmpty()) }.getOrNull()
            report?.let(onReport)
            busy = false
        }
    }
    LaunchedEffect(initial) { initial?.let(::analyze) }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pron_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            JaText(sentence, style = MaterialTheme.typography.titleLarge)
            if (voices != null) {
                OutlinedButton(onClick = { scope.launch { voices.say(sentence) } }) { PlayLabel(stringResource(R.string.pron_hear)) }
            }
            MicButton(
                input,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !busy,
                onError = { message = it },
                onResult = ::analyze,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            heard?.let { Text(buildAnnotatedString { append(stringResource(R.string.pron_heard)); append(ja(it)) }, style = MaterialTheme.typography.bodyMedium.japanese()) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            report?.let { ReportView(it) }
        }
    }
}

@Composable
fun ReportView(report: PronunciationReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val scoreLabel = stringResource(R.string.pron_score_description, report.composite)
            CircularProgressIndicator(
                progress = { report.composite / 100f },
                modifier = Modifier.clearAndSetSemantics { contentDescription = scoreLabel },
                strokeWidth = 6.dp,
            )
            Column(Modifier.clearAndSetSemantics {}) {
                Text("${report.composite} / 100", style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.pron_heuristic), style = MaterialTheme.typography.labelSmall)
            }
        }
        report.subScores.forEach { (key, value) ->
            val label = subScoreLabel(key)
            Row(Modifier.clearAndSetSemantics { contentDescription = "$label: $value / 100" }, verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.widthIn(min = 120.dp, max = 180.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.weight(1f))
                Text(" $value", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (report.rateMoraPerSec > 0) {
            Text(
                stringResource(R.string.pron_rate, "%.1f".format(report.rateMoraPerSec), report.pauses.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (report.words.isNotEmpty()) {
            Text(stringResource(R.string.pron_pitch_by_word), style = MaterialTheme.typography.titleSmall)
            report.words.forEach { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    JaText(w.reading, Modifier.widthIn(min = 96.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pron_expected, w.expectedMarks.ifBlank { "?" }), style = MaterialTheme.typography.bodySmall.japanese())
                        Text(stringResource(R.string.pron_you, w.observedMarks.ifBlank { stringResource(R.string.pron_unclear) }), style = MaterialTheme.typography.bodySmall.japanese(), color = verdictColor(w.verdict))
                    }
                    Text(verdictLabel(w.verdict), style = MaterialTheme.typography.labelMedium, color = verdictColor(w.verdict))
                }
            }
        }
        report.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun subScoreLabel(key: String) = when (key) {
    "mora" -> stringResource(R.string.pron_mora)
    "pitch" -> stringResource(R.string.pron_pitch)
    "fluency" -> stringResource(R.string.pron_fluency)
    else -> key.replaceFirstChar { it.uppercase() }
}

@Composable
private fun verdictLabel(v: PitchVerdict) = stringResource(
    when (v) {
        PitchVerdict.MATCH -> R.string.pron_match
        PitchVerdict.FLAT -> R.string.pron_flat
        PitchVerdict.WRONG_DROP -> R.string.pron_wrong_drop
        PitchVerdict.UNCLEAR -> R.string.pron_unclear
        PitchVerdict.UNKNOWN_ACCENT -> R.string.pron_no_accent
    },
)

@Composable
private fun verdictColor(v: PitchVerdict): Color = when (v) {
    PitchVerdict.MATCH -> Color(0xFF2E7D32)
    PitchVerdict.FLAT -> Color(0xFFEF6C00)
    PitchVerdict.WRONG_DROP -> MaterialTheme.colorScheme.error
    PitchVerdict.UNCLEAR, PitchVerdict.UNKNOWN_ACCENT -> MaterialTheme.colorScheme.onSurfaceVariant
}
