package app.tsumugi.android.features.practice

import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.ja
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.SpeechInput
import app.tsumugi.android.platform.Voices
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.japanese
import app.tsumugi.speaking.ConversationLine
import app.tsumugi.speaking.RoleplaySession
import app.tsumugi.study.activities.Activity
import app.tsumugi.study.activities.PomodoroSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

class PomodoroViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    var session by mutableStateOf<PomodoroSession?>(null)
        private set
    var missing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Bumped whenever the (non-observable) session changes, so Compose re-reads it. */
    var version by mutableIntStateOf(0)
        private set

    fun start(jlpt: Int) {
        loading = true
        viewModelScope.launch {
            session = graph.pomodoro(jlpt)
            missing = session == null
            loading = false
            version++
        }
    }

    fun complete(correct: Boolean?, score: Int? = null) {
        session?.complete(correct, score)
        version++
    }

    fun skip() {
        session?.skip()
        version++
    }

    fun tick() {
        version++
    }
}

/** A 25-minute speaking session with a queue of mini-activities, then a 5-minute break (BRIEF §5.10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PomodoroScreen(key: String) {
    val vm = keyedViewModel(key) { PomodoroViewModel(it) }
    val voices = rememberVoices()
    val input = rememberSpeechInput()
    var level by remember { mutableIntStateOf(4) }
    val session = vm.session

    LaunchedEffect(session) {
        while (session != null) {
            delay(1_000)
            vm.tick()
        }
    }
    @Suppress("UNUSED_VARIABLE") val v = vm.version // read so ticks recompose

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            vm.missing -> PracticePackMissing()
            session == null -> {
                Text(stringResource(R.string.title_speaking_session), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.pomodoro_intro))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (5 downTo 1).forEach { l -> FilterChip(level == l, { level = l }, { Text("N$l") }) }
                }
                Button(onClick = { vm.start(level) }, enabled = !vm.loading) { Text(stringResource(R.string.action_start)) }
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            session.finished -> BreakView(session)
            else -> {
                Row {
                    Text(stringResource(R.string.pomodoro_work, clock(session.remaining)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text("${session.index + 1} / ${session.activities.size}", style = MaterialTheme.typography.titleMedium)
                }
                LinearProgressIndicator(
                    progress = { 1f - (session.remaining / session.work).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                val activity = session.current ?: return@Column
                Text(activity.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                ActivityView(activity, voices, input, onDone = vm::complete)
                TextButton(onClick = { voices.stop(); vm.skip() }) { Text(stringResource(R.string.action_skip)) }
            }
        }
    }
}

private fun clock(d: Duration): String {
    val s = d.inWholeSeconds
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun BreakView(session: PomodoroSession) {
    val results = session.completed
    Text(stringResource(if (session.onBreak) R.string.pomodoro_break else R.string.pomodoro_finished), style = MaterialTheme.typography.headlineSmall)
    if (session.onBreak) Text(stringResource(R.string.pomodoro_break_left, clock(session.breakRemaining)), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.pomodoro_completed, results.size) + (session.accuracy?.let { stringResource(R.string.pomodoro_accuracy, percent(it)) } ?: ""))
    results.forEach { r ->
        Text(
            "• ${r.activity.title}" + when (r.correct) {
                true -> " ✓"
                false -> " ✗"
                null -> ""
            } + (r.score?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ActivityView(activity: Activity, voices: Voices, input: SpeechInput, onDone: (Boolean?, Int?) -> Unit) {
    val scope = rememberCoroutineScope()
    when (activity) {
        is Activity.WhatDoYouHear -> {
            val word = if (activity.playA) activity.pair.a else activity.pair.b
            LaunchedEffect(activity) { voices.say(word.text) }
            OutlinedButton(onClick = { scope.launch { voices.say(word.text) } }) { PlayLabel(stringResource(R.string.play_again)) }
            ChoiceList(listOf(activity.pair.a.text, activity.pair.b.text), activity.answer) { correct -> onDone(correct, null) }
        }
        is Activity.PickAWord -> {
            LaunchedEffect(activity) { voices.say(activity.line.japanese, activity.speaker?.voice) }
            OutlinedButton(onClick = { scope.launch { voices.say(activity.line.japanese, activity.speaker?.voice) } }) { PlayLabel(stringResource(R.string.play_again)) }
            Text(activity.prompt, style = MaterialTheme.typography.titleMedium.japanese())
            ChoiceList(activity.choices, activity.gap.text) { correct -> onDone(correct, null) }
        }
        is Activity.SentenceRepeat -> {
            var score by remember(activity) { mutableStateOf<Int?>(null) }
            LaunchedEffect(activity) { voices.say(activity.line.japanese, activity.speaker?.voice) }
            Text(activity.line.english, style = MaterialTheme.typography.bodySmall)
            PronunciationPanel(activity.line.japanese, voices, input, onReport = { score = it.composite })
            Button(onClick = { onDone(null, score) }) { Text(stringResource(R.string.action_next)) }
        }
        is Activity.StoryTime -> {
            val chosen = remember(activity) { mutableStateMapOf<Int, Int>() }
            val d = activity.dialogue
            LaunchedEffect(activity) { voices.sayAll(d.lines.map { it.japanese to d.speaker(it.speaker)?.voice }) }
            OutlinedButton(onClick = { scope.launch { voices.sayAll(d.lines.map { it.japanese to d.speaker(it.speaker)?.voice }) } }) { PlayLabel(stringResource(R.string.play_again)) }
            d.questions.forEachIndexed { qi, q ->
                Text(q.question, style = MaterialTheme.typography.titleSmall.japanese())
                q.choices.forEachIndexed { ci, c ->
                    val picked = chosen[qi]
                    Card(
                        Modifier.fillMaxWidth().clickable(enabled = picked == null) { chosen[qi] = ci },
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                picked == null -> MaterialTheme.colorScheme.surfaceVariant
                                ci == q.answer -> Color(0xFFC8E6C9)
                                ci == picked -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) { Text(c, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.japanese()) }
                }
            }
            if (chosen.size == d.questions.size) {
                val right = d.questions.indices.count { chosen[it] == d.questions[it].answer }
                Text(stringResource(R.string.listen_score, right, d.questions.size))
                Button(onClick = { onDone(right * 2 >= d.questions.size, right * 100 / d.questions.size.coerceAtLeast(1)) }) { Text(stringResource(R.string.action_next)) }
            }
        }
        is Activity.RoleplayTurn -> RoleplayTurnView(activity, voices, input) { onDone(null, null) }
    }
}

@Composable
private fun ChoiceList(choices: List<String>, answer: String, onAnswered: (Boolean) -> Unit) {
    var picked by remember(choices) { mutableStateOf<String?>(null) }
    choices.forEach { c ->
        Card(
            Modifier.fillMaxWidth().clickable(enabled = picked == null) { picked = c },
            colors = CardDefaults.cardColors(
                containerColor = when {
                    picked == null -> MaterialTheme.colorScheme.surfaceVariant
                    c == answer -> Color(0xFFC8E6C9)
                    c == picked -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) { Text(c, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium.japanese()) }
    }
    picked?.let { p -> Button(onClick = { onAnswered(p == answer) }) { Text(stringResource(R.string.action_next)) } }
}

/** One exchange of a scenario: the partner opens, the learner answers once, the partner replies. */
@Composable
private fun RoleplayTurnView(activity: Activity.RoleplayTurn, voices: Voices, input: SpeechInput, onDone: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var session by remember(activity) { mutableStateOf<RoleplaySession?>(null) }
    var lines by remember(activity) { mutableStateOf<List<ConversationLine>>(emptyList()) }
    var busy by remember(activity) { mutableStateOf(true) }
    LaunchedEffect(activity) {
        val s = graph.roleplay(activity.scenario.id)
        session = s
        s?.start()?.let { voices.say(it.japanese, "female") }
        lines = s?.transcript.orEmpty()
        busy = false
    }
    Text(buildAnnotatedString { append(ja(activity.scenario.titleJa)); append(" · "); append(stringResource(R.string.pomodoro_you_are, activity.scenario.learnerRole)) }, style = MaterialTheme.typography.bodyMedium.japanese())
    lines.forEach { l ->
        Text(
            (if (l.speaker == Speaker.PARTNER) "▷ " else "▶ ") + l.japanese,
            style = MaterialTheme.typography.bodyLarge.japanese(),
            color = if (l.speaker == Speaker.PARTNER) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
        if (l.aiGenerated) AiBadge(l.engine)
    }
    lines.lastOrNull { it.speaker == Speaker.PARTNER }?.hint?.takeIf { it.isNotBlank() }?.let {
        Text(buildAnnotatedString { append(stringResource(R.string.roleplay_hint_prefix)); append(ja(it)) }, style = MaterialTheme.typography.bodySmall.japanese())
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val answered = lines.count { it.speaker == Speaker.LEARNER } > 0
    if (!answered) {
        SpeakOrType(input, enabled = !busy && session != null, onSubmit = { text, _ ->
            val s = session ?: return@SpeakOrType
            busy = true
            scope.launch {
                lines = lines + ConversationLine(Speaker.LEARNER, text)
                val reply = s.reply(text)
                lines = s.transcript
                busy = false
                reply?.let { voices.say(it.japanese, "female") }
            }
        })
    } else if (!busy) {
        Button(onClick = onDone) { Text(stringResource(R.string.action_next)) }
    }
}
