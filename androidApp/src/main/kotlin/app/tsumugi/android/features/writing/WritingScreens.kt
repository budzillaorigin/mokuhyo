package app.tsumugi.android.features.writing

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.japanese
import app.tsumugi.jp.strokes.Candidate
import app.tsumugi.jp.strokes.Point
import app.tsumugi.jp.strokes.StrokeProblem
import app.tsumugi.jp.strokes.WritingSession
import kotlinx.coroutines.launch

private const val UNITS = 109f

/**
 * Drawing surface in KanjiVG units (0..109). Shows an optional faded template, accepted strokes in ink,
 * an optional hint stroke, and the stroke in progress. [onStroke] receives each finished stroke.
 */
@Composable
fun WritingCanvas(
    modifier: Modifier = Modifier,
    template: List<List<Point>> = emptyList(),
    showTemplate: Boolean = false,
    inked: List<List<Point>> = emptyList(),
    hint: List<Point>? = null,
    error: Boolean = false,
    contentDescription: String? = null,
    onStroke: (List<Point>) -> Unit,
) {
    val description = contentDescription ?: stringResource(R.string.writing_canvas_description, inked.size)
    var current by remember { mutableStateOf<List<Point>>(emptyList()) }
    val ink = MaterialTheme.colorScheme.onSurface
    val guide = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.secondary
    Canvas(
        modifier
            .aspectRatio(1f)
            .border(1.dp, if (error) Color(0xFFC62828) else guide)
            // A freehand canvas has no TalkBack gesture equivalent; describe it so the screen reader user knows what it is.
            .semantics { this.contentDescription = description }
            .pointerInput(Unit) {
                val scale = UNITS / size.width
                detectDragGestures(
                    onDragStart = { current = listOf(Point(it.x * scale, it.y * scale)) },
                    onDrag = { change, _ -> current = current + Point(change.position.x * scale, change.position.y * scale) },
                    onDragEnd = {
                        if (current.size > 1) onStroke(current)
                        current = emptyList()
                    },
                    onDragCancel = { current = emptyList() },
                )
            },
    ) {
        val s = size.width / UNITS
        drawLine(guide, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
        drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        if (showTemplate) template.forEach { stroke(it, s, guide.copy(alpha = 0.5f), 6f) }
        inked.forEach { stroke(it, s, ink, 5f) }
        hint?.let { stroke(it, s, accent, 5f) }
        if (current.size > 1) stroke(current, s, ink, 5f)
    }
}

private fun DrawScope.stroke(points: List<Point>, scale: Float, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x * scale, points[0].y * scale)
        points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
    }
    drawPath(path, color, style = Stroke(width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Skritter-style guided practice for one or more kanji (BRIEF §5.7). */
@Composable
fun WritingPracticeScreen(kanji: List<String>, onDone: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var index by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<WritingSession?>(null) }
    var template by remember { mutableStateOf<List<List<Point>>>(emptyList()) }
    var inked by remember { mutableStateOf<List<List<Point>>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var showTemplate by remember { mutableStateOf(true) }
    var missing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val literal = kanji.getOrNull(index)
    LaunchedEffect(literal) {
        literal ?: return@LaunchedEffect
        val writing = graph.writing()
        template = writing?.template(literal).orEmpty()
        session = writing?.guided(literal)
        missing = session == null
        inked = emptyList()
        feedback = null
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (literal == null) {
            Text(stringResource(R.string.writing_done), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            return@Column
        }
        Text(buildAnnotatedString { append(stringResource(R.string.writing_progress, index + 1, kanji.size)); append(ja(literal)) }, style = MaterialTheme.typography.titleLarge.japanese())
        if (missing) {
            Text(stringResource(R.string.writing_no_data, literal))
        } else {
            val s = session
            FilterChip(selected = showTemplate, onClick = { showTemplate = !showTemplate }, label = { Text(stringResource(R.string.writing_show_template)) })
            WritingCanvas(
                Modifier.fillMaxWidth(),
                template = template,
                showTemplate = showTemplate,
                inked = inked,
                hint = if (s != null && !s.done && s.failuresOnCurrent >= WritingSession.HINT_AFTER_FAILURES) s.hint() else null,
                error = error,
            ) { stroke ->
                val ws = session ?: return@WritingCanvas
                if (ws.done) return@WritingCanvas
                val result = ws.submit(stroke)
                error = !result.accepted
                if (result.accepted) {
                    inked = inked + listOf(template[result.index])
                    feedback = if (ws.done) context.getString(R.string.writing_done_rating, ws.suggestedRating) else null
                } else {
                    feedback = context.getString(
                        when (result.problem) {
                            StrokeProblem.WRONG_DIRECTION -> R.string.writing_wrong_direction
                            StrokeProblem.WRONG_ORDER -> R.string.writing_wrong_order
                            StrokeProblem.WRONG_POSITION -> R.string.writing_wrong_position
                            StrokeProblem.TOO_SHORT -> R.string.writing_too_short
                            else -> R.string.writing_try_again
                        },
                    ) + if (ws.failuresOnCurrent >= WritingSession.HINT_AFTER_FAILURES) context.getString(R.string.writing_hint_shown) else ""
                }
            }
            feedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (error) Color(0xFFC62828) else MaterialTheme.colorScheme.tertiary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { index = index }) { Text(stringResource(R.string.writing_restart)) }
                Button(onClick = { index++ }, enabled = session?.done == true || missing) { Text(stringResource(if (index < kanji.lastIndex) R.string.writing_next_kanji else R.string.action_finish)) }
            }
        }
    }
}

/** Draw a kanji to find it (BRIEF §5.3 handwriting search). */
@Composable
fun HandwritingSearchScreen(onPick: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var strokes by remember { mutableStateOf<List<List<Point>>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    LaunchedEffect(Unit) { graph.writing()?.recognizer?.warmUp() }
    fun recognize() = scope.launch {
        candidates = if (strokes.isEmpty()) emptyList() else graph.writing()?.recognizer?.recognize(strokes).orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (candidates.isEmpty()) Text(stringResource(R.string.writing_draw_below), color = MaterialTheme.colorScheme.onSurfaceVariant)
            candidates.forEach { c ->
                AssistChip(onClick = { onPick(c.kanji) }, label = { JaText(c.kanji, style = MaterialTheme.typography.headlineSmall) })
            }
        }
        WritingCanvas(Modifier.fillMaxWidth(), inked = strokes) { stroke ->
            strokes = strokes + listOf(stroke)
            recognize()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { strokes = strokes.dropLast(1); recognize() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.writing_undo_stroke)) }
            OutlinedButton(onClick = { strokes = emptyList(); candidates = emptyList() }) { Text(stringResource(R.string.writing_clear)) }
        }
        Text(stringResource(R.string.writing_strokes, strokes.size), style = MaterialTheme.typography.labelMedium)
    }
}
