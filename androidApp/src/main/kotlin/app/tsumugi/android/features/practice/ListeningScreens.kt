package app.tsumugi.android.features.practice

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import app.tsumugi.android.platform.Voices
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.FuriganaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.jp.Furigana
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.jp.Kana
import app.tsumugi.practice.Dialogue
import app.tsumugi.practice.DialogueLine
import app.tsumugi.practice.DialogueSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Listening dialogues by JLPT level (BRIEF §5.9). */
@Composable
fun DialogueListScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    var level by remember { mutableStateOf<Int?>(null) }
    var dialogues by remember { mutableStateOf<List<DialogueSummary>?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(level) {
        val practice = graph.practice()
        missing = practice == null
        dialogues = practice?.dialogues(level).orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        JlptFilter(level, { level = it }, Modifier.padding(vertical = 8.dp))
        when {
            missing -> Notice(PRACTICE_PACK_MISSING)
            dialogues == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            dialogues!!.isEmpty() -> Text("No dialogues at this level in the installed pack.", Modifier.padding(8.dp))
            else -> LazyColumn {
                items(dialogues!!, key = { it.id }) { d ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(d.id) },
                        headlineContent = { Text(d.title, style = MaterialTheme.typography.titleSmall.japanese()) },
                        supportingContent = { Text(d.topic) },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Tag("N${d.jlpt}")
                                if (d.isAiGenerated) AiBadge()
                            }
                        },
                    )
                }
            }
        }
    }
}

private enum class ListenMode(val label: String) { LISTEN("Listen"), GAPS("Gap-fill"), ORDER("Order"), QUESTIONS("Questions") }

/** Line-by-line dialogue player with gap-fill, chunk ordering and comprehension questions. */
@Composable
fun DialoguePlayerScreen(id: String) {
    val graph = rememberGraph()
    var dialogue by remember { mutableStateOf<Dialogue?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        dialogue = graph.practice()?.dialogue(id)
        loaded = true
    }
    val d = dialogue
    when {
        !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        d == null -> Notice(PRACTICE_PACK_MISSING, Modifier.padding(16.dp))
        else -> DialoguePlayer(d)
    }
}

