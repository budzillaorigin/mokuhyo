package app.tsumugi.android.features.onomatopoeia

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.SvgGlyph
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.onomatopoeia.OnomatopoeiaDetail
import app.tsumugi.onomatopoeia.OnomatopoeiaQuestion
import app.tsumugi.onomatopoeia.OnomatopoeiaQuizKind
import app.tsumugi.onomatopoeia.OnomatopoeiaRepository
import app.tsumugi.onomatopoeia.OnomatopoeiaTheme
import app.tsumugi.onomatopoeia.OnomatopoeiaType
import app.tsumugi.onomatopoeia.OnomatopoeiaWord
import kotlinx.coroutines.launch

/** What the module screens show while the repository loads: null repo = the dictionary pack predates onomatopoeia. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Missing : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** The pack has no onomatopoeia tables (an older dictionary pack) or no dictionary at all: rule 9, say so. */
@Composable
private fun OnomatopoeiaMissing(modifier: Modifier = Modifier) = Notice(stringResource(R.string.ono_missing), modifier)

@Composable
private fun OnomatopoeiaType.label(): String = "$labelJa · " + stringResource(
    when (this) {
        OnomatopoeiaType.GIONGO -> R.string.ono_type_giongo
        OnomatopoeiaType.GITAIGO -> R.string.ono_type_gitaigo
        OnomatopoeiaType.GIJOUGO -> R.string.ono_type_gijougo
    },
)

