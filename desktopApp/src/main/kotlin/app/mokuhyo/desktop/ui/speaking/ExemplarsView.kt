package app.mokuhyo.desktop.ui.speaking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.Page
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.opi.Exemplar
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.OggOpus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Speaking → Exemplars (BRIEF_PHASE8 N-05): model answers at ILR 1+, 2 and 3 for 30 interview questions, with audio. */
@Composable
fun ExemplarsBrowse(app: AppGraph, module: LanguageModule, close: () -> Unit) {
    val pack = remember(module.code) { app.exemplars(module.code) }
    Page("Exemplar answers", "${module.nameEnglish} · what ILR 1+, 2 and 3 sound like for the same question") {
        if (pack == null || pack.exemplars.isEmpty()) EmptyState("No exemplars in this build", "The exemplar pack isn't installed for ${module.nameEnglish}.")
        pack?.byQuestion()?.forEach { (head, answers) ->
            SectionCard {
                Text(head.prompt, fontFamily = Fonts.forLanguage(module.code), fontWeight = FontWeight.SemiBold)
                if (head.english.isNotBlank()) Text(head.english, style = MaterialTheme.typography.bodySmall)
                ExemplarAnswers(app, module, answers)
            }
        }
        TextButton(onClick = close) { Text("Back") }
    }
}

/** The 1+/2/3 answers with notes and ▶ (pre-rendered clip, else the voice service). */
@Composable
fun ExemplarAnswers(app: AppGraph, module: LanguageModule, answers: List<Exemplar>) {
    val scope = rememberCoroutineScope()
    answers.forEach { e ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ILR ${e.level}", fontWeight = FontWeight.SemiBold)
                if (!e.verified) Badge("AI-drafted · unreviewed", MaterialTheme.colorScheme.tertiaryContainer)
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val clip = e.audio?.let { app.packFile(module.code, it) }?.readBytes()?.let { if (OggOpus.isOggOpus(it)) AudioIO.wav(OggOpus.decode(it)) else it }
                        val wav = clip ?: app.speech.synthesize(e.response, module.code)?.wav
                        wav?.let { runCatching { AudioIO.play(it) } }
                    }
                }) { Text("▶") }
            }
            Text(e.response, fontFamily = Fonts.forLanguage(module.code))
            Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Compare with model answers" under a learner's interview answer. */
@Composable
fun CompareWithExemplars(app: AppGraph, module: LanguageModule, question: String) {
    val answers = remember(question) { app.exemplars(module.code)?.forQuestion(question).orEmpty() }
    if (answers.isEmpty()) return
    var open by remember(question) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide model answers" else "Compare with model answers (ILR 1+ / 2 / 3)") }
    if (open) ExemplarAnswers(app, module, answers)
}