@Composable
private fun DialoguePlayer(dialogue: Dialogue) {
    val voices = rememberVoices()
    var mode by remember { mutableStateOf(ListenMode.LISTEN) }
    var rate by remember { mutableFloatStateOf(1f) }
    val modes = ListenMode.entries.filter { m ->
        when (m) {
            ListenMode.GAPS -> dialogue.lines.any { it.gaps.isNotEmpty() }
            ListenMode.ORDER -> dialogue.lines.any { it.chunks.size >= 3 }
            ListenMode.QUESTIONS -> dialogue.questions.isNotEmpty()
            ListenMode.LISTEN -> true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dialogue.title, style = MaterialTheme.typography.titleLarge.japanese())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("N${dialogue.jlpt}")
                Text(dialogue.topic, style = MaterialTheme.typography.bodySmall)
                if (dialogue.isAiGenerated) AiBadge()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", style = MaterialTheme.typography.labelLarge)
                listOf(0.75f, 1f).forEach { r -> FilterChip(rate == r, { rate = r }, { Text("${r}×") }) }
            }
        }
        PrimaryTabRow(selectedTabIndex = modes.indexOf(mode).coerceAtLeast(0)) {
            modes.forEach { m -> Tab(selected = m == mode, onClick = { voices.stop(); mode = m }, text = { Text(m.label) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (mode) {
                ListenMode.LISTEN -> ListenView(dialogue, voices, rate)
                ListenMode.GAPS -> GapFillView(dialogue, voices, rate)
                ListenMode.ORDER -> OrderView(dialogue, voices, rate)
                ListenMode.QUESTIONS -> QuestionsView(dialogue, voices, rate)
            }
        }
    }
}

private fun voiceOf(dialogue: Dialogue, line: DialogueLine): String? = dialogue.speaker(line.speaker)?.voice

@Composable
private fun ListenView(dialogue: Dialogue, voices: Voices, rate: Float) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var showJa by remember { mutableStateOf(true) }
    var showEn by remember { mutableStateOf(false) }
    var furigana by remember { mutableStateOf(false) }
    var playing by remember { mutableIntStateOf(-1) }
    var job by remember { mutableStateOf<Job?>(null) }
    val readings = remember { mutableStateMapOf<Int, List<List<FuriganaSegment>>>() }
    var noTokenizer by remember { mutableStateOf(false) }
    LaunchedEffect(furigana) {
        if (!furigana || readings.isNotEmpty()) return@LaunchedEffect
        val analyzer = graph.analyzer()
        noTokenizer = analyzer == null
        analyzer ?: return@LaunchedEffect
        dialogue.lines.forEachIndexed { i, line ->
            readings[i] = analyzer.analyze(line.japanese).map { m ->
                val reading = m.reading?.let(Kana::toHiragana)
                if (reading == null || !Kana.containsKanji(m.surface)) listOf(FuriganaSegment(m.surface)) else Furigana.align(m.surface, reading)
            }
        }
    }

    fun play(from: Int, single: Boolean) {
        job?.cancel()
        job = scope.launch {
            try {
                val range = if (single) from..from else from..dialogue.lines.lastIndex
                for (i in range) {
                    playing = i
                    val line = dialogue.lines[i]
                    voices.say(line.japanese, voiceOf(dialogue, line), rate)
                }
            } finally {
                playing = -1
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(showJa, { showJa = !showJa }, { Text("JP") })
        FilterChip(showEn, { showEn = !showEn }, { Text("EN") })
        FilterChip(furigana, { furigana = !furigana }, { Text("Furigana") })
    }
    if (furigana && noTokenizer) Text("Furigana needs the tokenizer pack, which isn't installed in this build.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { play(0, single = false) }) { Text("▶ Play all") }
        OutlinedButton(onClick = { job?.cancel(); voices.stop() }) { Text("■ Stop") }
    }
    dialogue.lines.forEachIndexed { i, line ->
        val speaker = dialogue.speaker(line.speaker)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (i == playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(speaker?.name ?: line.speaker, style = MaterialTheme.typography.labelMedium)
                    if (showJa) {
                        val segments = readings[i]
                        if (furigana && segments != null) FuriganaLine(segments) else Text(line.japanese, style = MaterialTheme.typography.bodyLarge.japanese())
                    }
                    if (showEn) Text(line.english, style = MaterialTheme.typography.bodySmall)
                    if (!showJa && !showEn) Text("…", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { play(i, single = true) }, modifier = Modifier.semantics { contentDescription = "Replay line ${i + 1}" }) { Text("↻") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FuriganaLine(words: List<List<FuriganaSegment>>) {
    FlowRow {
        words.forEach { FuriganaText(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun GapFillView(dialogue: Dialogue, voices: Voices, rate: Float) {
    val scope = rememberCoroutineScope()
    val answers = remember { mutableStateMapOf<String, String>() }
    var checked by remember { mutableStateOf(false) }
    Text("Listen to each line and type the missing words (kana is fine).", style = MaterialTheme.typography.bodyMedium)
    var total = 0
    var right = 0
    dialogue.lines.forEachIndexed { i, line ->
        if (line.gaps.isEmpty()) return@forEachIndexed
        val prompt = buildString {
            var at = 0
            line.gaps.sortedBy { it.start }.forEach { g ->
                append(line.japanese.substring(at, g.start)).append("＿＿")
                at = g.end
            }
            append(line.japanese.substring(at))
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.japanese())
                    IconButton(
                        onClick = { scope.launch { voices.say(line.japanese, voiceOf(dialogue, line), rate) } },
                        modifier = Modifier.semantics { contentDescription = "Play line ${i + 1}" },
                    ) { Text("▶") }
                }
                line.gaps.sortedBy { it.start }.forEachIndexed { k, g ->
                    val key = "$i:$k"
                    val value = answers[key].orEmpty()
                    val ok = value.trim() == g.text || Kana.toHiragana(value.trim()) == Kana.toHiragana(g.text)
                    total++
                    if (ok) right++
                    OutlinedTextField(
                        value, { answers[key] = it; checked = false }, Modifier.fillMaxWidth(),
                        label = { Text("Gap ${k + 1}") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                        isError = checked && !ok,
                        supportingText = if (checked) ({ Text(if (ok) "✓" else "Answer: ${g.text}") }) else null,
                    )
                }
            }
        }
    }
    Button(onClick = { checked = true }) { Text("Check") }
    if (checked) Text("$right of $total correct", style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderView(dialogue: Dialogue, voices: Voices, rate: Float) {
    val scope = rememberCoroutineScope()
    val lines = remember(dialogue.id) { dialogue.lines.filter { it.chunks.size >= 3 } }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember(index) { mutableStateOf<List<Int>>(emptyList()) }
    var score by remember { mutableIntStateOf(0) }
    val line = lines.getOrNull(index)
    if (line == null) {
        Text("Done: $score of ${lines.size} lines in the right order.", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { index = 0; score = 0 }) { Text("Again") }
        return
    }
    val order = remember(line) { line.chunks.indices.shuffled(Random(line.japanese.hashCode())) }
    val complete = picked.size == line.chunks.size
    val correct = complete && picked.map { line.chunks[it] } == line.chunks
    Text("Line ${index + 1} of ${lines.size}: listen, then tap the chunks in order.", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { scope.launch { voices.say(line.japanese, voiceOf(dialogue, line), rate) } }) { Text("▶ Play") }
        OutlinedButton(onClick = { picked = emptyList() }) { Text("Reset") }
    }
    Card(Modifier.fillMaxWidth()) {
        Text(picked.joinToString("") { line.chunks[it] }.ifEmpty { " " }, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium.japanese())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        order.forEach { k ->
            if (k !in picked) OutlinedButton(onClick = { picked = picked + k }, enabled = !complete) { Text(line.chunks[k], style = MaterialTheme.typography.bodyLarge.japanese()) }
        }
    }
    if (complete) {
        Text(if (correct) "✓ Correct" else "Not quite: ${line.japanese}", color = if (correct) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
        Button(onClick = { if (correct) score++; index++ }) { Text("Next") }
    }
}

@Composable
private fun QuestionsView(dialogue: Dialogue, voices: Voices, rate: Float) {
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateMapOf<Int, Int>() }
    Button(onClick = {
        scope.launch { voices.sayAll(dialogue.lines.map { it.japanese to voiceOf(dialogue, it) }, rate) }
    }) { Text("▶ Play the dialogue") }
    dialogue.questions.forEachIndexed { qi, q ->
        Text("${qi + 1}. ${q.question}", style = MaterialTheme.typography.titleSmall.japanese())
        q.choices.forEachIndexed { ci, choice ->
            val answered = chosen[qi]
            val color = when {
                answered == null -> MaterialTheme.colorScheme.surfaceVariant
                ci == q.answer -> Color(0xFFC8E6C9)
                ci == answered -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(
                Modifier.fillMaxWidth().clickable(enabled = answered == null) { chosen[qi] = ci },
                colors = CardDefaults.cardColors(containerColor = color),
            ) { Text(choice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.japanese()) }
        }
    }
    if (chosen.size == dialogue.questions.size) {
        val right = dialogue.questions.indices.count { chosen[it] == dialogue.questions[it].answer }
        Text("$right of ${dialogue.questions.size} correct", style = MaterialTheme.typography.titleMedium)
    }
}
