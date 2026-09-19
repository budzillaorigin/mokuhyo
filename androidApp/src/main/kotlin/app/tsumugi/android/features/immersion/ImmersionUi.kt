package app.tsumugi.android.features.immersion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.immersion.ImmersionDay
import app.tsumugi.immersion.ImmersionMode
import app.tsumugi.immersion.ImmersionOrigin
import app.tsumugi.immersion.Milestone
import app.tsumugi.immersion.MilestoneMeasure
import app.tsumugi.immersion.RoadmapStage
import app.tsumugi.immersion.RoadmapStatus
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Automatic immersion logging (BRIEF_V2 §6.11, D-167): while [active] and the screen is in the foreground, a ticket
 * runs; it stops (and the shared log writes the elapsed time) when either ends or the screen leaves. The shared log
 * drops stretches under 15 s and caps one at 6 h.
 */
@Composable
fun ImmersionTracker(origin: ImmersionOrigin, mode: ImmersionMode, ref: String?, title: String?, active: Boolean = true) {
    val graph = rememberGraph()
    val app = LocalContext.current.applicationContext as TsumugiApplication
    var resumed by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }
    val running = active && resumed
    DisposableEffect(running, ref, origin, mode) {
        val ticket = if (running) graph.immersion.start(origin, mode, ref, title) else null
        onDispose {
            // The screen's scope may already be gone: write on the app scope.
            ticket?.let { t -> app.appScope.launch { runCatching { graph.immersion.stop(t) } } }
        }
    }
}

@Composable
fun originLabel(origin: ImmersionOrigin): String = stringResource(
    when (origin) {
        ImmersionOrigin.READER -> R.string.immersion_src_reader
        ImmersionOrigin.MEDIA -> R.string.immersion_src_media
        ImmersionOrigin.PODCAST -> R.string.immersion_src_podcast
        ImmersionOrigin.DIALOGUE -> R.string.immersion_src_dialogue
        ImmersionOrigin.MANUAL -> R.string.immersion_src_manual
    },
)

