package app.mokuhyo.desktop.ui.exam

import app.mokuhyo.desktop.ui.CheckRow
import app.mokuhyo.desktop.ui.SuggestButton
import app.mokuhyo.speech.Degrade
import app.mokuhyo.desktop.ui.RadioRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.Tier
import app.mokuhyo.db.Exam_in_progress
import app.mokuhyo.exam.GeneratePassage
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.settings.Settings
import app.mokuhyo.desktop.PassageAudio
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.Disclaimer
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.Page
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.exam.ExamAssembler
import app.mokuhyo.exam.ExamContent
import app.mokuhyo.exam.ExamForm
import app.mokuhyo.exam.ExamMode
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ExamProgress
import app.mokuhyo.exam.ExamProgressStore
import app.mokuhyo.exam.ExamResult
import app.mokuhyo.exam.ExamSession
import app.mokuhyo.exam.FormLength
import app.mokuhyo.exam.Skill
import app.mokuhyo.exam.restore
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.srs.ReviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Reading or Listening: Practice and Test tabs (BRIEF §2). */
@Composable
fun SkillScreen(app: AppGraph, skill: Skill) {
    val lang by app.language.collectAsState()
    var content by remember(lang) { mutableStateOf<ExamContent?>(null) }
    var loaded by remember(lang) { mutableStateOf(false) }
    LaunchedEffect(lang) {
        content = withContext(Dispatchers.IO) { app.exam(lang) }
        loaded = true
    }
    var session by remember(lang, skill) { mutableStateOf<ExamSession?>(null) }
    var result by remember(lang, skill) { mutableStateOf<ExamResult?>(null) }
    val module = remember(lang) { app.languages.module(lang) }
    val title = if (skill == Skill.READING) "Reading" else "Listening"
    val c = content
    when {
        !loaded -> Page(title) { Text("Loading…") }
        c == null || c.pool(skill).isEmpty() -> Page(title) {
            EmptyState("No ${title.lowercase()} content for ${module.nameEnglish} in this build",
                "The ${module.nameEnglish} content pack isn't installed. Content packs ship with the installer; in a development checkout run tools/packs/build_packs.py.")
        }
        result != null -> ResultView(app, module, c, skill, result!!) { result = null; session = null }
        session != null -> SessionView(app, module, c, skill, session!!) { r ->
            if (r != null) {
                val bank = if (r.form.passages.values.any { it.bank == "local" }) "local" else "shipped"
                app.history.saveAttempt(app.learnerId, skill, r, bank)
            }
            result = r
            if (r == null) session = null
        }
        else -> Setup(app, module, c, skill) { session = it }
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** Practice topic filters (BRIEF_PHASE8 C-03, C-07). */
val TRACK_TITLES = mapOf("cuas-base-defense" to "Counter-UAS & Base Defense", "pragmatics" to "Implied meaning")

/** Saves a running test after every move so a crash or quit never loses it (exam_in_progress). */
private class DbProgressStore(private val app: AppGraph, private val lang: String) : ExamProgressStore {
    override fun save(progress: ExamProgress) {
        app.db.historyQueries.saveInProgress(
            Exam_in_progress(progress.id, app.learnerId, lang, json.encodeToString(ExamProgress.serializer(), progress), System.currentTimeMillis()),
        )
    }

    override fun clear(id: String) {
        app.db.historyQueries.clearInProgress(id)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(app: AppGraph, module: LanguageModule, content: ExamContent, skill: Skill, start: (ExamSession) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var generatedVersion by remember { mutableIntStateOf(0) }
    val title = if (skill == Skill.READING) "Reading" else "Listening"
    val levels = content.blueprint.section(skill).levels
    val counts = content.countsByLevel(skill)
    Page(title, "${module.nameEnglish} · ${content.passagesFor(skill).size} passages · questions in English") {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Practice") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Test") })
            if (skill == Skill.READING) Tab(tab == 2, { tab = 2 }, text = { Text("This month") })
            if (skill == Skill.LISTENING) Tab(tab == 3, { tab = 3 }, text = { Text("Numbers") })
        }
        Spacer(Modifier.height(16.dp))
        if (tab == 2) {
            ThisMonth(app, module)
        } else if (tab == 3) {
            NumbersDrillView(app, module)
        } else if (tab == 0) {
            var level by remember { mutableStateOf(levels.firstOrNull { (counts[it] ?: 0) > 0 } ?: levels.first()) }
            var track by remember { mutableStateOf<String?>(null) }
            var textType by remember(level, track) { mutableStateOf<String?>(null) }
            var menu by remember { mutableStateOf(false) }
            SectionCard("Practice by ILR level and text type") {
                Text(if (skill == Skill.READING) "Untimed. Tap any word for its meaning; answers are checked as you go." else
                    "Untimed. Replay as often as you like at 0.8–1.0× speed; the transcript appears after you answer.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    levels.forEach { l ->
                        FilterChip(level == l, { level = l }, enabled = (counts[l] ?: 0) > 0, label = { Text("ILR $l · ${counts[l] ?: 0}") })
                    }
                }
                val tracks = remember(content) { content.tracks(skill) }
                if (tracks.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(track == null, { track = null }, label = { Text("All topics") })
                    tracks.forEach { (t, n) -> FilterChip(track == t, { track = t }, label = { Text("${TRACK_TITLES[t] ?: t} · $n") }) }
                }
                val types = ExamAssembler.textTypes(skill, level, content.pool(skill, track), content.passages)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("Text type: ${textType?.replace('_', ' ') ?: "any"}") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Any") }, { textType = null; menu = false })
                        types.forEach { (t, n) -> DropdownMenuItem({ Text("${t.replace('_', ' ')} ($n questions)") }, { textType = t; menu = false }) }
                    }
                }
                var includeLocal by remember { mutableStateOf(false) }
                val local = remember(module.code, skill, generatedVersion) { app.generated.load(app.learnerId, module.code, skill) }
                if (local.first.isNotEmpty()) CheckRow(includeLocal, { includeLocal = it }) { Text("Include ${local.first.size} passages generated on this computer") }
                Button(onClick = {
                    val seen = app.history.practisedPassages(app.learnerId, module.code, skill)
                    val c = if (includeLocal) content.withLocal(local.first, local.second) else content
                    val pool = if (track == null) c.pool(skill) else c.pool(skill, track)
                    val form = ExamAssembler.practice(module.code, skill, level, textType, pool, c.passages, seen, Random.Default)
                    if (!form.isEmpty) start(ExamSession(form, content.blueprint.section(skill).play))
                }) { Text("Start practice") }
            }
            GenerateMore(app, module, content, skill, level) { generatedVersion++ }
        } else {
            var length by remember { mutableStateOf(FormLength.SLICE_30) }
            val inProgress = remember(module.code) { app.db.historyQueries.inProgress(app.learnerId, module.code).executeAsOneOrNull() }
            val saved = inProgress?.let { runCatching { json.decodeFromString(ExamProgress.serializer(), it.state) }.getOrNull() }
                ?.takeIf { it.exam == skill.exam.name && !it.finished }
            if (saved != null) SectionCard("Unfinished test") {
                Text("${saved.answeredCount} of ${saved.totalCount} answered. The clock kept running while you were away.")
                Button(onClick = {
                    content.restore(saved)?.let { form ->
                        start(ExamSession(form, content.blueprint.section(skill).play, store = DbProgressStore(app, module.code), restored = saved))
                    }
                }) { Text("Resume") }
            }
            SectionCard("Timed test") {
                Text("DLPT-style multiple choice: English questions about ${module.nameEnglish} " +
                    (if (skill == Skill.READING) "texts" else "recordings") + ", from ILR 0+ to 3, easiest first. No dictionary, no pausing the clock." +
                    if (skill == Skill.LISTENING) " Each recording plays ${content.blueprint.listening.play.plays}×." else "")
                FormLength.entries.forEach { l ->
                    RadioRow(length == l, { length = l }) { Text("${l.title} — ${content.blueprint.section(skill).itemsFor(l).values.sum()} questions") }
                }
                // A listening test can only use passages this computer can play: pre-rendered clips, or any passage
                // when a voice for the language is available (docs/LANGUAGES.md, D-013).
                val canSpeak = remember(module.code) { app.speech.voicesFor(module.code).isNotEmpty() }
                val pool = remember(module.code, skill, canSpeak) {
                    if (skill == Skill.LISTENING && !canSpeak) content.pool(skill).filter { content.passages[it.passageId]?.audio != null } else content.pool(skill)
                }
                if (skill == Skill.LISTENING && !canSpeak) Text(
                    if (pool.isEmpty()) "No voice for ${module.nameEnglish} is available on this computer, so listening tests can't run here. Practice still shows the transcripts."
                    else "No voice for ${module.nameEnglish} on this computer: tests use only the ${pool.mapNotNull { it.passageId }.distinct().size} recordings that ship with the app.",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(enabled = pool.isNotEmpty(), onClick = {
                    val recent = app.history.recentTestPassages(app.learnerId, module.code, skill, content.blueprint.noRepeatForms)
                    val form = ExamAssembler.test(content.blueprint, skill, length, pool, content.passages, recent, Random.Default)
                    if (!form.isEmpty) start(ExamSession(form, content.blueprint.section(skill).play, store = DbProgressStore(app, module.code)).also { it.begin() })
                }) { Text("Start test") }
                Disclaimer()
            }
        }
    }
}

