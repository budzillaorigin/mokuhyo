package app.tsumugi.android.features.today

import app.tsumugi.android.ui.RecomputeBanner
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.localized
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.study.StudyOverviewViewModel
import app.tsumugi.android.ui.japanese
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.study.TodayBlockKind
import app.tsumugi.study.TodayPlan
import app.tsumugi.study.TodayPlanner
import kotlinx.coroutines.launch

/** The structured daily path (BRIEF §5.6): the day's blocks in order, sized to the chosen budget. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(onLessons: () -> Unit, onReviews: () -> Unit, onGrammar: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    val vm: StudyOverviewViewModel = viewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var plan by remember { mutableStateOf<TodayPlan?>(null) }
    LaunchedEffect(Unit) {
        vm.refresh()
        plan = graph.today()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JaText("今日", style = MaterialTheme.typography.displaySmall)
        RecomputeBanner()
        stats?.let { s -> Text(stringResource(R.string.today_streak, s.streak.current, s.reviewsToday), style = MaterialTheme.typography.titleMedium) }
        plan?.let { p ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.today_budget), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                TodayPlanner.BUDGET_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = p.budgetMinutes == minutes,
                        onClick = {
                            scope.launch {
                                graph.settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, minutes.toString())
                                plan = graph.today()
                            }
                        },
                        label = { Text(stringResource(R.string.minutes_short, minutes)) },
                    )
                }
            }
            Text(stringResource(R.string.today_phase, p.phase.localized(), p.plannedMinutes), style = MaterialTheme.typography.bodyMedium)
            p.blocks.forEachIndexed { i, block ->
                val action = when (block.kind) {
                    TodayBlockKind.REVIEWS -> onReviews
                    TodayBlockKind.LESSONS -> onLessons
                    TodayBlockKind.GRAMMAR -> onGrammar
                    else -> null
                }
                Card(Modifier.fillMaxWidth().clickable(enabled = action != null && block.count > 0) { action?.invoke() }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val doneLabel = stringResource(R.string.today_block_done)
                        Text(
                            if (block.done) "✓" else "${i + 1}",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(end = 16.dp).semantics { if (block.done) contentDescription = doneLabel },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(block.title, style = MaterialTheme.typography.titleMedium)
                            Text(block.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        if (block.minutes > 0 && !block.done) Text(stringResource(R.string.minutes_short, block.minutes), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val c = p.challenge
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.today_challenge, c.title), style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { (c.progress.toFloat() / c.goal).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                    Text(if (c.complete) stringResource(R.string.today_challenge_done) else "${c.progress} / ${c.goal}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        status?.let { st ->
            Text(stringResource(R.string.today_level, st.currentLevel, (st.levelProgress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
