package app.mokuhyo.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.ai.HardwareInfo
import app.mokuhyo.ai.Tier
import app.mokuhyo.ai.TierAdvisor
import app.mokuhyo.ai.TierOption
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.DownloadCenter
import app.mokuhyo.lang.Languages

/** Latency per tier as the brief states it (§6.1), shown so the trade-off is clear. */
private val latency = mapOf(Tier.A to "10–25 s per turn on CPU", Tier.B to "4–10 s per turn", Tier.C to "3–8 s per turn", Tier.D to "3–6 s per turn with a GPU")

/** The four tiers with the recommendation, disabled reasons, provenance and download controls (BRIEF §6.1). */
@Composable
fun TierPicker(app: AppGraph, hw: HardwareInfo, selected: Tier?, onSelect: (Tier) -> Unit) {
    val downloads by app.downloads.states.collectAsState()
    val lang by app.language.collectAsState()
    var timings by remember { mutableStateOf(app.modelTimings()) }
    val options = TierAdvisor.options(hw, app.manifest.models, timings, app.qualityFloor(lang))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option -> TierRow(app, option, option.tier == selected, downloads, lang, onSelect) { timings = app.modelTimings() } }
    }
}

@Composable
private fun TierRow(
    app: AppGraph, option: TierOption, selected: Boolean, downloads: Map<String, DownloadCenter.State>, lang: String,
    onSelect: (Tier) -> Unit, onTimed: () -> Unit,
) {
    val model = option.model
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        Modifier.fillMaxWidth().selectable(selected, enabled = option.runnable, role = Role.RadioButton) { onSelect(option.tier) },
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (option.runnable) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected, onClick = null, enabled = option.runnable)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tier ${option.tier.id} — ${option.tier.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (option.recommended) Badge("Recommended for this computer")
                }
                Text(option.tier.blurb, style = MaterialTheme.typography.bodyMedium)
                if (model != null) {
                    Text(
                        "${model.name} · ${gb(model.totalBytes)} download · " +
                            (option.timing?.let { "first sentence ≈ ${"%.1f".format(it.firstSentenceMs / 1000.0)} s measured here" } ?: latency[option.tier]),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    model.provenance?.let { p ->
                        Text("Made by ${p.developer} (${p.country}) · ${p.license}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${Languages.of(lang)?.nameEnglish ?: lang} quality: ${QualityBadges.label(model.id, lang)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (option.slower) Badge("Slower — better for end-of-session grading")
                option.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val reason = option.reason
                if (!option.runnable && reason != null) {
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (selected && model != null) DownloadControls(app, model, downloads[model.id])
                if (option.runnable && model != null) SpeedCheck(app, model, option.timing, downloads[model.id], lang, onTimed)
            }
        }
    }
}

@Composable
fun DownloadControls(app: AppGraph, model: app.mokuhyo.ai.ModelInfo, state: DownloadCenter.State?) {
    val installed = app.modelFile(model) != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            installed || state is DownloadCenter.State.Done -> Text("Downloaded and verified ✓", style = MaterialTheme.typography.bodyMedium)
            state is DownloadCenter.State.Running -> {
                Column(Modifier.weight(1f)) {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (state.verifying) "Verifying…" else "${gb(state.done)} of ${gb(state.total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { app.downloads.pause(model) }) { Text("Pause") }
            }
            state is DownloadCenter.State.Paused -> {
                Text("Paused at ${gb(state.done)} of ${gb(state.total)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { app.downloads.start(model) }) { Text("Resume") }
            }
            state is DownloadCenter.State.Failed -> {
                Text(state.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.retryable) OutlinedButton(onClick = { app.downloads.start(model) }) { Text("Retry") }
            }
            else -> OutlinedButton(onClick = { app.downloads.start(model) }) { Text("Download ${gb(model.totalBytes)}") }
        }
    }
}

/**
 * Per-language quality from `eval_speaking.py` (docs/MODELS.md, BRIEF §6.1). Filled in from the bundled results
 * once the eval has run; until then the honest answer is "not benchmarked yet".
 */
object QualityBadges {
    private val scores: Map<Pair<String, String>, Double> by lazy { EvalResults.load() }
    const val THRESHOLD = 0.6

    fun label(modelId: String, lang: String): String {
        val s = scores[modelId to lang] ?: return "not benchmarked yet"
        val pct = (s * 100).toInt()
        return if (s < THRESHOLD) "$pct/100 — below the quality bar for this language" else "$pct/100"
    }

    fun belowThreshold(modelId: String, lang: String): Boolean = (scores[modelId to lang] ?: 1.0) < THRESHOLD
}

/** Reads the eval table bundled from docs/MODELS.md ("| model-id | lang | score |" rows under "## Speaking eval"). */
object EvalResults {
    fun load(): Map<Pair<String, String>, Double> {
        val md = app.mokuhyo.desktop.Resources.textOrNull("docs/MODELS.md") ?: return emptyMap()
        val section = md.substringAfter("## Speaking eval", "").substringBefore("\n## ")
        return section.lines().mapNotNull { line ->
            val cells = line.trim().trim('|').split("|").map { it.trim().trim('`') }
            if (cells.size < 3) return@mapNotNull null
            val score = cells[2].toDoubleOrNull() ?: return@mapNotNull null
            (cells[0] to cells[1]) to score
        }.toMap()
    }
}

/**
 * The on-device timing check for one downloaded model (N2): runs by itself once the download finishes, and on demand.
 * It takes about a minute with the model, Whisper and the voice loaded; progress shows and Cancel stops it.
 */
@Composable
private fun SpeedCheck(app: AppGraph, model: app.mokuhyo.ai.ModelInfo, timing: app.mokuhyo.ai.ModelTiming?, state: DownloadCenter.State?, lang: String, onTimed: () -> Unit) {
    val installed = app.modelFile(model) != null || state is DownloadCenter.State.Done
    if (!installed || !app.runtime.available) return
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    fun start() {
        if (job != null) return
        status = "Checking conversation speed (loads the model, speech recognition and the voice; about a minute)…"
        job = scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { app.checkSpeed(model, lang) }
                status = t?.let { "First sentence ≈ ${"%.1f".format(it.firstSentenceMs / 1000.0)} s (first token ${it.firstTokenMs} ms, ${it.tokensPerSecond} tokens/s)." }
                    ?: "This model can't run on this computer right now."
                onTimed()
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "Speed check stopped."
                throw e
            } catch (e: Exception) {
                status = "Speed check failed: ${e.message}"
            } finally {
                job = null
            }
        }
    }
    // Right after a download completes, time it once without being asked.
    LaunchedEffect(state is DownloadCenter.State.Done) { if (state is DownloadCenter.State.Done && timing == null) start() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (job != null) {
            LinearProgressIndicator(Modifier.weight(1f))
            TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
        } else {
            OutlinedButton(onClick = { start() }) { Text(if (timing == null) "Check conversation speed" else "Check speed again") }
        }
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
}
