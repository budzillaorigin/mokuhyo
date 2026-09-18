package app.tsumugi.android.features.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.Exports
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.review.ContentReviewService
import app.tsumugi.review.ReviewCandidate
import app.tsumugi.review.ReviewKind
import app.tsumugi.review.ReviewVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.tsumugi.review.Verdict as ReviewDecision

/**
 * Me → Content review (G-16, D-118), behind a developer toggle: the unverified AI-drafted content of the installed
 * packs, accept / edit / reject with a note, and an export of the verdicts that `tools/items/review.py --ingest`
 * applies. Nothing here flips a badge in the app: that happens when the rebuilt pack ships.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentReviewScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf<ContentReviewService?>(null) }
    var kind by remember { mutableStateOf<ReviewKind?>(null) }
    var onlyOpen by remember { mutableStateOf(true) }
    var queue by remember { mutableStateOf<List<Pair<ReviewCandidate, ReviewVerdict?>>?>(null) }
    var summary by remember { mutableStateOf<Map<ReviewKind, Pair<Int, Int>>>(emptyMap()) }
    var open by remember { mutableStateOf<Pair<ReviewCandidate, ReviewVerdict?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reviewer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            val s = service ?: graph.contentReview().also { service = it }
            queue = s.queue(kind)
            summary = s.summary()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        }
    }
    LaunchedEffect(kind) { load() }

    open?.let { current ->
        ReviewDetail(
            current.first, current.second,
            onDecide = { verdict, notes, edits ->
                scope.launch {
                    runCatching { service?.decide(current.first, verdict, notes, edits) }.onFailure { error = it.readable() }
                    open = null
                    load()
                }
            },
            onUndo = {
                scope.launch {
                    runCatching { service?.undo(current.first.kind, current.first.id) }
                    open = null
                    load()
                }
            },
            onClose = { open = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.review_tool_intro), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(kind == null, { kind = null }, { Text(stringResource(R.string.filter_all)) })
            ReviewKind.entries.forEach { k ->
                val (total, decided) = summary[k] ?: (0 to 0)
                if (total > 0) FilterChip(kind == k, { kind = k }, { Text("${k.label} $decided/$total") })
            }
        }
        FilterChip(onlyOpen, { onlyOpen = !onlyOpen }, { Text(stringResource(R.string.review_tool_only_open)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(reviewer, { reviewer = it }, Modifier.fillMaxWidth(0.6f), label = { Text(stringResource(R.string.review_tool_reviewer)) }, singleLine = true)
            Button(onClick = {
                scope.launch {
                    message = try {
                        val json = service?.exportJson(reviewer.trim()) ?: return@launch
                        val file = Exports.file(context, "tsumugi-review-verdicts-${java.time.LocalDate.now()}.json")
                        withContext(Dispatchers.IO) { file.writeText(json) }
                        if (Exports.share(context, file, "application/json", context.getString(R.string.export_share))) null else context.getString(R.string.export_no_app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.readable()
                    }
                }
            }, enabled = reviewer.isNotBlank() && summary.values.any { it.second > 0 }) { Text(stringResource(R.string.review_tool_export)) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { load() } }) }
        val q = queue
        when {
            q == null && error == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            q != null && q.isEmpty() -> Notice(stringResource(R.string.review_tool_empty))
            q != null -> LazyColumn {
                items(q.filter { !onlyOpen || it.second == null }, key = { it.first.kind.code + it.first.id }) { entry ->
                    val (c, v) = entry
                    ListItem(
                        modifier = Modifier.clickable { open = entry },
                        headlineContent = { JaText(c.title, maxLines = 2) },
                        supportingContent = { Text("${c.kind.label} · ${c.id}") },
                        trailingContent = { Text(v?.verdict?.code ?: "—", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewDetail(
    candidate: ReviewCandidate,
    verdict: ReviewVerdict?,
    onDecide: (ReviewDecision, String, Map<String, String>) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    val fields = remember(candidate.id) { mutableStateMapOf<String, String>().apply { putAll(candidate.fields); verdict?.edits?.let { putAll(it) } } }
    var notes by remember(candidate.id) { mutableStateOf(verdict?.notes.orEmpty()) }
    val changed = fields.any { (k, v) -> candidate.fields[k] != v }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.nav_back)) }
        JaText(candidate.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text("${candidate.kind.label} · ${candidate.id}", style = MaterialTheme.typography.labelMedium)
        if (candidate.source == "llm") AiBadge()
        verdict?.let { Text(stringResource(R.string.review_tool_current, it.verdict.code), style = MaterialTheme.typography.bodyMedium) }
        if (candidate.display.isNotBlank()) {
            JaText(candidate.display, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
        }
        candidate.fields.keys.forEach { key ->
            OutlinedTextField(
                fields[key].orEmpty(), { fields[key] = it }, Modifier.fillMaxWidth(),
                label = { Text(key) }, textStyle = MaterialTheme.typography.bodyLarge.japanese(),
            )
        }
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.review_tool_notes)) }, minLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDecide(ReviewDecision.ACCEPT, notes, emptyMap()) }) { Text(stringResource(R.string.review_tool_accept)) }
            OutlinedButton(onClick = { onDecide(ReviewDecision.EDIT, notes, fields.toMap()) }, enabled = changed) { Text(stringResource(R.string.review_tool_edit)) }
            OutlinedButton(onClick = { onDecide(ReviewDecision.REJECT, notes, emptyMap()) }, enabled = notes.isNotBlank()) { Text(stringResource(R.string.review_tool_reject)) }
            if (verdict != null) TextButton(onClick = onUndo) { Text(stringResource(R.string.action_undo)) }
        }
        Text(stringResource(R.string.review_tool_reject_hint), style = MaterialTheme.typography.bodySmall)
    }
}
