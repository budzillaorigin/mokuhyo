package app.tsumugi.android.features.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.features.study.StudyOverviewViewModel
import app.tsumugi.android.ui.japanese

/** Phase 2 Today: lessons and reviews at a glance. The full daily planner arrives with Phase 3. */
@Composable
fun TodayScreen(onLessons: () -> Unit, onReviews: () -> Unit) {
    val vm: StudyOverviewViewModel = viewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("今日", style = MaterialTheme.typography.displaySmall.japanese())
        stats?.let { s ->
            Text("🔥 ${s.streak.current}-day streak · ${s.reviewsToday} answers today", style = MaterialTheme.typography.titleMedium)
        }
        status?.let { st ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Level ${st.currentLevel} of ${st.maxLevel}", style = MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(progress = { st.levelProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("${(st.levelProgress * 100).toInt()}% of this level's kanji at Guru (90% to level up)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onLessons, enabled = st.availableLessons > 0, modifier = Modifier.weight(1f)) { Text("Lessons  ${st.availableLessons}") }
                OutlinedButton(onClick = onReviews, enabled = st.dueReviews > 0, modifier = Modifier.weight(1f)) { Text("Reviews  ${st.dueReviews}") }
            }
        }
        stats?.let { s ->
            Text("Next 7 days", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            s.forecast.forEach { d -> Text("${d.date.dayOfWeek.name.take(3)} ${d.date}: ${d.count} reviews", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
