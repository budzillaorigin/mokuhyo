package app.mokuhyo.desktop.ui.speaking

import app.mokuhyo.lang.VoiceRotation
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import app.mokuhyo.culture.CultureCard
import app.mokuhyo.culture.Persona
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.QuestionDelivery
import app.mokuhyo.desktop.Voice
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.CheckRow
import app.mokuhyo.desktop.ui.Disclaimer
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.Page
import app.mokuhyo.desktop.ui.RadioRow
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.history.ConversationDraft
import app.mokuhyo.db.Conversation
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.lexicon.Scenario
import app.mokuhyo.lexicon.Track
import app.mokuhyo.ai.AiResult
import app.mokuhyo.opi.AfterActionBrief
import app.mokuhyo.opi.AfterActionBuilder
import app.mokuhyo.opi.Calibration
import app.mokuhyo.opi.CorrectionsMode
import app.mokuhyo.opi.CulturalReview
import app.mokuhyo.opi.FeedbackQueue
import app.mokuhyo.opi.GenerateTopics
import app.mokuhyo.opi.GlossLine
import app.mokuhyo.opi.OpiPack
import app.mokuhyo.opi.OpiProbeMap
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.OpiSession
import app.mokuhyo.opi.OpiTurnOutcome
import app.mokuhyo.opi.PersonaContext
import app.mokuhyo.opi.PragmaticFlag
import app.mokuhyo.opi.RolePlayContext
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.SpeakingActivity
import app.mokuhyo.opi.SpeakingTrend
import app.mokuhyo.opi.Topic
import app.mokuhyo.opi.TopicDomain
import app.mokuhyo.opi.TopicExchange
import app.mokuhyo.opi.TopicSession
import app.mokuhyo.opi.Turn
import app.mokuhyo.opi.TurnFeedback
import app.mokuhyo.opi.TurnFeedbackRecord
import app.mokuhyo.opi.Storyline
import app.mokuhyo.opi.StorylineRunner
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.FallbackKind
import app.mokuhyo.speech.Preflight
import app.mokuhyo.desktop.InterviewPreflight
import app.mokuhyo.desktop.PromptClips
import app.mokuhyo.desktop.ui.ReadinessRow
import app.mokuhyo.speech.TimelineTrace
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.traceMark
import app.mokuhyo.speech.FluencyAnalyzer
import app.mokuhyo.srs.ReviewService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import app.mokuhyo.lang.IcuSentences
import app.mokuhyo.speech.ReplyRepair
import app.mokuhyo.speech.SpeechBudget
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** The learner's default corrections mode for an activity (Settings → Speech & audio → Speaking; BRIEF_PHASE8 C-11). */
fun AppGraph.correctionsDefault(activity: SpeakingActivity): CorrectionsMode =
    CorrectionsMode.of(settings.get(settingKey(activity))) ?: activity.defaultMode

fun settingKey(activity: SpeakingActivity): Settings.Key = when (activity) {
    SpeakingActivity.OPI_PRACTICE -> Settings.Key.CORRECTIONS_OPI
    SpeakingActivity.TOPIC -> Settings.Key.CORRECTIONS_TOPIC
    SpeakingActivity.PERSONA -> Settings.Key.CORRECTIONS_PERSONA
}

private fun Persona.context() = PersonaContext(name, rankTitle, roleTitle, force, register, patience, formality)

