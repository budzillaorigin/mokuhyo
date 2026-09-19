package app.tsumugi.android.features.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsumugi.android.R
import app.tsumugi.android.platform.DrillPlayer
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.readable
import app.tsumugi.practice.DrillKind
import app.tsumugi.practice.DrillSet
import app.tsumugi.practice.DrillSetSummary
import app.tsumugi.practice.DrillStepKind
import app.tsumugi.practice.DrillTiming
import kotlinx.coroutines.launch

@Composable
private fun DrillKind.label(): String = stringResource(if (this == DrillKind.GRAMMAR) R.string.drillset_kind_grammar else R.string.drillset_kind_dialogue)

/** Speaking drill sets (BRIEF_V2 §6.10): prompt → pause → model answer → repeat, by JLPT level. */
@Composable
fun DrillSetsScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    var level by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var sets by remember { mutableStateOf<List<DrillSetSummary>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(level, retry) {
        sets = null
        error = null
        runCatching {
            val practice = graph.practice()
            missing = practice == null
            sets = practice?.drillSets(level).orEmpty()
        }.onFailure { error = it.readable() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.drillset_intro), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        JlptFilter(level, { level = it }, Modifier.padding(vertical = 8.dp))
        val list = sets
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { retry++ })
            missing -> PracticePackMissing()
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() -> Text(stringResource(R.string.drillset_none), Modifier.padding(8.dp))
            else -> LazyColumn {
                items(list, key = { it.id }) { s ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(s.id) },
                        headlineContent = { JaText(s.title, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = { Text(s.description, maxLines = 2) },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Tag("N${s.jlpt}")
                                Tag(s.kind.label())
                                if (s.isAiGenerated) AiBadge()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The pause presets offered on the player (DrillTiming presets plus fixed pauses). */
private data class TimingPreset(val label: Int, val arg: Int?, val timing: DrillTiming)

private val PRESETS = listOf(
    TimingPreset(R.string.drillset_timing_short, null, DrillTiming.SHORT),
    TimingPreset(R.string.drillset_timing_default, null, DrillTiming.DEFAULT),
    TimingPreset(R.string.drillset_timing_long, null, DrillTiming.LONG),
    TimingPreset(R.string.drillset_timing_fixed, 3, DrillTiming(pauseMode = DrillTiming.PauseMode.FIXED, fixedPauseMs = 3_000)),
    TimingPreset(R.string.drillset_timing_fixed, 5, DrillTiming(pauseMode = DrillTiming.PauseMode.FIXED, fixedPauseMs = 5_000)),
    TimingPreset(R.string.drillset_timing_fixed, 8, DrillTiming(pauseMode = DrillTiming.PauseMode.FIXED, fixedPauseMs = 8_000)),
)

/**
 * The hands-free player. Playback runs in [DrillPlayer] at process level with a media foreground service, so it keeps
 * going with the screen off and can be controlled from the notification, lock screen or a headset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillSetPlayerScreen(id: String) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retry by remember { mutableIntStateOf(0) }
    var set by remember(id) { mutableStateOf<DrillSet?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val player by DrillPlayer.state.collectAsStateWithLifecycle()
    // Reopening the set that is already playing keeps its timing; a new set starts with the default.
    var preset by remember(id) { mutableIntStateOf(1) }
    var repeat by remember(id) { mutableStateOf(true) }
    fun timing() = PRESETS[preset].timing.copy(repeat = repeat)
    LaunchedEffect(id, retry) {
        error = null
        runCatching {
            val s = graph.practice()?.drillSet(id)
            set = s
            if (s != null) {
                val current = DrillPlayer.state.value
                if (current.set?.id == s.id) {
                    PRESETS.indexOfFirst { it.timing.copy(repeat = current.timing.repeat) == current.timing }.takeIf { it >= 0 }?.let { preset = it }
                    repeat = current.timing.repeat
                }
                DrillPlayer.load(context, s, timing())
            }
        }.onFailure { error = it.readable() }
        loaded = true
    }
    val s = set
    when {
        error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { retry++ }, Modifier.padding(16.dp))
        !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        s == null -> PracticePackMissing(Modifier.padding(16.dp))
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JaText(s.summary.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("N${s.summary.jlpt}")
                Tag(s.summary.kind.label())
                if (s.summary.isAiGenerated) AiBadge()
            }
            if (s.summary.description.isNotBlank()) Text(s.summary.description, style = MaterialTheme.typography.bodyMedium)
            if (s.items.isEmpty()) {
                Text(stringResource(R.string.drillset_empty_set))
                return@Column
            }
            val mine = player.set?.id == s.id
            // Timing
            Text(stringResource(R.string.drillset_pause_length), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEachIndexed { i, p ->
                    val label = if (p.arg == null) stringResource(p.label) else stringResource(p.label, p.arg)
                    FilterChip(preset == i, { preset = i; if (mine) DrillPlayer.setTiming(timing()) }, { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(repeat, { repeat = it; if (mine) DrillPlayer.setTiming(timing()) })
                Text(stringResource(R.string.drillset_repeat), style = MaterialTheme.typography.bodyMedium)
            }
            if (!mine) {
                Button(onClick = { scope.launch { DrillPlayer.load(context, s, timing()) } }) { Text(stringResource(R.string.drillset_load)) }
                return@Column
            }
            // Now playing
            val total = s.items.size
            val itemIndex = player.itemIndex.coerceAtMost(total)
            val item = player.item
            val step = player.step
            val plan = player.plan
            if (plan != null && plan.totalMs > 0) {
                LinearProgressIndicator(
                    progress = { (1f - player.remainingMs.toFloat() / plan.totalMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.drillset_progress, (itemIndex + 1).coerceAtMost(total), total, ((player.remainingMs + 999) / 60_000).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        player.finished -> Text(stringResource(R.string.drillset_finished), style = MaterialTheme.typography.titleMedium)
                        item != null && step != null -> {
                            Text(stepLabel(step.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(item.promptEn, style = MaterialTheme.typography.titleMedium)
                            // The model answer is shown only once it has been played, so the pause is a real recall attempt.
                            val revealed = step.kind == DrillStepKind.ANSWER || step.kind == DrillStepKind.REPEAT_PAUSE
                            if (revealed) JaText(item.answerJa, style = MaterialTheme.typography.headlineSmall)
                            if (item.isAiGenerated) AiBadge()
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { DrillPlayer.previous() }) { Text(stringResource(R.string.drillset_previous)) }
                Button(onClick = { DrillPlayer.toggle(context) }) {
                    if (player.playing) Text(stringResource(R.string.drillset_pause_button))
                    else PlayLabel(stringResource(if (player.finished) R.string.drillset_play_again else R.string.drillset_play))
                }
                OutlinedButton(onClick = { DrillPlayer.next() }, enabled = !player.finished) { Text(stringResource(R.string.drillset_next)) }
            }
            Text(stringResource(R.string.drillset_background_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.drillset_clips, player.clips, total), style = MaterialTheme.typography.bodySmall)
            if (player.noEnglishVoice) Text(stringResource(R.string.drillset_no_english_voice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            SectionTitle(stringResource(R.string.drillset_items))
            s.items.forEachIndexed { i, it ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${i + 1}. ${it.promptEn}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (i == itemIndex && !player.finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    JaText(it.answerJa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun stepLabel(kind: DrillStepKind): String = stringResource(
    when (kind) {
        DrillStepKind.PROMPT -> R.string.drillset_step_prompt
        DrillStepKind.ANSWER_PAUSE -> R.string.drillset_step_answer_pause
        DrillStepKind.ANSWER -> R.string.drillset_step_answer
        DrillStepKind.REPEAT_PAUSE -> R.string.drillset_step_repeat
        DrillStepKind.GAP -> R.string.drillset_step_gap
    },
)
