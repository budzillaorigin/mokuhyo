package app.tsumugi.android.features.study

import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.RecomputeBanner
import app.tsumugi.android.ui.readable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.localized
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.japanese
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.srs.LevelEntry
import app.tsumugi.srs.PathItemDetail
import app.tsumugi.srs.PathStatus
import kotlinx.coroutines.launch

@Composable
fun PathLevelsScreen(onOpenLevel: (Int) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<PathStatus?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var resetTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            val path = graph.path()
            missing = path == null
            status = path?.status()
        }.onFailure { error = it.readable() }
    }
    val s = status
    when {
        missing -> Text(stringResource(R.string.path_missing), Modifier.padding(24.dp))
        s == null -> error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { attempt++ }, modifier = Modifier.padding(16.dp)) }
            ?: Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item { RecomputeBanner(Modifier.padding(16.dp)) }
            error?.let { item { ErrorState(it, onRetry = { attempt++ }, modifier = Modifier.padding(16.dp)) } }
            items((1..s.maxLevel).toList()) { level ->
                ListItem(
                    modifier = Modifier.clickable { onOpenLevel(level) },
                    headlineContent = { Text(stringResource(R.string.title_level, level)) },
                    // Rule 11: the only way the level goes down is this explicit, confirmed reset (D-041).
                    trailingContent = if (level < s.currentLevel) {
                        { TextButton(onClick = { resetTarget = level }) { Text(stringResource(R.string.path_reset_action)) } }
                    } else {
                        null
                    },
                    supportingContent = {
                        Text(
                            when {
                                level < s.currentLevel -> stringResource(R.string.path_passed)
                                level == s.currentLevel -> stringResource(R.string.path_current, (s.levelProgress * 100).toInt())
                                else -> stringResource(R.string.path_locked)
                            },
                        )
                    },
                )
            }
        }
    }
    resetTarget?.let { level ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(stringResource(R.string.path_reset_title, level)) },
            text = { Text(stringResource(R.string.path_reset_text, level)) },
            confirmButton = {
                TextButton(onClick = {
                    resetTarget = null
                    scope.launch {
                        runCatching { graph.path()?.resetToLevel(level) }
                            .onSuccess { attempt++ }
                            .onFailure { error = it.readable() }
                    }
                }) { Text(stringResource(R.string.path_reset_confirm, level)) }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun PathLevelScreen(level: Int, onOpenItem: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var items by remember(level) { mutableStateOf<List<LevelEntry>?>(null) }
    LaunchedEffect(level) { items = graph.path()?.level(level) }
    val list = items ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in listOf(ItemKind.RADICAL, ItemKind.KANJI, ItemKind.VOCAB)) {
            val group = list.filter { it.item.kind == kind }
            if (group.isEmpty()) continue
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("${kind.localized()} (${group.count { it.stage != null }}/${group.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(group, key = { it.item.id }) { (item, stage) ->
                Column(
                    Modifier
                        .background(if (stage == null) Color.Gray.copy(alpha = 0.25f) else kindColor(kind), RoundedCornerShape(10.dp))
                        .clickable { onOpenItem(item.id) }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    JaText(item.display, style = MaterialTheme.typography.titleLarge, color = if (stage == null) MaterialTheme.colorScheme.onSurface else Color.White, maxLines = 1)
                    Text(stage?.localized() ?: stringResource(R.string.path_not_started_short), style = MaterialTheme.typography.labelSmall, color = if (stage == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White)
                }
            }
        }
    }
}

@Composable
fun PathItemScreen(id: String, onOpenItem: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<PathItemDetail?>(null) }
    LaunchedEffect(id) { detail = graph.path()?.detail(id) }
    val d = detail ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PathItemContent(d, onSaveStory = { story -> scope.launch { graph.path()?.saveMyStory(id, story) } }, onOpenItem = onOpenItem)
        if (d.stage == null) {
            Text(stringResource(R.string.path_not_started), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Stage distribution bar (Apprentice → Burned). */
@Composable
fun StageBar(counts: Map<Stage, Int>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Stage.entries.forEach { stage ->
            val n = counts[stage] ?: 0
            val max = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
            val label = stage.localized()
            // One node per stage for TalkBack ("Guru: 12"); the label column grows with the font instead of clipping.
            androidx.compose.foundation.layout.Row(
                Modifier.clearAndSetSemantics { contentDescription = "$label: $n" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.widthIn(min = 96.dp).padding(end = 8.dp), style = MaterialTheme.typography.labelMedium)
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(n.toFloat() / max)
                            .widthIn(min = 2.dp)
                            .height(14.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Text("  $n", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
