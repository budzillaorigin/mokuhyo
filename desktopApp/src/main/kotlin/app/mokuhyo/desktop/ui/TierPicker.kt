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
import androidx.compose.runtime.getValue
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
    val options = TierAdvisor.options(hw, app.manifest.models)
    val downloads by app.downloads.states.collectAsState()
    val lang by app.language.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option -> TierRow(app, option, option.tier == selected, downloads, lang, onSelect) }
    }
}

@Composable
private fun TierRow(app: AppGraph, option: TierOption, selected: Boolean, downloads: Map<String, DownloadCenter.State>, lang: String, onSelect: (Tier) -> Unit) {
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
                        "${model.name} · ${gb(model.totalBytes)} download · ${latency[option.tier]}",
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
                val reason = option.reason
                if (!option.runnable && reason != null) {
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (selected && model != null) DownloadControls(app, model, downloads[model.id])
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
