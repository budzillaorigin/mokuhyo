package app.tsumugi.android.features.tracks

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.SpeakOrType
import app.tsumugi.android.features.practice.keyedViewModel
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.practice.Register
import app.tsumugi.tracks.DrillResult
import app.tsumugi.tracks.DrillType
import app.tsumugi.tracks.EmailDrill
import app.tsumugi.tracks.EmailSegment
import app.tsumugi.tracks.FadeLevel
import app.tsumugi.tracks.FillInDrill
import app.tsumugi.tracks.KeigoDrill
import app.tsumugi.tracks.KeigoForm
import app.tsumugi.tracks.MeaningDrill
import app.tsumugi.tracks.PerformDrill
import app.tsumugi.tracks.PerformanceSession
import app.tsumugi.tracks.SynonymDrill
import app.tsumugi.tracks.TrackDrill
import app.tsumugi.tracks.UsageDrill
import kotlinx.coroutines.launch

private val CorrectGreen = Color(0xFF2E7D32)

/** Runs the drills of one [type] of a track one by one, with feedback after each and a score at the end (BRIEF_V2 §6.5). */
@Composable
fun TrackDrillsScreen(trackId: String, type: String) {
    val graph = rememberGraph()
    val drillType = DrillType.of(type)
    var drills by remember(trackId, type) { mutableStateOf<List<TrackDrill>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(trackId, type, reload) {
        error = null
        runCatching {
            val repo = graph.trackRepository()
            missing = repo == null
            repo?.drills(trackId, drillType).orEmpty().filter { it.type != DrillType.PERFORM }
        }.onSuccess { drills = it }.onFailure { error = it.readable() }
    }
    var index by remember(trackId, type) { mutableIntStateOf(0) }
    var score by remember(trackId, type) { mutableIntStateOf(0) }
    var result by remember(trackId, type, index) { mutableStateOf<DrillResult?>(null) }
    val list = drills
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        drillType?.let { Text(stringResource(it.label()), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
        when {
            missing -> Notice(stringResource(R.string.track_pack_missing))
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { reload++ })
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() -> Notice(stringResource(R.string.track_drills_none))
            index >= list.size -> {
                Text(stringResource(R.string.track_drills_done, score, list.size), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { index = 0; score = 0 }) { Text(stringResource(R.string.action_again)) }
            }
            else -> {
                val drill = list[index]
                LinearProgressIndicator(progress = { index.toFloat() / list.size }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.track_drill_progress, index + 1, list.size), style = MaterialTheme.typography.labelLarge)
                    if (drill.topic.isNotBlank()) Tag(drill.topic)
                    drill.jlpt?.let { Tag("N$it") }
                    if (drill.isAiGenerated) AiBadge()
                }
                val onResult: (DrillResult) -> Unit = { r ->
                    if (result == null && r.correct) score++
                    result = r
                }
                when (drill) {
                    is KeigoDrill -> KeigoView(drill, result, onResult)
                    is EmailDrill -> EmailView(drill, result, onResult)
                    is FillInDrill -> FillInView(drill, result, onResult)
                    is SynonymDrill -> ChoiceView(
                        drill.word,
                        stringResource(if (drill.antonym) R.string.track_pick_antonym else R.string.track_pick_synonym),
                        drill.choices, result,
                    ) { onResult(drill.check(it)) }
                    is MeaningDrill -> ChoiceView(drill.word, stringResource(R.string.track_pick_meaning), drill.choices, result) { onResult(drill.check(it)) }
                    is UsageDrill -> UsageView(drill, result, onResult)
                    is PerformDrill -> Unit
                }
                result?.let { r ->
                    ResultCard(r)
                    Button(onClick = { index++ }) { Text(stringResource(R.string.action_next)) }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: DrillResult) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = if (r.correct) Color(0xFFC8E6C9) else MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (r.correct) "✓ " + stringResource(R.string.review_correct) else "✗ " + stringResource(R.string.track_not_quite),
                style = MaterialTheme.typography.titleMedium,
                color = if (r.correct) CorrectGreen else MaterialTheme.colorScheme.onErrorContainer,
            )
            if (r.expected.isNotEmpty()) JaText(stringResource(R.string.track_expected, r.expected.joinToString("／")), style = MaterialTheme.typography.bodyLarge)
            if (r.explanation.isNotBlank()) Text(r.explanation, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun keigoFormLabel(form: KeigoForm): String = stringResource(
    when (form) {
        KeigoForm.DICTIONARY -> R.string.track_form_dictionary
        KeigoForm.MASU -> R.string.track_form_masu
        KeigoForm.PAST -> R.string.track_form_past
        KeigoForm.MASU_PAST -> R.string.track_form_masu_past
        KeigoForm.TE -> R.string.track_form_te
    },
)

@Composable
private fun TypedAnswer(enabled: Boolean, onCheck: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        label = { Text(stringResource(R.string.track_your_answer)) },
        textStyle = MaterialTheme.typography.bodyLarge.japanese(),
    )
    Button(onClick = { onCheck(typed) }, enabled = enabled && typed.isNotBlank()) { Text(stringResource(R.string.action_check)) }
}

@Composable
private fun KeigoView(d: KeigoDrill, result: DrillResult?, onResult: (DrillResult) -> Unit) {
    Text(stringResource(R.string.track_keigo_prompt, d.target.labelJa, keigoFormLabel(d.form)), style = MaterialTheme.typography.titleMedium)
    JaText(d.plain, style = MaterialTheme.typography.headlineMedium)
    d.sentence?.let { JaText(it, style = MaterialTheme.typography.bodyLarge) }
    d.english?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    TypedAnswer(enabled = result == null) { onResult(d.check(it)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmailView(d: EmailDrill, result: DrillResult?, onResult: (DrillResult) -> Unit) {
    val answers = remember(d.id) { mutableStateMapOf<Int, String>() }
    val checks = remember(d.id) { mutableStateMapOf<Int, DrillResult>() }
    JaText(d.title, style = MaterialTheme.typography.titleMedium)
    JaText(d.situation, style = MaterialTheme.typography.bodyMedium)
    d.english?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            JaText(stringResource(R.string.track_email_subject, d.subject), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                d.segments.forEach { seg ->
                    when (seg) {
                        is EmailSegment.Text -> seg.text.split('\n').forEachIndexed { i, part ->
                            if (i > 0) Spacer(Modifier.fillMaxWidth())
                            if (part.isNotEmpty()) JaText(part, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyLarge)
                        }
                        is EmailSegment.Slot -> {
                            val check = checks[seg.blank]
                            OutlinedTextField(
                                answers[seg.blank].orEmpty(), { answers[seg.blank] = it },
                                Modifier.width(150.dp), singleLine = true, enabled = result == null,
                                label = { Text("${seg.blank + 1}") },
                                isError = check != null && !check.correct,
                                textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                            )
                        }
                    }
                }
            }
        }
    }
    d.blanks.forEachIndexed { i, b ->
        if (b.hint.isNotBlank() || !b.choices.isNullOrEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.track_email_blank, i + 1) + (if (b.hint.isNotBlank()) " · ${b.hint}" else ""), style = MaterialTheme.typography.labelLarge)
                b.choices?.takeIf { it.isNotEmpty() }?.let { choices ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { c ->
                            FilterChip(answers[i] == c, { if (result == null) answers[i] = c }, { JaText(c) })
                        }
                    }
                }
            }
        }
        checks[i]?.let { c ->
            Text(
                if (c.correct) "${i + 1}: ✓" else stringResource(R.string.track_email_blank_wrong, i + 1, c.expected.joinToString("／")),
                style = MaterialTheme.typography.bodyMedium.japanese(),
                color = if (c.correct) CorrectGreen else MaterialTheme.colorScheme.error,
            )
        }
    }
    Button(
        onClick = {
            d.blanks.indices.forEach { checks[it] = d.check(it, answers[it].orEmpty()) }
            val allRight = checks.values.all { it.correct }
            onResult(DrillResult(allRight, listOf(d.filled), "", answers.values.joinToString("、")))
        },
        enabled = result == null && d.blanks.indices.all { !answers[it].isNullOrBlank() },
    ) { Text(stringResource(R.string.action_check)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FillInView(d: FillInDrill, result: DrillResult?, onResult: (DrillResult) -> Unit) {
    val (before, after) = d.parts
    JaText("$before（　　）$after", style = MaterialTheme.typography.titleLarge)
    if (d.english.isNotBlank()) Text(d.english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val choices = d.choices
    if (!choices.isNullOrEmpty()) {
        var picked by remember(d.id) { mutableStateOf<String?>(null) }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { c ->
                ChoiceCard(c, selected = picked == c, enabled = result == null, highlight = result?.let { if (c in it.expected) true else if (c == picked) false else null }) {
                    picked = c
                    onResult(d.check(c))
                }
            }
        }
    } else {
        TypedAnswer(enabled = result == null) { onResult(d.check(it)) }
    }
}

@Composable
private fun ChoiceCard(text: String, selected: Boolean, enabled: Boolean, highlight: Boolean?, onClick: () -> Unit) {
    val color = when (highlight) {
        true -> Color(0xFFC8E6C9)
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = color),
    ) { JaText(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun ChoiceView(word: String, question: String, choices: List<String>, result: DrillResult?, onPick: (Int) -> Unit) {
    var picked by remember(word, choices) { mutableStateOf<Int?>(null) }
    Text(question, style = MaterialTheme.typography.titleMedium)
    JaText(word, style = MaterialTheme.typography.headlineMedium)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEachIndexed { i, c ->
            val highlight = result?.let { if (c in it.expected) true else if (i == picked) false else null }
            ChoiceCard(c, selected = picked == i, enabled = result == null, highlight = highlight) {
                picked = i
                onPick(i)
            }
        }
    }
}

@Composable
private fun UsageView(d: UsageDrill, result: DrillResult?, onResult: (DrillResult) -> Unit) {
    Text(stringResource(R.string.track_usage_question), style = MaterialTheme.typography.titleMedium)
    JaText(d.word, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    JaText(d.sentence, style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onResult(d.check(true)) }, enabled = result == null) { Text("○ " + stringResource(R.string.track_usage_right)) }
        OutlinedButton(onClick = { onResult(d.check(false)) }, enabled = result == null) { Text("× " + stringResource(R.string.track_usage_wrong)) }
    }
}

// --- Memorize and perform ------------------------------------------------------------------------------------------

/** Holds the [PerformanceSession] across configuration changes; [version] bumps on every change so Compose redraws. */
class PerformViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    var drill by mutableStateOf<PerformDrill?>(null)
        private set
    var session: PerformanceSession? = null
        private set
    var version by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    var missing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load(id: String) {
        if (loaded) return
        error = null
        viewModelScope.launch {
            runCatching {
                val repo = graph.trackRepository()
                missing = repo == null
                repo?.drill(id) as? PerformDrill
            }.onSuccess { d ->
                drill = d
                session = d?.let { PerformanceSession(it) }
                loaded = true
            }.onFailure { error = it.readable() }
        }
    }

    fun deliver(line: Int, text: String) { session?.deliver(line, text); version++ }
    fun selfRate(line: Int, gotIt: Boolean) { session?.selfRate(line, gotIt); version++ }
    fun nextRound() { session?.nextRound(); version++ }
    fun restart() { session = drill?.let { PerformanceSession(it) }; version++ }
}

@Composable
private fun FadeLevel.label(): String = stringResource(
    when (this) {
        FadeLevel.FULL -> R.string.track_fade_full
        FadeLevel.HALF -> R.string.track_fade_half
        FadeLevel.INITIAL -> R.string.track_fade_initial
        FadeLevel.CUE_ONLY -> R.string.track_fade_cue
    },
)

@Composable
private fun Register.label(): String = stringResource(
    when (this) {
        Register.CASUAL -> R.string.track_register_casual
        Register.POLITE -> R.string.track_register_polite
        Register.KEIGO -> R.string.track_register_keigo
    },
)

/**
 * Memorize-and-perform (BRIEF_V2 §6.5 Performing culture): the learner plays their part round after round while the
 * prompts for their lines fade. Each line is spoken (recognizer) or typed, or self-rated against the script.
 */
@Composable
fun PerformanceScreen(drillId: String, key: String) {
    val vm = keyedViewModel(key) { PerformViewModel(it) }
    LaunchedEffect(drillId) { vm.load(drillId) }
    val d = vm.drill
    val session = vm.session
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            vm.missing -> Notice(stringResource(R.string.track_pack_missing))
            vm.error != null -> ErrorState(stringResource(R.string.error_loading, vm.error!!), onRetry = { vm.load(drillId) })
            !vm.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
            d == null || session == null -> Notice(stringResource(R.string.track_perform_missing))
            else -> Performance(vm, d, session)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Performance(vm: PerformViewModel, d: PerformDrill, session: PerformanceSession) {
    @Suppress("UNUSED_VARIABLE") val v = vm.version // read so every session change recomposes
    val voices = rememberVoices()
    val input = rememberSpeechInput()
    val scope = rememberCoroutineScope()
    val prompts = session.prompts()
    val learnerLines = d.learnerLines
    var active by remember(vm.version) { mutableStateOf(learnerLines.firstOrNull { session.check(it) == null }) }
    val revealed = remember(session.round) { mutableStateListOf<Int>() }

    JaText(d.titleJa, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(d.title, style = MaterialTheme.typography.bodyMedium)
        Tag(d.register.label())
        d.jlpt?.let { Tag("N$it") }
        if (d.isAiGenerated) AiBadge()
    }
    Text(d.setting, style = MaterialTheme.typography.bodyMedium)
    val you = d.speaker(d.learner)?.name ?: d.learner
    Text(stringResource(R.string.track_perform_you_play, you), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    if (d.staging.isNotEmpty()) {
        SectionTitle(stringResource(R.string.track_perform_staging))
        d.staging.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    HorizontalDivider()
    if (session.finished) {
        Text(stringResource(R.string.track_perform_finished), style = MaterialTheme.typography.headlineSmall, color = CorrectGreen)
        Button(onClick = vm::restart) { Text(stringResource(R.string.track_perform_restart)) }
        return
    }
    Text(stringResource(R.string.track_perform_round, session.round + 1, session.level.label()), style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = {
        scope.launch {
            // Plays the whole script in order: partner lines in their voice, the learner's lines as a model.
            d.lines.forEach { line -> voices.say(line.ja, d.speaker(line.speaker)?.voice) }
        }
    }) { Text(stringResource(R.string.track_perform_listen_all)) }

    prompts.forEach { p ->
        val speakerName = d.speaker(p.speaker)?.name ?: p.speaker
        val check = session.check(p.lineIndex)
        val isActive = p.isLearner && active == p.lineIndex && check == null
        Card(
            Modifier.fillMaxWidth().then(if (p.isLearner && check == null) Modifier.clickable { active = p.lineIndex } else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    !p.isLearner -> MaterialTheme.colorScheme.surfaceVariant
                    check?.passed == true -> Color(0xFFC8E6C9)
                    check != null -> MaterialTheme.colorScheme.errorContainer
                    isActive -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (p.isLearner) stringResource(R.string.track_perform_your_line, speakerName) else speakerName,
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    )
                    if (!p.isLearner) {
                        val playLabel = stringResource(R.string.listen_play_line, p.lineIndex + 1)
                        IconButton(
                            onClick = { scope.launch { voices.say(d.lines[p.lineIndex].ja, d.speaker(p.speaker)?.voice) } },
                            modifier = Modifier.semantics { contentDescription = playLabel },
                        ) { Text("▶") }
                    }
                }
                p.stage?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.track_perform_stage, it), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
                when {
                    p.japanese != null -> JaText(p.japanese!!, style = MaterialTheme.typography.titleMedium)
                    else -> Text(stringResource(R.string.track_perform_cue_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(p.english, style = MaterialTheme.typography.bodySmall)
                if (check != null) {
                    val detail = when {
                        !check.selfRated -> stringResource(R.string.track_perform_match, (check.similarity * 100).toInt())
                        check.passed -> stringResource(R.string.track_perform_self_ok)
                        else -> stringResource(R.string.track_perform_self_missed)
                    }
                    Text((if (check.passed) "✓ " else "✗ ") + detail, style = MaterialTheme.typography.labelLarge)
                    if (check.given.isNotBlank()) JaText(stringResource(R.string.track_perform_heard, check.given), style = MaterialTheme.typography.bodySmall)
                    if (!check.passed || session.level != FadeLevel.FULL) JaText(stringResource(R.string.track_expected, check.expected), style = MaterialTheme.typography.bodySmall)
                }
                if (isActive) {
                    SpeakOrType(input, enabled = true, onSubmit = { text, _ -> if (text.isNotBlank()) vm.deliver(p.lineIndex, text) })
                    Text(stringResource(R.string.track_perform_self_hint), style = MaterialTheme.typography.bodySmall)
                    if (p.lineIndex in revealed) {
                        JaText(d.lines[p.lineIndex].ja, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.selfRate(p.lineIndex, true) }) { Text(stringResource(R.string.track_perform_said_it)) }
                            OutlinedButton(onClick = { vm.selfRate(p.lineIndex, false) }) { Text(stringResource(R.string.track_perform_missed_it)) }
                        }
                    } else {
                        TextButton(onClick = { revealed += p.lineIndex }) { Text(stringResource(R.string.track_perform_show_line)) }
                    }
                }
            }
        }
    }

    val allDelivered = learnerLines.all { session.check(it) != null }
    if (allDelivered) {
        val passed = session.roundPassed
        Text(
            stringResource(if (passed) R.string.track_perform_round_passed else R.string.track_perform_round_missed),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
            color = if (passed) CorrectGreen else MaterialTheme.colorScheme.error,
        )
        Button(onClick = { voices.stop(); vm.nextRound() }) {
            Text(stringResource(if (passed) R.string.track_perform_next_round else R.string.track_perform_retry_round))
        }
    }
}
