package app.tsumugi.android.features.today

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.app.Route
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.study.StudyOverviewViewModel
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.RecomputeBanner
import app.tsumugi.android.ui.localized
import app.tsumugi.android.ui.readable
import app.tsumugi.android.widget.TodayWidget
import app.tsumugi.l10n.Labels
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.study.FocusTimer
import app.tsumugi.study.ImmersionSource
import app.tsumugi.study.TodayBlock
import app.tsumugi.study.TodayBlockKind
import app.tsumugi.study.TodayLaunch
import app.tsumugi.study.TodayPlan
import app.tsumugi.study.TodayPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The screen a Today block opens (G-01): every block routes by its [TodayLaunch]. */
fun TodayLaunch.route(kind: TodayBlockKind): Route = when (this) {
    is TodayLaunch.Reviews -> Route.Reviews(limit)
    is TodayLaunch.Lessons -> Route.Lessons
    is TodayLaunch.Kana -> Route.Kana
    is TodayLaunch.Grammar -> Route.GrammarLessons
    is TodayLaunch.Immersion -> if (target.source == ImmersionSource.READER) Route.Read(target.id) else Route.DialoguePlayer(target.id)
    is TodayLaunch.Shadowing -> Route.Shadowing(sentences)
    is TodayLaunch.Speaking -> Route.Roleplay(scenarioId)
    is TodayLaunch.Writing -> Route.WritingPractice(kanji, kind)
}

/** Blocks the planner can't see finishing on its own; the learner (or the block's screen) marks them done. */
private val MANUAL_BLOCKS = setOf(TodayBlockKind.IMMERSION, TodayBlockKind.SHADOWING, TodayBlockKind.SPEAKING, TodayBlockKind.WRITING)

private fun TodayLaunch.aiGenerated(): Boolean = when (this) {
    is TodayLaunch.Speaking -> aiGenerated
    is TodayLaunch.Immersion -> target.aiGenerated
    is TodayLaunch.Shadowing -> sentences.any { it.aiGenerated }
    else -> false
}

