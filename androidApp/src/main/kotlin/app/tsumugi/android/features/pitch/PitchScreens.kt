package app.tsumugi.android.features.pitch

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.MicButton
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.ReportView
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.ShadowingView
import app.tsumugi.android.features.practice.keyedViewModel
import app.tsumugi.android.features.practice.percent
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.ClipPlayer
import app.tsumugi.android.platform.FileClipPlayer
import app.tsumugi.android.platform.SpeechResult
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.audio.PitchTestItem
import app.tsumugi.pitch.AccentPattern
import app.tsumugi.pitch.PitchDrill
import app.tsumugi.pitch.PitchFeedback
import app.tsumugi.pitch.PitchProductionResult
import app.tsumugi.pitch.PitchQuestion
import app.tsumugi.pitch.PitchQuestionMode
import app.tsumugi.pitch.PitchStats
import app.tsumugi.pitch.PitchTally
import app.tsumugi.pitch.PitchTestService
import app.tsumugi.pitch.PitchTestSession
import app.tsumugi.recordings.ReferenceClip
import app.tsumugi.speech.PitchVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The pitch-accent perception test (BRIEF_V2 §6.7; D-284/D-285 shared, D-290 Android). Everything that decides
 * something — question choice, adaptive level, marks, stats, production scoring — is PitchTestService/PitchTestSession
 * in shared. These screens play the pack clip, show the options, and hand back the tap and its response time.
 * Rule 20: only pack clips are played; there is no TTS fallback, since TTS can't guarantee the accent.
 */

private val RIGHT = Color(0xFF2E7D32)

@Composable
private fun AccentPattern.description(): String = stringResource(
    when (this) {
        AccentPattern.HEIBAN -> R.string.pt_pattern_heiban
        AccentPattern.ATAMADAKA -> R.string.pt_pattern_atamadaka
        AccentPattern.NAKADAKA -> R.string.pt_pattern_nakadaka
        AccentPattern.ODAKA -> R.string.pt_pattern_odaka
    },
)

@Composable
private fun levelDescription(level: Int): String = stringResource(
    when (level) {
        1 -> R.string.pt_level_1
        2 -> R.string.pt_level_2
        3 -> R.string.pt_level_3
        4 -> R.string.pt_level_4
        else -> R.string.pt_level_5
    },
)

@Composable
private fun PitchDrill.title(): String = stringResource(
    when (this) {
        PitchDrill.PATTERN_TEST -> R.string.pt_drill_pattern
        PitchDrill.DOWNSTEP_TEST -> R.string.pt_drill_downstep
        PitchDrill.WORD_PAIRS -> R.string.pt_drill_pairs
        PitchDrill.MINIMAL_PAIRS -> R.string.pt_drill_minimal
    },
)

@Composable
private fun PitchDrill.subtitle(): String = stringResource(
    when (this) {
        PitchDrill.PATTERN_TEST -> R.string.pt_drill_pattern_sub
        PitchDrill.DOWNSTEP_TEST -> R.string.pt_drill_downstep_sub
        PitchDrill.WORD_PAIRS -> R.string.pt_drill_pairs_sub
        PitchDrill.MINIMAL_PAIRS -> R.string.pt_drill_minimal_sub
    },
)

@Composable
private fun PitchQuestionMode.title(): String = stringResource(
    when (this) {
        PitchQuestionMode.PATTERN -> R.string.pt_drill_pattern
        PitchQuestionMode.DOWNSTEP -> R.string.pt_drill_downstep
        PitchQuestionMode.WORD_PAIR -> R.string.pt_drill_pairs
    },
)

/** The pack clip for [key] (never TTS), or null when it isn't on disk. */
private suspend fun packClip(context: android.content.Context, key: String): ClipPlayer? {
    val graph = (context.applicationContext as TsumugiApplication).graph
    val path = withContext(Dispatchers.IO) { graph.audio.clip(key) } ?: return null
    return FileClipPlayer(context, path.toString(), ReferenceClip.pack(key))
}

// --- Hub -----------------------------------------------------------------------------------------------------------

private sealed interface HubState {
    data object Loading : HubState
    data object Missing : HubState
    data class Failed(val message: String) : HubState
    data class Ready(val drills: List<PitchDrill>, val stats: PitchStats) : HubState
}

