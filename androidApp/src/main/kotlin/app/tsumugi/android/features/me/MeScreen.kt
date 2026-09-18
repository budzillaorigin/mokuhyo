package app.tsumugi.android.features.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.features.study.StageBar
import app.tsumugi.android.features.study.StudyOverviewViewModel
import app.tsumugi.study.DayCount

/** Stats, settings and integrations (BRIEF §6 Me tab). */
@Composable
fun MeScreen(onOpen: (MeDestination) -> Unit) {
    val vm: StudyOverviewViewModel = viewModel()
    val stats by vm.stats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats?.let { s ->
            Text("Streak: ${s.streak.current} days (longest ${s.streak.longest})", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vacation mode", Modifier.weight(1f))
                Switch(checked = s.streak.onVacation, onCheckedChange = { vm.setVacation(it) })
            }
            Text("Last 20 weeks", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Heatmap(s.heatmap.takeLast(140))
            Text("Stages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            StageBar(s.stages)
            if (s.accuracy.isNotEmpty()) {
                Text("Accuracy (30 days)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                s.accuracy.forEach { (kind, a) -> Text("${kind.label}: ${(a.ratio * 100).toInt()}% of ${a.total}") }
            }
        }
        HorizontalDivider()
        MeDestination.entries.forEach { d ->
            ListItem(modifier = Modifier.clickable { onOpen(d) }, headlineContent = { Text(d.title) }, supportingContent = { Text(d.subtitle) })
        }
    }
}

enum class MeDestination(val title: String, val subtitle: String) {
    IMPORT("Import & export", "Anki .apkg, imiwa lists, Bunpro, WaniKani"),
    SYNC("Sync", "Your devices, your server"),
    SETTINGS("Settings", "Lessons per batch, retention, reminders"),
    LICENSES("Licenses", "Data sources and open-source software"),
}

/** Review heat-map: 7 rows (weekdays) × N weeks, darker = more answers. */
@Composable
private fun Heatmap(days: List<DayCount>) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = (days.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val weeks = (days.size + 6) / 7
        val cell = minOf(size.width / weeks, size.height / 7)
        days.forEachIndexed { i, d ->
            val x = (i / 7) * cell
            val y = (i % 7) * cell
            val alpha = if (d.count == 0) 1f else 0.25f + 0.75f * d.count / max
            drawRoundRect(
                color = if (d.count == 0) empty else color.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(cell * 0.85f, cell * 0.85f),
                cornerRadius = CornerRadius(cell * 0.2f),
            )
        }
    }
}
