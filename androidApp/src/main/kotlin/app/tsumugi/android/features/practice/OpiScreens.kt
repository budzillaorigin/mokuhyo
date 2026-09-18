package app.tsumugi.android.features.practice

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.japanese
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.opi.InterviewerLine
import app.tsumugi.exam.opi.OpiRating
import app.tsumugi.exam.opi.OpiSession
import kotlinx.coroutines.launch

const val EXAM_DISCLAIMER = "Unofficial practice; not affiliated with DLI, ACTFL or JLPT. Ratings are estimates."

class OpiViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    enum class Stage { START, INTERVIEW, RESULTS }

    var stage by mutableStateOf(Stage.START)
        private set
    var session by mutableStateOf<OpiSession?>(null)
        private set
    var current by mutableStateOf<InterviewerLine?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var missing by mutableStateOf(false)
        private set
    var rating by mutableStateOf<OpiRating?>(null)
        private set
    var saved by mutableStateOf(false)
        private set
    var aiUnavailable by mutableStateOf<String?>(null)
        private set
    val asked = mutableStateListOf<InterviewerLine>()

    init {
        viewModelScope.launch { aiUnavailable = graph.ai.unavailableReason() }
    }

    fun start(level: IlrLevel) {
        busy = true
        viewModelScope.launch {
            val s = graph.opi(level)
            session = s
            missing = s == null
            if (s != null) {
                stage = Stage.INTERVIEW
                advance(s)
            }
            busy = false
        }
    }

    fun answer(text: String) {
        val s = session ?: return
        if (busy || text.isBlank()) return
        busy = true
        viewModelScope.launch {
            s.answer(text)
            advance(s)
            busy = false
        }
    }

    fun end() {
        val s = session ?: return
        s.stop()
        busy = true
        viewModelScope.launch { finish(s) }
    }

    private suspend fun advance(s: OpiSession) {
        val line = s.next()
        if (line == null) finish(s) else {
            current = line
            asked += line
        }
    }

    private suspend fun finish(s: OpiSession) {
        busy = true
        stage = Stage.RESULTS
        val r = s.rate()
        rating = r
        if (!r.needsSelfRating) save(s, r)
        busy = false
    }

    fun selfRate(checked: Set<String>) {
        val s = session ?: return
        val r = s.selfRate(checked)
        rating = r
        viewModelScope.launch { save(s, r) }
    }

    private suspend fun save(s: OpiSession, r: OpiRating) {
        graph.exams().saveOpi(s.startedAt, s.transcript.map { it.first.name to it.second }, r)
        saved = true
    }
}

