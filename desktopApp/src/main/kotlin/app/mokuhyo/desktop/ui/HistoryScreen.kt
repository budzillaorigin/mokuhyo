package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.lang.Languages
import app.mokuhyo.opi.Speaker
import app.mokuhyo.speech.AudioIO
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

private enum class Kind(val title: String) { ALL("All"), TESTS("Tests"), PRACTICE("Practice"), INTERVIEWS("Interviews"), CONVERSATIONS("Conversations") }

/** History (BRIEF §2): every attempt and conversation in this language, searchable; recordings playable. */
@Composable
fun HistoryList(app: AppGraph) {
    val lang by app.language.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.ALL) }
    var open by remember { mutableStateOf<String?>(null) }
    val attempts = remember(lang, version) { app.history.attempts(app.learnerId, lang) }
    val conversations = remember(lang, version) { app.conversations.list(app.learnerId, lang) }
    data class Row(val id: String, val at: Long, val title: String, val detail: String, val searchable: String, val kind: Kind, val conversation: Boolean, val local: Boolean)
    val rows = remember(attempts, conversations) {
        attempts.map { a ->
            val form = app.history.form(a)
            val correct = app.history.answers(a).count { it.correct }
            Row(a.id, a.startedAt, "${a.modality.lowercase().replaceFirstChar { it.uppercase() }} ${if (a.mode == "TEST") "test" else "practice"}",
                "$correct/${form.items.size} correct" + (a.ilrEstimate?.let { " · ILR $it" + if (a.provisional == 1L) " (provisional)" else "" } ?: ""),
                form.items.joinToString(" ") { it.stem + " " + it.type }, if (a.mode == "TEST") Kind.TESTS else Kind.PRACTICE, false, a.bank == "local")
        } + conversations.map { c ->
            val rating = app.conversations.rating(c)
            val stored = app.conversations.stored(c)
            Row(c.id, c.startedAt, when (c.kind) { "OPI_TEST" -> "Interview test"; "OPI" -> "Interview practice"; "SCENARIO" -> "Scenario: ${c.topic}"; else -> "Conversation: ${c.topic}" },
                "${stored.transcript.count { it.speaker == Speaker.LEARNER }} answers" + (rating?.estimate?.let { " · ILR $it" + if (rating.selfRated) " (self-rated)" else "" } ?: ""),
                stored.transcript.joinToString(" ") { it.text } + " " + (c.topic ?: ""), if (c.kind == "TOPIC" || c.kind == "SCENARIO") Kind.CONVERSATIONS else Kind.INTERVIEWS, true, false)
        }.sortedByDescending { it.at }
    }
    val shown = rows.filter { (kind == Kind.ALL || it.kind == kind) && (query.isBlank() || it.searchable.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true)) }
    Page("History", "${Languages.of(lang)?.nameEnglish} · ${rows.size} items") {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search questions, transcripts, topics") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Kind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.title) }) } }
        if (rows.isEmpty()) EmptyState("No history yet", "Practice sets, tests, interviews and conversations appear here.")
        shown.forEach { r ->
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.format(Instant.ofEpochMilli(r.at)), Modifier.width(150.dp))
                    Text(r.title, Modifier.width(260.dp), fontWeight = FontWeight.SemiBold)
                    Text(r.detail, Modifier.weight(1f))
                    if (r.local) Badge("Generated on this computer")
                    TextButton(onClick = { open = if (open == r.id) null else r.id }) { Text(if (open == r.id) "Hide" else "Open") }
                }
                if (open == r.id) {
                    if (r.conversation) ConversationDetail(app, lang, r.id) else AttemptDetail(app, lang, r.id)
                    TextButton(onClick = {
                        if (r.conversation) app.conversations.tombstone(r.id) else app.history.tombstoneAttempt(r.id)
                        version++
                    }) { Text("Delete from history") }
                }
            }
        }
    }
}

@Composable
private fun AttemptDetail(app: AppGraph, lang: String, id: String) {
    val a = app.history.attempts(app.learnerId, lang).firstOrNull { it.id == id } ?: return
    val form = app.history.form(a)
    val answers = app.history.answers(a).associateBy { it.itemId }
    app.history.scoring(a)?.byLevel?.takeIf { it.isNotEmpty() }?.let { lv -> Text(lv.joinToString(" · ") { "ILR ${it.key} ${it.correct}/${it.total}" }) }
    form.items.forEachIndexed { i, item ->
        val ans = answers[item.id]
        Column {
            Text("${i + 1}. ${item.stem}", fontWeight = FontWeight.SemiBold)
            Text("Your answer: ${ans?.choice?.let { item.choices.getOrNull(it) } ?: "—"}" + if (ans?.correct == true) " ✓" else " · Answer: ${item.choices[item.answer]}",
                color = if (ans?.correct == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConversationDetail(app: AppGraph, lang: String, id: String) {
    val c = app.conversations.get(id) ?: return
    val stored = app.conversations.stored(c)
    val recordings = app.conversations.recordings(id).associateBy { it.turnIndex?.toInt() }
    app.conversations.rating(c)?.let { r ->
        if (r.rationale.isNotBlank()) Text(r.rationale)
        r.nextSteps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
    }
    stored.transcript.forEachIndexed { i, t ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.speaker == Speaker.LEARNER) "You" else "Partner", Modifier.width(70.dp), fontWeight = FontWeight.SemiBold)
            Text(t.text, Modifier.weight(1f), fontFamily = Fonts.forLanguage(lang))
            recordings[i]?.let { rec ->
                val f = File(app.dataDir, rec.path)
                if (f.isFile) TextButton(onClick = { Thread { runCatching { AudioIO.play(f.readBytes()) } }.start() }) { Text("▶") }
            }
        }
    }
}
