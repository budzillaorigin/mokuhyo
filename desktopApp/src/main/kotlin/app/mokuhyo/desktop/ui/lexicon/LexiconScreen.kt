package app.mokuhyo.desktop.ui.lexicon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.PassageAudio
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.CheckRow
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.Page
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.desktop.ui.SuggestButton
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.lexicon.Dialogue
import app.mokuhyo.lexicon.Drill
import app.mokuhyo.lexicon.Drills
import app.mokuhyo.lexicon.Track
import app.mokuhyo.lexicon.TrackTerm
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.srs.ReviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LexiconTab(val title: String) { TERMS("Terms"), DRILLS("Drills"), DIALOGUES("Dialogues"), WHATS_NEW("What's new") }

/**
 * Lexicon (BRIEF_PHASE8 §B.3): the Counter-UAS & Base Defense track — terms with both-language definitions and their
 * sources, drills, and listening dialogues — plus lexicon updates (C-04). Everything AI-drafted is badged.
 */
@Composable
fun LexiconScreen(app: AppGraph) {
    val lang by app.language.collectAsState()
    val module = remember(lang) { app.languages.module(lang) }
    var imported by remember { mutableIntStateOf(0) }
    val track = remember(lang, imported) { app.lexicon(lang) }
    var tab by remember { mutableStateOf(LexiconTab.TERMS) }
    Page("Lexicon", "${module.nameEnglish} · ${track?.title ?: "Counter-UAS & Base Defense"}") {
        if (track == null) {
            EmptyState("No lexicon for ${module.nameEnglish} in this build", "The track pack (track-cuas-base-defense.json) isn't installed.")
            return@Page
        }
        val update = remember(lang, imported) { app.lexicons.current(lang, track.id, app.track(lang)) }
        if (update != null) Text("Includes lexicon update ${update.first.version}" + if (update.first.verified == 1L) " (verified publisher)" else
            " — unverified publisher: these terms were not signed by a publisher this app knows", style = MaterialTheme.typography.bodySmall,
            color = if (update.first.verified == 1L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        TabRow(selectedTabIndex = tab.ordinal) {
            LexiconTab.entries.forEach { t -> Tab(tab == t, { tab = t }, text = { Text(t.title) }) }
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            LexiconTab.TERMS -> TermsTab(app, module, track)
            LexiconTab.DRILLS -> DrillsTab(app, module, track)
            LexiconTab.DIALOGUES -> DialoguesTab(app, module, track)
            LexiconTab.WHATS_NEW -> LexiconUpdates(app, module)
        }
        Text(track.attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TermsTab(app: AppGraph, module: LanguageModule, track: Track) {
    var domain by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var page by remember(domain, query) { mutableIntStateOf(0) }
    val added = remember { mutableStateListOf<String>() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(domain == null, { domain = null }, label = { Text("All · ${track.terms.size}") })
        track.domains.forEach { d ->
            FilterChip(domain == d, { domain = d }, label = { Text("${Track.DOMAIN_TITLES[d] ?: d} · ${track.terms.count { it.domain == d }}") })
        }
    }
    SuggestButton(app, module.code, "suggest_term", "term", "", query)
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search (English or ${module.nameEnglish})") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
    val q = module.normalizeForCompare(query.trim())
    val shown = track.terms.filter { t ->
        (domain == null || t.domain == domain) &&
            (q.isEmpty() || module.normalizeForCompare(t.term).contains(q) || t.termEn.lowercase().contains(query.trim().lowercase()))
    }.sortedWith(compareBy({ it.priority }, { it.termEn.lowercase() }))
    val pageSize = 20
    val pages = (shown.size + pageSize - 1) / pageSize
    Text("${shown.size} terms" + if (pages > 1) " · page ${page + 1} of $pages" else "", style = MaterialTheme.typography.bodySmall)
    shown.drop(page * pageSize).take(pageSize).forEach { t -> TermCard(app, module, t, t.id in added, { track.sources[it] ?: it }) { added += t.id } }
    if (pages > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
        OutlinedButton(enabled = page < pages - 1, onClick = { page++ }) { Text("Next") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermCard(app: AppGraph, module: LanguageModule, t: TrackTerm, added: Boolean, sourceTitle: (String) -> String, onAdd: () -> Unit) {
    val scope = rememberCoroutineScope()
    SectionCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.term, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (t.termKind.isNotEmpty()) Badge(t.termKind)
            t.badgeLabel?.let { Badge(it, MaterialTheme.colorScheme.tertiaryContainer) }
            if (t.radioEnglish) Badge("Radio: English word")
        }
        Text(t.termEn, style = MaterialTheme.typography.titleSmall)
        if (t.definition.isNotBlank()) Text(t.definition, fontFamily = Fonts.forLanguage(module.code))
        Text(t.definitionEn, style = MaterialTheme.typography.bodyMedium)
        Text("English definition: ${t.englishCitation()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val eq = t.equivalents.firstOrNull()
        Text(
            if (eq != null && eq.source.isNotEmpty()) "${module.nameEnglish} term confirmed in: ${sourceTitle(eq.source)}, p. ${eq.page}"
            else "${module.nameEnglish} term: proposed by the model, not yet confirmed in an allied source",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        t.examples.forEach { ex ->
            Column {
                Text(ex.text, fontFamily = Fonts.forLanguage(module.code))
                if (ex.english.isNotBlank()) Text(ex.english, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (t.collocations.isNotEmpty()) Text("Collocations: " + t.collocations.joinToString(" · "), fontFamily = Fonts.forLanguage(module.code),
            style = MaterialTheme.typography.bodySmall)
        if (t.registerNote.isNotBlank()) Text("Register: ${t.registerNote}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) { app.speech.synthesize(t.term, module.code)?.let { runCatching { AudioIO.play(it.wav) } } }
            }) { Text("▶ Listen") }
            TextButton(enabled = !added, onClick = {
                app.reviews.add(app.learnerId, module.code, ReviewService.Kind.TERM, "term:${t.id}", t.term, "${t.termEn}\n${t.definition}", t.examples.firstOrNull()?.text)
                onAdd()
            }) { Text(if (added) "Added" else "Add to review") }
            SuggestButton(app, module.code, "flag", "term", t.id, t.term)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrillsTab(app: AppGraph, module: LanguageModule, track: Track) {
    val kinds = listOf("meaning" to "Meaning", "fill_in" to "Fill in", "register" to "Register", "brevity" to "Radio brevity")
    var kind by remember { mutableStateOf("meaning") }
    val drills = remember(kind) { track.drills.filter { it.kind == kind }.shuffled() }
    var index by remember(kind) { mutableIntStateOf(0) }
    var right by remember(kind) { mutableIntStateOf(0) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { (k, title) -> FilterChip(kind == k, { kind = k }, label = { Text("$title · ${track.drills.count { it.kind == k }}") }) }
    }
    Text(when (kind) {
        "meaning" -> "Read the ${module.nameEnglish} term and choose its English meaning."
        "fill_in" -> "Choose the term that completes the sentence."
        "register" -> "Choose the version that fits the situation and the rank of the person you are speaking to."
        else -> "Hear or read the English brevity word, then say or type what the partner force says on the radio, and read it back."
    })
    val d = drills.getOrNull(index)
    if (d == null) {
        EmptyState(if (drills.isEmpty()) "No drills of this kind" else "Done", if (drills.isEmpty()) "This track has none for ${module.nameEnglish}." else "$right of ${drills.size} right.")
        if (drills.isNotEmpty()) Button(onClick = { index = 0; right = 0 }) { Text("Again") }
        return
    }
    Text("${index + 1} of ${drills.size} · $right right", style = MaterialTheme.typography.bodySmall)
    DrillCard(app, module, track, d) { ok ->
        if (ok) right++
        index++
    }
}

@Composable
private fun DrillCard(app: AppGraph, module: LanguageModule, track: Track, d: Drill, next: (Boolean) -> Unit) {
    var chosen by remember(d.id) { mutableStateOf<Int?>(null) }
    var typed by remember(d.id) { mutableStateOf("") }
    var checked by remember(d.id) { mutableStateOf(false) }
    val term = track.term(d.termId)
    val scope = rememberCoroutineScope()
    SectionCard {
        Text(d.prompt, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleLarge)
        Badge("AI-drafted · unreviewed", MaterialTheme.colorScheme.tertiaryContainer)
        if (d.kind == "brevity") {
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !checked, label = { Text("Radio form") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
            if (!checked) Button(onClick = { checked = true }) { Text("Check") }
        } else {
            d.choices.forEachIndexed { i, c ->
                val mark = when { chosen == null -> ""; i == d.answer -> "✓ "; i == chosen -> "✗ "; else -> "" }
                OutlinedButton(enabled = chosen == null, onClick = { chosen = i; checked = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(mark + c, fontFamily = Fonts.forLanguage(module.code))
                }
            }
        }
        if (checked) {
            val ok = if (d.kind == "brevity") Drills.correctTyped(d, typed, module::normalizeForCompare) else Drills.correct(d, chosen ?: -1)
            Text(if (ok) "Right." else if (d.kind == "brevity") "Expected: ${d.expected}" else "Not quite.", fontWeight = FontWeight.SemiBold,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontFamily = Fonts.forLanguage(module.code))
            if (d.explanation.isNotBlank()) Text(d.explanation, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.kind == "brevity" && d.expected.isNotBlank()) TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { app.speech.synthesize(d.expected, module.code)?.let { runCatching { AudioIO.play(it.wav) } } }
                }) { Text("▶ Hear the read-back") }
                Button(onClick = {
                    if (!ok && term != null) app.reviews.add(app.learnerId, module.code, ReviewService.Kind.TERM, "term:${term.id}", term.term,
                        "${term.termEn}\n${term.definition}", term.examples.firstOrNull()?.text)
                    next(ok)
                }) { Text(if (!ok && term != null) "Add to review and continue" else "Next") }
            }
        }
    }
}

@Composable
private fun DialoguesTab(app: AppGraph, module: LanguageModule, track: Track) {
    if (track.dialogues.isEmpty()) {
        EmptyState("No dialogues", "This track has no dialogues for ${module.nameEnglish} yet.")
        return
    }
    var open by remember { mutableStateOf<Dialogue?>(null) }
    track.dialogues.forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { open = if (open?.id == d.id) null else d }) { Text(d.title) }
            Text("ILR ${d.level}", style = MaterialTheme.typography.bodySmall)
            Badge("AI-drafted · unreviewed", MaterialTheme.colorScheme.tertiaryContainer)
        }
        if (open?.id == d.id) DialogueView(app, module, d)
    }
}

@Composable
private fun DialogueView(app: AppGraph, module: LanguageModule, d: Dialogue) {
    val scope = rememberCoroutineScope()
    var status by remember(d.id) { mutableStateOf("") }
    var showText by remember(d.id) { mutableStateOf(false) }
    var showEnglish by remember(d.id) { mutableStateOf(false) }
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                status = "Preparing audio…"
                scope.launch {
                    when (val r = PassageAudio(app).load(d.asPassage(module.code))) {
                        is PassageAudio.Result.Ready -> { status = "Playing (${r.source})"; withContext(Dispatchers.IO) { runCatching { AudioIO.play(r.wav) } }; status = "" }
                        is PassageAudio.Result.Unavailable -> { status = r.reason; showText = true }
                    }
                }
            }) { Text("▶ Play") }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        CheckRow(showText, { showText = it }) { Text("Show the transcript") }
        if (showText) CheckRow(showEnglish, { showEnglish = it }) { Text("Show English") }
        if (showText) d.lines.forEach { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(l.speaker, fontFamily = Fonts.forLanguage(module.code), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(140.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.text, fontFamily = Fonts.forLanguage(module.code))
                    if (showEnglish && l.english.isNotBlank()) Text(l.english, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
    }
}
