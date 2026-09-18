package app.tsumugi.android.features.practice

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.SpeechResult
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.practice.Scenario
import app.tsumugi.speaking.ConversationLine
import app.tsumugi.speaking.RoleplaySession
import app.tsumugi.speaking.TurnFeedback
import kotlinx.coroutines.launch

/** Scenario role-plays, filterable by JLPT level (BRIEF §5.10). */
@Composable
fun ScenarioListScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    var level by remember { mutableStateOf<Int?>(null) }
    var scenarios by remember { mutableStateOf<List<Scenario>?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(level) {
        val practice = graph.practice()
        missing = practice == null
        scenarios = practice?.scenarios(level).orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        JlptFilter(level, { level = it }, Modifier.padding(vertical = 8.dp))
        when {
            missing -> Notice(PRACTICE_PACK_MISSING)
            scenarios == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            scenarios!!.isEmpty() -> Text("No scenarios at this level in the installed pack.", Modifier.padding(8.dp))
            else -> LazyColumn {
                items(scenarios!!, key = { it.id }) { s ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(s.id) },
                        headlineContent = { Text("${s.titleJa}  ${s.titleEn}", style = MaterialTheme.typography.titleSmall.japanese()) },
                        supportingContent = { Text("${s.setting} · you: ${s.learnerRole}") },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Tag("N${s.jlpt}")
                                if (s.isAiGenerated) AiBadge()
                            }
                        },
                    )
                }
            }
        }
    }
}

class RoleplayViewModel(app: Application, private val scenarioId: String) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    var session by mutableStateOf<RoleplaySession?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var lines by mutableStateOf<List<ConversationLine>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var unavailable by mutableStateOf<String?>(null)
        private set
    val feedback = mutableStateMapOf<Int, TurnFeedback?>()
    val recordings = mutableStateMapOf<Int, SpeechResult>()

    init {
        viewModelScope.launch {
            unavailable = graph.ai.unavailableReason()
            val s = graph.roleplay(scenarioId)
            session = s
            if (s != null) {
                busy = true
                s.start()
                lines = s.transcript
                busy = false
            }
            loading = false
        }
    }

    fun send(text: String, recording: SpeechResult?) {
        val s = session ?: return
        if (text.isBlank() || busy) return
        busy = true
        viewModelScope.launch {
            val learnerIndex = lines.size
            recording?.let { recordings[learnerIndex] = it }
            lines = lines + ConversationLine(Speaker.LEARNER, text.trim())
            s.reply(text)
            lines = s.transcript
            busy = false
        }
    }

    fun requestFeedback(index: Int) {
        val s = session ?: return
        if (feedback.containsKey(index)) return
        feedback[index] = null // loading
        viewModelScope.launch { feedback[index] = s.feedback(lines[index].japanese) }
    }
}

/**
 * A role-play conversation: the partner speaks (TTS) with an optional English line; the learner holds the mic or
 * types. Tapping a learner line shows corrections, a natural version and the pronunciation panel ("say it again").
 */
