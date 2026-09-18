package app.tsumugi.android.features.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tsumugi.android.app.Route
import kotlinx.coroutines.launch

/** Practice tab (BRIEF §6): Speak, Listen, Write, Exams. */
@Composable
fun PracticeHubScreen(push: (Route) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var writeMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionTitle("Speak")
        HubRow("Role-play scenarios", "Shop, station, doctor, interviews… with or without an AI model") { push(Route.Scenarios) }
        HubRow("Speaking session", "25-minute Pomodoro of short speaking and listening games") { push(Route.Pomodoro()) }
        HubRow("OPI practice interview", "Voice-first mock interview with an ILR estimate") { push(Route.Opi()) }
        ListItem(
            headlineContent = { Text("Free talk") },
            supportingContent = { Text("Adaptive tutor conversation — not built yet") },
        )

        SectionTitle("Listen")
        HubRow("Dialogues", "Two-voice dialogues: listen, gap-fill, order, questions") { push(Route.Dialogues) }
        HubRow("Media player", "Your own video/audio with Japanese + English subtitles") { push(Route.Media) }
        HubRow("Minimal pairs", "Ear training: vowel length, っ, voicing, ん, pitch") { push(Route.MinimalPairs) }

        SectionTitle("Write")
        HubRow("Kanji writing", "Stroke-order practice with kanji you already know") {
            scope.launch {
                val writing = graph.writing()
                val set = writing?.practiceSet(5).orEmpty()
                writeMessage = when {
                    writing == null -> "Writing practice needs the dictionary pack (it holds the KanjiVG stroke data), which isn't installed in this build."
                    set.isEmpty() -> "No kanji to practise yet: writing practice uses kanji you've taken to Guru on the kanji path."
                    else -> null
                }
                if (set.isNotEmpty()) push(Route.WritingPractice(set))
            }
        }
        writeMessage?.let { Notice(it, actionLabel = "Open the kanji path", onAction = { push(Route.PathLevels) }) }
        HubRow("Draw to search", "Handwrite a kanji to look it up") { push(Route.Handwriting) }

        SectionTitle("Exams")
        HubRow("JLPT & DLPT simulators", "Timed mocks, drills, history and review") { push(Route.Exams) }
    }
}

@Composable
private fun HubRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}
