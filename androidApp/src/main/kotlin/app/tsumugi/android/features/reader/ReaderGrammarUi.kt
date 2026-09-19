package app.tsumugi.android.features.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.features.study.BuildAnswer
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.localized
import app.tsumugi.android.ui.readable
import app.tsumugi.courses.ExplanationLanguage
import app.tsumugi.grammar.ExerciseKind
import app.tsumugi.grammar.GrammarExercise
import app.tsumugi.reader.DetectedConstruction
import app.tsumugi.reader.GrammarPracticeResult
import app.tsumugi.reader.PracticeKind
import app.tsumugi.reader.ReaderGrammar
import app.tsumugi.reader.ReaderSentence
import app.tsumugi.srs.CheckResult
import app.tsumugi.srs.Verdict
import kotlinx.coroutines.launch

/*
 * Grammar in the reader's sentence panel (BRIEF_V2 §6.16, D-289 shared, D-295 Android): the sentence with each
 * detected construction underlined (the shared F-39-filtered spans), a one-line explanation in the monolingual language,
 * and "practice this point", which adds the point to reviews or opens a fresh exercise inline.
 */

/** Practice state of one point in the panel. */
private sealed interface PracticeUi {
    data object Busy : PracticeUi
    data class Show(val added: Boolean, val exercise: GrammarExercise?, val note: String?) : PracticeUi
}

/** The sentence (highlighted) and its constructions; falls back to plain point links when the list can't load. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SentenceGrammar(sentence: ReaderSentence, onOpenGrammar: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var constructions by remember(sentence) { mutableStateOf<List<DetectedConstruction>?>(null) }
    var error by remember(sentence) { mutableStateOf<String?>(null) }
    var selected by remember(sentence) { mutableStateOf<String?>(null) }
    val practice = remember(sentence) { mutableStateMapOf<String, PracticeUi>() }
    LaunchedEffect(sentence) {
        if (sentence.grammarPointIds.isEmpty()) { constructions = emptyList(); return@LaunchedEffect }
        runCatching { graph.readerGrammar.constructions(sentence) }.onSuccess { constructions = it }.onFailure { error = it.readable() }
    }
    val list = constructions.orEmpty()
    val focus = list.firstOrNull { it.pointId == selected }
    HighlightedSentence(sentence.text, list, focus)
    if (sentence.grammarPointIds.isEmpty()) return
    Text(stringResource(R.string.reader_grammar_here), style = MaterialTheme.typography.titleSmall)
    when {
        error != null -> {
            Text(stringResource(R.string.rg_failed, error!!), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sentence.grammarPointIds.take(8).forEach { id -> TextButton(onClick = { onOpenGrammar(id) }) { Text(id.substringAfter('-').replace('-', ' ')) } }
            }
        }
        constructions == null -> Text(stringResource(R.string.rg_loading), style = MaterialTheme.typography.bodySmall)
        list.isEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The grammar pack is missing: the analyzer's ids still open the points.
            sentence.grammarPointIds.take(8).forEach { id -> TextButton(onClick = { onOpenGrammar(id) }) { Text(id.substringAfter('-').replace('-', ' ')) } }
        }
        else -> {
            if (list.any { it.spans.isNotEmpty() }) Text(stringResource(R.string.rg_highlight), style = MaterialTheme.typography.labelSmall)
            list.forEach { c ->
                ConstructionRow(
                    c,
                    selected = c.pointId == selected,
                    onSelect = { selected = if (selected == c.pointId) null else c.pointId },
                    practice = practice[c.pointId],
                    onPractice = {
                        practice[c.pointId] = PracticeUi.Busy
                        scope.launch {
                            val result = runCatching { graph.readerGrammar.practice(c.pointId) }
                            practice[c.pointId] = result.fold(
                                onSuccess = { r ->
                                    when (r) {
                                        is GrammarPracticeResult.AddedToReviews -> PracticeUi.Show(true, r.exercise, null)
                                        is GrammarPracticeResult.Exercise -> PracticeUi.Show(false, r.exercise, null)
                                        is GrammarPracticeResult.Unavailable -> PracticeUi.Show(
                                            false, null,
                                            if (r.reason == ReaderGrammar.NO_EXAMPLES) context.getString(R.string.rg_no_examples)
                                            else context.getString(R.string.rg_unavailable, r.reason),
                                        )
                                    }
                                },
                                onFailure = { PracticeUi.Show(false, null, context.getString(R.string.rg_unavailable, it.readable())) },
                            )
                            // Stage changed after adding: refresh the rows.
                            runCatching { graph.readerGrammar.constructions(sentence) }.onSuccess { constructions = it }
                        }
                    },
                    onOpenGrammar = onOpenGrammar,
                )
            }
            Text(stringResource(R.string.reader_grammar_guides_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The sentence with every construction underlined; the selected one also gets a background. */