/**
 * Me → Immersion: today against the daily target, a heat-map of the last weeks (active + passive minutes), minutes
 * by source, manual entry and the target setting (§6.11).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImmersionCard() {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<List<ImmersionDay>?>(null) }
    var totalHours by remember { mutableStateOf(0.0) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var targetOpen by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        runCatching {
            days = graph.immersion.days(84)
            totalHours = graph.immersion.totalHours()
        }.onSuccess { error = null }.onFailure { error = it.readable() }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.immersion_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
            val list = days
            if (list == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (list != null) {
                val today = list.lastOrNull()
                if (today != null) {
                    Text(
                        if (today.targetMinutes > 0) stringResource(R.string.immersion_today_target, today.totalMinutes, today.targetMinutes)
                        else stringResource(R.string.immersion_today, today.totalMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (today.targetMinutes > 0) {
                        LinearProgressIndicator(progress = { (today.totalMinutes.toFloat() / today.targetMinutes).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                val activeTotal = list.sumOf { it.activeMinutes }
                val passiveTotal = list.sumOf { it.passiveMinutes }
                Text(stringResource(R.string.immersion_totals, activeTotal, passiveTotal, "%.1f".format(totalHours)), style = MaterialTheme.typography.bodySmall)
                val description = stringResource(R.string.immersion_heatmap_description, list.count { it.totalMinutes > 0 }, list.size, activeTotal + passiveTotal)
                MinutesHeatmap(list, Modifier.semantics { contentDescription = description })
                val bySource = list.flatMap { it.bySource }.groupBy { it.source }.mapValues { (_, v) -> v.sumOf { it.minutes } }.filterValues { it > 0 }
                if (bySource.isNotEmpty()) {
                    val parts = ArrayList<String>()
                    for ((src, m) in bySource.entries.sortedByDescending { it.value }) parts += stringResource(R.string.immersion_source_minutes, originLabel(src), m)
                    Text(stringResource(R.string.immersion_by_source, parts.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.immersion_empty), style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.immersion_add)) }
                OutlinedButton(onClick = { targetOpen = true }) { Text(stringResource(R.string.immersion_target)) }
            }
        }
    }
    if (adding) ManualEntryDialog(onDismiss = { adding = false }, onSaved = { adding = false; version++ })
    if (targetOpen) TargetDialog(onDismiss = { targetOpen = false }, onSaved = { targetOpen = false; version++ })
}

/** Minutes per day, 7 rows × N weeks; darker = more minutes; a target-met day is fully opaque. */
@Composable
private fun MinutesHeatmap(days: List<ImmersionDay>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = (days.maxOfOrNull { it.totalMinutes } ?: 1).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(80.dp)) {
        val weeks = (days.size + 6) / 7
        if (weeks == 0) return@Canvas
        val cell = minOf(size.width / weeks, size.height / 7)
        days.forEachIndexed { i, d ->
            val m = d.totalMinutes
            val alpha = when {
                m == 0 -> 1f
                d.targetMet -> 1f
                else -> 0.25f + 0.6f * m / max
            }
            drawRoundRect(
                color = if (m == 0) empty else color.copy(alpha = alpha),
                topLeft = Offset((i / 7) * cell, (i % 7) * cell),
                size = Size(cell * 0.85f, cell * 0.85f),
                cornerRadius = CornerRadius(cell * 0.2f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualEntryDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableStateOf("30") }
    var mode by remember { mutableStateOf(ImmersionMode.ACTIVE) }
    var source by remember { mutableStateOf(ImmersionOrigin.MANUAL) }
    var daysAgo by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val value = minutes.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.immersion_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    minutes, { minutes = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.immersion_minutes)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to R.string.immersion_today_chip, 1 to R.string.immersion_yesterday, 2 to R.string.immersion_two_days).forEach { (d, label) ->
                        FilterChip(daysAgo == d, { daysAgo = d }, { Text(stringResource(label)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(mode == ImmersionMode.ACTIVE, { mode = ImmersionMode.ACTIVE }, { Text(stringResource(R.string.immersion_active)) })
                    FilterChip(mode == ImmersionMode.PASSIVE, { mode = ImmersionMode.PASSIVE }, { Text(stringResource(R.string.immersion_passive)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImmersionOrigin.entries.forEach { o -> FilterChip(source == o, { source = o }, { Text(originLabel(o)) }) }
                }
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.immersion_what)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = value ?: return@TextButton
                scope.launch {
                    val date = Clock.System.todayIn(TimeZone.currentSystemDefault()).minus(DatePeriod(days = daysAgo))
                    runCatching { graph.immersion.addManual(date, m, mode, source, title) }
                        .onSuccess { onSaved() }
                        .onFailure { error = it.readable() }
                }
            }, enabled = value != null && value in 1..360) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var target by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { target = runCatching { graph.immersion.targetMinutes() }.getOrDefault(20) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.immersion_target)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.immersion_target_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 10, 20, 30, 45, 60, 90, 120).forEach { m ->
                        FilterChip(target == m, { target = m }, { Text(if (m == 0) stringResource(R.string.immersion_no_target) else stringResource(R.string.minutes_short, m)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { runCatching { graph.immersion.setTargetMinutes(target) }; onSaved() } }, enabled = target >= 0) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun stageTitle(stage: RoadmapStage): String = stringResource(
    when (stage) {
        RoadmapStage.FOUNDATIONS -> R.string.roadmap_stage1
        RoadmapStage.COMPREHENSION -> R.string.roadmap_stage2
        RoadmapStage.OUTPUT -> R.string.roadmap_stage3
        RoadmapStage.REFINEMENT -> R.string.roadmap_stage4
    },
)

@Composable
private fun stageSummary(stage: RoadmapStage): String = stringResource(
    when (stage) {
        RoadmapStage.FOUNDATIONS -> R.string.roadmap_stage1_sub
        RoadmapStage.COMPREHENSION -> R.string.roadmap_stage2_sub
        RoadmapStage.OUTPUT -> R.string.roadmap_stage3_sub
        RoadmapStage.REFINEMENT -> R.string.roadmap_stage4_sub
    },
)

private fun ilr(index: Double?): String = index?.toInt()?.let { IlrLevel.entries.getOrNull(it)?.label } ?: "–"

@Composable
private fun milestoneText(m: Milestone): String = when (m.measure) {
    MilestoneMeasure.KNOWN_WORDS -> stringResource(R.string.roadmap_words, m.current?.toInt() ?: 0, m.target.toInt())
    MilestoneMeasure.IMMERSION_HOURS -> stringResource(R.string.roadmap_hours, "%.1f".format(m.current ?: 0.0), m.target.toInt())
    MilestoneMeasure.READER_COMPREHENSION ->
        if (m.current == null) stringResource(R.string.roadmap_readers_none, m.target.toInt())
        else stringResource(R.string.roadmap_readers, m.current!!.toInt(), m.target.toInt())
    MilestoneMeasure.OPI_LEVEL ->
        if (m.current == null) stringResource(R.string.roadmap_opi_none, ilr(m.target))
        else stringResource(R.string.roadmap_opi, ilr(m.current), ilr(m.target))
}

/** Me → the roadmap (§6.11, D-168): the current stage (never lower than one reached before) and its milestones. */
@Composable
fun RoadmapCard() {
    val graph = rememberGraph()
    var status by remember { mutableStateOf<RoadmapStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { runCatching { graph.roadmap.status() }.onSuccess { status = it; error = null }.onFailure { error = it.readable() } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.roadmap_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
            val s = status ?: return@Column
            Text(stringResource(R.string.roadmap_current, s.current.number, stageTitle(s.current)), style = MaterialTheme.typography.titleMedium)
            Text(stageSummary(s.current), style = MaterialTheme.typography.bodySmall)
            s.stages.firstOrNull { it.stage == s.current }?.milestones?.forEach { m ->
                Text((if (m.met) "✓ " else "") + milestoneText(m), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { m.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            s.next?.let { Text(stringResource(R.string.roadmap_next, milestoneText(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Text(stringResource(R.string.roadmap_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
