package app.mokuhyo.desktop.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.lang.Languages
import app.mokuhyo.lexicon.LexiconDownload
import app.mokuhyo.lexicon.LexiconPackage
import app.mokuhyo.lexicon.LexiconRepository
import app.mokuhyo.lexicon.LexiconVerifier
import app.mokuhyo.lexicon.Publisher
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

/**
 * Settings → Content → "Import lexicon update" (BRIEF_PHASE8 C-04): from a file or a URL the learner types. The
 * package is checked against the shipped publisher keys; an unsigned package can still be imported after a warning
 * and is labelled "unverified publisher"; an invalid signature is refused. New terms go into Review.
 */
@Composable
fun ImportLexicon(app: AppGraph, onImported: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf<Pair<LexiconPackage.Parsed, String>?>(null) }

    fun finish(parsed: LexiconPackage.Parsed, origin: String, publisher: Publisher) {
        val m = parsed.pkg.manifest
        runCatching {
            app.lexicons.import(parsed, publisher, origin, app.track(m.lang, m.domain), app.reviews, app.learnerId)
        }.onSuccess { r ->
            status = if (r.alreadyImported) "Version ${m.version} for ${Languages.of(m.lang)?.nameEnglish ?: m.lang} was already imported."
            else "Imported version ${m.version} (${Languages.of(m.lang)?.nameEnglish ?: m.lang}): ${r.delta.added.size} new, " +
                "${r.delta.changed.size} changed, ${r.delta.removed.size} no longer listed. ${r.queued} new terms added to Review. See Lexicon → What's new."
            onImported()
        }.onFailure { status = "Not imported: ${it.message}" }
        pending = null
    }

    fun handle(text: String, origin: String) {
        val parsed = runCatching { LexiconPackage.parse(text) }.getOrElse { status = "Not a lexicon package: ${it.message}"; return }
        when (val p = LexiconVerifier.verify(parsed, app.trustedLexiconKeys)) {
            is Publisher.Verified -> finish(parsed, origin, p)
            is Publisher.Unsigned -> { pending = parsed to origin; status = "" }
            is Publisher.Invalid -> status = "Refused: ${p.reason}"
        }
    }

    SectionCard("Import lexicon update") {
        Text("Lexicon updates add and revise terms in a track without a new app version. Packages from the Mokuhyo project are signed; " +
            "the app checks the signature against the key it ships with.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                val d = FileDialog(null as Frame?, "Choose a lexicon package", FileDialog.LOAD).apply { file = "lexicon-*.json" }
                d.isVisible = true
                val name = d.file ?: return@OutlinedButton
                val f = File(d.directory, name)
                busy = true
                scope.launch {
                    val text = withContext(Dispatchers.IO) { runCatching { f.readText() } }
                    busy = false
                    text.onSuccess { handle(it, f.name) }.onFailure { status = "Couldn't read ${f.name}: ${it.message}" }
                }
            }) { Text("Choose a file…") }
            Text("or", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Package URL (https://…)") })
        Text("Fetching from a URL is the only time this goes online, and only when you press Download.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && url.startsWith("http"), onClick = {
                busy = true
                status = "Downloading…"
                job = scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { LexiconDownload(Java.create()).fetch(url) } }
                    busy = false
                    r.onSuccess { handle(it, url) }.onFailure { status = "Download failed: ${it.message}" }
                }
            }) { Text("Download and import") }
            if (busy) TextButton(onClick = { job?.cancel(); busy = false; status = "Cancelled." }) { Text("Cancel") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        pending?.let { (parsed, origin) ->
            SectionCard {
                Text("Unverified publisher", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text("This package (${parsed.pkg.manifest.publisher}, version ${parsed.pkg.manifest.version}) is not signed by a publisher this app knows. " +
                    "Its terms will be labelled \"unverified publisher\". Import it only if you trust where it came from.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { finish(parsed, origin, Publisher.Unsigned) }) { Text("Import anyway") }
                    TextButton(onClick = { pending = null; status = "Not imported." }) { Text("Don't import") }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Lexicon → What's new: every imported package for the language, newest first, with its added and changed terms. */
@Composable
fun LexiconUpdates(app: AppGraph, module: LanguageModule) {
    var version by remember { mutableIntStateOf(0) }
    val packages = remember(module.code, version) { app.lexicons.packages(module.code) }
    if (packages.isEmpty()) EmptyState("No lexicon updates imported", "Updates you import (Settings → Content, or below) are listed here with what they add and change.")
    packages.forEach { p ->
        val delta: LexiconRepository.DeltaIds = remember(p.id) { app.lexicons.delta(p) }
        val terms = remember(p.id) { app.lexicons.terms(p.id).associateBy { it.id } }
        SectionCard("Version ${p.version} · ${dateFmt.format(Instant.ofEpochMilli(p.importedAt))}") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.verified == 1L) Badge("Verified publisher: ${p.publisher}") else Badge("Unverified publisher", MaterialTheme.colorScheme.errorContainer)
                Badge("${terms.size} terms")
            }
            Text("${delta.added.size} new · ${delta.changed.size} changed · ${delta.removed.size} no longer listed" +
                (p.previousVersion?.let { " · follows $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            if (delta.added.isNotEmpty()) Text("New (added to Review)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            delta.added.mapNotNull(terms::get).take(50).forEach { t -> Text("${t.term} — ${t.termEn}", fontFamily = Fonts.forLanguage(module.code)) }
            if (delta.changed.isNotEmpty()) Text("Changed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            delta.changed.mapNotNull(terms::get).take(50).forEach { t -> Text("${t.term} — ${t.termEn}", fontFamily = Fonts.forLanguage(module.code)) }
            if (delta.removed.isNotEmpty()) Text("No longer listed: ${delta.removed.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Text("From ${p.origin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ImportLexicon(app) { version++ }
}
