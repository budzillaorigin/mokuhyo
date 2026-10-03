package app.mokuhyo.desktop.ui

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
import androidx.compose.runtime.LaunchedEffect
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
import app.mokuhyo.desktop.PassageAudio
import app.mokuhyo.desktop.ui.exam.NumbersDrillView
import app.mokuhyo.desktop.ui.speaking.FlagLine
import app.mokuhyo.ai.AiResult
import app.mokuhyo.exam.Skill
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.numbers.NumberKind
import app.mokuhyo.opi.TurnFeedback
import app.mokuhyo.opi.TurnFeedbackRecord
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.srs.Rating
import app.mokuhyo.srs.ReviewService
import app.mokuhyo.standto.StandToPlanner
import app.mokuhyo.standto.StandToRepository
import app.mokuhyo.standto.StandToResult
import app.mokuhyo.standto.WeeklySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** Home → Daily stand-to card (BRIEF_PHASE8 N-12): one tap to start, and this week's summary. */
@Composable
fun StandToCard(app: AppGraph, lang: String, start: () -> Unit) {
    val week = remember(lang) {
        WeeklySummary.of(StandToRepository(app.db).since(app.learnerId, lang, System.currentTimeMillis() - 8 * 86_400_000L), System.currentTimeMillis(), ::dayOf)
    }
    SectionCard("Daily stand-to · 8–10 minutes") {
        Text("Numbers under pressure, three lexicon items, one listening clip at your level" +
            (if (app.languageModel() != null) " and one speaking turn (feedback at the end)." else ". (A speaking turn is added when an AI model is installed.)"))
        Button(onClick = start) { Text("Start today's stand-to") }
        if (week.sessions > 0) Text(
            "This week: ${week.days} day${if (week.days == 1) "" else "s"}, ${week.minutes} min" +
                (week.numbersAccuracy?.let { " · numbers $it% right" } ?: "") + " · ${week.termsReviewed} terms" +
                if (week.listeningTotal > 0) " · listening ${week.listeningRight}/${week.listeningTotal}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The guided stand-to: the planner's steps one after another, then a summary; saved to the stand_to log. */
@Composable
fun StandToView(app: AppGraph, module: LanguageModule, close: () -> Unit) {
    val startedAt = remember { System.currentTimeMillis() }
    val content = remember(module.code) { app.exam(module.code) }
    val recipe = remember(module.code) {
        val seen = app.history.practisedPassages(app.learnerId, module.code, Skill.LISTENING)
        val candidates = content?.passagesFor(Skill.LISTENING).orEmpty().filter { it.id !in seen }.groupBy { it.level }.mapValues { e -> e.value.map { it.id } }
        val level = app.history.estimates(app.learnerId, module.code).lastOrNull { it.modality == "LISTENING" }?.value
        StandToPlanner.plan(app.languageModel() != null, level, candidates, Random.Default)
    }
    var step by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf(StandToResult()) }
    var feedback by remember { mutableStateOf<TurnFeedbackRecord?>(null) }
    var saved by remember { mutableStateOf(false) }
    Page("Daily stand-to", "${module.nameEnglish} · step ${minOf(step + 1, recipe.steps.size)} of ${recipe.steps.size} · about ${recipe.minutes.toInt()} min") {
        LinearProgressIndicator(progress = { step.toFloat() / recipe.steps.size }, modifier = Modifier.fillMaxWidth())
        val s = recipe.steps.getOrNull(step)
        if (s == null) {
            if (!saved) {
                saved = true
                StandToRepository(app.db).save(app.learnerId, module.code, startedAt, System.currentTimeMillis(), recipe,
                    result.copy(minutes = (System.currentTimeMillis() - startedAt) / 60_000.0))
            }
            SectionCard("Stand-to complete") {
                Text("Numbers ${result.numbersRight}/${result.numbersTotal} · ${result.termsReviewed} terms reviewed" +
                    (result.listeningRight?.let { " · listening ${if (it) "right" else "missed"}" } ?: ""))
                feedback?.let { f ->
                    Text("Your speaking turn — After Action", fontWeight = FontWeight.SemiBold)
                    Text(f.learner, fontFamily = Fonts.forLanguage(module.code))
                    f.corrected?.let { Text("Correction: $it", fontFamily = Fonts.forLanguage(module.code), color = MaterialTheme.colorScheme.primary) }
                    f.rewrite?.let { Text("More natural: $it", fontFamily = Fonts.forLanguage(module.code)) }
                    f.pragmatics.forEach { FlagLine(it, module.code) }
                }
                Button(onClick = close) { Text("Done") }
            }
            return@Page
        }
        Text(s.kind.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when (s.kind) {
            StandToPlanner.StepKind.NUMBERS -> NumbersDrillView(app, module,
                listOf(NumberKind.TIME, NumberKind.GRID, NumberKind.BEARING, NumberKind.CALLSIGN, NumberKind.FREQUENCY, NumberKind.COUNT), s.count) { right, total ->
                result = result.copy(numbersRight = right, numbersTotal = total)
                step++
            }
            StandToPlanner.StepKind.LEXICON -> LexiconStep(app, module, s.count) { n -> result = result.copy(termsReviewed = n); step++ }
            StandToPlanner.StepKind.LISTENING -> ListeningStep(app, module, s.passageId!!) { ok -> result = result.copy(listeningRight = ok); step++ }
            StandToPlanner.StepKind.SPEAKING -> SpeakingStep(app, module, s.level ?: "1+") { rec -> feedback = rec; result = result.copy(spoke = rec != null); step++ }
        }
        TextButton(onClick = { step++ }) { Text("Skip this step") }
    }
}

@Composable
private fun LexiconStep(app: AppGraph, module: LanguageModule, count: Int, done: (Int) -> Unit) {
    val cards = remember(module.code) {
        val due = app.reviews.due(app.learnerId, module.code, 50).filter { it.item.kind == "TERM" }.map { it.item.id }
        val needed = count - due.size
        val fresh = if (needed > 0) app.lexicon(module.code)?.terms.orEmpty().filter { it.priority == 1 }.shuffled().take(needed).map { t ->
            app.reviews.add(app.learnerId, module.code, ReviewService.Kind.TERM, "term:${t.id}", t.term, "${t.termEn}\n${t.definition}", t.examples.firstOrNull()?.text)
        } else emptyList()
        (due + fresh).distinct().take(count)
    }
    var i by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val item = remember(i) { cards.getOrNull(i)?.let { id -> app.db.srsQueries.itemsAll().executeAsList().firstOrNull { it.id == id } } }
    if (item == null) {
        LaunchedEffect(Unit) { done(i) }
        return
    }
    SectionCard {
        Text(item.front, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.headlineSmall)
        if (!shown) Button(onClick = { shown = true }) { Text("Show meaning") } else {
            Text(item.back, fontFamily = Fonts.forLanguage(module.code))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.reviews.grade(item.id, Rating.AGAIN); shown = false; i++ }) { Text("Again") }
                Button(onClick = { app.reviews.grade(item.id, Rating.GOOD); shown = false; i++ }) { Text("Got it") }
            }
        }
        Text("${i + 1} of ${cards.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ListeningStep(app: AppGraph, module: LanguageModule, passageId: String, done: (Boolean) -> Unit) {
    val content = remember(module.code) { app.exam(module.code) }
    val passage = content?.passages?.get(passageId)
    val item = content?.items?.firstOrNull { it.passageId == passageId }
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }
    if (passage == null || item == null) {
        LaunchedEffect(Unit) { done(false) }
        return
    }
    SectionCard("ILR ${passage.level} · ${passage.title}") {
        Button(onClick = {
            status = "Preparing audio…"
            scope.launch {
                when (val a = PassageAudio(app).load(passage)) {
                    is PassageAudio.Result.Ready -> { status = ""; withContext(Dispatchers.IO) { runCatching { AudioIO.play(a.wav) } } }
                    is PassageAudio.Result.Unavailable -> status = a.reason
                }
            }
        }) { Text("▶ Play") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text(item.stem, fontWeight = FontWeight.SemiBold)
        item.choices.forEachIndexed { k, c ->
            OutlinedButton(enabled = chosen == null, onClick = { chosen = k }, modifier = Modifier.fillMaxWidth()) {
                Text((if (chosen != null && k == item.answer) "✓ " else if (chosen == k) "✗ " else "") + c)
            }
        }
        chosen?.let { c ->
            Text(item.explanation, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { done(c == item.answer) }) { Text("Next") }
        }
    }
}

@Composable
private fun SpeakingStep(app: AppGraph, module: LanguageModule, level: String, done: (TurnFeedbackRecord?) -> Unit) {
    val pack = remember(module.code) { app.opi(module.code) }
    val q = remember(module.code) {
        val pool = pack?.questions.orEmpty().filter { it.phase == "level_check" }
        pool.filter { it.level == level }.ifEmpty { pool }.randomOrNull()
    }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (q == null || pack == null) {
        LaunchedEffect(Unit) { done(null) }
        return
    }
    SectionCard("One question — feedback comes at the end") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope.launch(Dispatchers.IO) { app.speech.synthesize(q.prompt, module.code)?.let { runCatching { AudioIO.play(it.wav) } } } }) {
                Text("▶ Listen")
            }
            Text(q.prompt, fontFamily = Fonts.forLanguage(module.code))
        }
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("Your answer (type, or use Speaking for voice)") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = typed.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.Default) {
                    app.gateway.run(TurnFeedback(), TurnFeedback.Input(module.code, pack.profile.registerNotes, q.prompt, typed.trim(), app.culturalNotes(module.code)))
                }
                busy = false
                done((r as? AiResult.Ok)?.value?.let { o ->
                    TurnFeedbackRecord(1, typed.trim(), o.corrected.takeIf { it.trim() != typed.trim() }, o.changes, o.rewrite, o.vocabulary, o.turnLevel, o.pragmatics, r.engine)
                } ?: TurnFeedbackRecord(1, typed.trim()))
            }
        }) { Text("Answer") }
    }
}
