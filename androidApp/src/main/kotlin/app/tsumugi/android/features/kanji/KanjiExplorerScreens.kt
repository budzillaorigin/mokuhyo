package app.tsumugi.android.features.kanji

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.kanji.ComponentRole
import app.tsumugi.kanji.ComponentSearchResult
import app.tsumugi.kanji.GraphColoring
import app.tsumugi.kanji.GraphEdgeKind
import app.tsumugi.kanji.GraphNode
import app.tsumugi.kanji.GraphNodeKind
import app.tsumugi.kanji.KanjiExplorer
import app.tsumugi.kanji.KanjiNeighborhood
import app.tsumugi.kanji.NodePosition
import app.tsumugi.kanji.SoundSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Kanji explorer (BRIEF_V2 §6.15, D-292). The graph, its layout, colour buckets and caps all come from the shared
 * `KanjiExplorer`; this file only draws them and turns taps into navigation.
 */

/** Async state of an explorer screen. Missing = no dictionary pack (the explorer is null). */
internal sealed interface ExplorerLoad<out T> {
    data object Loading : ExplorerLoad<Nothing>
    data object Missing : ExplorerLoad<Nothing>
    data class Failed(val message: String) : ExplorerLoad<Nothing>
    data class Ready<T>(val value: T) : ExplorerLoad<T>
}

/** Runs [block] against the explorer, mapping a missing pack and failures to states (F-33). */
internal suspend fun <T> loadExplorer(explorer: suspend () -> KanjiExplorer?, block: suspend (KanjiExplorer) -> T): ExplorerLoad<T> = try {
    val e = explorer()
    if (e == null) ExplorerLoad.Missing else ExplorerLoad.Ready(block(e))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ExplorerLoad.Failed(e.readable())
}

/** Bucket colours 0 (N5 / most frequent) … 4 (N1 / rare), 5 unknown. Light fills with dark labels in both themes. */
internal val BucketColors = listOf(
    Color(0xFF81C784), Color(0xFFC5E1A5), Color(0xFFFFE082), Color(0xFFFFB74D), Color(0xFFEF9A9A), Color(0xFFCFD8DC),
)
private val NodeLabel = Color(0xFF1B1B1B)

private val CAPS = listOf(15, 30, 50)

@Composable
fun KanjiGraphScreen(
    center: String,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onRecenter: (String) -> Unit,
    onRecenterWord: (Long) -> Unit = {},
    onOpenSeries: () -> Unit = {},
    onComponentSearch: (String) -> Unit = {},
) {
    if (center.isBlank()) {
        CenterPicker(onRecenter, onOpenSeries, onComponentSearch)
        return
    }
    GraphScreen(
        key = "k:$center",
        load = { explorer, cap, coloring -> explorer.neighborhood(center, cap, coloring) },
        kanjiCenter = center,
        onOpenKanji = onOpenKanji, onOpenEntry = onOpenEntry, onRecenter = onRecenter, onRecenterWord = onRecenterWord,
        onComponentSearch = onComponentSearch,
    )
}

@Composable
fun WordGraphScreen(
    entryId: Long,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onRecenter: (String) -> Unit,
    onRecenterWord: (Long) -> Unit = {},
) {
    GraphScreen(
        key = "w:$entryId",
        load = { explorer, cap, coloring -> explorer.wordNeighborhood(entryId, cap, coloring) },
        kanjiCenter = null,
        onOpenKanji = onOpenKanji, onOpenEntry = onOpenEntry, onRecenter = onRecenter, onRecenterWord = onRecenterWord,
        onComponentSearch = {},
    )
}