@Composable
fun RoleplayScreen(scenarioId: String, key: String, onOpenAiSettings: () -> Unit) {
    val vm = keyedViewModel(key) { RoleplayViewModel(it, scenarioId) }
    val voices = rememberVoices()
    val input = rememberSpeechInput()
    val scope = rememberCoroutineScope()
    var showEnglish by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Speak each new partner line as it arrives.
    LaunchedEffect(vm.lines.size) {
        val last = vm.lines.lastOrNull() ?: return@LaunchedEffect
        hint = null
        listState.animateScrollToItem(vm.lines.size + 1)
        if (last.speaker == Speaker.PARTNER) voices.say(last.japanese, "female")
    }

    val session = vm.session
    if (vm.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        return
    }
    if (session == null) {
        Notice(PRACTICE_PACK_MISSING, Modifier.padding(16.dp))
        return
    }
    val scenario = session.scenario
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(scenario.titleJa, style = MaterialTheme.typography.titleLarge.japanese())
                Text("${scenario.titleEn} · you are ${scenario.learnerRole}, talking to ${scenario.partnerRole}", style = MaterialTheme.typography.bodyMedium)
                if (scenario.goals.isNotEmpty()) Text("Goals: " + scenario.goals.joinToString("; "), style = MaterialTheme.typography.bodySmall)
                if (scenario.isAiGenerated) AiBadge()
                vm.unavailable?.let {
                    Notice(
                        "Scripted mode: the partner follows a written script and the hint shows a sample answer. " +
                            "Corrections and the natural version need an AI model. $it",
                        actionLabel = "Open AI settings", onAction = onOpenAiSettings,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show English", Modifier.weight(1f))
                    Switch(showEnglish, { showEnglish = it })
                }
            }
        }
        itemsIndexed(vm.lines) { index, line ->
            if (line.speaker == Speaker.PARTNER) {
                PartnerLine(line, showEnglish, onReplay = { scope.launch { voices.say(line.japanese, "female") } })
            } else {
                LearnerLine(line, selected == index) {
                    selected = if (selected == index) null else index
                    vm.requestFeedback(index)
                }
                if (selected == index) {
                    FeedbackView(
                        original = line.japanese,
                        loaded = vm.feedback.containsKey(index),
                        feedback = vm.feedback[index],
                        onOpenAiSettings = onOpenAiSettings,
                    ) { target ->
                        PronunciationPanel(target, voices, input, initial = vm.recordings[index].takeIf { target == line.japanese })
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.goalReached) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text("🎉 Goal reached! Keep talking or pick another scenario.", Modifier.padding(12.dp))
                    }
                }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                hint?.let { Text("Hint: $it", style = MaterialTheme.typography.bodyMedium.japanese()) }
                OutlinedButton(
                    onClick = { hint = vm.lines.lastOrNull { it.speaker == Speaker.PARTNER }?.hint?.ifBlank { "No hint for this line." } },
                ) { Text("Hint") }
                SpeakOrType(input, enabled = !vm.busy, onSubmit = { text, rec -> vm.send(text, rec) })
                Text(
                    "Tap one of your lines for corrections, a natural version and pronunciation feedback. Recordings stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PartnerLine(line: ConversationLine, showEnglish: Boolean, onReplay: () -> Unit) {
    Card(Modifier.fillMaxWidth(0.9f)) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.japanese, style = MaterialTheme.typography.bodyLarge.japanese())
                if (showEnglish && line.english.isNotBlank()) Text(line.english, style = MaterialTheme.typography.bodySmall)
                if (line.aiGenerated) AiBadge(line.engine)
            }
            IconButton(onClick = onReplay, modifier = Modifier.semantics { contentDescription = "Play this line again" }) { Text("▶") }
        }
    }
}

@Composable
private fun LearnerLine(line: ConversationLine, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(
            Modifier.fillMaxWidth(0.9f).clickable(onClickLabel = "Show feedback", onClick = onClick),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Text(line.japanese, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge.japanese())
        }
    }
}

/** Corrections (diff), the natural version, and "say it again" with the pronunciation panel. */
@Composable
private fun FeedbackView(
    original: String,
    loaded: Boolean,
    feedback: TurnFeedback?,
    onOpenAiSettings: () -> Unit,
    panel: @Composable (String) -> Unit,
) {
    var sayAgain by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!loaded || feedback == null) {
                Text("Checking your sentence…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            feedback.unavailable?.let {
                Text("No corrections: $it", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenAiSettings) { Text("Open AI settings") }
            }
            feedback.correction?.let { c ->
                Text(
                    when {
                        c.isUnsure -> "Not sure — the model's confidence is low."
                        c.isCorrect -> "Looks right."
                        else -> "Correction"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!c.isCorrect && c.corrected.isNotBlank()) {
                    Text(diff(original, c.corrected), style = MaterialTheme.typography.bodyLarge.japanese())
                }
                c.edits.forEach { e ->
                    Text("${e.original} → ${e.replacement}: ${e.reason}", style = MaterialTheme.typography.bodySmall.japanese())
                }
                if (c.explanation.isNotBlank()) Text(c.explanation, style = MaterialTheme.typography.bodySmall)
            }
            feedback.natural?.let { n ->
                HorizontalDivider()
                Text("Natural version", style = MaterialTheme.typography.titleSmall)
                Text(n.rewrite, style = MaterialTheme.typography.bodyLarge.japanese())
                if (n.notes.isNotBlank()) Text(n.notes, style = MaterialTheme.typography.bodySmall)
            }
            if (feedback.engine != null) AiBadge(feedback.engine)
            val target = feedback.natural?.rewrite ?: feedback.correction?.corrected?.takeIf { it.isNotBlank() } ?: original
            OutlinedButton(onClick = { sayAgain = !sayAgain }) { Text(if (sayAgain) "Hide" else "Say it again") }
            if (sayAgain) panel(target)
        }
    }
}

/** Character diff for display: removed text struck through in red, added text in green. */
internal fun diff(from: String, to: String): AnnotatedString {
    val n = from.length
    val m = to.length
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        lcs[i][j] = if (from[i] == to[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val removed = SpanStyle(color = Color(0xFFC62828), textDecoration = TextDecoration.LineThrough)
    val added = SpanStyle(color = Color(0xFF2E7D32))
    return buildAnnotatedString {
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && from[i] == to[j] -> { append(from[i]); i++; j++ }
                j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]) -> { withStyle(added) { append(to[j]) }; j++ }
                else -> { withStyle(removed) { append(from[i]) }; i++ }
            }
        }
    }
}
