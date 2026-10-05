package app.mokuhyo.desktop.ui.speaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.Disclaimer
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.desktop.ui.SuggestButton
import app.mokuhyo.opi.AfterActionBrief
import app.mokuhyo.opi.CorrectionsMode
import app.mokuhyo.opi.PragmaticFlag
import app.mokuhyo.opi.TurnFeedbackRecord
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.srs.ReviewService
import java.io.File

/** The label every cultural-appropriateness block carries (BRIEF_PHASE8 C-06 hard rule). */
const val NOT_ILR = "Not part of the ILR scale — it does not change your level or rating."

/**
 * The After Action Brief screen (BRIEF_PHASE8 §B.5): summary, turn-by-turn review with audio, patterns, cultural
 * appropriateness. The interview part (phase map, rating, evidence) is shown by the interview results above it.
 */
@Composable
fun AfterActionBriefView(app: AppGraph, lang: String, aab: AfterActionBrief, conversationId: String, questionFor: ((Int) -> String?)? = null) {
    val queued = remember { mutableStateListOf<String>() }
    val mode = CorrectionsMode.of(aab.mode) ?: CorrectionsMode.AFTER_ACTION
    SectionCard("After Action Brief") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(aab.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Badge(mode.title)
            aab.engine?.let { Badge("AI-generated · $it") }
        }
        Text("%d:%02d · %d turns".format(aab.durationSec / 60, aab.durationSec % 60, aab.turns) + (aab.persona?.let { " · with $it" } ?: ""))
        if (aab.levelTrack.isNotEmpty()) Text("Level by turn: " + aab.levelTrack.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
        if (!mode.records) Text("Corrections were off for this session: no feedback was generated.", style = MaterialTheme.typography.bodySmall)
        if (aab.nextSteps.isNotEmpty()) {
            Text("Three things to work on next", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            aab.nextSteps.forEachIndexed { i, s -> Text("${i + 1}. $s") }
        }
        Disclaimer()
    }
    if (aab.records.isNotEmpty()) SectionCard("Turn by turn") {
        aab.records.forEach { r ->
            TurnReview(app, lang, r, conversationId, queued)
            // Interviews (BRIEF_PHASE8 N-05): model answers for the same question, linked from the brief.
            questionFor?.invoke(r.turnIndex)?.let { q -> CompareWithExemplars(app, app.languages.module(lang), q) }
        }
        Button(enabled = aab.records.any { it.changes.isNotEmpty() || it.pragmatics.isNotEmpty() }, onClick = {
            aab.records.forEach { r -> queue(app, lang, r, queued) }
        }) { Text("Queue all corrections and cultural notes to Review") }
    }
    val p = aab.patterns
    if (p.grammar.isNotEmpty() || p.register.isNotEmpty() || p.avoidance.isNotEmpty() || aab.fluency != null) SectionCard("Patterns") {
        if (p.grammar.isNotEmpty()) { Text("Recurring grammar", fontWeight = FontWeight.SemiBold); p.grammar.forEach { Text("• $it", fontFamily = Fonts.forLanguage(lang)) } }
        if (p.register.isNotEmpty()) { Text("Register slips", fontWeight = FontWeight.SemiBold); p.register.forEach { Text("• $it", fontFamily = Fonts.forLanguage(lang)) } }
        if (p.avoidance.isNotEmpty()) { Text("Avoidance", fontWeight = FontWeight.SemiBold); p.avoidance.forEach { Text("• $it") } }
        aab.fluency?.let { f ->
            Text("Fluency (heuristic): composite ${f.composite}/100 · ${f.wordsPerMinute} words/min · pauses ${f.pausePercent}% · false starts ${f.falseStarts}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (aab.cultural.isNotEmpty()) SectionCard("Cultural appropriateness") {
        Text(NOT_ILR, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        aab.cultural.forEach { g ->
            Text(g.tag.replaceFirstChar { it.uppercase() } + (g.cardTitle?.let { " — card: $it" } ?: ""), fontWeight = FontWeight.SemiBold)
            g.flags.forEach { f -> FlagLine(f, lang) }
        }
    }
}

@Composable
private fun TurnReview(app: AppGraph, lang: String, r: TurnFeedbackRecord, conversationId: String, queued: MutableList<String>) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("You", fontWeight = FontWeight.SemiBold)
            Text(r.learner, fontFamily = Fonts.forLanguage(lang))
            val f = File(app.dataDir, "recordings/$conversationId/turn-%02d.wav".format(r.turnIndex))
            if (f.isFile) TextButton(onClick = { Thread { runCatching { AudioIO.play(f.readBytes()) } }.start() }) { Text("▶ my audio") }
        }
        r.corrected?.let { c ->
            Text("Correction: $c", fontFamily = Fonts.forLanguage(lang), color = MaterialTheme.colorScheme.primary)
            r.changes.forEach { ch -> Text("“${ch.from}” → “${ch.to}”: ${ch.why}", style = MaterialTheme.typography.bodySmall, fontFamily = Fonts.forLanguage(lang)) }
        }
        r.rewrite?.takeIf { it.isNotBlank() }?.let { rw ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("More natural: $rw", fontFamily = Fonts.forLanguage(lang))
                TextButton(onClick = { Thread { runCatching { kotlinx.coroutines.runBlocking { app.speech.synthesize(rw, lang) }?.let { AudioIO.play(it.wav) } } }.start() }) {
                    Text("▶ model version")
                }
            }
        }
        r.vocabulary.forEach { v -> Text("${v.word} — ${v.meaning}", fontFamily = Fonts.forLanguage(lang), style = MaterialTheme.typography.bodySmall) }
        r.pragmatics.forEach { FlagLine(it, lang) }
        r.partnerReply?.let { Text("Partner: $it", fontFamily = Fonts.forLanguage(lang), style = MaterialTheme.typography.bodySmall) }
        val key = "turn:${r.turnIndex}"
        if (r.changes.isNotEmpty() || r.pragmatics.isNotEmpty()) TextButton(enabled = key !in queued, onClick = { queue(app, lang, r, queued) }) {
            Text(if (key in queued) "Added to Review" else "Add to review")
        }
        SuggestButton(app, lang, "flag", "turn", "$conversationId:${r.turnIndex}", r.learner, "Flag this feedback")
        HorizontalDivider()
    }
}

@Composable
fun FlagLine(f: PragmaticFlag, lang: String) {
    Column {
        Text("Cultural note · ${f.kind} (${f.severity}): ${f.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (f.better.isNotBlank()) Text("Try: ${f.better}", fontFamily = Fonts.forLanguage(lang), style = MaterialTheme.typography.bodySmall)
    }
}

private fun queue(app: AppGraph, lang: String, r: TurnFeedbackRecord, queued: MutableList<String>) {
    r.changes.forEach { c -> app.reviews.add(app.learnerId, lang, ReviewService.Kind.ERROR, "${c.from}→${c.to}", c.from, "${c.to}\n${c.why}") }
    r.pragmatics.forEach { f ->
        app.reviews.add(app.learnerId, lang, ReviewService.Kind.PRAGMATIC, "prag:${f.kind}:${f.what.take(60)}", f.what, "${f.better}\n${f.why}", f.kind)
    }
    queued += "turn:${r.turnIndex}"
}