/** Blank center: type a kanji (or a word; its first kanji is used), or go to sound families / component search. */
@Composable
private fun CenterPicker(onRecenter: (String) -> Unit, onOpenSeries: () -> Unit, onComponentSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var noKanji by remember { mutableStateOf(false) }
    val go = {
        val first = text.trim().codePointStrings().firstOrNull { isKanjiLike(it) }
        noKanji = first == null
        if (first != null) onRecenter(first)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.kx_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            text, { text = it; noKanji = false }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.kx_center_label)) },
            textStyle = MaterialTheme.typography.bodyLarge.japanese(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
        )
        if (noKanji) Text(stringResource(R.string.kx_center_no_kanji), color = MaterialTheme.colorScheme.error)
        Button(onClick = go, enabled = text.isNotBlank()) { Text(stringResource(R.string.kx_explore)) }
        Text(stringResource(R.string.kx_try), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("青", "方", "語", "休", "明").forEach { k ->
                OutlinedButton(onClick = { onRecenter(k) }) { JaText(k, style = MaterialTheme.typography.titleMedium) }
            }
        }
        HorizontalDivider()
        ListItem(
            modifier = Modifier.clickable(onClick = onOpenSeries),
            headlineContent = { Text(stringResource(R.string.title_sound_series)) },
            supportingContent = { Text(stringResource(R.string.kx_series_sub)) },
        )
        ListItem(
            modifier = Modifier.clickable { onComponentSearch("") },
            headlineContent = { Text(stringResource(R.string.title_component_search)) },
            supportingContent = { Text(stringResource(R.string.kx_component_search_sub)) },
        )
    }
}

private fun String.codePointStrings(): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < length) {
        val n = Character.charCount(codePointAt(i))
        out += substring(i, i + n)
        i += n
    }
    return out
}

internal fun isKanjiLike(s: String): Boolean {
    val cp = s.codePointAt(0)
    val block = Character.UnicodeBlock.of(cp)
    return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN ||
        block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT || block == Character.UnicodeBlock.KANGXI_RADICALS
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphScreen(
    key: String,
    load: suspend (KanjiExplorer, Int, GraphColoring) -> KanjiNeighborhood?,
    kanjiCenter: String?,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onRecenter: (String) -> Unit,
    onRecenterWord: (Long) -> Unit,
    onComponentSearch: (String) -> Unit,
) {
    val graph = rememberGraph()
    var cap by rememberSaveable { mutableIntStateOf(KanjiExplorer.DEFAULT_MAX_NODES) }
    var coloring by rememberSaveable { mutableStateOf(GraphColoring.JLPT) }
    var focusOnly by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<ExplorerLoad<KanjiNeighborhood?>>(ExplorerLoad.Loading) }
    var selected by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, cap, coloring, attempt) {
        state = ExplorerLoad.Loading
        state = loadExplorer({ graph.kanjiExplorer() }) { load(it, cap, coloring) }
    }

    fun activate(node: GraphNode) {
        when {
            node.isFocus && node.kind == GraphNodeKind.WORD -> node.entryId?.let(onOpenEntry)
            node.isFocus -> onOpenKanji(node.label)
            node.kind == GraphNodeKind.WORD -> node.entryId?.let(onRecenterWord)
            else -> onRecenter(node.label)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Controls: colouring, focus (one hop), the node cap.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(coloring == GraphColoring.JLPT, { coloring = GraphColoring.JLPT }, { Text(stringResource(R.string.kx_color_jlpt)) })
            FilterChip(coloring == GraphColoring.FREQUENCY, { coloring = GraphColoring.FREQUENCY }, { Text(stringResource(R.string.kx_color_frequency)) })
            FilterChip(focusOnly, { focusOnly = !focusOnly }, { Text(stringResource(R.string.kx_focus)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.kx_cap), style = MaterialTheme.typography.labelLarge)
            CAPS.forEach { c -> FilterChip(cap == c, { cap = c }, { Text("$c") }) }
        }
        when (val s = state) {
            ExplorerLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ExplorerLoad.Missing -> Notice(stringResource(R.string.kx_no_dictionary))
            is ExplorerLoad.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { attempt++ })
            is ExplorerLoad.Ready -> {
                val full = s.value
                if (full == null) {
                    Notice(stringResource(R.string.kx_no_graph, kanjiCenter ?: ""))
                } else {
                    val hood = if (focusOnly) oneHop(full) else full
                    val positions = remember(hood) { hood.layout() }
                    val focus = hood.nodes.firstOrNull { it.isFocus }
                    if (kanjiCenter != null) KanjiActions(kanjiCenter, onOpenKanji, onComponentSearch)
                    GraphCanvas(hood, positions, selected, onTap = { node ->
                        if (selected == node.id) activate(node) else selected = node.id
                    }, onLongPress = { selected = it.id })
                    Text(stringResource(R.string.kx_tap_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    hood.nodes.firstOrNull { it.id == selected }?.let { node ->
                        NodeCard(node, onActivate = { activate(node) }, onOpen = {
                            if (node.kind == GraphNodeKind.WORD) node.entryId?.let(onOpenEntry) else onOpenKanji(node.label)
                        })
                    }
                    Legend(hood.coloring)
                    val hiddenNow = full.hidden + (full.nodes.size - hood.nodes.size)
                    if (hiddenNow > 0) {
                        Text(
                            if (focusOnly && full.nodes.size > hood.nodes.size) stringResource(R.string.kx_hidden_focus, hiddenNow)
                            else stringResource(R.string.kx_hidden, hiddenNow),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NodeList(hood, focus, onActivate = ::activate, onOpen = { node ->
                        if (node.kind == GraphNodeKind.WORD) node.entryId?.let(onOpenEntry) else onOpenKanji(node.label)
                    })
                }
            }
        }
    }
}

/** Focus mode: only the nodes one edge away from the centre (a word graph's other words drop out). */
private fun oneHop(h: KanjiNeighborhood): KanjiNeighborhood {
    val keep = h.edges.filter { it.from == h.focusId || it.to == h.focusId }.flatMap { listOf(it.from, it.to) }.toSet() + h.focusId
    return h.copy(nodes = h.nodes.filter { it.id in keep }, edges = h.edges.filter { it.from in keep && it.to in keep })
}

/** Bookmark to SRS, and the kanji page / component search from the graph of a kanji. */
@Composable
private fun KanjiActions(literal: String, onOpenKanji: (String) -> Unit, onComponentSearch: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onOpenKanji(literal) }) { Text(stringResource(R.string.kx_open_kanji, literal)) }
        BookmarkButton(literal)
        ComponentQueryButton(literal, onComponentSearch)
    }
}

