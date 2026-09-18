package app.tsumugi.android.features.me

import androidx.annotation.StringRes
import app.tsumugi.android.ui.localized
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
            Text(stringResource(R.string.me_streak, s.streak.current, s.streak.longest), style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = s.streak.onVacation, role = Role.Switch, onValueChange = { vm.setVacation(it) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.me_vacation), Modifier.weight(1f))
                Switch(checked = s.streak.onVacation, onCheckedChange = null)
            }
            Text(stringResource(R.string.me_last_weeks), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val days = s.heatmap.takeLast(140)
            val heatmapDescription = heatmapLabel(days)
            Heatmap(days, Modifier.semantics { contentDescription = heatmapDescription })
            Text(stringResource(R.string.me_stages), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            StageBar(s.stages)
            if (s.accuracy.isNotEmpty()) {
                Text(stringResource(R.string.me_accuracy), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                s.accuracy.forEach { (kind, a) -> Text(stringResource(R.string.me_accuracy_row, kind.localized(), (a.ratio * 100).toInt(), a.total)) }
            }
        }
        HorizontalDivider()
        MeDestination.entries.forEach { d ->
            ListItem(modifier = Modifier.clickable { onOpen(d) }, headlineContent = { Text(stringResource(d.title)) }, supportingContent = { Text(stringResource(d.subtitle)) })
        }
    }
}

enum class MeDestination(@StringRes val title: Int, @StringRes val subtitle: Int) {
    IMPORT(R.string.title_import, R.string.me_import_sub),
    SYNC(R.string.title_sync, R.string.me_sync_sub),
    SETTINGS(R.string.title_settings, R.string.me_settings_sub),
    AI(R.string.title_ai, R.string.ai_settings_sub),
    EXAMS(R.string.me_exams, R.string.me_exams_sub),
    LICENSES(R.string.title_licenses, R.string.me_licenses_sub),
}

/** Spoken summary of the heat-map for TalkBack. */
@Composable
private fun heatmapLabel(days: List<DayCount>): String =
    stringResource(R.string.me_heatmap_description, days.count { it.count > 0 }, days.size, days.sumOf { it.count })

/** Review heat-map: 7 rows (weekdays) × N weeks, darker = more answers. */
@Composable
private fun Heatmap(days: List<DayCount>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = (days.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(90.dp)) {
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
