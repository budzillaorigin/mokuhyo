package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.lang.Languages
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Every attempt in this language, newest first (searchable history and recordings arrive with Phase 5). */
@Composable
fun HistoryList(app: AppGraph) {
    val lang by app.language.collectAsState()
    val attempts = remember(lang) { app.history.attempts(app.learnerId, lang) }
    Page("History", "${Languages.of(lang)?.nameEnglish} · ${attempts.size} attempts") {
        if (attempts.isEmpty()) EmptyState("No attempts yet", "Practice sets and tests you finish appear here.")
        attempts.forEach { a ->
            val form = app.history.form(a)
            val correct = app.history.answers(a).count { it.correct }
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(fmt.format(Instant.ofEpochMilli(a.startedAt)), Modifier.width(160.dp))
                    Text("${a.modality.lowercase().replaceFirstChar { it.uppercase() }} ${a.mode.lowercase()}", Modifier.width(160.dp))
                    Text("$correct/${form.items.size} correct", Modifier.width(120.dp))
                    Text(a.ilrEstimate?.let { "ILR $it" + if (a.provisional == 1L) " (provisional)" else "" } ?: "—")
                    if (a.bank == "local") Badge("Generated on this computer")
                }
                Text(form.items.map { it.level }.distinct().joinToString(" · ") { "ILR $it" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