/** The structured daily path (BRIEF §5.6, G-01): the day's blocks in order, sized to the chosen budget. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(push: (Route) -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    val vm: StudyOverviewViewModel = viewModel()
    val timer: FocusTimerViewModel = viewModel()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var plan by remember { mutableStateOf<TodayPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var timerFor by remember { mutableStateOf<TodayBlock?>(null) }
    suspend fun load() {
        try {
            plan = graph.today()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        }
    }
    LaunchedEffect(Unit) {
        vm.refresh()
        load()
        TodayWidget.refresh(context)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JaText("今日", style = MaterialTheme.typography.displaySmall)
        RecomputeBanner()
        stats?.let { s ->
            Text(stringResource(R.string.today_streak, s.streak.current, s.reviewsToday), style = MaterialTheme.typography.titleMedium)
            if (s.streak.frozenToday) Text(stringResource(R.string.today_frozen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { load() } }) }
        plan?.let { p ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.today_budget), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                TodayPlanner.BUDGET_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = p.budgetMinutes == minutes,
                        onClick = {
                            scope.launch {
                                graph.settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, minutes.toString())
                                load()
                            }
                        },
                        label = { Text(stringResource(R.string.minutes_short, minutes)) },
                    )
                }
            }
            Text(stringResource(R.string.today_phase, p.phase.localized(), p.plannedMinutes), style = MaterialTheme.typography.bodyMedium)
            p.blocks.forEachIndexed { i, block ->
                BlockCard(
                    index = i,
                    block = block,
                    onOpen = { block.launch?.let { push(it.route(block.kind)) } },
                    onTimer = { timerFor = block },
                    onMarkDone = {
                        scope.launch {
                            graph.markTodayBlockDone(block.kind)
                            load()
                        }
                    },
                    timerRunning = timer.timer?.block == block.kind,
                )
            }
            val c = p.challenge
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.today_challenge, c.title), style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { (c.progress.toFloat() / c.goal.coerceAtLeast(1)).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                    Text(if (c.complete) stringResource(R.string.today_challenge_done) else "${c.progress} / ${c.goal}", style = MaterialTheme.typography.bodySmall)
                }
            }
            timerFor?.let { block ->
                AlertDialog(
                    onDismissRequest = { timerFor = null },
                    title = { Text(stringResource(R.string.timer_title, Labels.block(block.kind))) },
                    text = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            p.timerOptions.forEach { m ->
                                FilterChip(false, { timer.start(block.kind, m); timerFor = null }, { Text(stringResource(R.string.minutes_short, m)) })
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { timerFor = null }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
        if (plan == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun BlockCard(index: Int, block: TodayBlock, onOpen: () -> Unit, onTimer: () -> Unit, onMarkDone: () -> Unit, timerRunning: Boolean) {
    val launch = block.launch
    val openable = launch != null && !block.done
    Card(Modifier.fillMaxWidth().clickable(enabled = openable, onClickLabel = stringResource(R.string.today_open_block), onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val doneLabel = stringResource(R.string.today_block_done)
                Text(
                    if (block.done) "✓" else "${index + 1}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 16.dp).semantics { if (block.done) contentDescription = doneLabel },
                )
                Column(Modifier.weight(1f)) {
                    Text(block.title, style = MaterialTheme.typography.titleMedium)
                    Text(block.detail, style = MaterialTheme.typography.bodySmall)
                    if (block.optional) Text(stringResource(R.string.today_optional), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (block.minutes > 0 && !block.done) Text(stringResource(R.string.minutes_short, block.minutes), style = MaterialTheme.typography.labelMedium)
            }
            if (launch?.aiGenerated() == true) AiBadge()
            if (!block.done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (openable) OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.today_start)) }
                    TextButton(onClick = onTimer, enabled = !timerRunning) { Text(stringResource(R.string.timer_start)) }
                    if (block.kind in MANUAL_BLOCKS && launch != null) TextButton(onClick = onMarkDone) { Text(stringResource(R.string.today_mark_done)) }
                }
            }
        }
    }
}

/**
 * The focus timer that can wrap any Today block (D-100 `FocusTimer.forBlock`). Activity-scoped, so it keeps running
 * while the block's screen is open; [FocusTimerBanner] shows it above every screen.
 */
class FocusTimerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    var timer by mutableStateOf<FocusTimer?>(null)
        private set
    /** Ticks once a second so the banner recomposes. */
    var tick by mutableLongStateOf(0L)
        private set
    private var job: Job? = null

    fun start(kind: TodayBlockKind, minutes: Int) {
        timer = FocusTimer.forBlock(kind, minutes)
        job?.cancel()
        job = viewModelScope.launch {
            while (true) {
                delay(1_000)
                tick++
                if (timer?.finished != false) break
            }
        }
    }

    fun stop() {
        job?.cancel()
        timer = null
    }

    /** Ends the timer and records the block as finished (`markTodayBlockDone`). */
    fun finishBlock() {
        val kind = timer?.block
        stop()
        if (kind != null) viewModelScope.launch { runCatching { graph.markTodayBlockDone(kind) } }
    }
}

private fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

/** The running focus timer, shown under the top bar on every screen. */
@Composable
fun FocusTimerBanner() {
    val vm: FocusTimerViewModel = viewModel()
    val t = vm.timer ?: return
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = t.block?.let { Labels.block(it) }.orEmpty()
            val text = when {
                t.finished -> stringResource(R.string.timer_finished, label)
                t.onBreak -> stringResource(R.string.timer_break, clock(t.breakRemaining.inWholeSeconds))
                else -> stringResource(R.string.timer_running, label, clock(t.remaining.inWholeSeconds))
            }
            Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { t.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.block != null) TextButton(onClick = vm::finishBlock) { Text(stringResource(R.string.today_mark_done)) }
                TextButton(onClick = vm::stop) { Text(stringResource(R.string.timer_stop)) }
            }
        }
    }
}
