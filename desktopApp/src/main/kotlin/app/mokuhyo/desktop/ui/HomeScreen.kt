package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.history.IlrPoint
import app.mokuhyo.lang.Languages
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

/** Home (BRIEF §2): ILR estimate per modality with trend, the next recommended activity, the review queue count. */
@Composable
fun HomeDashboard(app: AppGraph, navigate: (Destination) -> Unit) {
    val lang by app.language.collectAsState()
    var standTo by remember(lang) { mutableStateOf(false) }
    if (standTo) {
        StandToView(app, app.languages.module(lang)) { standTo = false }
        return
    }
    val points = remember(lang) { app.history.estimates(app.learnerId, lang) }
    val due = remember(lang) { app.reviews.dueCount(app.learnerId, lang) }
    val byModality = points.groupBy { it.modality }
    Page("Home", Languages.of(lang)?.nameEnglish) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            listOf("READING" to "Reading", "LISTENING" to "Listening", "SPEAKING" to "Speaking").forEach { (key, title) ->
                ModalityCard(title, byModality[key].orEmpty(), Modifier.weight(1f))
            }
        }
        StandToCard(app, lang) { standTo = true }
        val next = recommend(byModality, due)
        SectionCard("Next") {
            Text(next.first)
            Button(onClick = { navigate(next.second) }) { Text("Go to ${next.second.title}") }
        }
        SectionCard("Review queue") {
            Text(if (due == 0L) "Nothing due right now." else "$due card${if (due == 1L) "" else "s"} due.")
        }
        Disclaimer()
    }
}

@Composable
private fun ModalityCard(title: String, points: List<IlrPoint>, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val last = points.lastOrNull()
            Text(last?.let { "ILR ${it.value}" + if (it.provisional) "*" else "" } ?: "—", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(last?.let { "from ${points.size} test${if (points.size == 1) "" else "s"} · last ${dateFmt.format(Instant.ofEpochMilli(it.at))}" } ?: "No tests yet",
                style = MaterialTheme.typography.bodySmall)
            if (points.size >= 2) Text("Trend: " + points.takeLast(6).joinToString(" → ") { it.value }, style = MaterialTheme.typography.bodySmall)
            if (last?.provisional == true) Text("* provisional (short form)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun recommend(byModality: Map<String, List<IlrPoint>>, due: Long): Pair<String, Destination> = when {
    due >= 20 -> "You have $due reviews waiting — clear them first; they are the words and questions you missed." to Destination.REVIEW
    byModality["READING"].isNullOrEmpty() -> "Take a 30-minute Reading test to get a first estimate." to Destination.READING
    byModality["LISTENING"].isNullOrEmpty() -> "Take a 30-minute Listening test to get a first estimate." to Destination.LISTENING
    byModality["SPEAKING"].isNullOrEmpty() -> "Try an interview in Speaking to estimate your speaking level." to Destination.SPEAKING
    else -> {
        val weakest = byModality.minBy { (_, p) -> levelRank(p.last().value) }
        "Your lowest estimate is ${weakest.key.lowercase()} (ILR ${weakest.value.last().value}): practise one level above it." to
            when (weakest.key) { "READING" -> Destination.READING; "LISTENING" -> Destination.LISTENING; else -> Destination.SPEAKING }
    }
}

private fun levelRank(v: String) = listOf("0", "0+", "1", "1+", "2", "2+", "3", "3+", "4").indexOf(v)