/** The module's hub: where the next test starts, the drill types (minimal pairs included), and stats. */
@Composable
fun PitchTestScreen(onStart: (PitchDrill) -> Unit, onStats: () -> Unit, onMinimalPairs: () -> Unit) {
    val graph = rememberGraph()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<HubState>(HubState.Loading) }
    LaunchedEffect(attempt) {
        state = HubState.Loading
        state = runCatching {
            val service = graph.pitchTest() ?: return@runCatching HubState.Missing
            HubState.Ready(service.drills(), service.stats())
        }.getOrElse { HubState.Failed(it.readable()) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = state) {
            HubState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            HubState.Missing -> Notice(stringResource(R.string.pt_missing))
            is HubState.Failed -> ErrorState(stringResource(R.string.pt_load_failed, s.message), onRetry = { attempt++ })
            is HubState.Ready -> {
                Text(stringResource(R.string.pt_intro), style = MaterialTheme.typography.bodyMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.pt_level, s.stats.level), style = MaterialTheme.typography.titleMedium)
                        Text(levelDescription(s.stats.level), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.pt_start_level, s.stats.level), style = MaterialTheme.typography.bodySmall)
                        s.stats.total.accuracy?.let {
                            Text(stringResource(R.string.pt_stats_total, s.stats.total.attempts, percent(it)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // The adaptive mix is the default test: PATTERN_TEST also stands in as its route key (see below).
                SectionTitle(stringResource(R.string.pt_drills))
                DrillRow(stringResource(R.string.pt_drill_mixed), stringResource(R.string.pt_drill_mixed_sub)) { onStart(MIXED) }
                s.drills.forEach { d ->
                    DrillRow(d.title(), d.subtitle()) { if (d == PitchDrill.MINIMAL_PAIRS) onMinimalPairs() else onStart(d) }
                }
                OutlinedButton(onClick = onStats) { Text(stringResource(R.string.pt_stats)) }
            }
        }
    }
}

/**
 * The route carries a [PitchDrill]; the adaptive mix has no drill of its own, so it travels as [MINIMAL_PAIRS] (which
 * never starts a session here: that drill opens the minimal-pair screen instead) and is mapped back to null.
 */
private val MIXED = PitchDrill.MINIMAL_PAIRS

@Composable
private fun DrillRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium.japanese()) },
    )
}

// --- Session -------------------------------------------------------------------------------------------------------

class PitchSessionViewModel(app: Application, private val drill: PitchDrill?) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    var service by mutableStateOf<PitchTestService?>(null)
        private set
    var session by mutableStateOf<PitchTestSession?>(null)
        private set
    var question by mutableStateOf<PitchQuestion?>(null)
        private set
    var feedback by mutableStateOf<PitchFeedback?>(null)
        private set
    var missing by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var answerError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** When the current question was shown (response time). */
    private var shownAt = 0L

    init { load() }

    fun load() {
        loadError = null
        viewModelScope.launch {
            runCatching {
                val s = graph.pitchTest()
                service = s
                missing = s == null
                if (s != null) {
                    val started = s.start(drill)
                    session = started
                    next()
                }
            }.onFailure { loadError = it.readable() }
        }
    }

    fun next() {
        val s = session ?: return
        feedback = null
        answerError = null
        question = s.next()
        shownAt = System.currentTimeMillis()
    }

    fun answer(optionId: String) {
        val s = session ?: return
        if (busy || feedback != null) return
        busy = true
        answerError = null
        val ms = System.currentTimeMillis() - shownAt
        viewModelScope.launch {
            runCatching { s.answer(optionId, ms) }
                .onSuccess { feedback = it }
                .onFailure { answerError = it.readable() }
            busy = false
        }
    }
}

