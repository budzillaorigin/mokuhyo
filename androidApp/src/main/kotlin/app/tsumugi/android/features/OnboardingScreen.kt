package app.tsumugi.android.features

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.localized
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.japanese
import app.tsumugi.study.LearningGoal
import app.tsumugi.study.PlacementQuestion
import app.tsumugi.study.TodayPlanner
import kotlinx.coroutines.launch

/** First-run setup: goal, daily budget, a quick kanji check for the starting level. Skippable throughout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onDone: (openImport: Boolean) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var goal by remember { mutableStateOf(LearningGoal.GENERAL) }
    var budget by remember { mutableIntStateOf(TodayPlanner.DEFAULT_BUDGET) }
    var questions by remember { mutableStateOf<List<PlacementQuestion>>(emptyList()) }
    val answers = remember { mutableStateMapOf<PlacementQuestion, Boolean>() }
    var qIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { questions = graph.onboarding.placementQuestions(System.currentTimeMillis()) }
    fun finish(openImport: Boolean) = scope.launch {
        val level = if (answers.isEmpty()) 1 else graph.onboarding.suggestedLevel(answers)
        graph.onboarding.finish(goal, budget, level)
        onDone(openImport)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (step) {
            0 -> {
                JaText("紡ぎ", style = MaterialTheme.typography.displayMedium)
                Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.onboarding_intro))
                Text(stringResource(R.string.onboarding_goal_question), style = MaterialTheme.typography.titleMedium)
                LearningGoal.entries.forEach { g ->
                    FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(g.localized()) }, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = { step = 1 }) { Text(stringResource(R.string.action_next)) }
            }
            1 -> {
                Text(stringResource(R.string.onboarding_time_question), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.onboarding_time_hint))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TodayPlanner.BUDGET_OPTIONS.forEach { m -> FilterChip(selected = budget == m, onClick = { budget = m }, label = { Text(stringResource(R.string.minutes_short, m)) }) }
                }
                Button(onClick = { step = if (questions.isEmpty()) 3 else 2 }) { Text(stringResource(R.string.action_next)) }
            }
            2 -> {
                val q = questions.getOrNull(qIndex)
                if (q == null) {
                    LaunchedEffect(Unit) { step = 3 }
                } else {
                    Text(stringResource(R.string.onboarding_check_title, qIndex + 1, questions.size), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.onboarding_check_question))
                    JaText(q.item.display, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.displayLarge, fontSize = 96.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { answers[q] = true; qIndex++ }) { Text(stringResource(R.string.onboarding_know_it)) }
                        OutlinedButton(onClick = { answers[q] = false; qIndex++ }) { Text(stringResource(R.string.onboarding_not_yet)) }
                    }
                    TextButton(onClick = { answers.clear(); step = 3 }) { Text(stringResource(R.string.onboarding_skip)) }
                }
            }
            else -> {
                val level = if (answers.isEmpty()) 1 else graph.onboarding.suggestedLevel(answers)
                Text(stringResource(R.string.onboarding_done_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.onboarding_done_level, level))
                Text(stringResource(R.string.onboarding_done_import))
                Button(onClick = { finish(openImport = false) }) { Text(stringResource(R.string.onboarding_start)) }
                OutlinedButton(onClick = { finish(openImport = true) }) { Text(stringResource(R.string.onboarding_import_first)) }
            }
        }
    }
}
