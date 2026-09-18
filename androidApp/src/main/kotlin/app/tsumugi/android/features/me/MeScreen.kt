package app.tsumugi.android.features.me

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.WeeklyPatternsCard
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.features.study.StageBar
import app.tsumugi.android.features.study.StudyOverviewViewModel
import app.tsumugi.android.ui.localized
import app.tsumugi.speaking.ErrorPattern
import app.tsumugi.speaking.WeeklyPatterns
import app.tsumugi.study.DayCount
import app.tsumugi.study.FreezeResult
import app.tsumugi.study.WeeklyChallenge
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Device setting (rule 16) that shows Me → Content review (G-16). */
const val DEV_CONTENT_REVIEW = "dev.contentReview"

/** Stats, streak freezes, speaking patterns, settings and integrations (BRIEF §6 Me tab). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeScreen(onOpen: (MeDestination) -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: StudyOverviewViewModel = viewModel()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var patterns by remember { mutableStateOf<WeeklyPatterns?>(null) }
    var recurring by remember { mutableStateOf<List<ErrorPattern>>(emptyList()) }
    var challenge by remember { mutableStateOf<WeeklyChallenge?>(null) }
    var freezeMessage by remember { mutableStateOf<String?>(null) }
    var devReview by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.refresh()
        patterns = runCatching { graph.conversations.weeklyPatterns() }.getOrNull()
        recurring = runCatching { graph.conversations.recurringErrors(30) }.getOrDefault(emptyList())
        challenge = runCatching { graph.today().challenge }.getOrNull()
        devReview = runCatching { graph.deviceSettings.bool(DEV_CONTENT_REVIEW, false) }.getOrDefault(false)
    }
    fun freeze(tomorrow: Boolean) = scope.launch {
        val day = Clock.System.todayIn(TimeZone.currentSystemDefault()).let { if (tomorrow) it.plus(DatePeriod(days = 1)) else it }
        val result = runCatching { graph.stats.freeze(day) }.getOrNull()
        freezeMessage = context.getString(
            when (result) {
                FreezeResult.FROZEN -> R.string.freeze_done
                FreezeResult.ALREADY_FROZEN -> R.string.freeze_already
                FreezeResult.ALREADY_STUDIED -> R.string.freeze_studied
                FreezeResult.NO_FREEZES_LEFT -> R.string.freeze_none_left
                FreezeResult.OUT_OF_RANGE, null -> R.string.freeze_failed
            },
        )
        vm.refresh()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats?.let { s ->
            Text(stringResource(R.string.me_streak, s.streak.current, s.streak.longest), style = MaterialTheme.typography.titleMedium)
            // G-11 / D-106: a freeze day neither breaks nor extends the streak.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.freeze_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.freeze_left, s.streak.freezesLeft), style = MaterialTheme.typography.bodyMedium)
                    if (s.streak.frozenToday) Text(stringResource(R.string.today_frozen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { freeze(false) }, enabled = s.streak.freezesLeft > 0 && !s.streak.studiedToday && !s.streak.frozenToday) { Text(stringResource(R.string.freeze_today)) }
                        OutlinedButton(onClick = { freeze(true) }, enabled = s.streak.freezesLeft > 0) { Text(stringResource(R.string.freeze_tomorrow)) }
                    }
                    freezeMessage?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = s.streak.onVacation, role = Role.Switch, onValueChange = { vm.setVacation(it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.me_vacation), Modifier.weight(1f))
                        Switch(checked = s.streak.onVacation, onCheckedChange = null)
                    }
                }
            }
            challenge?.let { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.today_challenge, c.title), style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { (c.progress.toFloat() / c.goal.coerceAtLeast(1)).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                        Text(if (c.complete) stringResource(R.string.today_challenge_done) else "${c.progress} / ${c.goal}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            WeeklyPatternsCard(patterns, recurring, onFreeTalk = { onOpen(MeDestination.FREE_TALK) })
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
        MeDestination.entries.filter { it.listed && (it != MeDestination.CONTENT_REVIEW || devReview) }.forEach { d ->
            ListItem(modifier = Modifier.clickable { onOpen(d) }, headlineContent = { Text(stringResource(d.title)) }, supportingContent = { Text(stringResource(d.subtitle)) })
        }
    }
}

enum class MeDestination(@StringRes val title: Int, @StringRes val subtitle: Int, val listed: Boolean = true) {
    IMPORT(R.string.title_import, R.string.me_import_sub),
    EXPORT(R.string.title_export, R.string.me_export_sub),
    SYNC(R.string.title_sync, R.string.me_sync_sub),
    RECORDINGS(R.string.title_recordings, R.string.me_recordings_sub),
    LEADERBOARD(R.string.title_leaderboard, R.string.me_leaderboard_sub),
    SETTINGS(R.string.title_settings, R.string.me_settings_sub),
    AI(R.string.title_ai, R.string.ai_settings_sub),
    EXAMS(R.string.me_exams, R.string.me_exams_sub),
    CONTENT_REVIEW(R.string.title_content_review, R.string.me_content_review_sub),
    LICENSES(R.string.title_licenses, R.string.me_licenses_sub),
    FREE_TALK(R.string.practice_free_talk, R.string.practice_free_talk_sub, listed = false),
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
        if (weeks == 0) return@Canvas
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