/** A test run: hear the clip, answer, see the marks and the level move; "Say it" links to production. */
@Composable
fun PitchSessionScreen(drill: PitchDrill, key: String, onStats: () -> Unit, onMinimalPairs: () -> Unit) {
    val real: PitchDrill? = if (drill == MIXED) null else drill
    val vm = keyedViewModel(key) { PitchSessionViewModel(it, real) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = vm.session
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            vm.loadError != null -> ErrorState(stringResource(R.string.pt_load_failed, vm.loadError!!), onRetry = vm::load)
            vm.missing -> Notice(stringResource(R.string.pt_missing))
            session == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pt_level, session.level), style = MaterialTheme.typography.titleMedium)
                        Text(levelDescription(session.level), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onStats) { Text(stringResource(R.string.pt_stats)) }
                }
                if (session.answered > 0) {
                    Text(stringResource(R.string.pt_session_score, session.correct, session.answered, session.streak), style = MaterialTheme.typography.labelLarge)
                }
                val q = vm.question
                if (q == null) {
                    Notice(stringResource(R.string.pt_no_questions))
                    return@Column
                }
                var clip by remember(q) { mutableStateOf<ClipPlayer?>(null) }
                var clipMissing by remember(q) { mutableStateOf(false) }
                LaunchedEffect(q) {
                    clip = packClip(context, q.clipKey)
                    clipMissing = clip == null
                    clip?.let { runCatching { it.play() } }
                }
                Text(
                    stringResource(
                        when (q.mode) {
                            PitchQuestionMode.PATTERN -> R.string.pt_q_pattern
                            PitchQuestionMode.DOWNSTEP -> R.string.pt_q_downstep
                            PitchQuestionMode.WORD_PAIR -> R.string.pt_q_word
                        },
                    ),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                // Pattern and downstep questions show the kana (the task is the accent, not the word); which-word hides it.
                if (q.mode != PitchQuestionMode.WORD_PAIR) JaText(q.item.reading + "が", style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = { clip?.let { c -> scope.launch { runCatching { c.play() } } } }, enabled = clip != null) {
                    PlayLabel(stringResource(if (vm.feedback == null && session.answered == 0) R.string.pt_play else R.string.play_again))
                }
                if (clipMissing) Text(stringResource(R.string.pt_no_clip), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OptionButtons(q, vm.feedback, enabled = !vm.busy, onPick = vm::answer)
                // The answer wasn't stored: the options stay open, so tapping one again retries.
                vm.answerError?.let { Text(stringResource(R.string.pt_answer_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                vm.feedback?.let { fb ->
                    FeedbackCard(fb, previousLevel = q.level)
                    SayItPanel(vm.service, q.item, clip)
                    Button(onClick = vm::next, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_next)) }
                }
            }
        }
    }
}

@Composable
private fun OptionButtons(q: PitchQuestion, feedback: PitchFeedback?, enabled: Boolean, onPick: (String) -> Unit) {
    q.options.forEach { o ->
        val colors = when {
            feedback == null -> ButtonDefaults.buttonColors()
            o.id == q.expected -> ButtonDefaults.buttonColors(containerColor = RIGHT)
            o.id == feedback.answer -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = { if (feedback == null) onPick(o.id) },
            enabled = enabled || feedback != null,
            colors = colors,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                JaText(o.label, style = MaterialTheme.typography.titleMedium)
                val detail = when (q.mode) {
                    PitchQuestionMode.PATTERN -> AccentPattern.of(o.id)?.description()
                    // Downstep options draw the marks (は↑しが); which-word options show the gloss only after answering.
                    PitchQuestionMode.DOWNSTEP -> o.detail
                    PitchQuestionMode.WORD_PAIR -> o.detail.takeIf { feedback != null }
                }
                detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall.japanese()) }
            }
        }
    }
}

