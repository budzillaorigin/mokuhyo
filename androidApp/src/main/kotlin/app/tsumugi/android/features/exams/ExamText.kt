package app.tsumugi.android.features.exams

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tsumugi.android.ui.japanese

/*
 * Presentation of the exam bank markup (docs/CONTENT_PACKS.md "Exam item banks"):
 * - `<u>…</u>` underlines the target word; the tags are never shown.
 * - `（　　）` blanks, sentence-assembly slots `＿＿＿` / `＿★＿`, and the `Ａ` / `Ｂ` headings of integrated passages are
 *   shown as written.
 * - `［1］`…`［5］` markers in text-grammar passages: the current item's marker is highlighted.
 * - Lines starting with `|` form a table (header row first) and are drawn as a grid.
 */

private val UNDERLINE = Regex("<u>(.*?)</u>", RegexOption.DOT_MATCHES_ALL)

/** [text] with `<u>` spans underlined and [highlight] (e.g. "［2］") marked; other tags are stripped. */
fun examAnnotated(text: String, highlight: String? = null, highlightColor: Color = Color(0xFFFFE082)): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in UNDERLINE.findAll(text)) {
        appendMarked(text.substring(at, m.range.first), highlight, highlightColor)
        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { appendMarked(m.groupValues[1], highlight, highlightColor) }
        at = m.range.last + 1
    }
    appendMarked(text.substring(at), highlight, highlightColor)
}

private fun AnnotatedString.Builder.appendMarked(raw: String, highlight: String?, color: Color) {
    val s = raw.replace(Regex("</?[a-zA-Z][^>]*>"), "")
    if (highlight.isNullOrEmpty()) {
        append(s)
        return
    }
    var at = 0
    while (true) {
        val i = s.indexOf(highlight, at)
        if (i < 0) break
        append(s.substring(at, i))
        withStyle(SpanStyle(background = color, fontWeight = FontWeight.Bold)) { append(highlight) }
        at = i + highlight.length
    }
    append(s.substring(at))
}

/** Stem, choice or passage text with the bank markup rendered; tables become grids. */
@Composable
fun ExamText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, highlight: String? = null) {
    val blocks = remember(text) { splitBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Table -> TableGrid(block.rows, style)
                is Block.Lines -> Text(examAnnotated(block.text, highlight), style = style.japanese())
            }
        }
    }
}

private sealed interface Block {
    data class Lines(val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
}

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    fun flushLines() { if (lines.isNotEmpty()) { out += Block.Lines(lines.joinToString("\n")); lines.clear() } }
    fun flushRows() { if (rows.isNotEmpty()) { out += Block.Table(rows.toList()); rows.clear() } }
    for (line in text.lines()) {
        val t = line.trim()
        if (t.startsWith("|") && t.length > 1) {
            flushLines()
            val cells = t.trim('|').split('|').map { it.trim() }
            // Skip Markdown-style separator rows (| --- | --- |).
            if (cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' || it == '－' } }) continue
            rows += cells
        } else {
            flushRows()
            lines += line
        }
    }
    flushRows()
    flushLines()
    return out
}

@Composable
private fun TableGrid(rows: List<List<String>>, style: TextStyle) {
    val border = MaterialTheme.colorScheme.outline
    val columns = rows.maxOf { it.size }
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, border)) {
        rows.forEachIndexed { r, cells ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                for (c in 0 until columns) {
                    Text(
                        examAnnotated(cells.getOrElse(c) { "" }),
                        Modifier.fillMaxHeight().border(0.5.dp, border).widthIn(min = 64.dp, max = 220.dp).padding(6.dp),
                        style = (if (r == 0) style.copy(fontWeight = FontWeight.Bold) else style).japanese(),
                    )
                }
            }
        }
    }
}

/** Text-grammar stems are just the marker ("［3］"); returns it so the passage can highlight it. */
fun markerOf(stem: String): String? = Regex("［[0-9０-９]+］").find(stem.trim())?.value?.takeIf { it == stem.trim() }