/** Onomatopoeia module (BRIEF_V2 §6.8): theme tiles with their glyphs, a type filter, search and the word list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnomatopoeiaScreen(onOpenWord: (Long) -> Unit, onQuiz: () -> Unit) {
    val graph = rememberGraph()
    var retry by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<Pair<OnomatopoeiaRepository, List<OnomatopoeiaTheme>>>>(Load.Loading) }
    LaunchedEffect(retry) {
        state = Load.Loading
        state = runCatching {
            val repo = graph.onomatopoeia()
            if (repo == null || !repo.available()) Load.Missing else Load.Ready(repo to repo.themes())
        }.getOrElse { Load.Failed(it.readable()) }
    }
    var theme by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf<OnomatopoeiaType?>(null) }
    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<OnomatopoeiaWord>?>(null) }
    val ready = state as? Load.Ready<Pair<OnomatopoeiaRepository, List<OnomatopoeiaTheme>>>
    LaunchedEffect(ready, theme, type, query) {
        val repo = ready?.value?.first ?: return@LaunchedEffect
        words = runCatching {
            if (query.isNotBlank()) repo.search(query).filter { (type == null || it.type == type) && (theme == null || it.theme == theme) }
            else repo.words(theme, type)
        }.getOrDefault(emptyList())
    }

    when (val s = state) {
        Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        Load.Missing -> OnomatopoeiaMissing(Modifier.padding(16.dp))
        is Load.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { retry++ }, Modifier.padding(16.dp))
        is Load.Ready -> {
            val themes = s.value.second
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.ono_intro), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onQuiz) { Text(stringResource(R.string.ono_quiz)) }
                        SectionTitle(stringResource(R.string.ono_themes))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            themes.forEach { t -> ThemeTile(t, selected = theme == t.id) { theme = if (theme == t.id) null else t.id } }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(type == null, { type = null }, { Text(stringResource(R.string.filter_all)) })
                            OnomatopoeiaType.entries.forEach { t -> FilterChip(type == t, { type = if (type == t) null else t }, { JaText(t.label()) }) }
                        }
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.ono_search)) },
                            textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                        )
                    }
                }
                val list = words
                when {
                    list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    list.isEmpty() -> item { Text(stringResource(R.string.ono_no_words), Modifier.padding(8.dp)) }
                    else -> {
                        item { Text(stringResource(R.string.ono_count, list.size), style = MaterialTheme.typography.labelMedium) }
                        items(list, key = { it.entryId }) { w -> WordRow(w) { onOpenWord(w.entryId) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeTile(theme: OnomatopoeiaTheme, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.width(104.dp).selectable(selected = selected, role = Role.Checkbox, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SvgGlyph(theme.svg, Modifier.size(40.dp).clearAndSetSemantics {})
            JaText(theme.titleJa, style = MaterialTheme.typography.titleSmall)
            Text(theme.title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(stringResource(R.string.ono_theme_count, theme.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WordRow(w: OnomatopoeiaWord, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { JaText(w.text, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(if (w.hasFeel) w.feel else w.glosses.take(3).joinToString("; "), maxLines = 2)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tag(w.type.labelJa)
                if (w.aiGenerated) AiBadge()
            }
        },
    )
}

/** One word: feel lines, glosses, examples with TTS, and a link into the dictionary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnomatopoeiaWordScreen(entryId: Long, onOpenEntry: (Long) -> Unit) {
    val graph = rememberGraph()
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var retry by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<OnomatopoeiaDetail>>(Load.Loading) }
    var noVoice by remember { mutableStateOf(false) }
    LaunchedEffect(entryId, retry) {
        state = Load.Loading
        state = runCatching {
            val repo = graph.onomatopoeia()
            val d = if (repo == null || !repo.available()) null else repo.detail(entryId)
            if (d == null) Load.Missing else Load.Ready(d)
        }.getOrElse { Load.Failed(it.readable()) }
        noVoice = !voices.available()
    }
    fun say(text: String) = scope.launch { voices.say(text) }
    when (val s = state) {
        Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        Load.Missing -> OnomatopoeiaMissing(Modifier.padding(16.dp))
        is Load.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { retry++ }, Modifier.padding(16.dp))
        is Load.Ready -> {
            val d = s.value
            val w = d.word
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    d.theme?.let { SvgGlyph(it.svg, Modifier.size(56.dp).clearAndSetSemantics {}, color = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.weight(1f)) {
                        JaText(w.text, Modifier.semantics { heading() }, style = MaterialTheme.typography.displaySmall)
                        if (w.variants.isNotEmpty()) JaText(w.variants.joinToString("・"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val playLabel = stringResource(R.string.ono_play_word, w.text)
                    IconButton(onClick = { say(w.text) }, Modifier.semantics { contentDescription = playLabel }) { Text("▶") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(w.type.label())
                    d.theme?.let { Tag("${it.titleJa} · ${it.title}") }
                    if (w.aiGenerated) AiBadge()
                }
                if (noVoice) Text(stringResource(R.string.ono_no_voice), style = MaterialTheme.typography.bodySmall)
                if (w.hasFeel || w.feelJa.isNotBlank()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.ono_feel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (w.feelJa.isNotBlank()) JaText(w.feelJa, style = MaterialTheme.typography.bodyLarge)
                            if (w.hasFeel) Text(w.feel, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                SectionTitle(stringResource(R.string.ono_glosses))
                w.glosses.forEachIndexed { i, g -> Text("${i + 1}. $g", style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.ono_gloss_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                SectionTitle(stringResource(R.string.dict_examples))
                if (d.examples.isEmpty()) {
                    Text(stringResource(R.string.ono_no_examples), style = MaterialTheme.typography.bodySmall)
                } else {
                    d.examples.forEachIndexed { i, ex ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                JaText(ex.japanese, style = MaterialTheme.typography.bodyLarge)
                                Text(ex.english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val label = stringResource(R.string.ono_play_example, i + 1)
                            IconButton(onClick = { say(ex.japanese) }, Modifier.semantics { contentDescription = label }) { Text("▶") }
                        }
                    }
                    Text(stringResource(R.string.ono_examples_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                OutlinedButton(onClick = { onOpenEntry(w.entryId) }) { Text(stringResource(R.string.ono_open_dictionary)) }
            }
        }
    }
}

/** "Pick the word for the scene" / "pick the scene for the word" (D-237), with each option explained after answering. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnomatopoeiaQuizScreen(key: String) {
    val graph = rememberGraph()
    var kind by remember(key) { mutableStateOf<OnomatopoeiaQuizKind?>(null) }
    var theme by remember(key) { mutableStateOf<String?>(null) }
    var themes by remember(key) { mutableStateOf<List<OnomatopoeiaTheme>>(emptyList()) }
    var round by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<List<OnomatopoeiaQuestion>>>(Load.Loading) }
    val chosen = remember(key, round) { mutableStateMapOf<Int, Int>() }
    var index by remember(key, round) { mutableIntStateOf(0) }
    LaunchedEffect(key, round, kind, theme) {
        state = Load.Loading
        state = runCatching {
            val repo = graph.onomatopoeia()
            if (repo == null || !repo.available()) {
                Load.Missing
            } else {
                if (themes.isEmpty()) themes = repo.themes()
                Load.Ready(repo.quiz(QUIZ_LENGTH, kind, theme))
            }
        }.getOrElse { Load.Failed(it.readable()) }
        chosen.clear()
        index = 0
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(kind == null, { kind = null }, { Text(stringResource(R.string.ono_quiz_mixed)) })
            FilterChip(kind == OnomatopoeiaQuizKind.WORD_FOR_SCENE, { kind = OnomatopoeiaQuizKind.WORD_FOR_SCENE }, { Text(stringResource(R.string.ono_quiz_word_for_scene)) })
            FilterChip(kind == OnomatopoeiaQuizKind.SCENE_FOR_WORD, { kind = OnomatopoeiaQuizKind.SCENE_FOR_WORD }, { Text(stringResource(R.string.ono_quiz_scene_for_word)) })
        }
        if (themes.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(theme == null, { theme = null }, { Text(stringResource(R.string.ono_all_themes)) })
                themes.forEach { t -> FilterChip(theme == t.id, { theme = t.id }, { JaText(t.titleJa) }) }
            }
        }
        when (val s = state) {
            Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            Load.Missing -> OnomatopoeiaMissing()
            is Load.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { round++ })
            is Load.Ready -> {
                val questions = s.value
                if (questions.isEmpty()) {
                    Notice(stringResource(R.string.ono_quiz_empty))
                    return@Column
                }
                val q = questions.getOrNull(index)
                if (q == null) {
                    val right = questions.indices.count { chosen[it] == questions[it].answer }
                    Text(stringResource(R.string.ono_quiz_score, right, questions.size), style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = { round++ }) { Text(stringResource(R.string.action_again)) }
                    return@Column
                }
                Text(stringResource(R.string.ono_quiz_progress, index + 1, questions.size), style = MaterialTheme.typography.labelLarge)
                QuestionView(q, chosen[index]) { chosen[index] = it }
                if (chosen[index] != null) Button(onClick = { index++ }) { Text(stringResource(R.string.action_next)) }
            }
        }
    }
}

@Composable
private fun QuestionView(q: OnomatopoeiaQuestion, answered: Int?, onChoose: (Int) -> Unit) {
    val wordPrompt = q.kind == OnomatopoeiaQuizKind.SCENE_FOR_WORD
    Text(
        stringResource(if (wordPrompt) R.string.ono_quiz_ask_scene else R.string.ono_quiz_ask_word),
        style = MaterialTheme.typography.titleSmall,
    )
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (wordPrompt) {
                JaText(q.prompt, style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(q.prompt, style = MaterialTheme.typography.bodyLarge)
                if (q.promptJa.isNotBlank()) JaText(q.promptJa, style = MaterialTheme.typography.bodyMedium)
            }
            if (q.target.aiGenerated) AiBadge()
        }
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        q.choices.forEachIndexed { i, choice ->
            val color = when {
                answered == null -> MaterialTheme.colorScheme.surfaceVariant
                i == q.answer -> Color(0xFFC8E6C9)
                i == answered -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = answered == i, enabled = answered == null, role = Role.RadioButton, onClick = { onChoose(i) }),
                colors = CardDefaults.cardColors(containerColor = color),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (wordPrompt) Text(choice, style = MaterialTheme.typography.bodyMedium) else JaText(choice, style = MaterialTheme.typography.titleMedium)
                    // After answering, each option shows its word and feel, so wrong choices teach too.
                    if (answered != null) {
                        val o = q.options.getOrNull(i)
                        if (o != null) {
                            val detail = if (wordPrompt) o.text else o.feel.ifBlank { o.glosses.take(2).joinToString("; ") }
                            JaText(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (answered != null) {
        Text(
            if (q.isCorrect(answered)) "✓ " + stringResource(R.string.ono_quiz_right) else stringResource(R.string.ono_quiz_wrong, q.target.text),
            color = if (q.isCorrect(answered)) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium.japanese(),
        )
    }
}

private const val QUIZ_LENGTH = 10