/** Speaking (BRIEF §2, §6; BRIEF_PHASE8 C-03, C-05, C-08, C-11): interview practice and test, topic conversation, scenarios. */
@Composable
fun SpeakingScreen(app: AppGraph) {
    val lang by app.language.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val module = remember(lang) { app.languages.module(lang) }
    val pack = remember(lang) { app.opi(lang) }
    val personas = remember(lang) { app.personas(lang) }
    var active by remember(lang) { mutableStateOf(false) }
    val modes = remember { mutableStateListOf(*SpeakingActivity.entries.map { app.correctionsDefault(it) }.toTypedArray()) }
    var persona by remember(lang) { mutableStateOf<Persona?>(null) }
    val activity = when (tab) { 0, 1 -> SpeakingActivity.OPI_PRACTICE; 2 -> if (persona != null) SpeakingActivity.PERSONA else SpeakingActivity.TOPIC; else -> SpeakingActivity.PERSONA }
    val modelInstalled = remember(lang) { app.languageModel() != null }
    val mode = CorrectionsMode.effective(modes[activity.ordinal], opiTest = tab == 1)
        .let { if (it == CorrectionsMode.LIVE && !modelInstalled) CorrectionsMode.AFTER_ACTION else it }
    var recovered by remember(lang) { mutableStateOf<Conversation?>(null) }
    recovered?.let { row ->
        val stored = app.conversations.stored(row)
        val draft = stored.draft!!
        if (draft.opi != null) InterviewView(app, module, pack!!, draft.opi!!.test, app.conversations.mode(row), stored) { recovered = null }
        else draft.topic?.let { t ->
            TopicConversation(app, module, pack!!, t.topic, { recovered = null }, kind = row.kind,
                mode = app.conversations.mode(row), restored = stored)
        }
        return
    }
    if (active) {
        when (tab) {
            0, 1 -> InterviewView(app, module, pack!!, test = tab == 1, mode) { active = false }
            3 -> ScenarioView(app, module, pack!!, app.lexicon(module.code), mode, persona) { active = false }
            4 -> InterpretSetup(app, module) { active = false }
            5 -> ExemplarsBrowse(app, module) { active = false }
            else -> TopicView(app, module, pack!!, mode, persona) { active = false }
        }
        return
    }
    Page("Speaking", "${module.nameEnglish} · interviews are voice-first; you can also type") {
        app.conversations.list(app.learnerId, lang).filter { app.conversations.stored(it).draft != null }.forEach { row ->
            SectionCard("Interrupted session") {
                Text("Your accepted turns are saved. Continue here or read the transcript in History.")
                Button(onClick = { recovered = row }) {
                    Text(if (app.conversations.stored(row).draft?.opi != null) "Resume interrupted interview" else "Resume interrupted conversation")
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Interview practice") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Interview test") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Topic conversation") })
            Tab(tab == 3, { tab = 3 }, text = { Text("Scenarios") })
            Tab(tab == 4, { tab = 4 }, text = { Text("Interpret") })
            Tab(tab == 5, { tab = 5 }, text = { Text("Exemplars") })
        }
        Spacer(Modifier.height(16.dp))
        if (pack == null) {
            EmptyState("No speaking content for ${module.nameEnglish} in this build", "The ${module.nameEnglish} interview pack (opi.json) isn't installed.")
            return@Page
        }
        val hasModel = remember { app.languageModel() != null }
        val hasStt = remember { app.recognizer() != null }
        if (!hasModel) SectionCard {
            Text("No AI model is installed.", fontWeight = FontWeight.SemiBold)
            Text(if (tab >= 2) "Conversations need a model. Download one in Settings → AI." else
                "Interviews use the scripted question bank, and at the end you rate yourself against the ILR descriptions. With a model, the interviewer adapts and rates you.")
        }
        if (!hasStt) Text("Speech recognition isn't available, so you'll type your answers.", color = MaterialTheme.colorScheme.error)
        // What a spoken interview in this language runs on here, before it starts (N4).
        if (tab <= 1) {
            var readiness by remember(lang) { mutableStateOf<app.mokuhyo.speech.LanguageReadiness?>(null) }
            LaunchedEffect(lang) { readiness = withContext(Dispatchers.IO) { app.readiness().firstOrNull { it.language == lang } } }
            readiness?.let { r -> SectionCard("This computer, for ${r.name}") { ReadinessRow(r) } }
        }
        when (tab) {
            0 -> SectionCard("Interview practice") {
                Text("A short interview (about 10 questions): warm-up, level checks, probes, a role-play and a wind-down. " +
                    (if (hasModel) "The questions adapt to your answers." else "Without an AI model the questions come from the scripted bank, phase by phase; they don't adapt to your answers.") +
                    " You can show the question text if you didn't catch it.")
                ModePicker(modes[activity.ordinal], hasModel) { modes[activity.ordinal] = it }
                Button(onClick = { active = true }) { Text("Start interview") }
            }
            1 -> SectionCard("Interview test") {
                Text("A full 20–30 minute interview with no hints: the questions are spoken only and the transcript stays hidden until the end. Your rating counts toward your speaking estimate.")
                Text("Corrections: After action (always, in a test) — your After Action Brief comes at the end.", style = MaterialTheme.typography.bodySmall)
                // Test questions are spoken only, so a test needs a working voice (QC 2026-10-19 #2).
                val hasVoice = remember(lang) { app.speech.voicesFor(lang).isNotEmpty() }
                Button(enabled = hasModel && hasVoice, onClick = { active = true }) { Text("Start test") }
                if (!hasModel) Text("The test needs an AI model for the rating.", style = MaterialTheme.typography.bodySmall)
                if (!hasVoice) Text("The test speaks every question, and no voice for ${module.nameEnglish} is available on this computer. " +
                    "Interview practice can show the questions as text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!hasStt) Text("Without speech recognition you'd type your answers, and a typed interview doesn't count toward your speaking estimate.",
                    style = MaterialTheme.typography.bodySmall)
                Disclaimer()
            }
            2 -> SectionCard("Topic conversation") {
                Text("Talk about a topic. The partner adjusts to your level; you get corrections, a natural rewrite, vocabulary notes and cultural notes " +
                    "after each turn (Live) or all together at the end (After action).")
                PersonaPicker(personas, persona, module) { persona = it }
                ModePicker(modes[activity.ordinal], hasModel) { modes[activity.ordinal] = it }
                Button(enabled = hasModel, onClick = { active = true }) { Text("Choose a topic") }
            }
            5 -> SectionCard("Exemplar answers") {
                Text("Hear and read what an answer at ILR 1+, 2 and 3 sounds like for the same interview question, with a note on what makes " +
                    "each one that level and not the next.")
                Button(onClick = { active = true }) { Text("Browse exemplars") }
            }
            4 -> SectionCard("Interpret") {
                Text("Consecutive interpretation drills on the Counter-UAS & Base Defense dialogues: hear a chunk, take notes, say it in the other " +
                    "language. Also a radio relay over a noisy channel and timed sight translation of notices. Feedback comes at the end.")
                Button(onClick = { active = true }) { Text("Set up a drill") }
            }
            else -> SectionCard("Scenarios — Counter-UAS & Base Defense") {
                val track = remember(lang) { app.track(lang) }
                Text("Role-play a work situation with a host-nation counterpart: a BDOC handover, a drone sighting report, an airspace call, a gate incident. " +
                    "You get a briefing, the key terms and culture cards first; the partner stays in role.")
                if (track == null) Text("The Counter-UAS & Base Defense track isn't installed for ${module.nameEnglish}.", style = MaterialTheme.typography.bodySmall)
                PersonaPicker(personas, persona, module) { persona = it }
                ModePicker(modes[activity.ordinal], hasModel) { modes[activity.ordinal] = it }
                Button(enabled = hasModel && track != null, onClick = { active = true }) { Text("Choose a scenario") }
                if (!hasModel) Text("Scenarios need an AI model.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Live / After action / Off for this session (BRIEF_PHASE8 §B.5); the default per activity is in Settings. */
@Composable
private fun ModePicker(mode: CorrectionsMode, hasModel: Boolean, onChange: (CorrectionsMode) -> Unit) {
    Text("Corrections", style = MaterialTheme.typography.labelLarge)
    CorrectionsMode.entries.forEach { m ->
        // Live corrections are written by the model; without one, don't offer them (QC 2026-10-19).
        val available = hasModel || m != CorrectionsMode.LIVE
        RadioRow(mode == m, { onChange(m) }, enabled = available) {
            Column {
                Text(m.title + if (available) "" else " (needs an AI model)")
                Text(when (m) {
                    CorrectionsMode.LIVE -> "Corrections, a natural rewrite and cultural notes under each of your turns."
                    CorrectionsMode.AFTER_ACTION -> if (hasModel) "Nothing but the conversation; everything is recorded and given to you at the end in an After Action Brief."
                        else "Nothing but the conversation; at the end you get your transcript and recordings and rate yourself. No corrections without an AI model."
                    CorrectionsMode.OFF -> "Free-flow warm-up: transcript and recordings only — no feedback will be generated."
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (!hasModel && mode == CorrectionsMode.LIVE) Text("Live corrections need an AI model; this session runs as After action.", style = MaterialTheme.typography.bodySmall)
}

/** Who you talk to (BRIEF_PHASE8 C-08): six partner personas per language (twelve for Arabic: RSAF and QEAF). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaPicker(personas: List<Persona>, chosen: Persona?, module: LanguageModule, onChoose: (Persona?) -> Unit) {
    if (personas.isEmpty()) return
    Text("Talk with", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(chosen == null, { onChoose(null) }, label = { Text("A conversation partner") })
        personas.forEach { p -> FilterChip(chosen?.id == p.id, { onChoose(p) }, label = { Text("${p.roleTitle}: ${p.name}") }) }
    }
    chosen?.let { p ->
        Text("${p.rankTitle} · ${p.force}", fontFamily = Fonts.forLanguage(module.code), fontWeight = FontWeight.SemiBold)
        if (p.bio.isNotBlank()) Text(p.bio, style = MaterialTheme.typography.bodySmall)
        Text("How to address them: ${p.register}", style = MaterialTheme.typography.bodySmall)
        if (p.source == "llm" && !p.verified) Badge("AI-drafted persona")
    }
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Saves a turn's audio under recordings/<conversation>/ and indexes it — unless the learner turned AAB audio off. */
private fun saveRecording(app: AppGraph, conversationId: String, turn: Int, pcm: ShortArray): String? {
    if (!app.settings.bool(Settings.Key.AAB_KEEP_AUDIO, default = true)) return null
    val dir = File(app.dataDir, "recordings/$conversationId").apply { mkdirs() }
    val wav = AudioIO.wav(pcm)
    val original = File(dir, "turn-%02d.wav".format(turn))
    val file = if (!original.exists()) original else File(dir, "turn-%02d-%s.wav".format(turn, java.util.UUID.randomUUID()))
    file.writeBytes(wav)
    app.conversations.addRecording(conversationId, turn, "recordings/$conversationId/${file.name}", pcm.size / 16L, sha256(wav))
    return file.path
}

/** Says [text]; the outcome tells the caller whether the learner actually heard it (QC 2026-10-19 #2). */
private suspend fun speak(app: AppGraph, text: String, lang: String, persona: Persona? = null): Voice.Outcome {
    // A persona keeps one voice of their gender (BRIEF_PHASE8 N-07).
    val voice = persona?.let { p -> VoiceRotation.forPersona(app.speech.voicesFor(lang), p.id, p.gender) }
    return Voice.say(app, text, lang, voice)
}

private fun wordCounter(module: LanguageModule): (String) -> Int {
    val factor = when (module.code) { "ja", "ko" -> 1.5; "zh-Hans" -> 1.2; "ar" -> 0.85; "ru" -> 0.9; else -> 1.0 }
    return { s -> (module.segment(s).count { it.isWord } / factor).toInt() }
}

private fun fluencyStats(reports: List<FluencyAnalyzer.Report>): AfterActionBrief.FluencyStats? =
    reports.takeIf { it.isNotEmpty() }?.let { r ->
        AfterActionBrief.FluencyStats(r.map { it.composite }.average().toInt(), r.map { it.wordsPerMinute }.average().toInt(),
            (r.map { it.pauseRatio }.average() * 100).toInt(), r.sumOf { it.falseStarts })
    }

private fun cardLookup(app: AppGraph, lang: String): (String) -> Pair<String, String>? = { tag ->
    app.culture(lang)?.forTags(listOf(tag), 1)?.firstOrNull()?.let { it.id to it.title }
}

@Composable
private fun ModeChip(mode: CorrectionsMode) {
    Badge(mode.chip, if (mode == CorrectionsMode.LIVE) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
}

@Composable
private fun InterviewView(app: AppGraph, module: LanguageModule, pack: OpiPack, test: Boolean, mode: CorrectionsMode, restored: StoredConversation? = null, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conversationId = remember { restored?.draft?.sessionId ?: app.conversations.newId() }
    // One conversation model for the whole interview: a settings change mid-session never swaps it (N2).
    val gateway = remember { app.gateway.pinned() }
    val session = remember {
        OpiSession(module.code, pack.profile, pack.questions, pack.rolePlays, gateway, wordCounter(module), IlrLevel.L1, test = test,
            culturalNotes = app.culturalNotes(module.code), restored = restored?.draft?.opi, replyDeadlineMs = app.replyDeadlineMs())
    }
    val notes = remember { app.culturalNotes(module.code) }
    val lines = remember { mutableStateListOf<Pair<Turn, String>>().apply {
        restored?.transcript?.forEachIndexed { i, t -> add(t to restored.english.getOrElse(i) { "" }) }
    } } // (turn, english gloss)
    var asker by remember { mutableStateOf<String?>(null) }
    val records = remember { mutableStateListOf<TurnFeedbackRecord>().apply { addAll(app.conversations.feedback(conversationId)) } }
    val queue = remember {
        if (mode.records && gateway.hasModel()) FeedbackQueue(gateway, scope, deferredUntilEnd = !mode.backgroundBetweenTurns(app.runtime.gpuActive)) { rec ->
            records += rec
            app.conversations.addFeedback(conversationId, rec)
        }.also { q ->
            restored?.opiTurns?.filter { it.answer != null && records.none { r -> r.turnIndex == it.index * 2 + 1 } }?.forEach {
                q.enqueue(it.index * 2 + 1, TurnFeedback.Input(module.code, pack.profile.registerNotes, it.question, it.answer!!, notes))
            }
        } else null
    }
    DisposableEffect(Unit) { onDispose { queue?.close() } }
    var status by remember { mutableStateOf("Press Start when you're ready.") }
    var busy by remember { mutableStateOf(false) }
    var captureBusy by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0.0) }
    var typed by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<OpiRating?>(null) }
    var cultural by remember { mutableStateOf<CulturalReview?>(null) }
    var aab by remember { mutableStateOf<AfterActionBrief?>(null) }
    var finished by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(restored != null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val fluency = remember { mutableStateListOf<FluencyAnalyzer.Report>() }
    val hasStt = remember { app.recognizer() != null }
    // How the learner answered, and whether a test had to fall back to text (QC 2026-10-19 #2, #3).
    var spoken by remember { mutableIntStateOf(restored?.spokenAnswers ?: 0) }
    var typedAnswers by remember { mutableIntStateOf(restored?.typedAnswers ?: 0) }
    // Per question: pending → playing → heard / failed / cancelled. A test answers only a question it heard (F2).
    val delivery = remember { QuestionDelivery(test).also {
        if (restored?.textFallback == true) it.fallBackToText()
        if (lines.lastOrNull()?.first?.speaker == Speaker.PARTNER) it.newQuestion(lines.lastIndex)
    } }
    var trendNote by remember { mutableStateOf<String?>(null) }
    var pendingAudio by remember { mutableStateOf<Pair<Int, ShortArray>?>(null) }
    var pendingFeedback by remember { mutableStateOf<Pair<Int, TurnFeedback.Input>?>(null) }
    var operationFailed by remember { mutableStateOf(false) }
    val questionTextAllowed = !test || delivery.textFallback
    var preflight by remember { mutableStateOf<Preflight.Report?>(null) }
    // The current question's bank id when it is scripted: its prerendered clip (N5), Repeat included.
    var lineBankId by remember { mutableStateOf<String?>(null) }
    var preflightJob by remember { mutableStateOf<Job?>(null) }
    var preflightRunning by remember { mutableStateOf(false) }
    // Stage timings for Settings → Speech & audio → Diagnostics (BRIEF_INTERVIEW_SPEED N1): no text, no audio.
    val timeline = remember { app.timings.start(conversationId, if (test) "OPI_TEST" else "OPI", module.code, app.timelineSetup(module.code)) }

    suspend fun checkpoint() {
        val snapshot = StoredConversation(session.transcript, opiTurns = session.turns, english = lines.map { it.second },
            spokenAnswers = spoken, typedAnswers = typedAnswers, textFallback = delivery.textFallback,
            draft = ConversationDraft(conversationId, System.currentTimeMillis(), opi = session.checkpoint()))
        withContext(NonCancellable + Dispatchers.IO) {
            app.conversations.checkpoint(conversationId, app.learnerId, module.code, if (test) "OPI_TEST" else "OPI", null,
                session.startedAt.toEpochMilliseconds(), snapshot, mode)
        }
    }

    /** The English gloss of partner line [lineIndex] (record [recordIndex]), off the speech path (N3). */
    suspend fun gloss(lineIndex: Int, recordIndex: Int) {
        val text = lines.getOrNull(lineIndex)?.takeIf { it.second.isBlank() }?.first?.text ?: return
        val english = (gateway.run(GlossLine(), GlossLine.Input(module.code, text)) as? AiResult.Ok)?.value?.english ?: return
        if (lines.getOrNull(lineIndex)?.first?.text != text) return
        lines[lineIndex] = lines[lineIndex].first to english
        session.gloss(recordIndex, english)
    }

    /**
     * Says the question: a guided (scripted) one from its prerendered clip when the pack has it (N5, no synthesis wait),
     * else with the live voice; practice shows the text when neither plays.
     */
    suspend fun askAloud(text: String, bankId: String? = lineBankId) {
        val outcome = delivery.deliver(delivery.question) {
            val clip = bankId?.let { id ->
                traceMark(TurnStage.TTS_START)
                PromptClips.load(app, module.code, id, text)?.also { traceMark(TurnStage.TTS_SYNTH_DONE) }
            }
            if (clip != null) {
                timeline.note { prerendered = true }
                val spec = VoiceSpec(clip.entry.voice ?: "prerendered", module.code, "", "prerendered", clip.entry.credit ?: "")
                Voice.playRendered(app, SpokenAudio(clip.wav, spec))
            } else speak(app, text, module.code)
        } ?: return
        // Practice shows the question when it couldn't be heard; a test waits for the learner to fix it or fall back.
        if (!outcome.ok && !test) showText = true
    }

    fun readyStatus() = if (delivery.state == QuestionDelivery.State.FAILED) "The question couldn't be played."
        else if (hasStt) "Press Record and answer." else "Type your answer."

    fun complete() {
        finished = true
        busy = true
        status = if (mode.records) "Preparing your After Action Brief…" else "Saving…"
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    checkpoint()
                    pendingAudio?.let { (turn, pcm) -> saveRecording(app, conversationId, turn, pcm) }
                }
                pendingAudio = null
                queue?.drain()
                // Glosses that background work didn't get to yet, bounded like feedback (N3).
                if (gateway.hasModel()) withTimeoutOrNull(30_000) {
                    // Partner line k of the transcript is question record k.
                    lines.indices.filter { lines[it].first.speaker == Speaker.PARTNER }.forEachIndexed { k, li -> gloss(li, k) }
                }
                val r = if (mode.records) withContext(Dispatchers.Default) { session.rate() } else null
                val c = if (mode.records && r?.engine != null) withContext(Dispatchers.Default) { session.culturalReview(notes) } else null
                val brief = AfterActionBuilder.build(
                    gateway.takeIf { mode.records && gateway.hasModel() }, module.code, mode, SpeakingActivity.OPI_PRACTICE,
                    if (test) "Interview test" else "Interview practice", null, (System.currentTimeMillis() - session.startedAt.toEpochMilliseconds()) / 1000,
                    session.transcript, records.sortedBy { it.turnIndex }, session.turns.mapNotNull { it.levelAfter?.label }, fluencyStats(fluency),
                    cardLookup(app, module.code),
                )
                rating = r
                cultural = c
                aab = brief
                trendNote = withContext(Dispatchers.IO) {
                    saveInterview(app, conversationId, module.code, session, r, test, lines.map { it.second }, c, mode, brief, spoken, typedAnswers, delivery.textFallback)
                }
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finished = false
                operationFailed = true
                status = "Couldn't finish saving: ${e.message}. Keep this screen open and retry."
            } finally {
                busy = false
            }
        }
    }

    fun nextQuestion() {
        busy = true
        operationFailed = false
        status = "Saving your answer…"
        if (timeline.current < 0) timeline.beginTurn(cold = true)
        job = scope.launch(TimelineTrace(timeline), start = CoroutineStart.UNDISPATCHED) {
            try {
                queue?.foregroundBusy(true)
                // A navigation/cancel arriving during the checkpoint must not skip the permitted audio write.
                val audio = pendingAudio
                val report = withContext(NonCancellable + Dispatchers.IO) {
                    checkpoint()
                    audio?.let { (turn, pcm) ->
                        saveRecording(app, conversationId, turn, pcm)
                        val text = lines.getOrNull(turn)?.first?.text.orEmpty()
                        val words = module.segment(text).filter { it.isWord }.map { module.normalizeForCompare(it.text) }
                        FluencyAnalyzer.analyze(pcm, words, tokenFactor = if (module.code in setOf("ja", "ko")) 1.5 else 1.0)
                    }
                }
                pendingAudio = null
                traceMark(TurnStage.CHECKPOINT_SAVED)
                report?.let { fluency += it }
                pendingFeedback?.let { (turn, input) -> queue?.enqueue(turn, input) }
                pendingFeedback = null
                status = "The interviewer is thinking…"
                // The question is spoken sentence by sentence while the model is still writing it (N3).
                val sentences = Channel<String>(2)
                // Text-ready, audio-ready and output-start are separate deadlines (QC 2026-10-30 V6): every synthesis
                // attempt for this question shares one budget, so an engine's per-request timeout can't extend the wait.
                val speaking = async(SpeechBudget(SpeechBudget.forResponse(app.replyDeadlineMs()))) { Voice.sayStreamed(app, sentences, module.code) }
                val line = try {
                    withContext(Dispatchers.Default) {
                        session.next(speak = { s -> status = if (test) "Listen, then answer." else "Listen…"; sentences.send(s) }, splitter = IcuSentences(module.code))
                    }
                } finally {
                    sentences.close()
                }
                if (line == null) {
                    speaking.await()
                    complete()
                    return@launch
                }
                // The stream stopped part-way (QC 2026-10-30 V10): what the learner heard decides the question. Never a
                // different scripted question over one they already heard; the record keeps both, append-only.
                val repair = if (!line.spokenByStream && line.streamed.isNotEmpty()) {
                    val heard = speaking.await()
                    ReplyRepair.plan(heard?.heard.orEmpty(), line.text).also { session.delivered(it.text, heard?.reply?.record(session.turns.lastIndex).orEmpty()) }
                } else null
                val shown = repair?.text ?: line.text
                if (lines.size < session.transcript.size) lines += Turn(Speaker.PARTNER, shown) to line.english
                lineBankId = line.bankId
                delivery.newQuestion(lines.lastIndex)
                checkpoint()
                if (line.engine == null) timeline.note { fallback = FallbackKind.ofReason(line.fallbackReason ?: "no AI model is set up") }
                asker = line.engine?.let { "Asked by $it" } ?: ("Guided interview — scripted question" + (line.fallbackReason?.let { " ($it)" } ?: ""))
                status = if (test) "Listen, then answer." else "Listen, then answer (show the question if you need it)."
                if (line.spokenByStream) {
                    val outcome = delivery.deliver(delivery.question) {
                        speaking.await()?.also { session.delivered(line.text, it.reply.record(session.turns.lastIndex)) }?.outcome ?: Voice.Outcome.NoVoice
                    }
                    if (outcome != null && !outcome.ok && !test) showText = true
                } else if (repair == null || repair.playScripted) {
                    // Scripted, or the stream stopped before a question was heard: the scripted question itself.
                    speaking.await()
                    askAloud(line.text)
                }
                status = readyStatus()
                // The English gloss is not part of the live reply: fill it in the background now the question was said
                // (or at the end, where background work between turns would evict the interview's cached prompt).
                if (line.english.isBlank() && gateway.hasModel() && mode.backgroundBetweenTurns(app.runtime.gpuActive)) {
                    val li = lines.lastIndex
                    val ri = session.turns.lastIndex
                    scope.launch { gloss(li, ri) }
                }
                // This partner line is done; the learner's answer to it starts the next turn.
                withContext(NonCancellable + Dispatchers.IO) { app.timings.turnDone(timeline) }
                timeline.beginTurn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                operationFailed = true
                status = "Couldn't continue: ${e.message}. Keep this screen open and retry; your answer is retained."
            } finally {
                queue?.foregroundBusy(false)
                if (!finished) busy = false
            }
        }
    }

    fun submitAnswer(text: String, pcm: ShortArray?) {
        val turnIndex = lines.size
        val question = lines.lastOrNull { it.first.speaker == Speaker.PARTNER }?.first?.text.orEmpty()
        lines += Turn(Speaker.LEARNER, text) to ""
        if (pcm != null && pcm.isNotEmpty()) spoken++ else typedAnswers++
        session.answer(text)
        pendingFeedback = turnIndex to TurnFeedback.Input(module.code, pack.profile.registerNotes, question, text, notes)
        if (pcm != null && pcm.isNotEmpty()) pendingAudio = turnIndex to pcm
        nextQuestion()
    }

    Page(if (test) "Interview test" else "Interview practice", "${module.nameEnglish} · phase: ${session.phase.wireName.replace('_', ' ')}" +
        if (!test) " · working level ILR ${session.workingLevel.label}" else "") {
        if (done) {
            val r = rating
            trendNote?.let { SectionCard { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (r != null) InterviewResults(app, module, session, r, cultural, lines, fluency, pack, conversationId, test && !delivery.textFallback)
            else SectionCard { Text("Corrections were off: no rating or feedback was generated."); Text("Your transcript and recordings are saved in History.") }
            aab?.let { brief ->
                AfterActionBriefView(app, module.code, brief, conversationId) { turnIndex ->
                    lines.getOrNull(turnIndex - 1)?.first?.takeIf { it.speaker == Speaker.PARTNER }?.text
                }
            }
            if (r == null) TranscriptCard(app, module, session, lines, conversationId)
            Button(onClick = close) { Text("Done") }
            return@Page
        }
        ModeChip(mode)
        SectionCard {
            Text(status, style = MaterialTheme.typography.titleMedium)
            asker?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (busy || captureBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            queue?.pendingCount?.collectAsState()?.value?.takeIf { it > 0 && mode == CorrectionsMode.LIVE }?.let {
                Text("Feedback on $it answer(s) is being prepared…", style = MaterialTheme.typography.bodySmall)
            }
            // Capture owns its scope outside the conditional generation UI. Visibility never cancels ASR.
            if (started && !finished && hasStt) VoiceCapture(app, module,
                confirm = !test && app.settings.bool(Settings.Key.CONFIRM_TRANSCRIPT, default = true),
                enabled = !busy, onStatus = { status = it }, onBusy = { captureBusy = it },
                prompt = lines.lastOrNull()?.first?.text.orEmpty(),
                visible = !busy && !operationFailed && lines.lastOrNull()?.first?.speaker == Speaker.PARTNER && delivery.canAnswer,
                timeline = timeline,
            ) { t, pcm -> submitAnswer(t, pcm) }
            if (!started) {
                // Preflight (N4): every device and engine checked and warmed before the first question; warm-up time is
                // reported apart from answer time. A failure blocks Start and says what to do.
                val report = preflight
                report?.items?.forEach { item ->
                    val mark = when (item.state) {
                        Preflight.State.PENDING -> "○"
                        Preflight.State.RUNNING -> "…"
                        Preflight.State.OK -> "✓"
                        Preflight.State.NOT_NEEDED -> "–"
                        Preflight.State.FAILED -> "✗"
                    }
                    Text("$mark ${item.title}" + (item.message?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == Preflight.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (preflightRunning && report != null) {
                    val done = report.items.count { it.state != Preflight.State.PENDING && it.state != Preflight.State.RUNNING }
                    LinearProgressIndicator(progress = { done.toFloat() / report.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!preflightRunning) Button(onClick = {
                        status = "Checking the microphone, speakers, voice and models…"
                        preflightRunning = true
                        preflightJob = scope.launch {
                            try {
                                val checks = InterviewPreflight.checks(app, module.code, gateway, test, session.warmupInput())
                                val r = Preflight(checks).run(timeline) { preflight = it }
                                preflight = r
                                val blocking = r.blocking
                                if (blocking == null) {
                                    if (r.state(Preflight.Kind.VOICE) == Preflight.State.NOT_NEEDED) { delivery.fallBackToText(); showText = true }
                                    started = true
                                    nextQuestion()
                                } else status = "Not ready: ${blocking.message}"
                            } finally {
                                preflightRunning = false
                            }
                        }
                    }) { Text(if (preflight?.blocking != null) "Check again" else "Start") }
                    else TextButton(onClick = { preflightJob?.cancel(); status = "Check stopped. Press Start to try again." }) { Text("Cancel") }
                    // No voice for a spoken test: guided text practice instead, labelled and never counted as speech.
                    if (preflight?.blocking?.kind == Preflight.Kind.VOICE) OutlinedButton(onClick = {
                        delivery.fallBackToText(); showText = true; started = true; nextQuestion()
                    }) { Text("Guided text practice instead (not a spoken test)") }
                }
            } else if (!busy && !finished) {
                val last = lines.lastOrNull()
                if (last?.first?.speaker == Speaker.PARTNER && !operationFailed) {
                    delivery.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (test && !delivery.canAnswer && delivery.needsRecovery) {
                        Text("Test questions are spoken only. Fix the audio and press Repeat, or carry on with the questions shown as text: " +
                            "the interview then becomes text practice and won't count toward your speaking estimate.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { delivery.fallBackToText(); showText = true }) { Text("Continue as text practice") }
                    }
                    if (delivery.textFallback) Badge("Text practice — the voice couldn't play; not counted as a test")
                    if (questionTextAllowed) CheckRow(showText, { showText = it }) { Text("Show the question text") }
                    if (showText && questionTextAllowed) Text(last.first.text, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Repeat runs as the tracked job: one playback at a time, and Cancel stops it.
                        OutlinedButton(enabled = !delivery.playing && !captureBusy, onClick = {
                            busy = true
                            status = "Repeating the question…"
                            job = scope.launch {
                                try {
                                    askAloud(last.first.text)
                                } finally {
                                    busy = false
                                }
                                status = readyStatus()
                            }
                        }) { Text("Repeat the question") }
                    }
                    // Interview tests send what was heard; practice shows it first unless turned off (BRIEF_PHASE8 N-00b).
                    // A test whose question wasn't heard waits until it's played or the learner falls back to text.
                    val canAnswer = delivery.canAnswer
                    if (canAnswer) OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("…or type your answer") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = canAnswer && !captureBusy && typed.isNotBlank(), onClick = { val t = typed; typed = ""; submitAnswer(t, null) }) { Text("Send typed answer") }
                        TextButton(enabled = !captureBusy, onClick = { session.stop(); complete() }) {
                            Text(if (mode == CorrectionsMode.AFTER_ACTION) "End and show After Action Brief" else "End interview")
                        }
                    }
                } else {
                    Text("You can retry the question or end the interview. Your accepted answer is kept.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nextQuestion() }) { Text("Retry question") }
                        TextButton(onClick = { session.stop(); complete() }) { Text("End interview") }
                    }
                }
            }
            if (busy && job != null && !finished) TextButton(onClick = {
                val previous = job
                timeline.mark(TurnStage.CANCEL_REQUESTED)
                job = scope.launch {
                    previous?.cancelAndJoin()
                    timeline.mark(TurnStage.CANCEL_EFFECTIVE)
                    delivery.stop()
                    busy = false
                    status = "Stopped."
                    if (!test && delivery.needsRecovery) showText = true
                }
            }) { Text("Cancel") }
        }
        if (!test) lines.filter { it.first.speaker == Speaker.LEARNER }.lastOrNull()?.let { (turn, _) ->
            SectionCard("You said") {
                Text(turn.text, fontFamily = Fonts.forLanguage(module.code))
                // Live: the answer's feedback shows here when it arrives; After action records it silently.
                if (mode == CorrectionsMode.LIVE) records.lastOrNull { it.learner == turn.text }?.let { LiveFeedback(module, it) }
            }
        }
    }
}

@Composable
private fun LiveFeedback(module: LanguageModule, r: TurnFeedbackRecord) {
    r.corrected?.let { c ->
        Text("Correction", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(c, fontFamily = Fonts.forLanguage(module.code))
        r.changes.forEach { ch -> Text("“${ch.from}” → “${ch.to}”: ${ch.why}", style = MaterialTheme.typography.bodySmall, fontFamily = Fonts.forLanguage(module.code)) }
    }
    r.rewrite?.takeIf { it.isNotBlank() }?.let {
        Text("More natural", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(it, fontFamily = Fonts.forLanguage(module.code))
    }
    if (r.pragmatics.isNotEmpty()) {
        Text("Cultural notes — not part of your level", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        r.pragmatics.forEach { FlagLine(it, module.code) }
    }
}

/** Saves the interview; returns why it didn't join the speaking trend, if it didn't. */
private fun saveInterview(
    app: AppGraph, id: String, lang: String, session: OpiSession, rating: OpiRating?, test: Boolean, english: List<String>,
    cultural: CulturalReview?, mode: CorrectionsMode, aab: AfterActionBrief?, spoken: Int, typed: Int, textFallback: Boolean,
): String? {
    // Only a spoken, model-rated test counts toward the speaking trend, and it stays provisional until the language and
    // tier are calibrated against instructor ratings (rule 7: honest labels; QC 2026-10-19 #3).
    val estimate = rating?.estimate
    val modelRated = rating?.engine != null
    val tier = app.tier
    val calibrated = estimate != null && tier != null && Calibration.band(estimate, app.calibration.entry(lang, tier.id), lang, "Tier ${tier.id}").calibrated
    val guided = session.scriptedTurns
    val questions = session.turns.size
    val decision = SpeakingTrend.decide(test, modelRated, spoken, typed, textFallback, calibrated, rated = estimate != null, guided = guided, questions = questions)
    // A test that fell back to showing the questions is filed as practice (it no longer met the test's conditions).
    // How it was answered and the trend decision are kept with it, so History and the PDF label it the same way (F4).
    app.conversations.save(
        id, app.learnerId, lang, if (test && !textFallback) "OPI_TEST" else "OPI", null, session.startedAt.toEpochMilliseconds(),
        StoredConversation(session.transcript, opiTurns = session.turns, english = english, engine = rating?.engine, cultural = cultural,
            spokenAnswers = spoken, typedAnswers = typed, textFallback = textFallback, calibrated = calibrated,
            trend = decision.status, trendReason = decision.reason),
        rating, session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$id", mode, aab,
    )
    if (estimate == null) return null
    val entry = SpeakingTrend.entry(test, modelRated, spoken, typed, textFallback, calibrated, guided, questions)
        ?: return SpeakingTrend.exclusion(test, modelRated, spoken, typed, textFallback, guided, questions).takeIf { test }
    app.history.saveSpeakingEstimate(app.learnerId, lang, id, estimate, entry.confidence, entry.provisional)
    return if (entry.provisional) "Added to your speaking trend as provisional: " +
        (if (!calibrated) "this model's ratings aren't yet calibrated against instructor ratings for this language"
        else if (typed > 0) "some answers were typed" else "$guided of $questions questions were scripted (guided)") + "." else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterviewResults(
    app: AppGraph, module: LanguageModule, session: OpiSession, rating: OpiRating, cultural: CulturalReview?, lines: List<Pair<Turn, String>>,
    fluency: List<FluencyAnalyzer.Report>, pack: OpiPack, conversationId: String, test: Boolean,
) {
    var selfRating by remember { mutableStateOf<OpiRating?>(null) }
    val checked = remember { mutableStateListOf<String>() }
    if (rating.needsSelfRating && selfRating == null) {
        SectionCard("Rate yourself") {
            Text("No model rated this interview. Tick every statement that is true of how you spoke today.")
            pack.checklist.forEach { (lv, statements) ->
                Text("ILR $lv", fontWeight = FontWeight.SemiBold)
                statements.forEach { st ->
                    CheckRow(st in checked, { if (it) checked += st else checked -= st }) { Text(st) }
                }
            }
            Button(onClick = {
                selfRating = session.selfRate(pack.checklist, checked.toSet())
                val c = app.conversations.get(conversationId)
                // Keep what saveInterview stored (glosses, answer counts, fallback); a self-rating stays out of the trend (F4).
                val saved = c?.let { app.conversations.stored(it) } ?: StoredConversation(session.transcript, cultural = cultural)
                val decision = SpeakingTrend.decide(test, modelRated = false, saved.spokenAnswers ?: 0, saved.typedAnswers ?: 0,
                    saved.textFallback ?: false, calibrated = false, rated = selfRating?.estimate != null,
                    guided = session.scriptedTurns, questions = session.turns.size)
                app.conversations.save(conversationId, app.learnerId, module.code, if (test) "OPI_TEST" else "OPI", null,
                    session.startedAt.toEpochMilliseconds(),
                    saved.copy(transcript = session.transcript, opiTurns = session.turns, cultural = cultural, trend = decision.status, trendReason = decision.reason),
                    selfRating, session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$conversationId",
                    c?.let { app.conversations.mode(it) }, c?.let { app.conversations.aab(it) })
            }) { Text("Save my self-rating") }
        }
    }
    val shown = selfRating ?: rating
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The same label History and the PDF use (QC follow-up F4), read back from what was saved.
            val label = remember(shown) {
                app.conversations.get(conversationId)?.let { app.conversations.speakingLabel(it, app.history.speakingRows(app.learnerId, module.code)) }
            }
            Text(shown.estimate?.let { "Estimated speaking level: " + (label?.text ?: "ILR $it") } ?: "No estimate",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (shown.engine != null) Badge("AI-generated · ${shown.engine}")
            if (shown.selfRated) Badge("Self-rated")
        }
        if (shown.sustained != null) Text("Sustained: ILR ${shown.sustained}" + (shown.breakdown?.let { " · breaks down at ILR $it" } ?: ""))
        // BRIEF_PHASE8 N-04: the confidence band from instructor-rated samples for this language and tier.
        shown.estimate?.takeIf { shown.engine != null }?.let { est ->
            val tier = app.tier
            val band = Calibration.band(est, tier?.let { app.calibration.entry(module.code, it.id) }, module.nameEnglish, tier?.let { "Tier ${it.id}" } ?: "your model")
            if (band.calibrated) Text("Likely range: ILR ${band.low}–${band.high}", fontWeight = FontWeight.SemiBold)
            Text(band.sentence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.rationale.isNotBlank()) Text(shown.rationale)
        Disclaimer()
    }
    if (shown.factors.isNotEmpty()) SectionCard("Evidence by factor") {
        shown.factors.forEach { (name, f) ->
            Text("${name.replace('_', '/').replaceFirstChar { it.uppercase() }}: ILR ${f.level}", fontWeight = FontWeight.SemiBold)
            Text(f.evidence)
            f.quotes.forEach { q -> Text("“$q”", fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (shown.nextSteps.isNotEmpty()) SectionCard("Next steps") { shown.nextSteps.forEachIndexed { i, s -> Text("${i + 1}. $s") } }
    // BRIEF_PHASE8 C-06: a separate block, computed separately, that never changes the rating above.
    if (cultural != null) SectionCard("Cultural appropriateness") {
        Text(NOT_ILR, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(cultural.summary)
        cultural.flags.forEach { f ->
            Text("Answer ${f.turn}: “${f.what}”", fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.bodySmall)
            FlagLine(PragmaticFlag(f.kind, f.severity, f.what, f.why, f.better), module.code)
        }
        cultural.engine?.let { Badge("AI-generated · $it") }
    }
    val map = OpiProbeMap.from(session.turns)
    SectionCard("Level checks and probes") {
        Text("Working level by turn: " + map.levelTrack.joinToString(" → ") { it.second.label }.ifEmpty { "—" })
        map.byLevel.forEach { t -> Text("ILR ${t.level.label}: ${t.sustained} sustained, ${t.partial} partial, ${t.breakdown} breakdown") }
        Text("This picture uses answer length, a rough heuristic — not a rating.", style = MaterialTheme.typography.bodySmall)
    }
    if (fluency.isNotEmpty()) SectionCard("Fluency (heuristic)") {
        val avg = fluency.map { it.composite }.average().toInt()
        Text("Composite $avg/100 · ${fluency.map { it.wordsPerMinute }.average().toInt()} words/min · pauses ${(fluency.map { it.pauseRatio }.average() * 100).toInt()}% · false starts ${fluency.sumOf { it.falseStarts }}")
        Text("Measured from your recordings and transcripts; no pronunciation scoring.", style = MaterialTheme.typography.bodySmall)
    }
    TranscriptCard(app, module, session, lines, conversationId)
}

@Composable
private fun TranscriptCard(app: AppGraph, module: LanguageModule, session: OpiSession, lines: List<Pair<Turn, String>>, conversationId: String) {
    SectionCard("Transcript") {
        lines.forEachIndexed { i, (turn, english) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (turn.speaker == Speaker.PARTNER) "Interviewer" else "You", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                Column(Modifier.weight(1f)) {
                    Text(turn.text, fontFamily = Fonts.forLanguage(module.code))
                    if (english.isNotBlank()) Text(english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rec = session.turns.firstOrNull { it.answer == turn.text && turn.speaker == Speaker.LEARNER }
                    if (rec?.outcome == OpiTurnOutcome.BREAKDOWN) Text("breakdown at ILR ${rec.targetLevel.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (turn.speaker == Speaker.LEARNER && rec != null) CompareWithExemplars(app, module, rec.question)
                }
                if (turn.speaker == Speaker.LEARNER) {
                    val path = app.conversations.recordings(conversationId).lastOrNull { it.turnIndex == i.toLong() }?.path
                    val file = File(app.dataDir, path ?: "recordings/$conversationId/turn-%02d.wav".format(i))
                    if (file.isFile) TextButton(onClick = { Thread { runCatching { Voice.play(app, file.readBytes()) } }.start() }) { Text("▶") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicView(app: AppGraph, module: LanguageModule, pack: OpiPack, mode: CorrectionsMode, persona: Persona?, close: () -> Unit) {
    var domain by remember { mutableStateOf<TopicDomain?>(null) }
    var topic by remember { mutableStateOf<Topic?>(null) }
    val scope = rememberCoroutineScope()
    val generatedTopics = remember(module.code) { mutableStateListOf<Topic>().apply { addAll(app.generated.topics(app.learnerId, module.code)) } }
    var generating by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    val t = topic
    if (t == null) {
        Page("Topic conversation", module.nameEnglish) {
            SectionCard("Domain") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopicDomain.entries.forEach { d ->
                        FilterChip(domain == d, { domain = d }, label = { Text("${d.title} · ${pack.topics.count { it.domain == d.id }}") })
                    }
                }
            }
            domain?.let { d ->
                SectionCard(d.title) {
                    (pack.topics.filter { it.domain == d.id } + generatedTopics.filter { it.domain == d.id }).forEach { tp ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { topic = tp }) { Text(tp.title) }
                            Text("from ILR ${tp.minLevel}", style = MaterialTheme.typography.bodySmall)
                            if (tp.id.startsWith("local-")) Badge("Generated on this computer") else if (tp.source == "llm" && !tp.verified) Badge("AI-generated")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !generating && app.languageModel() != null, onClick = {
                            generating = true
                            scope.launch {
                                val input = GenerateTopics.Input(module.code, pack.profile.registerNotes, d, pack.topics.filter { it.domain == d.id }.map { it.title } + generatedTopics.map { it.title })
                                val r = withContext(Dispatchers.Default) { app.gateway.run(GenerateTopics(), input) }
                                if (r is app.mokuhyo.ai.AiResult.Ok) r.value.topics.forEachIndexed { i, t ->
                                    val topic = Topic("local-${module.code}-topic-${System.currentTimeMillis()}-$i", d.id, t.title, t.opener, t.minLevel, "llm", false)
                                    app.generated.saveTopic(app.learnerId, module.code, topic, r.engine)
                                    generatedTopics += topic
                                }
                                generating = false
                            }
                        }) { Text(if (generating) "Generating…" else "Generate more topics") }
                    }
                }
            }
            SectionCard("Or type your own topic") {
                OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Topic (in English or ${module.nameEnglish})") })
                Button(enabled = custom.isNotBlank(), onClick = {
                    topic = Topic("custom", domain?.id ?: "daily_life", custom.trim(), "", "1", "learner", true)
                }) { Text("Start") }
            }
            TextButton(onClick = close) { Text("Back") }
        }
        return
    }
    val opened = if (t.opener.isBlank() && persona != null && persona.greeting.isNotBlank()) t.copy(opener = persona.greeting) else t
    TopicConversation(app, module, pack, opened, close, mode = mode, persona = persona,
        activity = if (persona != null) SpeakingActivity.PERSONA else SpeakingActivity.TOPIC)
}

@Composable
private fun TopicConversation(
    app: AppGraph, module: LanguageModule, pack: OpiPack, topic: Topic, close: () -> Unit,
    rolePlay: RolePlayContext? = null, kind: String = "TOPIC", mode: CorrectionsMode = CorrectionsMode.LIVE, persona: Persona? = null,
    activity: SpeakingActivity = SpeakingActivity.TOPIC, memory: List<String> = emptyList(),
    restored: StoredConversation? = null,
    onFinished: ((TopicSession, String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val conversationId = remember { restored?.draft?.sessionId ?: app.conversations.newId() }
    val gateway = remember { app.gateway.pinned() }
    val session = remember {
        TopicSession(module.code, pack.profile, topic, gateway,
            rolePlay = restored?.draft?.topic?.rolePlay ?: rolePlay, persona = restored?.draft?.topic?.persona ?: persona?.context(),
            culturalNotes = restored?.draft?.topic?.culturalNotes ?: app.culturalNotes(module.code, persona), mode = mode,
            memory = restored?.draft?.topic?.memory ?: memory, critiqueTimeoutMs = app.critiqueTimeoutMs(), restored = restored?.draft?.topic)
    }
    val exchanges = remember { mutableStateListOf<TopicExchange>().apply { addAll(session.exchanges) } }
    val fluency = remember { mutableStateListOf<FluencyAnalyzer.Report>() }
    var busy by remember { mutableStateOf(false) }
    var captureBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0.0) }
    var aab by remember { mutableStateOf<AfterActionBrief?>(null) }
    val hasStt = remember { app.recognizer() != null }
    var pendingAudio by remember { mutableStateOf<Pair<Int, ShortArray>?>(null) }
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    val deferFeedback = !mode.backgroundBetweenTurns(app.runtime.gpuActive)
    val added = remember { mutableStateListOf<String>() }
    val timeline = remember { app.timings.start(conversationId, kind, module.code, app.timelineSetup(module.code)).also { it.beginTurn(cold = true) } }

    suspend fun checkpoint() {
        val snapshot = StoredConversation(session.transcript, exchanges = session.exchanges.toList(),
            draft = ConversationDraft(conversationId, System.currentTimeMillis(), topic = session.checkpoint()))
        withContext(NonCancellable + Dispatchers.IO) {
            app.conversations.checkpoint(conversationId, app.learnerId, module.code, kind, topic.title,
                session.startedAt.toEpochMilliseconds(), snapshot, mode)
        }
    }

    /** The reply's English gloss for exchange [index], off the speech path (N3). */
    suspend fun gloss(index: Int) {
        val ex = exchanges.getOrNull(index)?.takeIf { it.replyEnglish.isBlank() } ?: return
        val english = (gateway.run(GlossLine(), GlossLine.Input(module.code, ex.reply)) as? AiResult.Ok)?.value?.english ?: return
        val updated = session.gloss(index, english) ?: return
        if (exchanges.getOrNull(index)?.reply == ex.reply) exchanges[index] = updated
    }

    suspend fun collectFeedback(index: Int) {
        val updated = session.feedback(index) ?: return
        if (index < exchanges.size) exchanges[index] = updated
        withContext(Dispatchers.IO) { app.conversations.addFeedback(conversationId, TurnFeedbackRecord.of(index * 2 + 1, updated)) }
        checkpoint()
    }

    fun send(text: String, pcm: ShortArray?) {
        session.accept(text)
        if (pcm != null) pendingAudio = session.transcript.lastIndex to pcm
        busy = true
        status = "Your partner is replying…"
        scope.launch(TimelineTrace(timeline), start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    checkpoint()
                    pendingAudio?.let { (turn, samples) -> saveRecording(app, conversationId, turn, samples) }
                }
                pendingAudio = null
                traceMark(TurnStage.CHECKPOINT_SAVED)
                feedbackJob?.cancelAndJoin()
                // The reply is spoken sentence by sentence as the model writes it (N3), in the persona's one voice.
                val sentences = Channel<String>(2)
                val voice = persona?.let { p -> VoiceRotation.forPersona(app.speech.voicesFor(module.code), p.id, p.gender) }
                val speaking = async(SpeechBudget(SpeechBudget.forResponse(app.replyDeadlineMs()))) { Voice.sayStreamed(app, sentences, module.code, voice) }
                val ex = try {
                    withContext(Dispatchers.Default) { session.say(text, speak = { sentences.send(it) }, splitter = IcuSentences(module.code)) }
                } finally {
                    sentences.close()
                }
                val streamed = speaking.await()
                // What was heard is recorded with the exchange; a part-played reply is kept (QC 2026-10-30 V10).
                if (ex != null && streamed != null) session.delivered(session.exchanges.lastIndex, ReplyRepair.join(streamed.heard), streamed.reply.record(session.exchanges.lastIndex))
                if (ex == null) {
                    // Say why (no model, timeout, load failure, schema failure), not a generic line (BRIEF_PHASE8 N-00b).
                    status = "No reply: ${session.lastError ?: "unknown error"}. Settings → AI → Test the model shows the details."
                } else {
                    checkpoint()
                    exchanges += session.exchanges.last()
                    status = (streamed?.outcome ?: speak(app, ex.reply, module.code, persona)).problem?.let { "$it The reply is shown as text." }.orEmpty()
                    withContext(NonCancellable + Dispatchers.IO) { app.timings.turnDone(timeline) }
                    timeline.beginTurn()
                    if (!deferFeedback) exchanges.lastIndex.let { i -> scope.launch { gloss(i) } }
                    if (pcm != null) fluency += withContext(Dispatchers.Default) {
                        val words = module.segment(text).filter { it.isWord }.map { module.normalizeForCompare(it.text) }
                        FluencyAnalyzer.analyze(pcm, words, tokenFactor = if (module.code in setOf("ja", "ko")) 1.5 else 1.0)
                    }
                    if (mode.records && !deferFeedback) {
                        val index = exchanges.lastIndex
                        feedbackJob = scope.launch { collectFeedback(index) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't continue: ${e.message}. Your answer is retained; retry the reply."
            } finally {
                busy = false
            }
        }
    }

    suspend fun save(brief: AfterActionBrief?) {
        withContext(Dispatchers.IO) {
            onFinished?.invoke(session, conversationId)
            app.conversations.save(conversationId, app.learnerId, module.code, kind, topic.title, session.startedAt.toEpochMilliseconds(),
                StoredConversation(session.transcript, exchanges = exchanges.toList()), null, session.levelTrack.toList(), "recordings/$conversationId", mode, brief)
            session.recurringErrors().forEach { c ->
                app.reviews.add(app.learnerId, module.code, ReviewService.Kind.ERROR, "${c.from}→${c.to}", c.from, "${c.to}\n${c.why}")
            }
        }
    }

    fun finish() {
        busy = true
        status = if (mode.records) "Preparing your After Action Brief…" else "Saving…"
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    checkpoint()
                    pendingAudio?.let { (turn, pcm) -> saveRecording(app, conversationId, turn, pcm) }
                }
                pendingAudio = null
                if (!mode.records) {
                    save(null)
                    busy = false
                    close()
                    return@launch
                }
                feedbackJob?.cancelAndJoin()
                withTimeoutOrNull(30_000) {
                    session.exchanges.indices.filter { session.exchanges[it].feedbackMissing != null }.takeLast(32).forEach { collectFeedback(it) }
                    exchanges.indices.forEach { gloss(it) }
                }
                val brief = AfterActionBuilder.build(gateway, module.code, mode, activity, topic.title, persona?.let { "${it.name} (${it.roleTitle})" },
                    (System.currentTimeMillis() - session.startedAt.toEpochMilliseconds()) / 1000, session.transcript, session.records,
                    session.levelTrack.toList(), fluencyStats(fluency), cardLookup(app, module.code))
                save(brief)
                aab = brief
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't finish saving: ${e.message}. Keep this screen open and retry."
            } finally {
                busy = false
            }
        }
    }

    aab?.let { brief ->
        Page(topic.title, "${module.nameEnglish} · After Action Brief") {
            AfterActionBriefView(app, module.code, brief, conversationId)
            SectionCard("Transcript") {
                session.transcript.forEach { t ->
                    Text((if (t.speaker == Speaker.LEARNER) "You: " else "Partner: ") + t.text, fontFamily = Fonts.forLanguage(module.code))
                }
            }
            Button(onClick = close) { Text("Done") }
        }
        return
    }

    Page(topic.title, "${module.nameEnglish}" + (persona?.let { " · with ${it.name}, ${it.rankTitle}" } ?: "") +
        if (mode == CorrectionsMode.LIVE) " · your rolling level: ILR ${session.rollingLevel.label}" else "") {
        ModeChip(mode)
        if (mode == CorrectionsMode.LIVE && deferFeedback) Text("Feedback is deferred until the end to keep speech responsive on this computer.")
        if (topic.opener.isNotBlank()) SectionCard {
            Text(persona?.name ?: "Partner", fontWeight = FontWeight.SemiBold)
            Text(topic.opener, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleMedium)
            if (topic.source == "llm") Badge("AI-generated")
            TextButton(onClick = { scope.launch { speak(app, topic.opener, module.code, persona).problem?.let { status = it } } }) { Text("▶ Listen") }
        }
        exchanges.forEach { ex ->
            SectionCard {
                Text("You", fontWeight = FontWeight.SemiBold)
                Text(ex.learner, fontFamily = Fonts.forLanguage(module.code))
                if (mode == CorrectionsMode.LIVE) {
                    ex.feedbackMissing?.let { why ->
                        Text("No feedback for this turn ($why). The conversation goes on.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ex.corrected?.let { c ->
                        Text("Correction", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(c, fontFamily = Fonts.forLanguage(module.code))
                        ex.changes.forEach { ch -> Text("“${ch.from}” → “${ch.to}”: ${ch.why}", style = MaterialTheme.typography.bodySmall, fontFamily = Fonts.forLanguage(module.code)) }
                    }
                    ex.rewrite?.let {
                        Text("More natural", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(it, fontFamily = Fonts.forLanguage(module.code))
                    }
                    if (ex.vocabulary.isNotEmpty()) {
                        Text("Vocabulary", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        ex.vocabulary.forEach { v ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${v.word} — ${v.meaning}", fontFamily = Fonts.forLanguage(module.code), modifier = Modifier.weight(1f))
                                TextButton(enabled = v.word !in added, onClick = {
                                    app.reviews.add(app.learnerId, module.code, ReviewService.Kind.WORD, "word:${v.word}", v.word, v.meaning, v.example)
                                    added += v.word
                                }) { Text(if (v.word in added) "Added" else "Add to review") }
                            }
                        }
                    }
                    if (ex.pragmatics.isNotEmpty()) {
                        Text("Cultural notes — not part of your level", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                        ex.pragmatics.forEach { FlagLine(it, module.code) }
                    }
                }
                HorizontalDivider()
                Text(persona?.name ?: "Partner", fontWeight = FontWeight.SemiBold)
                Text(ex.reply, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleMedium)
                if (ex.replyEnglish.isNotBlank()) Text(ex.replyEnglish, style = MaterialTheme.typography.bodySmall)
                if (ex.engine != null) Badge("AI-generated · ${ex.engine}")
            }
        }
        if (status.isNotEmpty()) Text(status)
        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            Text("Working… (Tier A models on a CPU can take 10–25 seconds)")
        }
        if (hasStt) VoiceCapture(app, module, confirm = app.settings.bool(Settings.Key.CONFIRM_TRANSCRIPT, default = true),
            enabled = !busy, onStatus = { status = it }, onBusy = { captureBusy = it },
            visible = !busy && session.transcript.lastOrNull()?.speaker != Speaker.LEARNER,
            prompt = (listOf(topic.title) + session.transcript.takeLast(1).map { it.text }).joinToString(" "), timeline = timeline) { t, pcm -> send(t, pcm) }
        if (!busy) SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == CorrectionsMode.LIVE) OutlinedButton(enabled = !captureBusy && exchanges.isNotEmpty() && session.transcript.lastOrNull()?.speaker != Speaker.LEARNER, onClick = { if (session.redoLast()) exchanges.removeAt(exchanges.lastIndex) }) { Text("Say it again") }
                TextButton(enabled = !captureBusy, onClick = { finish() }) { Text(if (mode == CorrectionsMode.AFTER_ACTION) "End and show After Action Brief" else "End conversation") }
            }
            val pendingAnswer = session.transcript.lastOrNull()?.takeIf { it.speaker == Speaker.LEARNER }
            if (pendingAnswer != null) {
                Text("Saved answer: ${pendingAnswer.text}")
                Button(onClick = { send(pendingAnswer.text, null) }) { Text("Retry reply") }
            }
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("…or type") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
            TextButton(enabled = pendingAnswer == null && !captureBusy && typed.isNotBlank(), onClick = { val s = typed; typed = ""; send(s, null) }) { Text("Send") }
        }
    }
}

/** Culture cards for a set of tags (BRIEF_PHASE8 §B.4.1), with their field-guide citation. */
@Composable
fun CultureCards(cards: List<CultureCard>, lang: String) {
    if (cards.isEmpty()) return
    Text("Culture cards", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    cards.forEach { c ->
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, fontWeight = FontWeight.SemiBold)
                if (!c.verified) Badge("AI-drafted · unreviewed", MaterialTheme.colorScheme.tertiaryContainer)
                if (!c.cited) Badge("General knowledge — no field guide")
            }
            Text(c.body)
            if (c.doThis.isNotEmpty()) Text("Do: " + c.doThis.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontFamily = Fonts.forLanguage(lang))
            if (c.avoidThis.isNotEmpty()) Text("Avoid: " + c.avoidThis.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontFamily = Fonts.forLanguage(lang))
            Text(c.citation(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Scenario role-plays from the Counter-UAS & Base Defense track (BRIEF_PHASE8 §B.3): pick, read the briefing and cards, talk. */
@Composable
private fun ScenarioView(app: AppGraph, module: LanguageModule, pack: OpiPack, track: Track?, mode: CorrectionsMode, persona: Persona?, close: () -> Unit) {
    var chosen by remember { mutableStateOf<Scenario?>(null) }
    var started by remember { mutableStateOf(false) }
    val s = chosen
    if (track == null) {
        Page("Scenarios") { EmptyState("No scenarios", "The track isn't installed for ${module.nameEnglish}."); TextButton(onClick = close) { Text("Back") } }
        return
    }
    if (s != null && started) {
        val opener = s.opener.ifBlank { persona?.greeting.orEmpty() }
        TopicConversation(app, module, pack, Topic(s.id, "military_operations", s.title, opener, s.level, s.source, s.verified), close,
            RolePlayContext(s.situation, persona?.let { "${it.name}, ${it.rankTitle} — ${s.partnerRole}" } ?: s.partnerRole, s.learnerRole),
            kind = "SCENARIO", mode = mode, persona = persona, activity = SpeakingActivity.PERSONA)
        return
    }
    var week by remember { mutableStateOf(false) }
    if (week) {
        ExerciseWeek(app, module, pack, mode, persona) { week = false }
        return
    }
    Page("Scenarios", "${module.nameEnglish} · ${track.title}") {
        if (s == null) {
            SectionCard("Exercise week") {
                val st = remember { app.storylines.current(app.learnerId, module.code) }
                Text("Five linked sessions with the same counterpart — arrival, a drone sighting, an intrusion, a gate incident, the joint after-action " +
                    "review. Your counterpart remembers what happened on earlier days, and how you handled each day shapes the next.")
                Text(when {
                    st == null -> "Not started."
                    st.completed -> "Completed."
                    else -> "Next: ${Storyline.day(st.nextDay)?.title}"
                }, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { week = true }) { Text(if (st == null || st.completed) "Start an exercise week" else "Continue") }
            }
            track.scenarios.forEach { sc ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chosen = sc }) { Text(sc.title) }
                    Text("ILR ${sc.level}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = close) { Text("Back") }
            return@Page
        }
        SectionCard("Before you start") {
            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(s.situation)
            Text("You: ${s.learnerRole} · Your counterpart: ${persona?.let { "${it.name}, ${it.rankTitle}" } ?: s.partnerRole}", style = MaterialTheme.typography.bodySmall)
            val terms = s.terms.mapNotNull { track.term(it) }
            if (terms.isNotEmpty()) {
                Text("Key terms", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                terms.forEach { t -> Text("${t.term} — ${t.termEn}", fontFamily = Fonts.forLanguage(module.code)) }
            }
            val culture = remember(module.code) { app.culture(module.code) }
            val personaCard = persona?.card?.let { id -> culture?.cards?.firstOrNull { it.id == id } }
            CultureCards((listOfNotNull(personaCard) + culture?.forTags(s.tags, 3).orEmpty()).distinctBy { it.id }, module.code)
            if (s.opener.isBlank()) Text("This scenario has no opening line yet; you start the conversation.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { started = true }) { Text("Start") }
                TextButton(onClick = { chosen = null }) { Text("Choose another") }
            }
        }
    }
}


/** The exercise-week storyline (BRIEF_PHASE8 N-03): day by day with a persistent counterpart and memory. */
@Composable
private fun ExerciseWeek(app: AppGraph, module: LanguageModule, pack: OpiPack, mode: CorrectionsMode, chosenPersona: Persona?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val personas = remember(module.code) { app.personas(module.code) }
    var state by remember { mutableStateOf(app.storylines.current(app.learnerId, module.code)?.takeIf { !it.completed }) }
    var playing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val st = state
    val persona = st?.let { s -> personas.firstOrNull { it.id == s.personaId } } ?: chosenPersona
    if (st != null && playing) {
        val (day, role) = StorylineRunner.rolePlay(st, persona?.let { PersonaContext(it.name, it.rankTitle, it.roleTitle, it.force, it.register, it.patience, it.formality) })
        TopicConversation(app, module, pack, Topic(day.id, "military_operations", day.title, ""), { playing = false }, role, kind = "STORYLINE", mode = mode,
            persona = persona, activity = SpeakingActivity.PERSONA, memory = st.memory) { session, conversationId ->
            status = "Saving what your counterpart will remember…"
            scope.launch {
                val rec = withContext(Dispatchers.Default) { StorylineRunner.summarize(app.gateway, module.code, day, conversationId, session.transcript) }
                app.storylines.addDay(st.id, rec)
                state = app.storylines.current(app.learnerId, module.code)
                status = "Day ${day.n} saved: ${rec.summary}"
            }
        }
        return
    }
    Page("Exercise week", module.nameEnglish) {
        if (st == null) {
            SectionCard("Choose your counterpart") {
                if (personas.isEmpty()) Text("No personas in this language's pack; your counterpart will be a generic host-nation officer.")
                personas.filter { it.role in setOf("senior_counterpart", "peer_officer") }.forEach { p ->
                    TextButton(onClick = { state = app.storylines.start(app.learnerId, module.code, p.id) }) { Text("${p.name}, ${p.rankTitle} (${p.force})") }
                }
                if (personas.isEmpty()) Button(onClick = { state = app.storylines.start(app.learnerId, module.code, "") }) { Text("Start") }
            }
        } else {
            SectionCard(Storyline.day(st.nextDay)?.title ?: "Week complete") {
                st.days.sortedBy { it.day }.forEach { d -> Text("Day ${d.day}: ${d.summary}", style = MaterialTheme.typography.bodySmall) }
                if (!st.completed) {
                    Text(Storyline.situation(st.nextDay, st.lastChoice))
                    Button(onClick = { playing = true }) { Text("Play day ${st.nextDay}") }
                } else Text("Exercise week complete.", fontWeight = FontWeight.SemiBold)
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = close) { Text("Back") }
    }
}