/** OPI-style practice interview (BRIEF §5.11): start → voice-first interview → rating and transcript. */
@Composable
fun OpiScreen(key: String, onOpenAiSettings: () -> Unit) {
    val vm = keyedViewModel(key) { OpiViewModel(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (vm.stage) {
            OpiViewModel.Stage.START -> OpiStart(vm, onOpenAiSettings)
            OpiViewModel.Stage.INTERVIEW -> OpiInterview(vm)
            OpiViewModel.Stage.RESULTS -> OpiResults(vm)
        }
    }
}

@Composable
private fun OpiStart(vm: OpiViewModel, onOpenAiSettings: () -> Unit) {
    var level by remember { mutableStateOf(IlrLevel.L1) }
    Text("OPI practice interview", style = MaterialTheme.typography.headlineSmall)
    Notice(EXAM_DISCLAIMER)
    Text(
        "An interviewer asks questions out loud: warm-up, level checks, probes, a role-play and a wind-down (15–30 minutes). " +
            "Answer by holding the mic, or type. The transcript stays hidden until the end.",
    )
    vm.aiUnavailable?.let {
        Notice(
            "No AI model: questions come from the scripted banks and you'll rate yourself with the ILR checklist at the end. $it",
            actionLabel = "Open AI settings", onAction = onOpenAiSettings,
        )
    }
    Text("Starting level (ILR)", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IlrLevel.lowerRange.forEach { l -> FilterChip(level == l, { level = l }, { Text(l.label) }) }
    }
    if (vm.missing) Notice(PRACTICE_PACK_MISSING)
    Button(onClick = { vm.start(level) }, enabled = !vm.busy) { Text("Start interview") }
    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

private fun OpiPhase.label() = when (this) {
    OpiPhase.WARMUP -> "Warm-up"
    OpiPhase.LEVEL_CHECK -> "Level check"
    OpiPhase.PROBE -> "Probe"
    OpiPhase.ROLEPLAY -> "Role-play"
    OpiPhase.WINDDOWN -> "Wind-down"
}

@Composable
private fun OpiInterview(vm: OpiViewModel) {
    val voices = rememberVoices()
    val input = rememberSpeechInput()
    val scope = rememberCoroutineScope()
    var showText by remember { mutableStateOf(false) }
    var ttsMissing by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val line = vm.current
    LaunchedEffect(line) {
        ttsMissing = !voices.available()
        line?.let { voices.say(it.japanese, "male") }
    }
    val phase = line?.phase ?: OpiPhase.WARMUP
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OpiPhase.entries.forEach { p ->
            FilterChip(selected = p == phase, onClick = {}, label = { Text(p.label()) }, enabled = p.ordinal <= phase.ordinal)
        }
    }
    Text("Question ${vm.asked.size}", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { line?.let { scope.launch { voices.say(it.japanese, "male") } } }) { Text("▶ Repeat question") }
        TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide text" else "Show text") }
    }
    if (ttsMissing) Text("No Japanese voice is installed, so the question is shown as text.", style = MaterialTheme.typography.bodySmall)
    if ((showText || ttsMissing) && line != null) {
        Text(line.japanese, style = MaterialTheme.typography.titleMedium.japanese())
        if (line.engine != null) AiBadge(line.engine)
    }
    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    SpeakOrType(input, enabled = !vm.busy && line != null, onSubmit = { text, _ -> vm.answer(text) })
    OutlinedButton(onClick = { confirmEnd = true }, enabled = !vm.busy) { Text("End interview") }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the interview?") },
            text = { Text("The rating will use what you've said so far.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; voices.stop(); vm.end() }) { Text("End") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun OpiResults(vm: OpiViewModel) {
    val rating = vm.rating
    val session = vm.session
    Text("Results", style = MaterialTheme.typography.headlineSmall)
    Notice(EXAM_DISCLAIMER)
    if (rating == null || vm.busy) {
        Text("Rating the interview…")
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    if (rating.needsSelfRating && !vm.saved && session != null) {
        SelfRating(session.checklist(), vm::selfRate)
    } else {
        RatingView(rating)
    }
    if (vm.saved) Text("Saved to your exam history.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Transcript", style = MaterialTheme.typography.titleMedium)
    val english = vm.asked.associate { it.japanese to it.english }
    session?.transcript?.forEach { (speaker, text) ->
        Column {
            Text(
                (if (speaker == Speaker.PARTNER) "Interviewer: " else "You: ") + text,
                style = MaterialTheme.typography.bodyMedium.japanese(),
                color = if (speaker == Speaker.PARTNER) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
            english[text]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            vm.asked.firstOrNull { it.japanese == text && it.engine != null }?.let { AiBadge(it.engine) }
        }
    }
}

@Composable
fun RatingView(rating: OpiRating) {
    Text(
        rating.ilr?.let { "Estimated ILR ${it.label}" + (rating.actfl?.let { a -> " (≈ ACTFL $a)" } ?: "") } ?: "Not rated",
        style = MaterialTheme.typography.titleLarge,
    )
    if (rating.engine != null) AiBadge(rating.engine)
    listOf("Functions" to rating.functions, "Accuracy" to rating.accuracy, "Vocabulary" to rating.vocabulary, "Fluency" to rating.fluency)
        .forEach { (name, v) -> v?.let { Text("$name: $it / 5") } }
    if (rating.rationale.isNotBlank()) Text(rating.rationale, style = MaterialTheme.typography.bodyMedium)
    if (rating.strengths.isNotEmpty()) Text("Strengths: " + rating.strengths.joinToString("; "), style = MaterialTheme.typography.bodySmall)
    if (rating.nextSteps.isNotEmpty()) Text("Next steps: " + rating.nextSteps.joinToString("; "), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SelfRating(checklist: List<Pair<IlrLevel, String>>, onRate: (Set<String>) -> Unit) {
    val checked = remember { mutableStateListOf<String>() }
    Text("Rate yourself", style = MaterialTheme.typography.titleMedium)
    Text(
        "No AI rating is available. Tick every statement that describes how you spoke. Your level is the highest one " +
            "where you ticked everything (and everything below).",
        style = MaterialTheme.typography.bodySmall,
    )
    if (checklist.isEmpty()) Text("The installed practice pack has no self-rating checklist.")
    checklist.groupBy { it.first }.forEach { (level, items) ->
        Text("ILR ${level.label}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        items.forEach { (_, statement) ->
            Row(
                Modifier.fillMaxWidth().clickable { if (statement in checked) checked.remove(statement) else checked.add(statement) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(statement in checked, { on -> if (on) checked.add(statement) else checked.remove(statement) })
                Text(statement, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Button(onClick = { onRate(checked.toSet()) }) { Text("Save my rating") }
}
