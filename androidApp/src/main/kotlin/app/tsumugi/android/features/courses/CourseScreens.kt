package app.tsumugi.android.features.courses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.exams.ExamSpec
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.readable
import app.tsumugi.courses.CourseGrammar
import app.tsumugi.courses.CourseKanji
import app.tsumugi.courses.CourseMockSection
import app.tsumugi.courses.CourseModule
import app.tsumugi.courses.CourseProgress
import app.tsumugi.courses.CourseQuizType
import app.tsumugi.courses.CourseStep
import app.tsumugi.courses.CourseStepKind
import app.tsumugi.courses.CourseWord
import app.tsumugi.courses.JlptCourse
import app.tsumugi.courses.LevelProgress
import app.tsumugi.courses.LevelRemaining
import kotlinx.coroutines.launch

@Composable
private fun CourseStepKind.label(): String = stringResource(
    when (this) {
        CourseStepKind.KANJI -> R.string.course_step_kanji
        CourseStepKind.VOCAB -> R.string.course_step_vocab
        CourseStepKind.GRAMMAR -> R.string.course_step_grammar
        CourseStepKind.QUIZ -> R.string.course_step_quiz
        CourseStepKind.MOCK -> R.string.course_step_mock
    },
)

private fun pct(value: Double?): String? = value?.let { "${(it * 100).toInt()}%" }

@Composable
private fun ProgressRow(label: String, done: Int, total: Int, fraction: Float) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.course_count, done, total), style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ProgressBreakdown(p: CourseProgress) {
    if (p.kanjiTotal > 0) ProgressRow(stringResource(R.string.course_step_kanji), p.kanjiLearned, p.kanjiTotal, p.kanjiLearned.toFloat() / p.kanjiTotal)
    if (p.wordsTotal > 0) ProgressRow(stringResource(R.string.course_step_vocab), p.wordsLearned, p.wordsTotal, p.wordsLearned.toFloat() / p.wordsTotal)
    if (p.grammarTotal > 0) ProgressRow(stringResource(R.string.course_step_grammar_mastered), p.grammarMastered, p.grammarTotal, p.grammarMastered.toFloat() / p.grammarTotal)
    if (p.sectionsTotal > 0) ProgressRow(stringResource(R.string.course_step_mock), p.sectionsPassed, p.sectionsTotal, p.sectionsPassed.toFloat() / p.sectionsTotal)
}

// --- Overview -----------------------------------------------------------------------------------------------------

/** JLPT courses N5 → N1, each with its progress bar (BRIEF_V2 §6.6). */
@Composable
fun CoursesScreen(onOpenLevel: (Int) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var levels by remember { mutableStateOf<List<LevelProgress>?>(null) }
    var current by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            current = graph.courses.courseLevel()
            levels = graph.courses.overview()
        }.onFailure { error = it.readable() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.course_intro), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        error?.let { e -> item { ErrorState(stringResource(R.string.error_loading, e), onRetry = { attempt++ }) } }
        val list = levels
        when {
            list == null -> if (error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.all { it.progress.isEmpty } -> item { Notice(stringResource(R.string.course_empty_all)) }
            else -> items(list, key = { it.level }) { lp ->
                val selected = lp.level == current
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !lp.progress.isEmpty) {
                        scope.launch { runCatching { graph.courses.setCourseLevel(lp.level) } }
                        onOpenLevel(lp.level)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.course_level_title, lp.level), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (selected) Tag(stringResource(R.string.course_current))
                            Text("${lp.progress.percent}%", style = MaterialTheme.typography.titleMedium)
                        }
                        if (lp.progress.isEmpty) {
                            Text(stringResource(R.string.course_empty_level), style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(progress = { lp.progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                            ProgressBreakdown(lp.progress)
                        }
                    }
                }
            }
        }
    }
}

// --- One level ----------------------------------------------------------------------------------------------------