private fun nodeRadiusDp(node: GraphNode) = if (node.isFocus) 30f else if (node.kind == GraphNodeKind.WORD) 24f else 20f

@Composable
private fun GraphCanvas(
    hood: KanjiNeighborhood,
    positions: List<NodePosition>,
    selected: String?,
    onTap: (GraphNode) -> Unit,
    onLongPress: (GraphNode) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val byId = hood.nodes.associateBy { it.id }
    val primary = MaterialTheme.colorScheme.primary
    val edgeColor = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val focusLabel = hood.nodes.firstOrNull { it.isFocus }?.label.orEmpty()
    val summary = stringResource(R.string.kx_graph_description, focusLabel, hood.nodes.size)
    val base = MaterialTheme.typography.bodyLarge.japanese()
    // Tap targets: the nearest node within its radius plus a margin.
    fun hit(offset: Offset, width: Float, height: Float, density: Float): GraphNode? {
        val pad = 24f * density
        return positions.mapNotNull { p ->
            val node = byId[p.id] ?: return@mapNotNull null
            val c = Offset(pad + p.x.toFloat() * (width - 2 * pad), pad + p.y.toFloat() * (height - 2 * pad))
            val d = (c - offset).getDistance()
            if (d <= (nodeRadiusDp(node) + 10f) * density) node to d else null
        }.minByOrNull { it.second }?.first
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.9f)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .semantics { contentDescription = summary },
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(positions) {
                detectTapGestures(
                    onTap = { o -> hit(o, size.width.toFloat(), size.height.toFloat(), density)?.let(onTap) },
                    onLongPress = { o -> hit(o, size.width.toFloat(), size.height.toFloat(), density)?.let(onLongPress) },
                )
            },
        ) {
            val pad = 24.dp.toPx()
            val at = positions.associate { it.id to Offset(pad + it.x.toFloat() * (size.width - 2 * pad), pad + it.y.toFloat() * (size.height - 2 * pad)) }
            val highlight = selected?.let { s -> hood.edges.filter { it.from == s || it.to == s }.flatMap { listOf(it.from, it.to) }.toSet() + s }
            hood.edges.forEach { e ->
                val a = at[e.from] ?: return@forEach
                val b = at[e.to] ?: return@forEach
                val lit = highlight == null || (e.from in highlight && e.to in highlight)
                val color = (if (e.kind == GraphEdgeKind.SOUND) primary else edgeColor).copy(alpha = if (lit) 0.9f else 0.2f)
                drawLine(
                    color, a, b,
                    strokeWidth = (if (e.kind == GraphEdgeKind.PART || e.kind == GraphEdgeKind.SOUND) 2.5f else 1.5f).dp.toPx(),
                    pathEffect = when (e.kind) {
                        GraphEdgeKind.SOUND -> PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        GraphEdgeKind.WORD -> PathEffect.dashPathEffect(floatArrayOf(3f, 6f))
                        else -> null
                    },
                )
            }
            hood.nodes.forEach { node ->
                val c = at[node.id] ?: return@forEach
                val dim = highlight != null && node.id !in highlight
                drawNode(node, c, measurer, base, dim, node.id == selected, primary, surface)
            }
        }
    }
}

