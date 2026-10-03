package app.mokuhyo.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.lang.Languages
import app.mokuhyo.srs.Rating
import app.mokuhyo.srs.ReviewCard

/** The per-language FSRS queue (BRIEF §2 Review): missed questions, looked-up words, recurring errors. */
@Composable
fun ReviewScreen(app: AppGraph) {
    val lang by app.language.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val due = remember(lang, version) { app.reviews.due(app.learnerId, lang, 100) }
    var shown by remember(lang, version) { mutableStateOf(false) }
    var lastUndo by remember(lang) { mutableStateOf<Pair<String, String>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(lang, version) { runCatching { focus.requestFocus() } }
    val card: ReviewCard? = due.firstOrNull()
    fun grade(r: Rating) {
        val c = card ?: return
        lastUndo = app.reviews.grade(c.item.id, r) to c.item.id
        version++
    }
    Page("Review", "${Languages.of(lang)?.nameEnglish} · ${due.size} due · ${app.reviews.total(app.learnerId, lang)} in the queue") {
        if (card == null) {
            EmptyState("Nothing due", "Words you look up and questions you miss are added here and come back on a spaced schedule.")
        } else {
            SectionCard(modifier = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.Enter -> { shown = true; true }
                    Key.One -> { if (shown) grade(Rating.AGAIN); true }
                    Key.Two -> { if (shown) grade(Rating.HARD); true }
                    Key.Three -> { if (shown) grade(Rating.GOOD); true }
                    Key.Four -> { if (shown) grade(Rating.EASY); true }
                    else -> false
                }
            }) {
                Text(when (card.item.kind) { "WORD" -> "Word"; "ITEM" -> "Missed question"; "TERM" -> "Military term"; "PRAGMATIC" -> "Cultural note"; else -> "Recurring error" },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(card.item.front, fontFamily = Fonts.forLanguage(lang), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                card.item.context?.takeIf { it.isNotBlank() && card.item.kind != "WORD" }?.let {
                    Text(it, fontFamily = Fonts.forLanguage(lang), style = MaterialTheme.typography.bodyMedium)
                }
                if (!shown) {
                    Button(onClick = { shown = true }) { Text("Show answer (Space)") }
                } else {
                    Text(card.item.back, fontFamily = Fonts.forLanguage(lang), style = MaterialTheme.typography.bodyLarge)
                    if (card.item.kind == "WORD") card.item.context?.let { Text("“$it”", fontFamily = Fonts.forLanguage(lang), style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { grade(Rating.AGAIN) }) { Text("1 Again") }
                        OutlinedButton(onClick = { grade(Rating.HARD) }) { Text("2 Hard") }
                        Button(onClick = { grade(Rating.GOOD) }) { Text("3 Good") }
                        OutlinedButton(onClick = { grade(Rating.EASY) }) { Text("4 Easy") }
                    }
                }
            }
        }
        lastUndo?.let { (reviewId, itemId) ->
            TextButton(onClick = {
                app.reviews.undo(reviewId, itemId)
                lastUndo = null
                version++
            }) { Text("Undo last answer") }
        }
    }
}
