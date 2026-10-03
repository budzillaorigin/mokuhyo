package app.mokuhyo.desktop.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.lang.LanguageModule
import java.awt.Desktop
import java.net.URI

/**
 * Reading → This month (BRIEF_PHASE8 C-09): official defense press pages for the language, opened in the learner's own
 * browser. Mokuhyo fetches and stores nothing; the learner pastes a release into the reader below to practise it
 * with tap-to-define and the reading aids. Pasted text is not saved.
 */
@Composable
fun ThisMonth(app: AppGraph, module: LanguageModule) {
    val feeds = remember(module.code) { app.feeds(module.code) }
    var pasted by remember(module.code) { mutableStateOf("") }
    var reading by remember(module.code) { mutableStateOf<String?>(null) }
    var aids by remember { mutableStateOf(AidState()) }
    var note by remember { mutableStateOf("") }
    SectionCard("Current defense news — ${module.nameEnglish}") {
        Text("Official press and news pages. They open in your browser; Mokuhyo doesn't download or keep them. Copy a release you want to work on and paste it below.",
            style = MaterialTheme.typography.bodyMedium)
        if (feeds.isEmpty()) EmptyState("No links for ${module.nameEnglish}", "The pack has no current-events links.")
        feeds.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.title, fontFamily = Fonts.forLanguage(module.code), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(f.publisher, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    note = runCatching { Desktop.getDesktop().browse(URI(f.url)); "" }.getOrElse { "Couldn't open a browser: ${f.url}" }
                }) { Text("Open") }
            }
            if (f.reachability == "unreliable") Text("This site is often unreachable from some networks.", style = MaterialTheme.typography.bodySmall)
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
    }
    SectionCard("Paste text to practice") {
        OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Paste a ${module.nameEnglish} article or release") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = pasted.isNotBlank(), onClick = { reading = pasted.trim() }) { Text("Read it") }
            if (reading != null) TextButton(onClick = { reading = null; pasted = "" }) { Text("Clear") }
        }
        reading?.let { text ->
            AidToggles(module, aids) { aids = it }
            PassageText(app, module, text.take(20_000), aids, tapToDefine = true)
            Text("Tap a word for its meaning and add it to Review. The pasted text stays only on this screen.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
