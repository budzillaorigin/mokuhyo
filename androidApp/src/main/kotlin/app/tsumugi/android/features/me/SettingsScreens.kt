package app.tsumugi.android.features.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.settings.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var batch by remember { mutableFloatStateOf(SettingsRepository.DEFAULT_LESSON_BATCH.toFloat()) }
    var retention by remember { mutableFloatStateOf(0.9f) }
    LaunchedEffect(Unit) {
        batch = graph.settings.lessonBatchSize().toFloat()
        retention = graph.settings.desiredRetention().toFloat()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lessons per batch: ${batch.toInt()}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = batch, onValueChange = { batch = it }, valueRange = 3f..15f, steps = 11,
            onValueChangeFinished = { scope.launch { graph.settings.setLessonBatchSize(batch.toInt()) } },
        )
        Text("Desired retention: ${(retention * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Text(
            "The share of reviews you aim to get right. Higher means shorter intervals and more reviews.",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = retention, onValueChange = { retention = it }, valueRange = 0.8f..0.97f,
            onValueChangeFinished = {
                scope.launch {
                    graph.settings.setDesiredRetention(retention.toDouble())
                    graph.reloadScheduler()
                }
            },
        )
    }
}

/** Renders docs/LICENSES.md, bundled into the APK by the bundlePacks task. */
@Composable
fun LicensesScreen() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = runCatching { context.assets.open("LICENSES.md").bufferedReader().readText() }.getOrDefault("Licenses file missing from this build.")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        text.lines().forEach { line ->
            when {
                line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.headlineSmall)
                line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                line.startsWith("|") && line.contains("---") -> Unit
                line.startsWith("|") -> Text(line.trim('|').split("|").joinToString(" · ") { it.trim() }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                line.isNotBlank() -> Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
