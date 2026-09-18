package app.tsumugi.android.features.practice

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionTitle(stringResource(R.string.practice_speak))
        HubRow(stringResource(R.string.practice_roleplay), stringResource(R.string.practice_roleplay_sub)) { push(Route.Scenarios) }
        HubRow(stringResource(R.string.title_speaking_session), stringResource(R.string.practice_session_sub)) { push(Route.Pomodoro()) }
        HubRow(stringResource(R.string.practice_opi), stringResource(R.string.practice_opi_sub)) { push(Route.Opi()) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.practice_free_talk)) },
            supportingContent = { Text(stringResource(R.string.practice_free_talk_sub)) },
        )

        SectionTitle(stringResource(R.string.practice_listen))
        HubRow(stringResource(R.string.title_dialogues), stringResource(R.string.practice_dialogues_sub)) { push(Route.Dialogues) }
        HubRow(stringResource(R.string.title_media), stringResource(R.string.practice_media_sub)) { push(Route.Media) }
        HubRow(stringResource(R.string.title_minimal_pairs), stringResource(R.string.practice_pairs_sub)) { push(Route.MinimalPairs) }

        SectionTitle(stringResource(R.string.practice_write))
        HubRow(stringResource(R.string.practice_kanji_writing), stringResource(R.string.practice_kanji_writing_sub)) {
            scope.launch {
                val writing = graph.writing()
                val set = writing?.practiceSet(5).orEmpty()
                writeMessage = when {
                    writing == null -> context.getString(R.string.practice_writing_no_dict)
                    set.isEmpty() -> context.getString(R.string.practice_writing_none)
                    else -> null
                }
                if (set.isNotEmpty()) push(Route.WritingPractice(set))
            }
        }
        writeMessage?.let { Notice(it, actionLabel = stringResource(R.string.practice_open_path), onAction = { push(Route.PathLevels) }) }
        HubRow(stringResource(R.string.title_draw_search), stringResource(R.string.learn_draw_sub)) { push(Route.Handwriting) }

        SectionTitle(stringResource(R.string.title_exams))
        HubRow(stringResource(R.string.practice_exams), stringResource(R.string.practice_exams_sub)) { push(Route.Exams) }
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