@Composable
private fun FeedbackCard(fb: PitchFeedback, previousLevel: Int) {
    val item = fb.question.item
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = if (fb.correct) Color(0xFFC8E6C9) else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (fb.correct) R.string.pt_correct else R.string.pt_wrong),
                style = MaterialTheme.typography.titleLarge,
                color = if (fb.correct) RIGHT else MaterialTheme.colorScheme.error,
            )
            JaText("${item.text}（${item.reading}）", style = MaterialTheme.typography.titleMedium)
            if (item.gloss.isNotBlank()) Text(item.gloss, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.pt_heard_marks, fb.marks), style = MaterialTheme.typography.bodyLarge.japanese())
            if (!fb.correct) fb.chosenMarks?.let { Text(stringResource(R.string.pt_chosen_marks, it), style = MaterialTheme.typography.bodyLarge.japanese()) }
            when {
                fb.nextLevel > previousLevel -> Text(stringResource(R.string.pt_level_up, fb.nextLevel), style = MaterialTheme.typography.labelLarge)
                fb.nextLevel < previousLevel -> Text(stringResource(R.string.pt_level_down, fb.nextLevel), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Perception → production (§6.7): the learner says the word + が; `PitchTestService.production` scores the pitch against
 * the item's known accent (the pronunciation panel's analyzer) and compares with the pack clip when it decodes.
 */
@Composable
private fun SayItPanel(service: PitchTestService?, item: PitchTestItem, clip: ClipPlayer?) {
    var open by remember(item.id) { mutableStateOf(false) }
    if (!open) {
        OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.pt_say_it)) }
        return
    }
    val input = rememberSpeechInput()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember(item.id) { mutableStateOf(false) }
    var result by remember(item.id) { mutableStateOf<PitchProductionResult?>(null) }
    var message by remember(item.id) { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pt_say_it), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pt_say_it_hint), style = MaterialTheme.typography.bodySmall)
            JaText(item.spoken.ifBlank { item.reading + "が" }, style = MaterialTheme.typography.headlineSmall)
            MicButton(
                input,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !busy,
                onError = { message = it },
                onResult = { r: SpeechResult ->
                    message = r.error
                    if (r.audio.isEmpty) {
                        message = listOfNotNull(r.error, context.getString(R.string.pron_no_audio)).joinToString("\n")
                        return@MicButton
                    }
                    if (service == null) {
                        message = context.getString(R.string.pt_say_unavailable)
                        return@MicButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            val reference = clip?.let { runCatching { it.pcm() }.getOrNull() }
                            result = service.production(item, r.audio.samples, r.transcript.ifBlank { null }, reference)
                            if (result == null) message = context.getString(R.string.pt_say_unavailable)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = context.getString(R.string.pron_failed, e.readable())
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result?.let { p ->
                Text(stringResource(R.string.pt_say_expected, p.expectedMarks), style = MaterialTheme.typography.bodyLarge.japanese())
                Text(
                    stringResource(R.string.pt_say_observed, p.observedMarks.ifBlank { "?" }),
                    style = MaterialTheme.typography.bodyLarge.japanese(),
                )
                Text(
                    stringResource(
                        when {
                            p.matched -> R.string.pt_say_match
                            p.verdict == PitchVerdict.UNCLEAR || p.verdict == PitchVerdict.UNKNOWN_ACCENT -> R.string.pt_say_unclear
                            else -> R.string.pt_say_mismatch
                        },
                    ),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (p.matched) RIGHT else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
                p.shadowing?.let { ShadowingView(it) }
                ReportView(p.report)
            }
        }
    }
}

// --- Stats ---------------------------------------------------------------------------------------------------------

/** Results per pattern, per mora length, per question type, and the most confused pattern pairs. */
@Composable
fun PitchStatsScreen() {
    val graph = rememberGraph()
    var attempt by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf<PitchStats?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            val s = graph.pitchTest()
            missing = s == null
            stats = s?.stats()
        }.onFailure { error = it.readable() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val s = stats
        when {
            error != null -> ErrorState(stringResource(R.string.pt_load_failed, error!!), onRetry = { attempt++ })
            missing -> Notice(stringResource(R.string.pt_missing))
            s == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            s.total.attempts == 0 -> Notice(stringResource(R.string.pt_stats_empty))
            else -> {
                Text(stringResource(R.string.pt_stats_total, s.total.attempts, percent(s.total.accuracy ?: 0.0)), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pt_start_level, s.level), style = MaterialTheme.typography.bodySmall)

                SectionTitle(stringResource(R.string.pt_by_pattern))
                AccentPattern.entries.forEach { p ->
                    s.byPattern[p]?.let { TallyRow("${p.ja} · ${p.description()}", it) }
                }
                SectionTitle(stringResource(R.string.pt_by_length))
                s.byMoraCount.forEach { (n, t) -> TallyRow(stringResource(R.string.pt_morae, n), t) }
                SectionTitle(stringResource(R.string.pt_by_mode))
                PitchQuestionMode.entries.forEach { m -> s.byMode[m]?.let { TallyRow(m.title(), it) } }

                val pairs = s.pairs.filter { it.attempts > 0 }
                if (pairs.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.pt_pairs))
                    Text(stringResource(R.string.pt_pairs_hint), style = MaterialTheme.typography.bodySmall)
                    pairs.forEach { pair ->
                        val rate = pair.confusionRate ?: 0.0
                        val label = "${pair.a.ja} ↔ ${pair.b.ja}"
                        val detail = stringResource(R.string.pt_pair_row, pair.confusions, pair.attempts, percent(rate))
                        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: $detail" }, verticalAlignment = Alignment.CenterVertically) {
                            JaText(label, Modifier.widthIn(min = 110.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                            Column(Modifier.weight(1f)) {
                                LinearProgressIndicator(progress = { rate.toFloat() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                                Text(detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TallyRow(label: String, t: PitchTally) {
    val acc = t.accuracy ?: 0.0
    val detail = stringResource(R.string.pt_tally, t.correct, t.attempts, percent(acc))
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: $detail" }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 140.dp, max = 200.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium.japanese())
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(progress = { acc.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