private fun DrawScope.drawNode(
    node: GraphNode, c: Offset, measurer: TextMeasurer, base: TextStyle, dim: Boolean, isSelected: Boolean, primary: Color, surface: Color,
) {
    val alpha = if (dim) 0.3f else 1f
    val fill = BucketColors[node.colorBucket.coerceIn(0, BucketColors.lastIndex)].copy(alpha = alpha)
    val r = nodeRadiusDp(node).dp.toPx()
    val isWord = node.kind == GraphNodeKind.WORD
    val label = if (isWord && node.label.length > 6) node.label.take(5) + "…" else node.label
    val style = base.copy(color = NodeLabel.copy(alpha = alpha), fontSize = if (node.isFocus) 24.sp else if (isWord) 14.sp else 20.sp)
    val text = measurer.measure(label, style)
    if (isWord) {
        val w = maxOf(text.size.width + 12.dp.toPx(), 2 * r)
        val h = 2 * r * 0.8f
        val topLeft = Offset(c.x - w / 2, c.y - h / 2)
        drawRoundRect(fill, topLeft, Size(w, h), CornerRadius(10.dp.toPx()))
        if (node.isFocus || isSelected) drawRoundRect(primary, topLeft, Size(w, h), CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
    } else {
        drawCircle(fill, r, c)
        // A part (component) gets a ring, a sound-family sibling a double ring: kind without colour alone.
        if (node.kind == GraphNodeKind.COMPONENT) drawCircle(surface.copy(alpha = alpha), r - 3.dp.toPx(), c, style = Stroke(1.5f.dp.toPx()))
        if (node.kind == GraphNodeKind.SERIES) drawCircle(primary.copy(alpha = alpha), r + 3.dp.toPx(), c, style = Stroke(1.5f.dp.toPx()))
        if (node.isFocus || isSelected) drawCircle(primary, r, c, style = Stroke(3.dp.toPx()))
    }
    drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
}

@Composable
private fun NodeCard(node: GraphNode, onActivate: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JaText(node.label, style = MaterialTheme.typography.headlineMedium)
                Column {
                    if (node.reading.isNotBlank()) JaText(node.reading, style = MaterialTheme.typography.bodyMedium)
                    if (node.gloss.isNotBlank()) Text(node.gloss, style = MaterialTheme.typography.bodyMedium)
                }
            }
            NodeTags(node)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!node.isFocus) Button(onClick = onActivate) { Text(stringResource(R.string.kx_recenter)) }
                OutlinedButton(onClick = onOpen) { Text(stringResource(if (node.kind == GraphNodeKind.WORD) R.string.kx_open_word else R.string.kx_open_page)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeTags(node: GraphNode) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Tag(kindLabel(node.kind, node.isFocus))
        node.role?.let { Tag(roleLabel(it)) }
        node.jlpt?.let { Tag(stringResource(R.string.dict_unofficial, "N$it")) }
        node.frequency?.let { Tag(stringResource(R.string.kx_frequency, it)) }
    }
}

@Composable
internal fun kindLabel(kind: GraphNodeKind, focus: Boolean = false): String = stringResource(
    when {
        focus -> R.string.kx_kind_focus
        kind == GraphNodeKind.KANJI -> R.string.kx_kind_kanji
        kind == GraphNodeKind.COMPONENT -> R.string.kx_kind_component
        kind == GraphNodeKind.CONTAINER -> R.string.kx_kind_container
        kind == GraphNodeKind.SERIES -> R.string.kx_kind_series
        else -> R.string.kx_kind_word
    },
)