/** A JLPT level's modules: kanji → vocab → grammar → quiz → mock, each step with its bar. */
@Composable
fun CourseScreen(
    level: Int,
    onStartExam: (ExamSpec) -> Unit,
    onOpenGrammar: (String) -> Unit,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onOpenRemaining: (Int) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var course by remember(level) { mutableStateOf<JlptCourse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val expanded = remember(level) { mutableStateMapOf<Int, Boolean>() }
    // Checkbox changes show at once; the course is rebuilt in the background.
    val mastered = remember(level) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(level, attempt) {
        error = null
        runCatching { graph.courses.course(level) }
            .onSuccess { c -> course = c; mastered.clear() }
            .onFailure { error = it.readable() }
    }
    fun setMastered(id: String, on: Boolean) {
        mastered[id] = on
        scope.launch {
            runCatching { graph.courses.setMastered(id, on) }.onFailure { error = it.readable(); mastered.remove(id) }
            runCatching { graph.courses.course(level) }.onSuccess { c -> course = c; mastered.clear() }
        }
    }
    val c = course
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { e -> item { ErrorState(stringResource(R.string.error_loading, e), onRetry = { attempt++ }, Modifier.padding(top = 8.dp)) } }
        when {
            c == null -> if (error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
            c.modules.isEmpty() -> item { Notice(stringResource(R.string.course_empty_level), Modifier.padding(top = 8.dp)) }
            else -> {
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.course_level_title, level), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                            Text("${c.progress.percent}%", style = MaterialTheme.typography.titleLarge)
                        }
                        LinearProgressIndicator(progress = { c.progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                        ProgressBreakdown(c.progress)
                        OutlinedButton(onClick = { onOpenRemaining(level) }) { Text(stringResource(R.string.course_remaining_open)) }
                        Text(stringResource(R.string.course_mastery_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(c.modules, key = { it.index }) { m ->
                    val open = expanded[m.index] ?: (m == c.currentModule)
                    ModuleCard(
                        level, m, open, current = m == c.currentModule, mastered = mastered,
                        onToggle = { expanded[m.index] = !open },
                        onMastered = ::setMastered,
                        onStartExam = onStartExam, onOpenGrammar = onOpenGrammar, onOpenKanji = onOpenKanji, onOpenEntry = onOpenEntry,
                    )
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(
    level: Int,
    m: CourseModule,
    open: Boolean,
    current: Boolean,
    mastered: Map<String, Boolean>,
    onToggle: () -> Unit,
    onMastered: (String, Boolean) -> Unit,
    onStartExam: (ExamSpec) -> Unit,
    onOpenGrammar: (String) -> Unit,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.course_module, m.index), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                when {
                    m.complete -> Tag(stringResource(R.string.course_module_done))
                    current -> Tag(stringResource(R.string.course_current))
                }
                Text(if (open) "▲" else "▼", Modifier.padding(start = 8.dp))
            }
            m.steps.forEach { s: CourseStep ->
                ProgressRow(s.kind.label() + if (s.kind == m.nextStep) " ←" else "", s.done, s.total, s.fraction.toFloat())
            }
            if (!open) return@Column
            HorizontalDivider()
            if (m.kanji.isNotEmpty()) {
                SectionTitle(stringResource(R.string.course_step_kanji))
                KanjiChips(m.kanji, onOpenKanji)
            }
            if (m.words.isNotEmpty()) {
                SectionTitle(stringResource(R.string.course_step_vocab))
                m.words.forEach { WordRow(it, onOpenEntry) }
            }
            if (m.grammar.isNotEmpty()) {
                SectionTitle(stringResource(R.string.course_step_grammar))
                m.grammar.forEach { g -> GrammarRow(g, mastered[g.pointId] ?: g.mastered, onMastered, onOpenGrammar) }
            }
            if (m.quiz.isNotEmpty()) {
                SectionTitle(stringResource(R.string.course_step_quiz))
                m.quiz.forEach { q -> QuizRow(level, q, onStartExam) }
            }
            m.mock?.let { mock ->
                SectionTitle(stringResource(R.string.course_step_mock))
                MockRow(level, mock, onStartExam)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KanjiChips(kanji: List<CourseKanji>, onOpenKanji: (String) -> Unit) {
    val learned = stringResource(R.string.course_learned)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kanji.forEach { k ->
            AssistChip(
                onClick = { onOpenKanji(k.text) },
                label = { JaText(if (k.learned) "${k.text} ✓" else k.text, style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.semantics { contentDescription = k.text + " " + k.keyword + if (k.learned) ", $learned" else "" },
            )
        }
    }
}

@Composable
private fun WordRow(w: CourseWord, onOpenEntry: (Long) -> Unit) {
    val entry = w.entryId
    Row(
        Modifier.fillMaxWidth().then(if (entry != null) Modifier.clickable { onOpenEntry(entry) } else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JaText(w.text, style = MaterialTheme.typography.bodyLarge)
        if (w.reading != w.text) JaText(w.reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(w.meaning, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (w.learned) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GrammarRow(g: CourseGrammar, checked: Boolean, onMastered: (String, Boolean) -> Unit, onOpenGrammar: (String) -> Unit) {
    val state = stringResource(if (checked) R.string.course_mastered else R.string.course_not_mastered)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onMastered(g.pointId, it) }, Modifier.semantics { stateDescription = state; contentDescription = g.title })
        Column(Modifier.weight(1f).clickable { onOpenGrammar(g.pointId) }.padding(vertical = 4.dp)) {
            JaText(g.title, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.meaning, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (g.aiGenerated) AiBadge()
            }
        }
    }
}

@Composable
private fun QuizRow(level: Int, q: CourseQuizType, onStartExam: (ExamSpec) -> Unit) {
    ListItem(
        headlineContent = { JaText(q.title) },
        supportingContent = {
            Text(
                listOfNotNull(
                    stringResource(R.string.course_bank_items, q.bankItems),
                    pct(q.bestAccuracy)?.let { stringResource(R.string.course_best, it) },
                    if (q.passed) stringResource(R.string.course_passed) else null,
                ).joinToString(" · "),
            )
        },
        trailingContent = {
            TextButton(onClick = { onStartExam(ExamSpec.JlptType(level, q.type)) }, enabled = q.bankItems > 0) {
                Text(stringResource(R.string.course_start))
            }
        },
    )
}

@Composable
private fun MockRow(level: Int, mock: CourseMockSection, onStartExam: (ExamSpec) -> Unit) {
    ListItem(
        headlineContent = { JaText(mock.title) },
        supportingContent = {
            Text(
                listOfNotNull(
                    stringResource(R.string.minutes_short, mock.minutes),
                    stringResource(R.string.course_items, mock.itemCount),
                    pct(mock.bestAccuracy)?.let { stringResource(R.string.course_best, it) },
                    if (mock.passed) stringResource(R.string.course_passed) else null,
                ).joinToString(" · "),
            )
        },
        trailingContent = {
            TextButton(onClick = { onStartExam(ExamSpec.JlptSection(level, mock.sectionId, mock.title)) }) {
                Text(stringResource(R.string.course_start))
            }
        },
    )
}

// --- One book to pass ---------------------------------------------------------------------------------------------

/** "One book to pass" (日本語の森): exactly what is left for the level, in course order. */
@Composable
fun CourseRemainingScreen(
    level: Int,
    onOpenGrammar: (String) -> Unit,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onStartExam: (ExamSpec) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var remaining by remember(level) { mutableStateOf<LevelRemaining?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val ticked = remember(level) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(level, attempt) {
        error = null
        runCatching { graph.courses.remaining(level) }.onSuccess { remaining = it }.onFailure { error = it.readable() }
    }
    val r = remaining
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { e -> item { ErrorState(stringResource(R.string.error_loading, e), onRetry = { attempt++ }, Modifier.padding(top = 8.dp)) } }
        when {
            r == null -> if (error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
            r.isDone -> item { Notice(stringResource(R.string.course_remaining_done, level), Modifier.padding(top = 8.dp)) }
            else -> {
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.course_remaining_title, level), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.course_remaining_summary, r.total, r.kanji.size, r.words.size, r.grammar.size, r.mockSections.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (r.kanji.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.course_remaining_kanji, r.kanji.size)) }
                    item { KanjiChips(r.kanji, onOpenKanji) }
                }
                if (r.words.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.course_remaining_words, r.words.size)) }
                    items(r.words, key = { "w" + it.itemId }) { WordRow(it, onOpenEntry) }
                }
                if (r.grammar.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.course_remaining_grammar, r.grammar.size)) }
                    items(r.grammar, key = { "g" + it.pointId }) { g ->
                        GrammarRow(g, ticked[g.pointId] ?: g.mastered, { id, on ->
                            ticked[id] = on
                            scope.launch { runCatching { graph.courses.setMastered(id, on) }.onFailure { error = it.readable(); ticked.remove(id) } }
                        }, onOpenGrammar)
                    }
                }
                if (r.mockSections.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.course_remaining_mocks, r.mockSections.size)) }
                    items(r.mockSections, key = { "m" + it.sectionId }) { MockRow(level, it, onStartExam) }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
            }
        }
    }
}

