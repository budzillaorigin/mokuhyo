package app.tsumugi.android.features.practice

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.SpeechResult
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.speaking.ConversationRecord
import app.tsumugi.speaking.ConversationTurn
import app.tsumugi.speaking.ErrorPattern
import app.tsumugi.speaking.ErrorType
import app.tsumugi.speaking.FreeTalkSession
import app.tsumugi.speaking.TurnFeedback
import app.tsumugi.speaking.WeeklyPatterns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Free talk (G-02, D-101): an open conversation with the model at the learner's rolling level. */
class FreeTalkViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    var session by mutableStateOf<FreeTalkSession?>(null)
        private set
    var turns by mutableStateOf<List<ConversationTurn>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var unavailable by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf<ConversationRecord?>(null)
        private set
    var started by mutableStateOf(false)
        private set
    val feedback = mutableStateMapOf<Int, TurnFeedback?>()
    val recordings = mutableStateMapOf<Int, SpeechResult>()

    fun start(topic: String?) {
        if (busy) return
        loadError = null
        viewModelScope.launch {
            try {
                val s = graph.freeTalk(topic?.trim()?.ifEmpty { null })
                session = s
                started = true
                turn { s.start() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.readable()
            }
        }
    }

    fun send(text: String, recording: SpeechResult?) {
        val s = session ?: return
        if (text.isBlank() || busy || unavailable != null) return
        recording?.let { recordings[turns.size] = it }
        turns = turns + ConversationTurn(ConversationTurn.LEARNER, text.trim())
        viewModelScope.launch { turn { s.reply(text) } }
    }

    fun retry() {
        val s = session ?: return
        if (busy) return
        viewModelScope.launch { turn { s.retry() } }
    }

    private suspend fun turn(block: suspend () -> ConversationTurn?) {
        val s = session ?: return
        busy = true
        try {
            block()
            unavailable = s.unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable = e.readable()
        } finally {
            turns = s.transcript
            busy = false
        }
    }

    fun requestFeedback(index: Int) {
        val s = session ?: return
        if (feedback.containsKey(index)) return
        feedback[index] = null
        viewModelScope.launch {
            feedback[index] = try {
                s.feedback(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TurnFeedback(null, null, null, e.readable())
            }
            turns = s.transcript
        }
    }

    /** Stores the conversation (its level estimate and errors feed the Me patterns card). */
    fun finish(onSaved: () -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            busy = true
            try {
                saved = s.finish()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unavailable = e.readable()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun FreeTalkScreen(key: String, onOpenAiSettings: () -> Unit, onDone: () -> Unit) {
    val vm = keyedViewModel(key) { FreeTalkViewModel(it) }
    val voices = rememberVoices()
    val input = rememberSpeechInput()
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf("") }
    var showEnglish by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    if (!vm.started) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.freetalk_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.freetalk_topic)) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.japanese())
            Button(onClick = { vm.start(topic) }, enabled = !vm.busy) { Text(stringResource(R.string.freetalk_start)) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.loadError?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { vm.start(topic) }) }
        }
        return
    }
    LaunchedEffect(vm.turns.size) {
        val last = vm.turns.lastOrNull() ?: return@LaunchedEffect
        listState.animateScrollToItem(vm.turns.size + 1)
        if (!last.isLearner) voices.say(last.ja, "female")
    }
    val session = vm.session
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.freetalk_header, session?.level.orEmpty()) + (session?.currentTopic?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                )
                AiBadge()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = showEnglish, role = Role.Switch, onValueChange = { showEnglish = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.roleplay_show_english), Modifier.weight(1f))
                    Switch(showEnglish, onCheckedChange = null)
                }
            }
        }
        itemsIndexed(vm.turns) { index, turn ->
            if (!turn.isLearner) {
                Card(Modifier.fillMaxWidth(0.9f)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            JaText(turn.ja, style = MaterialTheme.typography.bodyLarge)
                            if (showEnglish && turn.en.isNotBlank()) Text(turn.en, style = MaterialTheme.typography.bodySmall)
                        }
                        val replay = stringResource(R.string.roleplay_replay)
                        IconButton(onClick = { scope.launch { voices.say(turn.ja, "female") } }, modifier = Modifier.semantics { contentDescription = replay }) { Text("▶") }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Card(
                        Modifier.fillMaxWidth(0.9f).clickable(onClickLabel = stringResource(R.string.roleplay_show_feedback)) {
                            selected = if (selected == index) null else index
                            vm.requestFeedback(index)
                        },
                        colors = CardDefaults.cardColors(containerColor = if (selected == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer),
                    ) { JaText(turn.ja, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                if (selected == index) {
                    TurnFeedbackCard(turn.ja, vm.feedback.containsKey(index), vm.feedback[index], onOpenAiSettings) { target ->
                        PronunciationPanel(target, voices, input, initial = vm.recordings[index].takeIf { target == turn.ja })
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.unavailable?.let { reason ->
                    if (!vm.busy) {
                        Notice(stringResource(R.string.freetalk_unavailable, reason), actionLabel = stringResource(R.string.action_retry), onAction = vm::retry)
                        TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.ai_open_settings)) }
                    }
                }
                SpeakOrType(input, enabled = !vm.busy && vm.unavailable == null, onSubmit = { text, rec -> vm.send(text, rec) })
                val saved = vm.saved
                if (saved == null) {
                    OutlinedButton(onClick = { vm.finish {} }, enabled = !vm.busy && vm.turns.any { it.isLearner }) { Text(stringResource(R.string.freetalk_end)) }
                } else {
                    Text(
                        saved.levelEstimate?.let { stringResource(R.string.freetalk_saved_level, it.jlptLabel, it.ilr) } ?: stringResource(R.string.freetalk_saved),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(stringResource(R.string.patterns_estimate_note), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
                }
                Text(stringResource(R.string.roleplay_tap_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Corrections for one learner line (labeled AI), with "say it again". */
@Composable
fun TurnFeedbackCard(original: String, loaded: Boolean, feedback: TurnFeedback?, onOpenAiSettings: () -> Unit, panel: @Composable (String) -> Unit) {
    var sayAgain by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!loaded || feedback == null) {
                Text(stringResource(R.string.roleplay_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            feedback.unavailable?.let {
                Text(stringResource(R.string.roleplay_no_corrections, it), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.ai_open_settings)) }
            }
            feedback.correction?.let { c ->
                Text(
                    when {
                        c.isUnsure -> stringResource(R.string.roleplay_unsure)
                        c.isCorrect -> stringResource(R.string.roleplay_looks_right)
                        else -> stringResource(R.string.roleplay_correction)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!c.isCorrect && c.corrected.isNotBlank()) Text(ja(diff(original, c.corrected)), style = MaterialTheme.typography.bodyLarge.japanese())
                c.edits.forEach { e -> Text("${e.original} → ${e.replacement}: ${e.reason}", style = MaterialTheme.typography.bodySmall.japanese()) }
                if (c.explanation.isNotBlank()) Text(c.explanation, style = MaterialTheme.typography.bodySmall)
            }
            feedback.natural?.let { n ->
                HorizontalDivider()
                Text(stringResource(R.string.roleplay_natural), style = MaterialTheme.typography.titleSmall)
                JaText(n.rewrite, style = MaterialTheme.typography.bodyLarge)
                if (n.notes.isNotBlank()) Text(n.notes, style = MaterialTheme.typography.bodySmall)
            }
            if (feedback.engine != null) AiBadge(feedback.engine)
            val target = feedback.natural?.rewrite ?: feedback.correction?.corrected?.takeIf { it.isNotBlank() } ?: original
            OutlinedButton(onClick = { sayAgain = !sayAgain }) { Text(stringResource(if (sayAgain) R.string.action_hide else R.string.roleplay_say_again)) }
            if (sayAgain) panel(target)
        }
    }
}

@Composable
fun errorTypeLabel(type: ErrorType): String = stringResource(errorTypeRes(type))

private fun errorTypeRes(type: ErrorType): Int =
    when (type) {
        ErrorType.PARTICLE -> R.string.error_type_particle
        ErrorType.CONJUGATION -> R.string.error_type_conjugation
        ErrorType.TENSE -> R.string.error_type_tense
        ErrorType.POLITENESS -> R.string.error_type_politeness
        ErrorType.SPELLING -> R.string.error_type_spelling
        ErrorType.WORD_CHOICE -> R.string.error_type_word_choice
        ErrorType.OTHER -> R.string.error_type_other
    }

/** Me → this week's speaking patterns (G-02, D-102): the rolling level estimate and recurring errors. */
@Composable
fun WeeklyPatternsCard(patterns: WeeklyPatterns?, recurring: List<ErrorPattern>, onFreeTalk: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.patterns_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (patterns == null || patterns.isEmpty) {
                Text(stringResource(R.string.patterns_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.patterns_summary, patterns.conversations, patterns.learnerTurns, patterns.minutes), style = MaterialTheme.typography.bodyMedium)
                patterns.level?.let { rolling ->
                    val change = patterns.levelChange
                    Text(
                        stringResource(R.string.patterns_level, rolling.level.jlptLabel, rolling.level.ilr, rolling.level.score) +
                            (change?.takeIf { it != 0 }?.let { " (" + (if (it > 0) "+$it" else "$it") + ")" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(stringResource(R.string.patterns_estimate_note), style = MaterialTheme.typography.bodySmall)
                }
                if (patterns.errors.isEmpty()) {
                    Text(stringResource(R.string.patterns_no_errors), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.patterns_errors), style = MaterialTheme.typography.titleSmall)
                    patterns.errors.take(5).forEach { e ->
                        val trend = when {
                            e.trend > 0 -> "↑"
                            e.trend < 0 -> "↓"
                            else -> "→"
                        }
                        Text("${errorTypeLabel(e.type)}: ${e.count} $trend", style = MaterialTheme.typography.bodyMedium)
                        e.examples.take(2).forEach { x -> Text("  ${x.original} → ${x.replacement}", style = MaterialTheme.typography.bodySmall.japanese()) }
                    }
                }
            }
            if (recurring.isNotEmpty()) {
                Text(stringResource(R.string.patterns_recurring), style = MaterialTheme.typography.titleSmall)
                val parts = recurring.take(3).map { "${errorTypeLabel(it.type)} ${it.count}" }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onFreeTalk) { Text(stringResource(R.string.practice_free_talk)) }
        }
    }
}