@Composable
internal fun roleLabel(role: ComponentRole): String = stringResource(
    when (role) {
        ComponentRole.SEMANTIC -> R.string.kx_role_semantic
        ComponentRole.PHONETIC -> R.string.kx_role_phonetic
        ComponentRole.FORM -> R.string.kx_role_form
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(coloring: GraphColoring) {
    val labels = when (coloring) {
        GraphColoring.JLPT -> listOf("N5", "N4", "N3", "N2", "N1", stringResource(R.string.kx_legend_unknown))
        GraphColoring.FREQUENCY -> listOf(
            stringResource(R.string.kx_legend_freq_1), stringResource(R.string.kx_legend_freq_2), stringResource(R.string.kx_legend_freq_3),
            stringResource(R.string.kx_legend_freq_4), stringResource(R.string.kx_legend_freq_5), stringResource(R.string.kx_legend_unranked),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.kx_legend), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(BucketColors[i], CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(stringResource(R.string.kx_legend_shapes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The same nodes as a list, grouped by kind: the TalkBack-friendly way through the graph. */
@Composable
private fun NodeList(hood: KanjiNeighborhood, focus: GraphNode?, onActivate: (GraphNode) -> Unit, onOpen: (GraphNode) -> Unit) {
    Text(stringResource(R.string.kx_nodes), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    val order = listOf(GraphNodeKind.KANJI, GraphNodeKind.COMPONENT, GraphNodeKind.SERIES, GraphNodeKind.CONTAINER, GraphNodeKind.WORD)
    hood.nodes.filter { !it.isFocus }.sortedBy { order.indexOf(it.kind) }.groupBy { it.kind }.forEach { (kind, nodes) ->
        Text(kindLabel(kind) + " · ${nodes.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        nodes.forEach { node ->
            ListItem(
                modifier = Modifier.clickable { onActivate(node) },
                leadingContent = {
                    Box(Modifier.size(12.dp).background(BucketColors[node.colorBucket.coerceIn(0, 5)], CircleShape))
                },
                headlineContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        JaText(node.label, style = MaterialTheme.typography.titleMedium)
                        if (node.reading.isNotBlank()) JaText(node.reading, style = MaterialTheme.typography.bodySmall)
                        node.role?.let { Tag(roleLabel(it)) }
                    }
                },
                supportingContent = { if (node.gloss.isNotBlank()) Text(node.gloss, maxLines = 1) },
                trailingContent = {
                    TextButton(onClick = { onOpen(node) }) { Text(stringResource(R.string.kx_open_short)) }
                },
            )
        }
    }
    if (focus == null) Text(stringResource(R.string.kx_no_focus), style = MaterialTheme.typography.bodySmall)
}

/** Every sound series (声符 family), filterable by a kanji in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundSeriesScreen(onOpenKanji: (String) -> Unit) {
    val graph = rememberGraph()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ExplorerLoad<List<SoundSeries>>>(ExplorerLoad.Loading) }
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(attempt) { state = loadExplorer({ graph.kanjiExplorer() }) { it.allSeries() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.kx_series_intro), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        when (val s = state) {
            ExplorerLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ExplorerLoad.Missing -> Notice(stringResource(R.string.kx_no_dictionary))
            is ExplorerLoad.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { attempt++ })
            is ExplorerLoad.Ready -> {
                if (s.value.isEmpty()) {
                    Notice(stringResource(R.string.kx_series_none))
                } else {
                    OutlinedTextField(
                        filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.kx_series_filter)) },
                        textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                    )
                    val f = filter.trim()
                    val shown = if (f.isEmpty()) s.value else s.value.filter { series -> series.phonetic in f || series.members.any { it.kanji in f } }
                    Text(stringResource(R.string.kx_series_count, shown.size), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown.size, key = { shown[it].phonetic }) { i -> SeriesCard(shown[i], null, onOpenKanji) }
                    }
                }
            }
        }
    }
}

/** One sound series: the phonetic, its readings, and the family with each member's on'yomi. [current] is highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesCard(series: SoundSeries, current: String?, onOpenKanji: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JaText(series.phonetic, Modifier.clickable { onOpenKanji(series.phonetic) }, style = MaterialTheme.typography.headlineMedium)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.kx_series_phonetic), style = MaterialTheme.typography.labelMedium)
                    if (series.readings.isNotEmpty()) JaText(series.readings.joinToString("・"), style = MaterialTheme.typography.bodyLarge)
                }
                if (series.derived) DerivedTag()
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                series.family.forEach { m ->
                    val isCurrent = m.kanji == current
                    Card(
                        Modifier.clickable { onOpenKanji(m.kanji) }.widthIn(min = 56.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            JaText(m.kanji, style = MaterialTheme.typography.titleLarge)
                            if (m.onyomi.isNotEmpty()) JaText(m.onyomi.take(2).joinToString("・"), style = MaterialTheme.typography.labelSmall)
                            if (m.match != "same") Text(matchLabel(m.match), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun matchLabel(match: String?): String = when (match) {
    "same" -> stringResource(R.string.kx_match_same)
    "related" -> stringResource(R.string.kx_match_related)
    "shared" -> stringResource(R.string.kx_match_shared)
    null, "" -> ""
    else -> match
}

/** "derived": the build script's guess from KanjiVG + KANJIDIC2, not reviewed yet (D-281). */
@Composable
internal fun DerivedTag() {
    val description = stringResource(R.string.kx_derived_description)
    Tag(stringResource(R.string.kx_derived), Modifier.semantics { contentDescription = description })
}

/** Kanji built from typed parts ("氵青", "木+目"), most frequent first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentSearchScreen(query: String, onOpenKanji: (String) -> Unit) {
    val graph = rememberGraph()
    var text by rememberSaveable { mutableStateOf(query) }
    var submitted by rememberSaveable { mutableStateOf(query) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ExplorerLoad<ComponentSearchResult>?>(null) }
    LaunchedEffect(submitted, attempt) {
        if (submitted.isBlank()) { state = null; return@LaunchedEffect }
        state = ExplorerLoad.Loading
        state = loadExplorer({ graph.kanjiExplorer() }) { it.componentSearch(submitted) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.kx_component_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp), singleLine = true,
            label = { Text(stringResource(R.string.kx_component_label)) },
            textStyle = MaterialTheme.typography.titleMedium.japanese(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = text }),
        )
        Button(onClick = { submitted = text }, enabled = text.isNotBlank()) { Text(stringResource(R.string.kx_component_find)) }
        when (val s = state) {
            null -> Unit
            ExplorerLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ExplorerLoad.Missing -> Notice(stringResource(R.string.kx_no_dictionary))
            is ExplorerLoad.Failed -> ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { attempt++ })
            is ExplorerLoad.Ready -> {
                val r = s.value
                if (r.components.isNotEmpty()) Text(stringResource(R.string.kx_component_parts, r.components.joinToString(" ")), style = MaterialTheme.typography.labelLarge.japanese())
                if (r.unknown.isNotEmpty()) Notice(stringResource(R.string.kx_component_unknown, r.unknown.joinToString(" ")))
                if (r.kanji.isEmpty()) {
                    Text(stringResource(R.string.kx_component_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.kx_component_count, r.kanji.size), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.kanji.forEach { k ->
                            Card(Modifier.clickable { onOpenKanji(k.literal) }.widthIn(min = 64.dp)) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    JaText(k.literal, style = MaterialTheme.typography.headlineSmall)
                                    Text(k.keyword, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Bookmark to SRS" (`KanjiBookmarks`): adds the kanji to reviews, or says it's already there. */
@Composable
internal fun BookmarkButton(literal: String) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var bookmarked by remember(literal) { mutableStateOf<Boolean?>(null) }
    var busy by remember(literal) { mutableStateOf(false) }
    var error by remember(literal) { mutableStateOf<String?>(null) }
    LaunchedEffect(literal) { bookmarked = runCatching { graph.kanjiBookmarks.isBookmarked(literal) }.getOrNull() ?: false }
    Column {
        if (bookmarked == true) {
            OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.kx_bookmarked)) }
        } else {
            Button(
                enabled = bookmarked == false && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val info = graph.dictionary()?.kanji(literal)?.info
                            if (info == null) error = "" else {
                                graph.kanjiBookmarks.bookmark(info)
                                bookmarked = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.readable()
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.kx_bookmark)) }
        }
        error?.let { Text(if (it.isBlank()) stringResource(R.string.kx_bookmark_missing) else stringResource(R.string.kx_bookmark_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** "Kanji with these parts": the kanji's own parts as a component query. */
@Composable
internal fun ComponentQueryButton(literal: String, onComponentSearch: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            val q = runCatching { graph.kanjiExplorer()?.componentQuery(literal) }.getOrNull().orEmpty()
            onComponentSearch(q.ifBlank { literal })
        }
    }) { Text(stringResource(R.string.kx_search_parts)) }
}
