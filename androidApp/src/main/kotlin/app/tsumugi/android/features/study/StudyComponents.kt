package app.tsumugi.android.features.study

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.localized
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.StrokeOrderView
import app.tsumugi.android.ui.japanese
import app.tsumugi.dictionary.EntryDetail
import app.tsumugi.dictionary.KanjiStroke
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.jp.Romaji
import app.tsumugi.srs.PathItemDetail
import app.tsumugi.study.AnswerMode
import androidx.compose.ui.platform.LocalContext

/** Colour per item kind (our own palette). */
fun kindColor(kind: ItemKind): Color = when (kind) {
    ItemKind.RADICAL -> Color(0xFF2A8C8C)
    ItemKind.KANJI -> Color(0xFFC8553D)
    ItemKind.VOCAB -> Color(0xFF5B4A9E)
    else -> Color(0xFF4A5A6A)
}

/** Big glyph on a coloured card, as the question or the lesson header. */
@Composable
fun ItemGlyph(text: String, kind: ItemKind, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().background(kindColor(kind), RoundedCornerShape(16.dp)).padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        JaText(
            text,
            Modifier.padding(horizontal = 12.dp).semantics { heading() },
            color = Color.White,
            fontSize = if (text.length <= 2) 88.sp else if (text.length <= 4) 56.sp else 36.sp,
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Answer input. READING mode converts romaji to kana as you type (IME-style: "shi" → し, "nn" → ん),
 * MEANING mode is plain English.
 */
@Composable
fun AnswerField(mode: AnswerMode, enabled: Boolean, resetKey: Any, onSubmit: (String) -> Unit) {
    var value by remember(resetKey) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(resetKey) { runCatching { focus.requestFocus() } }
    val reading = mode == AnswerMode.READING
    val answerLabel = stringResource(if (reading) R.string.answer_reading_label else R.string.answer_meaning_label)
    val answerHint = stringResource(if (reading) R.string.answer_reading_hint else R.string.answer_meaning_hint)
    fun submit() {
        val text = if (reading) Romaji.finalize(value.text) else value.text
        if (text.isNotBlank()) onSubmit(text.trim())
    }
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            value = if (reading) {
                val converted = Romaji.imeConvert(v.text).text
                TextFieldValue(converted, TextRange(converted.length))
            } else v
        },
        enabled = enabled,
        // Readings are typed in romaji and shown as kana; tell TalkBack which kind of answer is expected.
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "$answerLabel. $answerHint" },
        label = { Text(answerLabel) },
        placeholder = { Text(stringResource(if (reading) R.string.answer_reading_placeholder else R.string.answer_meaning_placeholder)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.japanese().copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (reading) KeyboardType.Ascii else KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
}

/**
 * Lesson / item page: keyword, readings, components and where the item is used, stroke order, the user's own
 * mnemonic (myStory — the app ships none), and for vocabulary the dictionary entry with examples.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PathItemContent(detail: PathItemDetail, onSaveStory: (String) -> Unit, onOpenItem: ((String) -> Unit)? = null) {
    val item = detail.item
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var strokes by remember(item.id) { mutableStateOf<List<KanjiStroke>>(emptyList()) }
    var entry by remember(item.id) { mutableStateOf<EntryDetail?>(null) }
    LaunchedEffect(item.id) {
        val dict = graph.dictionary() ?: return@LaunchedEffect
        if (item.kind != ItemKind.VOCAB) strokes = dict.strokes(item.display)
        item.entryId?.let { entry = dict.entry(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ItemGlyph(item.display, item.kind)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.keyword, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.title_level, item.level), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            detail.stage?.let { Text(it.localized(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
        }
        if (item.meanings.size > 1) Text(stringResource(R.string.dict_also) + item.meanings.drop(1).take(6).joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        if (item.readings.isNotEmpty()) {
            Text(stringResource(R.string.path_readings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            JaText(item.readings.joinToString("、"), style = MaterialTheme.typography.titleLarge)
        }
        if (strokes.isNotEmpty()) StrokeOrderView(strokes, Modifier.size(140.dp))
        if (detail.components.isNotEmpty()) {
            Text(stringResource(if (item.kind == ItemKind.VOCAB) R.string.kind_kanji else R.string.path_radicals), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.components.forEach { c ->
                    AssistChip(onClick = { onOpenItem?.invoke(c.id) }, label = { Text(buildAnnotatedString { append(ja(c.display)); append("  ${c.keyword}") }, style = MaterialTheme.typography.bodyLarge.japanese()) })
                }
            }
        }
        if (detail.usedIn.isNotEmpty()) {
            Text(stringResource(R.string.path_used_in), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.usedIn.take(12).forEach { c ->
                    AssistChip(onClick = { onOpenItem?.invoke(c.id) }, label = { JaText(c.display, style = MaterialTheme.typography.bodyLarge) })
                }
            }
        }
        entry?.let { e ->
            e.sentences.take(3).forEach { s ->
                Column {
                    JaText(s.japanese, style = MaterialTheme.typography.bodyLarge)
                    Text(s.english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        MyStoryEditor(item.id, detail.myStory, onSaveStory)
    }
}

@Composable
private fun MyStoryEditor(itemId: String, initial: String, onSave: (String) -> Unit) {
    var story by remember(itemId) { mutableStateOf(initial) }
    var saved by remember(itemId) { mutableStateOf(initial) }
    Column {
        Text(stringResource(R.string.path_my_story), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.path_my_story_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = story,
            onValueChange = { story = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(stringResource(R.string.path_my_story)) },
        )
        if (story != saved) {
            TextButton(onClick = { onSave(story); saved = story }) { Text(stringResource(R.string.path_save_story)) }
        }
    }
}