@Composable
private fun HighlightedSentence(text: String, list: List<DetectedConstruction>, focus: DetectedConstruction?) {
    val under = MaterialTheme.colorScheme.tertiary
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val all = list.flatMap { it.spans }
    val focusSpans = focus?.spans.orEmpty()
    val annotated = buildAnnotatedString {
        append(text)
        all.forEach { r ->
            if (r.first >= 0 && r.last < text.length) addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = under), r.first, r.last + 1)
        }
        focusSpans.forEach { r ->
            if (r.first >= 0 && r.last < text.length) addStyle(SpanStyle(background = bg, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
        }
    }
    Text(ja(annotated), style = MaterialTheme.typography.bodyLarge.japanese())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstructionRow(
    c: DetectedConstruction,
    selected: Boolean,
    onSelect: () -> Unit,
    practice: PracticeUi?,
    onPractice: () -> Unit,
    onOpenGrammar: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JaText(c.title, style = MaterialTheme.typography.titleSmall)
                if (c.structure.isNotBlank() && c.structure != c.title) JaText(c.structure, style = MaterialTheme.typography.bodySmall)
                if (c.jlpt in 1..5) Tag("N${c.jlpt}")
                Tag(c.stage?.localized() ?: stringResource(R.string.rg_not_in_reviews))
            }
            if (c.explanation.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val style = MaterialTheme.typography.bodyMedium
                    if (c.language == ExplanationLanguage.JAPANESE) JaText(c.explanation, Modifier.weight(1f, fill = false), style = style)
                    else Text(c.explanation, Modifier.weight(1f, fill = false), style = style)
                    if (c.aiGenerated) AiBadge()
                }
            }
            if (c.japaneseMissing) Text(stringResource(R.string.rg_japanese_missing), style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = if (c.practiceAction == PracticeKind.ADD_TO_REVIEWS) R.string.rg_add_practice else R.string.rg_practice
                Button(onClick = onPractice, enabled = practice != PracticeUi.Busy) { Text(stringResource(label)) }
                OutlinedButton(onClick = { onOpenGrammar(c.pointId) }) { Text(stringResource(R.string.rg_open)) }
            }
            when (practice) {
                is PracticeUi.Show -> {
                    if (practice.added) Text(stringResource(R.string.rg_added), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.tertiary)
                    practice.note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
                    practice.exercise?.let { InlineExercise(it, onAnother = onPractice) }
                }
                else -> Unit
            }
        }
    }
}

/** One grammar exercise in the panel, checked by the shared `GrammarService.check`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineExercise(ex: GrammarExercise, onAnother: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var answer by remember(ex) { mutableStateOf("") }
    var result by remember(ex) { mutableStateOf<CheckResult?>(null) }
    var attempt by remember(ex) { mutableIntStateOf(0) }
    fun check(given: String) = scope.launch {
        result = runCatching { graph.grammar()?.check(ex, given) }.getOrNull() ?: CheckResult(Verdict.WRONG)
    }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (ex.kind) {
            ExerciseKind.MEANING_CHOICE -> {
                Text(stringResource(R.string.rg_exercise_meaning), style = MaterialTheme.typography.labelLarge)
                JaText(ex.marked, style = MaterialTheme.typography.bodyLarge)
                ex.choices.forEachIndexed { i, choice ->
                    OutlinedButton(onClick = { check(i.toString()) }, enabled = result == null, modifier = Modifier.fillMaxWidth()) { Text(choice) }
                }
            }
            ExerciseKind.BUILD -> {
                Text(stringResource(R.string.rg_exercise_build), style = MaterialTheme.typography.labelLarge)
                Text(ex.example.english, style = MaterialTheme.typography.bodySmall)
                BuildAnswer(ex, resetKey = attempt, enabled = result == null) { check(it) }
            }
            ExerciseKind.PRODUCTION -> {
                Text(stringResource(R.string.rg_exercise_produce), style = MaterialTheme.typography.labelLarge)
                Text(ex.example.english, style = MaterialTheme.typography.bodyMedium)
                AnswerField(answer, { answer = it }, enabled = result == null) { check(answer) }
            }
            ExerciseKind.CLOZE, ExerciseKind.FILL_HINT -> {
                Text(stringResource(R.string.rg_exercise_fill), style = MaterialTheme.typography.labelLarge)
                JaText(ex.prompt, style = MaterialTheme.typography.bodyLarge)
                Text(if (ex.kind == ExerciseKind.FILL_HINT) ex.pointHint else ex.example.english, style = MaterialTheme.typography.bodySmall)
                AnswerField(answer, { answer = it }, enabled = result == null) { check(answer) }
            }
        }
        if (ex.example.isAiGenerated) AiBadge()
        result?.let { r ->
            val shown = r.matched ?: ex.example.answer.ifBlank { ex.example.japanese }
            Text(
                when (r.verdict) {
                    Verdict.CORRECT -> stringResource(R.string.rg_correct)
                    Verdict.CLOSE -> stringResource(R.string.rg_close, shown)
                    else -> stringResource(R.string.rg_wrong, shown)
                },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium.japanese(),
                color = if (r.accepted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { attempt++; onAnother() }) { Text(stringResource(R.string.rg_another)) }
        }
    }
}

@Composable
private fun AnswerField(value: String, onChange: (String) -> Unit, enabled: Boolean, onCheck: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, onChange, Modifier.weight(1f), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.rg_answer)) },
            textStyle = MaterialTheme.typography.bodyLarge.japanese(),
        )
        Button(onClick = onCheck, enabled = enabled && value.isNotBlank()) { Text(stringResource(R.string.action_check)) }
    }
}