/**
 * "Generate more" (BRIEF §5.4): the local model drafts new passages for practice, checked like the shipped banks and
 * saved to the "Generated on this computer" bank. Rate-limited by tier: Tier A reading only, one passage per request.
 */
@Composable
private fun GenerateMore(app: AppGraph, module: LanguageModule, content: ExamContent, skill: Skill, level: String, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tier = app.tier
    val allowed = app.languageModel() != null && !(tier == Tier.A && skill == Skill.LISTENING)
    var topic by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val count = if (tier == Tier.A) 1 else 3
    SectionCard("Generate more (ILR $level)") {
        Text("Your AI model writes new ${if (skill == Skill.READING) "texts" else "recordings"} with questions. They're labelled “Generated on this computer”, " +
            "kept out of your test statistics, and can be deleted all at once.", style = MaterialTheme.typography.bodySmall)
        if (!allowed) Text(if (app.languageModel() == null) "Needs an AI model (Settings → AI)." else "Tier A generates reading passages only.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), label = { Text("Topic (optional, any language)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = allowed && !busy, onClick = {
                busy = true
                job = scope.launch {
                    var made = 0
                    val types = content.blueprint.section(skill).textTypes[level].orEmpty().ifEmpty { listOf("news") }
                    val factor = when (module.code) { "ja", "ko" -> 1.5; "zh-Hans" -> 1.2; "ar" -> 0.85; "ru" -> 0.9; else -> 1.0 }
                    repeat(count) { i ->
                        status = "Writing ${i + 1} of $count… (this can take a minute on a small model)"
                        val input = GeneratePassage.Input(module.code, skill, level, types.random(), topic.ifBlank { listOf("daily life", "work", "travel", "the news", "a community event").random() },
                            { t -> (module.segment(t).count { it.isWord } / factor).toInt() }, items = if (level == "0+") 1 else 3)
                        val task = GeneratePassage()
                        when (val r = withContext(Dispatchers.Default) { app.gateway.run(task, input) }) {
                            is AiResult.Ok -> {
                                val id = "local-${module.code}-${System.currentTimeMillis()}-$i"
                                val (p, items) = task.toBank(input, r.value, id)
                                app.generated.save(app.learnerId, p, items, r.engine)
                                made++
                            }
                            else -> status = "One draft didn't pass the checks and was discarded."
                        }
                    }
                    status = "Added $made passage${if (made == 1) "" else "s"}."
                    busy = false
                    done()
                }
            }) { Text("Generate ${if (count == 1) "a passage" else "$count passages"}") }
            if (busy) TextButton(onClick = { job?.cancel(); busy = false; status = "Stopped." }) { Text("Cancel") }
            val n = remember(module.code, status) { app.generated.count(app.learnerId, module.code) }
            if (n > 0 && !busy) TextButton(onClick = { app.generated.deleteAll(app.learnerId, module.code); status = "Deleted all generated passages."; done() }) {
                Text("Delete all $n generated")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/** A running practice set or test. [done] gets the result, or null when the learner leaves practice. */
@Composable
private fun SessionView(app: AppGraph, module: LanguageModule, content: ExamContent, skill: Skill, session: ExamSession, done: (ExamResult?) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val practice = session.form.mode == ExamMode.PRACTICE
    var revealed by remember { mutableStateOf(setOf<String>()) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var aids by remember { mutableStateOf(AidState()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(session) {
        focus.requestFocus()
        while (!session.finished) {
            delay(1000)
            while (session.tick()) Unit
            tick++
        }
        if (!practice) done(session.submit())
    }
    val current = session.current ?: return
    val item = current.item
    val passage = item.passageId?.let { session.form.passages[it] }
    @Suppress("UNUSED_EXPRESSION") tick
    val answered = session.choiceFor(item.id)
    val showFeedback = practice && item.id in revealed

    fun choose(i: Int) {
        if (practice && item.id in revealed) return
        if (!session.questionsVisible(passage?.id)) return
        session.choose(i)
        if (practice) revealed = revealed + item.id
        tick++
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.One, Key.NumPad1 -> { choose(0); true }
                Key.Two, Key.NumPad2 -> { choose(1); true }
                Key.Three, Key.NumPad3 -> { choose(2); true }
                Key.Four, Key.NumPad4 -> { choose(3); true }
                Key.Enter, Key.NumPadEnter -> { session.next(); tick++; true }
                else -> false
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(session.section?.title ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Question ${session.index + 1} of ${session.section?.items?.size ?: 0} · ILR ${item.level} · ${item.type.replace('_', ' ')}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.aiGenerated || passage?.aiGenerated == true) Badge("AI-generated")
            if (passage?.bank == "local") Badge("Generated on this computer")
            Spacer(Modifier.weight(1f))
            session.remainingMs()?.let { ms ->
                val s = ms / 1000
                Text("%d:%02d:%02d left".format(s / 3600, s / 60 % 60, s % 60), style = MaterialTheme.typography.titleMedium,
                    color = if (ms < 5 * 60_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (practice) TextButton(onClick = { done(if (session.answeredCount > 0) session.submit() else null) }) { Text("Finish") }
            else OutlinedButton(onClick = { confirmSubmit = true }) { Text("Submit test") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (passage != null) {
                    Text(passage.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (skill == Skill.READING) {
                        AidToggles(module, aids) { aids = it }
                        PassageText(app, module, passage.body, aids, tapToDefine = practice)
                    } else {
                        ListeningPanel(app, module, session, passage, practice, transcriptVisible = showFeedback, aids = aids, onAids = { aids = it }) { tick++ }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!session.questionsVisible(passage?.id)) {
                    Text("Play the recording to see the questions.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(item.stem, style = MaterialTheme.typography.titleMedium)
                    item.choices.forEachIndexed { i, choice ->
                        val isAnswer = i == item.answer
                        val border = when {
                            showFeedback && isAnswer -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            showFeedback && answered == i -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                            answered == i -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else -> null
                        }
                        Card(onClick = { choose(i) }, border = border, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(choice)
                            }
                        }
                    }
                    if (showFeedback) Feedback(app, module, item, passage, answered == item.answer)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = session.index > 0, onClick = { session.previous(); tick++ }) { Text("Previous") }
                    val last = session.index + 1 >= (session.section?.items?.size ?: 0)
                    Button(onClick = {
                        if (last) { if (practice) done(session.submit()) else confirmSubmit = true } else { session.next(); tick++ }
                    }) { Text(if (last) (if (practice) "See results" else "Submit test") else "Next  ⏎") }
                }
                if (!practice) QuestionPalette(session) { session.goTo(it); tick++ }
                Text("Keys: 1–4 choose · Enter next" + if (skill == Skill.LISTENING) " · Space play/pause" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmSubmit) AlertDialog(
        onDismissRequest = { confirmSubmit = false },
        title = { Text("Submit the test?") },
        text = { Text("${session.answeredCount} of ${session.totalCount} answered. Unanswered questions count as wrong.") },
        confirmButton = { Button(onClick = { confirmSubmit = false; done(session.submit()) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Keep going") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionPalette(session: ExamSession, go: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        session.section?.items?.forEachIndexed { i, f ->
            val answered = session.choiceFor(f.item.id) != null
            OutlinedButton(onClick = { go(i) }, modifier = Modifier.size(width = 48.dp, height = 36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                border = if (i == session.index) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                Text("${i + 1}" + if (answered) "✓" else "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Feedback(app: AppGraph, module: LanguageModule, item: app.mokuhyo.exam.ExamItem, passage: ExamPassage?, right: Boolean) {
    var added by remember(item.id) { mutableStateOf(false) }
    SectionCard {
        Text(if (right) "Correct." else "Not quite — the answer is ${item.answer + 1}: ${item.choices[item.answer]}",
            fontWeight = FontWeight.SemiBold, color = if (right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (item.explanation.isNotBlank()) Text(item.explanation, fontFamily = Fonts.forLanguage(module.code))
        if (!right) OutlinedButton(enabled = !added, onClick = {
            app.reviews.add(app.learnerId, module.code, ReviewService.Kind.ITEM, item.id, item.stem,
                item.choices[item.answer] + if (item.explanation.isNotBlank()) "\n\n" + item.explanation else "", passage?.text?.take(600))
            added = true
        }) { Text(if (added) "Added to review" else "Add to review") }
        SuggestButton(app, module.code, "flag", "item", item.id, item.stem, "Flag this question")
    }
}

/** Listening: play/replay with the blueprint's play policy, 0.8–1.0× speed and dictation in practice, transcript after answering. */
@Composable
private fun ListeningPanel(
    app: AppGraph, module: LanguageModule, session: ExamSession, passage: ExamPassage, practice: Boolean,
    transcriptVisible: Boolean, aids: AidState, onAids: (AidState) -> Unit, changed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var audio by remember(passage.id) { mutableStateOf<PassageAudio.Result?>(null) }
    var playing by remember(passage.id) { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0) }
    // Degraded audio (BRIEF_PHASE8 N-06): practice only — a test never shows these controls and Degrade refuses test mode.
    var preset by remember { mutableStateOf<Degrade.Preset?>(null) }
    var difficulty by remember { mutableStateOf(0.4f) }
    var clean by remember(passage.id) { mutableStateOf(false) }
    val stop = remember(passage.id) { AtomicBoolean(false) }
    LaunchedEffect(passage.id) { audio = PassageAudio(app).load(passage) }
    DisposableEffect(passage.id) { onDispose { stop.set(true) } }
    SectionCard {
        when (val a = audio) {
            null -> Text("Preparing audio…")
            is PassageAudio.Result.Unavailable -> {
                Text(a.reason, color = MaterialTheme.colorScheme.error)
                if (practice) PassageText(app, module, passage.text, aids, tapToDefine = true)
            }
            is PassageAudio.Result.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = playing || session.canPlayAudio(passage.id), onClick = {
                        if (playing) { stop.set(true); return@Button }
                        stop.set(false)
                        session.audioPlayed(passage.id)
                        changed()
                        playing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val p = preset
                                val wav = if (p == null || clean) a.wav else AudioIO.wav(Degrade.apply(
                                    AudioIO.toPcm16kMono(a.wav), p, difficulty.toDouble(), seed = passage.id.hashCode(), testMode = !practice))
                                runCatching { AudioIO.play(wav, app.settings.get(Settings.Key.OUTPUT_DEVICE), speed) { stop.get() } }
                            }
                            playing = false
                        }
                    }) { Text(if (playing) "Stop" else if (session.playsOf(passage.id) == 0) "▶ Play" else "▶ Play again") }
                    if (!practice) Text("Plays left: ${(app.exam(module.code)?.blueprint?.listening?.play?.plays ?: 1) - session.playsOf(passage.id)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (practice) listOf(0.8, 0.9, 1.0).forEach { s -> FilterChip(speed == s, { speed = s }, label = { Text("${s}×") }) }
                }
                Text("Audio: ${a.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (practice) DegradeControls(preset, difficulty, clean, { preset = it }, { difficulty = it }, { clean = it })
            }
        }
    }
    if (practice && transcriptVisible) {
        HorizontalDivider()
        Text("Transcript", style = MaterialTheme.typography.titleSmall)
        AidToggles(module, aids, onAids)
        passage.script.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line.speaker + ":", fontFamily = Fonts.forLanguage(module.code), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
                Column(Modifier.weight(1f)) { PassageText(app, module, line.text, aids, tapToDefine = true, context = passage.text) }
            }
        }
        Dictation(module, passage)
    }
}

/** Dictation drill: type a line you heard; compared with the language's own folding. */
@Composable
private fun Dictation(module: LanguageModule, passage: ExamPassage) {
    var lineIndex by remember(passage.id) { mutableIntStateOf(0) }
    var typed by remember(passage.id, lineIndex) { mutableStateOf("") }
    var checked by remember(passage.id, lineIndex) { mutableStateOf<Double?>(null) }
    val line = passage.script.getOrNull(lineIndex) ?: return
    SectionCard("Dictation drill") {
        Text("Line ${lineIndex + 1} of ${passage.script.size}: listen again and type it.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { checked = app.mokuhyo.desktop.LangSmoke.tokenMatch(module, line.text, typed) }) { Text("Check") }
            TextButton(enabled = lineIndex + 1 < passage.script.size, onClick = { lineIndex++ }) { Text("Next line") }
        }
        checked?.let { score ->
            Text("${(score * 100).toInt()}% of the words match.", fontWeight = FontWeight.SemiBold)
            Text(line.text, fontFamily = Fonts.forLanguage(module.code))
        }
    }
}

@Composable
private fun ResultView(app: AppGraph, module: LanguageModule, content: ExamContent, skill: Skill, result: ExamResult, again: () -> Unit) {
    val s = result.scoring
    var addedMissed by remember { mutableStateOf(false) }
    val practice = result.form.mode == ExamMode.PRACTICE
    Page(if (practice) "Practice results" else "Test results", "${module.nameEnglish} ${if (skill == Skill.READING) "Reading" else "Listening"} · ${result.form.mode.title}") {
        SectionCard {
            val est = s.ilr?.let { "ILR $it" + if (s.ilrConfident) "" else " (low confidence)" }
                ?: s.ilrProvisional?.let { "≈ ILR $it (provisional — too few questions for a firm estimate)" } ?: "Below ILR 0+ on this form"
            Text(if (practice) "${result.answers.count { it.correct }} of ${result.answers.size} correct" else "Estimated level: $est",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${result.answers.count { it.correct }} of ${result.answers.size} correct")
            Disclaimer()
        }
        if (s.byLevel.isNotEmpty()) SectionCard("By ILR level") {
            s.byLevel.forEach { t -> Text("ILR ${t.key}: ${t.correct}/${t.total} (${if (t.total == 0) 0 else t.correct * 100 / t.total}%)") }
        }
        if (s.weakAreas.isNotEmpty()) SectionCard("Work on") { Text(s.weakAreas.joinToString(", ") { it.replace('_', ' ') }) }
        val missed = result.form.items.filter { f -> result.answers.firstOrNull { it.itemId == f.item.id }?.correct != true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = again) { Text("Done") }
            if (missed.isNotEmpty()) OutlinedButton(enabled = !addedMissed, onClick = {
                missed.forEach { f ->
                    val p = f.item.passageId?.let { result.form.passages[it] }
                    app.reviews.add(app.learnerId, module.code, ReviewService.Kind.ITEM, f.item.id, f.item.stem,
                        f.item.choices[f.item.answer] + "\n\n" + f.item.explanation, p?.text?.take(600))
                }
                addedMissed = true
            }) { Text(if (addedMissed) "Added ${missed.size} to review" else "Add ${missed.size} missed questions to review") }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("Answers") {
            result.form.items.forEachIndexed { i, f ->
                val a = result.answers.firstOrNull { it.itemId == f.item.id }
                Text("${i + 1}. ${f.item.stem}", fontWeight = FontWeight.SemiBold)
                Text("Your answer: ${a?.choice?.let { f.item.choices[it] } ?: "—"}${if (a?.correct == true) " ✓" else "  ·  Answer: ${f.item.choices[f.item.answer]}"}",
                    color = if (a?.correct == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (f.item.explanation.isNotBlank()) Text(f.item.explanation, style = MaterialTheme.typography.bodySmall, fontFamily = Fonts.forLanguage(module.code))
                Spacer(Modifier.height(6.dp))
            }
        }
        @Suppress("UNUSED_EXPRESSION") content
    }
}

@Suppress("unused")
private fun ExamForm.describe() = "$exam ${items.size}"

/** QA/screenshot hook: a practice session at [level] with the first question answered (shows feedback). */
@Composable
internal fun SessionPreview(app: AppGraph, skill: Skill, level: String, answer: Boolean) {
    val lang by app.language.collectAsState()
    val content = remember(lang) { app.exam(lang) } ?: return
    val module = remember(lang) { app.languages.module(lang) }
    val session = remember(lang, skill) {
        ExamSession(ExamAssembler.practice(lang, skill, level, null, content.pool(skill), content.passages, emptySet(), Random(1)), content.blueprint.section(skill).play)
            .also { s -> if (answer) s.current?.let { s.choose((it.item.answer + 1) % it.item.choices.size) } }
    }
    SessionView(app, module, content, skill, session) {}
}


/** Practice-only listening conditions (BRIEF_PHASE8 N-06): telephone, radio, flightline… with a difficulty slider. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DegradeControls(
    preset: Degrade.Preset?, difficulty: Float, clean: Boolean,
    onPreset: (Degrade.Preset?) -> Unit, onDifficulty: (Float) -> Unit, onClean: (Boolean) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Conditions:", style = MaterialTheme.typography.bodySmall)
        FilterChip(preset == null, { onPreset(null) }, label = { Text("Clean") })
        Degrade.Preset.entries.forEach { p -> FilterChip(preset == p, { onPreset(p) }, label = { Text(p.title) }) }
    }
    if (preset != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Difficulty", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Slider(difficulty, onDifficulty, Modifier.width(220.dp))
            CheckRow(clean, onClean) { Text("Replay clean") }
        }
        Text("Noise is synthesized on this computer (no field recordings ship with the app).", style = MaterialTheme.typography.bodySmall)
    }
}
