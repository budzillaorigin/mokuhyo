package app.tsumugi.android.features

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
                Text("紡ぎ", style = MaterialTheme.typography.displayMedium.japanese())
                Text("Welcome to Tsumugi", style = MaterialTheme.typography.headlineSmall)
                Text("Kanji, vocabulary, grammar, reading and speaking in one offline app. Nothing needs an account or a subscription.")
                Text("What are you working towards?", style = MaterialTheme.typography.titleMedium)
                LearningGoal.entries.forEach { g ->
                    FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(g.label) }, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = { step = 1 }) { Text("Next") }
            }
            1 -> {
                Text("How much time a day?", style = MaterialTheme.typography.headlineSmall)
                Text("Today's plan is sized to this. You can change it any day.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TodayPlanner.BUDGET_OPTIONS.forEach { m -> FilterChip(selected = budget == m, onClick = { budget = m }, label = { Text("$m min") }) }
                }
                Button(onClick = { step = if (questions.isEmpty()) 3 else 2 }) { Text("Next") }
            }
            2 -> {
                val q = questions.getOrNull(qIndex)
                if (q == null) {
                    LaunchedEffect(Unit) { step = 3 }
                } else {
                    Text("Quick kanji check (${qIndex + 1}/${questions.size})", style = MaterialTheme.typography.titleMedium)
                    Text("Do you know this kanji's meaning and a reading?")
                    Text(q.item.display, fontSize = 96.sp, style = MaterialTheme.typography.displayLarge.japanese(), modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { answers[q] = true; qIndex++ }) { Text("I know it") }
                        OutlinedButton(onClick = { answers[q] = false; qIndex++ }) { Text("Not yet") }
                    }
                    TextButton(onClick = { answers.clear(); step = 3 }) { Text("Skip — start from level 1") }
                }
            }
            else -> {
                val level = if (answers.isEmpty()) 1 else graph.onboarding.suggestedLevel(answers)
                Text("You're set", style = MaterialTheme.typography.headlineSmall)
                Text("Kanji path starts at level $level. Earlier items stay available if you want them.")
                Text("Coming from WaniKani, Anki (NihongoShark), Bunpro or imiwa? Import your progress so it carries over.")
                Button(onClick = { finish(openImport = false) }) { Text("Start learning") }
                OutlinedButton(onClick = { finish(openImport = true) }) { Text("Import my progress first") }
            }
        }
    }
}
