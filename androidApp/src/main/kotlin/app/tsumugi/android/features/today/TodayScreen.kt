package app.tsumugi.android.features.today

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
        Text("今日", style = MaterialTheme.typography.displaySmall.japanese())
        stats?.let { s -> Text("🔥 ${s.streak.current}-day streak · ${s.reviewsToday} answers today", style = MaterialTheme.typography.titleMedium) }
        plan?.let { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Daily budget", style = MaterialTheme.typography.labelLarge)
                TodayPlanner.BUDGET_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = p.budgetMinutes == minutes,
                        onClick = {
                            scope.launch {
                                graph.settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, minutes.toString())
                                plan = graph.today()
                            }
                        },
                        label = { Text("$minutes m") },
                    )
                }
            }
            Text("${p.phase.label} phase · about ${p.plannedMinutes} min planned", style = MaterialTheme.typography.bodyMedium)
            p.blocks.forEachIndexed { i, block ->
                val action = when (block.kind) {
                    TodayBlockKind.REVIEWS -> onReviews
                    TodayBlockKind.LESSONS -> onLessons
                    TodayBlockKind.GRAMMAR -> onGrammar
                    else -> null
                }
                Card(Modifier.fillMaxWidth().clickable(enabled = action != null && block.count > 0) { action?.invoke() }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (block.done) "✓" else "${i + 1}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(block.title, style = MaterialTheme.typography.titleMedium)
                            Text(block.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        if (block.minutes > 0 && !block.done) Text("${block.minutes} min", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val c = p.challenge
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This week: ${c.title}", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { (c.progress.toFloat() / c.goal).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                    Text(if (c.complete) "Done — nice work!" else "${c.progress} / ${c.goal}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        status?.let { st ->
            Text("Level ${st.currentLevel} · ${(st.levelProgress * 100).toInt()}% of kanji at Guru", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
