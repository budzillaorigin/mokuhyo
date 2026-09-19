package app.tsumugi.android.features.courses

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.readable
import app.tsumugi.courses.ExplanationLanguage
import app.tsumugi.courses.Explanations
import app.tsumugi.courses.GrammarExplanation
import app.tsumugi.courses.ParaphraseRequest
import app.tsumugi.courses.WordExplanation
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.grammar.GrammarPoint
import kotlinx.coroutines.launch

/**
 * Settings → Monolingual mode (BRIEF_V2 §6.6, D-232): on/off and the easiest level explained in Japanese only.
 * The setting syncs; turning it on starts at N2 unless the learner picks an earlier level.
 */
@Composable
fun MonolingualSettingsSection() {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { graph.monolingual.fromLevel() }.onSuccess { from = it }.onFailure { error = it.readable() }
        loaded = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mono_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.mono_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = from != null,
                enabled = loaded,
                onCheckedChange = { on ->
                    scope.launch {
                        runCatching {
                            graph.monolingual.setEnabled(on)
                            from = graph.monolingual.fromLevel()
                        }.onFailure { error = it.readable() }
                    }
                },
            )
        }
        val level = from
        if (level != null) {
            Text(stringResource(R.string.mono_from_level), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (5 downTo 1).forEach { l ->
                    FilterChip(
                        level == l,
                        {
                            from = l
                            scope.launch { runCatching { graph.monolingual.setFromLevel(l) }.onFailure { error = it.readable() } }
                        },
                        { Text("N$l") },
                        Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
            Text(
                if (level == 1) stringResource(R.string.mono_scope_n1) else stringResource(R.string.mono_scope, level),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** A grammar point's explanation in the language monolingual mode asks for, or null while loading or on failure. */
@Composable
fun rememberGrammarExplanation(point: GrammarPoint): GrammarExplanation? {
    val graph = rememberGraph()
    var explanation by remember(point.id) { mutableStateOf<GrammarExplanation?>(null) }
    LaunchedEffect(point.id) { explanation = runCatching { graph.explanations.grammar(point) }.getOrNull() }
    return explanation
}

/** Japanese meaning and nuance (with the AI badge when unreviewed), or the honest "not written yet" note. */
@Composable
fun GrammarExplanationText(point: GrammarPoint, explanation: GrammarExplanation?) {
    if (explanation != null && explanation.language == ExplanationLanguage.JAPANESE) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mono_japanese_explanation), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (explanation.aiGenerated) AiBadge()
        }
        JaText(explanation.meaning, style = MaterialTheme.typography.titleMedium)
        if (explanation.nuance.isNotBlank()) JaText(explanation.nuance, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(point.meaning, style = MaterialTheme.typography.titleMedium)
        if (point.nuance.isNotBlank()) Text(point.nuance, style = MaterialTheme.typography.bodyMedium)
        if (explanation?.japaneseMissing == true) {
            Text(stringResource(R.string.mono_grammar_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The course checklist's mastery checkbox for one grammar point, independent of SRS (D-231). */
@Composable
fun GrammarMasteryRow(pointId: String) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var mastered by remember(pointId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pointId) { mastered = runCatching { pointId in graph.courses.masteredIds() }.getOrNull() }
    val checked = mastered ?: return
    val label = stringResource(R.string.course_mastered_checkbox)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked,
            { on ->
                mastered = on
                scope.launch { runCatching { graph.courses.setMastered(pointId, on) }.onFailure { mastered = !on } }
            },
            Modifier.semantics { contentDescription = label },
        )
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.course_mastery_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The Japanese gloss of a dictionary word in monolingual mode (D-234): the cached LLM paraphrase, labeled with its
 * engine. Nothing is generated until the learner asks; list screens never call this. [onJapanese] reports whether a
 * Japanese explanation is showing, so the screen can fold the English senses away.
 */
@Composable
fun JapaneseGlossCard(entry: DictionaryEntry, englishShown: Boolean, onToggleEnglish: () -> Unit, onJapanese: (Boolean) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var explanation by remember(entry.id) { mutableStateOf<WordExplanation?>(null) }
    var learnerLevel by remember(entry.id) { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = remember(entry.id) {
        ParaphraseRequest(
            word = entry.headword,
            reading = entry.reading,
            glosses = entry.senses.flatMap { it.glosses }.take(8),
            partOfSpeech = entry.senses.firstOrNull()?.partsOfSpeech.orEmpty(),
            entryId = entry.id,
            jlpt = entry.jlpt,
        )
    }
    LaunchedEffect(entry.id) {
        learnerLevel = runCatching { graph.courses.courseLevel() }.getOrNull()
        runCatching { graph.explanations.word(request, learnerLevel, generate = false) }
            .onSuccess { explanation = it; onJapanese(it.language == ExplanationLanguage.JAPANESE) }
            .onFailure { error = it.readable() }
    }
    fun generate(forget: Boolean) {
        busy = true
        error = null
        scope.launch {
            runCatching {
                if (forget) graph.explanations.forgetParaphrase(request)
                graph.explanations.word(request, learnerLevel, generate = true)
            }.onSuccess { explanation = it; onJapanese(it.language == ExplanationLanguage.JAPANESE) }
                .onFailure { error = it.readable() }
            busy = false
        }
    }

    val ex = explanation
    error?.let { Text(stringResource(R.string.mono_word_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (ex == null) return
    when {
        ex.language == ExplanationLanguage.JAPANESE -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mono_japanese_explanation), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    AiBadge(ex.engine)
                }
                JaText(ex.paraphrase, style = MaterialTheme.typography.bodyLarge)
                if (ex.example.isNotBlank()) JaText(stringResource(R.string.mono_example_prefix) + ex.example, style = MaterialTheme.typography.bodyMedium)
                if (ex.note.isNotBlank()) JaText(ex.note, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggleEnglish) { Text(stringResource(if (englishShown) R.string.mono_hide_english else R.string.mono_show_english)) }
                    TextButton(onClick = { generate(forget = true) }, enabled = !busy) { Text(stringResource(R.string.mono_rewrite)) }
                }
            }
        }
        ex.unavailableReason == Explanations.NOT_GENERATED -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.mono_word_not_yet), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { generate(forget = false) }, enabled = !busy) { Text(stringResource(R.string.mono_word_generate)) }
                    AiBadge()
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        ex.unavailableReason != null -> Notice(
            stringResource(R.string.mono_word_unavailable, ex.unavailableReason.orEmpty()),
            actionLabel = if (busy) null else stringResource(R.string.action_retry),
            onAction = { generate(forget = false) },
        )
        else -> Unit
    }
}
