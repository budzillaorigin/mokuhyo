package app.mokuhyo.desktop.ui.speaking

import app.mokuhyo.lang.VoiceRotation
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
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.lexicon.Scenario
import app.mokuhyo.lexicon.Track
import app.mokuhyo.opi.AfterActionBrief
import app.mokuhyo.opi.AfterActionBuilder
import app.mokuhyo.opi.Calibration
import app.mokuhyo.opi.CorrectionsMode
import app.mokuhyo.opi.CulturalReview
import app.mokuhyo.opi.FeedbackQueue
import app.mokuhyo.opi.GenerateTopics
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
import app.mokuhyo.speech.FluencyAnalyzer
import app.mokuhyo.srs.ReviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    val mode = CorrectionsMode.effective(modes[activity.ordinal], opiTest = tab == 1)
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
        when (tab) {
            0 -> SectionCard("Interview practice") {
                Text("A short interview (about 10 questions): warm-up, level checks, probes, a role-play and a wind-down. The questions adapt to your answers. You can show the question text if you didn't catch it.")
                ModePicker(modes[activity.ordinal], hasModel) { modes[activity.ordinal] = it }
                Button(onClick = { active = true }) { Text("Start interview") }
            }
            1 -> SectionCard("Interview test") {
                Text("A full 20–30 minute interview with no hints: the questions are spoken only and the transcript stays hidden until the end. Your rating counts toward your speaking estimate.")
                Text("Corrections: After action (always, in a test) — your After Action Brief comes at the end.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = hasModel, onClick = { active = true }) { Text("Start test") }
                if (!hasModel) Text("The test needs an AI model for the rating.", style = MaterialTheme.typography.bodySmall)
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
        RadioRow(mode == m, { onChange(m) }) {
            Column {
                Text(m.title)
                Text(when (m) {
                    CorrectionsMode.LIVE -> "Corrections, a natural rewrite and cultural notes under each of your turns."
                    CorrectionsMode.AFTER_ACTION -> "Nothing but the conversation; everything is recorded and given to you at the end in an After Action Brief."
                    CorrectionsMode.OFF -> "Free-flow warm-up: transcript and recordings only — no feedback will be generated."
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (!hasModel && mode != CorrectionsMode.OFF) Text("Corrections need an AI model.", style = MaterialTheme.typography.bodySmall)
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

/** Push-to-talk recorder shared by every mode; returns 16 kHz PCM. */
private class Recorder(private val app: AppGraph) {
    private var rec: AudioIO.Recording? = null
    val recording: Boolean get() = rec != null

    fun start(onLevel: (Double) -> Unit) {
        rec = AudioIO.record(app.settings.get(Settings.Key.INPUT_DEVICE), onLevel)
    }

    fun stop(): ShortArray = rec?.stop().also { rec = null } ?: ShortArray(0)
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Saves a turn's audio under recordings/<conversation>/ and indexes it — unless the learner turned AAB audio off. */
private fun saveRecording(app: AppGraph, conversationId: String, turn: Int, pcm: ShortArray): String? {
    if (!app.settings.bool(Settings.Key.AAB_KEEP_AUDIO, default = true)) return null
    val dir = File(app.dataDir, "recordings/$conversationId").apply { mkdirs() }
    val wav = AudioIO.wav(pcm)
    val file = File(dir, "turn-%02d.wav".format(turn))
    file.writeBytes(wav)
    app.conversations.addRecording(conversationId, turn, "recordings/$conversationId/${file.name}", pcm.size / 16L, sha256(wav))
    return file.path
}

private suspend fun speak(app: AppGraph, text: String, lang: String, persona: Persona? = null) = withContext(Dispatchers.IO) {
    // A persona keeps one voice of their gender (BRIEF_PHASE8 N-07).
    val voice = persona?.let { p -> VoiceRotation.forPersona(app.speech.voicesFor(lang), p.id, p.gender) }
    app.speech.synthesize(text, lang, voice)?.let { runCatching { AudioIO.play(it.wav, app.settings.get(Settings.Key.OUTPUT_DEVICE)) } }
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
private fun InterviewView(app: AppGraph, module: LanguageModule, pack: OpiPack, test: Boolean, mode: CorrectionsMode, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conversationId = remember { app.conversations.newId() }
    val session = remember {
        OpiSession(module.code, pack.profile, pack.questions, pack.rolePlays, app.gateway, wordCounter(module), IlrLevel.L1, test = test,
            culturalNotes = app.culturalNotes(module.code))
    }
    val notes = remember { app.culturalNotes(module.code) }
    val lines = remember { mutableStateListOf<Pair<Turn, String>>() } // (turn, english gloss)
    val records = remember { mutableStateListOf<TurnFeedbackRecord>() }
    val queue = remember {
        if (mode.records && app.languageModel() != null) FeedbackQueue(app.gateway, scope) { rec ->
            records += rec
            app.conversations.addFeedback(conversationId, rec)
        } else null
    }
    DisposableEffect(Unit) { onDispose { queue?.close() } }
    var status by remember { mutableStateOf("Press Start when you're ready.") }
    var busy by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0.0) }
    var typed by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<OpiRating?>(null) }
    var cultural by remember { mutableStateOf<CulturalReview?>(null) }
    var aab by remember { mutableStateOf<AfterActionBrief?>(null) }
    var finished by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val recorder = remember { Recorder(app) }
    val fluency = remember { mutableStateListOf<FluencyAnalyzer.Report>() }
    val hasStt = remember { app.recognizer() != null }

    fun complete() {
        finished = true
        busy = true
        status = if (mode.records) "Preparing your After Action Brief…" else "Saving…"
        job = scope.launch {
            queue?.drain()
            val r = if (mode.records) withContext(Dispatchers.Default) { session.rate() } else null
            val c = if (mode.records && r?.engine != null) withContext(Dispatchers.Default) { session.culturalReview(notes) } else null
            val brief = AfterActionBuilder.build(
                app.gateway.takeIf { mode.records && app.languageModel() != null }, module.code, mode, SpeakingActivity.OPI_PRACTICE,
                if (test) "Interview test" else "Interview practice", null, (System.currentTimeMillis() - session.startedAt.toEpochMilliseconds()) / 1000,
                session.transcript, records.sortedBy { it.turnIndex }, session.turns.mapNotNull { it.levelAfter?.label }, fluencyStats(fluency),
                cardLookup(app, module.code),
            )
            rating = r
            cultural = c
            aab = brief
            saveInterview(app, conversationId, module.code, session, r, test, lines.map { it.second }, c, mode, brief)
            busy = false
            done = true
        }
    }

    fun nextQuestion() {
        busy = true
        status = "The interviewer is thinking…"
        job = scope.launch {
            queue?.foregroundBusy(true)
            val line = withContext(Dispatchers.Default) { session.next() }
            queue?.foregroundBusy(false)
            if (line == null) {
                complete()
                return@launch
            }
            lines += Turn(Speaker.PARTNER, line.text) to line.english
            status = if (test) "Listen, then answer." else "Listen, then answer (show the question if you need it)."
            speak(app, line.text, module.code)
            busy = false
            status = if (hasStt) "Press Record and answer." else "Type your answer."
        }
    }

    fun submitAnswer(text: String, pcm: ShortArray?) {
        val turnIndex = lines.size
        val question = lines.lastOrNull { it.first.speaker == Speaker.PARTNER }?.first?.text.orEmpty()
        lines += Turn(Speaker.LEARNER, text) to ""
        session.answer(text)
        queue?.enqueue(turnIndex, TurnFeedback.Input(module.code, pack.profile.registerNotes, question, text, notes))
        if (pcm != null && pcm.isNotEmpty()) {
            saveRecording(app, conversationId, turnIndex, pcm)
            val words = module.segment(text).filter { it.isWord }.map { module.normalizeForCompare(it.text) }
            fluency += FluencyAnalyzer.analyze(pcm, words, tokenFactor = if (module.code in setOf("ja", "ko")) 1.5 else 1.0)
        }
        nextQuestion()
    }

    Page(if (test) "Interview test" else "Interview practice", "${module.nameEnglish} · phase: ${session.phase.wireName.replace('_', ' ')}" +
        if (!test) " · working level ILR ${session.workingLevel.label}" else "") {
        if (done) {
            val r = rating
            if (r != null) InterviewResults(app, module, session, r, cultural, lines, fluency, pack, conversationId, test)
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            queue?.pendingCount?.collectAsState()?.value?.takeIf { it > 0 && mode == CorrectionsMode.LIVE }?.let {
                Text("Feedback on $it answer(s) is being prepared…", style = MaterialTheme.typography.bodySmall)
            }
            if (!started) {
                Button(onClick = { started = true; nextQuestion() }) { Text("Start") }
            } else if (!busy && !finished) {
                val last = lines.lastOrNull()
                if (last?.first?.speaker == Speaker.PARTNER) {
                    if (!test) CheckRow(showText, { showText = it }) { Text("Show the question text") }
                    if (showText && !test) Text(last.first.text, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { scope.launch { speak(app, last.first.text, module.code) } }) { Text("Repeat the question") }
                        if (hasStt) Button(onClick = {
                            if (!recorder.recording) {
                                runCatching { recorder.start { level = it } }.onFailure { status = "Couldn't open the microphone: ${it.message}" }
                                status = "Recording… press Stop when you're done."
                            } else {
                                val pcm = recorder.stop()
                                level = 0.0
                                busy = true
                                status = "Transcribing…"
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) { runCatching { app.recognizer()!!.transcribe(pcm, module.sttLanguage).text }.getOrDefault("") }
                                    busy = false
                                    if (text.isBlank()) status = "I didn't catch anything. Try again, or type your answer." else submitAnswer(text, pcm)
                                }
                            }
                        }) { Text(if (recorder.recording) "■ Stop" else "● Record") }
                    }
                    if (recorder.recording) LinearProgressIndicator(progress = { (level * 4).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("…or type your answer") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = typed.isNotBlank(), onClick = { val t = typed; typed = ""; submitAnswer(t, null) }) { Text("Send typed answer") }
                        TextButton(onClick = { session.stop(); complete() }) {
                            Text(if (mode == CorrectionsMode.AFTER_ACTION) "End and show After Action Brief" else "End interview")
                        }
                    }
                }
            }
            if (busy && job != null && !finished) TextButton(onClick = { job?.cancel(); busy = false; status = "Stopped." }) { Text("Cancel") }
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

private fun saveInterview(
    app: AppGraph, id: String, lang: String, session: OpiSession, rating: OpiRating?, test: Boolean, english: List<String>,
    cultural: CulturalReview?, mode: CorrectionsMode, aab: AfterActionBrief?,
) {
    app.conversations.save(
        id, app.learnerId, lang, if (test) "OPI_TEST" else "OPI", null, session.startedAt.toEpochMilliseconds(),
        StoredConversation(session.transcript, opiTurns = session.turns, english = english, engine = rating?.engine, cultural = cultural),
        rating, session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$id", mode, aab,
    )
    // Only a model-rated test counts toward the speaking trend (rule 7: honest labels; practice and self-ratings don't).
    val estimate = rating?.estimate
    if (test && estimate != null && rating.engine != null) {
        app.history.saveSpeakingEstimate(app.learnerId, lang, id, estimate, 0.5, provisional = false)
    }
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
                app.conversations.save(conversationId, app.learnerId, module.code, if (test) "OPI_TEST" else "OPI", null,
                    session.startedAt.toEpochMilliseconds(), StoredConversation(session.transcript, opiTurns = session.turns, cultural = cultural),
                    selfRating, session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$conversationId",
                    c?.let { app.conversations.mode(it) }, c?.let { app.conversations.aab(it) })
            }) { Text("Save my self-rating") }
        }
    }
    val shown = selfRating ?: rating
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shown.estimate?.let { "Estimated speaking level: ILR $it" } ?: "No estimate", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
                    val file = File(app.dataDir, "recordings/$conversationId/turn-%02d.wav".format(i))
                    if (file.isFile) TextButton(onClick = { Thread { runCatching { AudioIO.play(file.readBytes()) } }.start() }) { Text("▶") }
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
    onFinished: ((TopicSession, String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val conversationId = remember { app.conversations.newId() }
    val session = remember {
        TopicSession(module.code, pack.profile, topic, app.gateway, rolePlay = rolePlay, persona = persona?.context(),
            culturalNotes = app.culturalNotes(module.code, persona), mode = mode, memory = memory)
    }
    val exchanges = remember { mutableStateListOf<TopicExchange>() }
    val fluency = remember { mutableStateListOf<FluencyAnalyzer.Report>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0.0) }
    var aab by remember { mutableStateOf<AfterActionBrief?>(null) }
    val recorder = remember { Recorder(app) }
    val hasStt = remember { app.recognizer() != null }
    val added = remember { mutableStateListOf<String>() }

    fun send(text: String, pcm: ShortArray?) {
        busy = true
        status = "Your partner is replying…"
        scope.launch {
            val ex = withContext(Dispatchers.Default) { session.say(text) }
            if (ex == null) {
                status = "The model didn't answer. Check Settings → AI, or try again."
            } else {
                exchanges += ex
                val turn = exchanges.size * 2 - 1
                if (mode.records) app.conversations.addFeedback(conversationId, TurnFeedbackRecord.of(turn, ex))
                if (pcm != null) {
                    saveRecording(app, conversationId, turn, pcm)
                    val words = module.segment(text).filter { it.isWord }.map { module.normalizeForCompare(it.text) }
                    fluency += FluencyAnalyzer.analyze(pcm, words, tokenFactor = if (module.code in setOf("ja", "ko")) 1.5 else 1.0)
                }
                status = ""
                speak(app, ex.reply, module.code, persona)
            }
            busy = false
        }
    }

    fun save(brief: AfterActionBrief?) {
        onFinished?.invoke(session, conversationId)
        app.conversations.save(conversationId, app.learnerId, module.code, kind, topic.title, session.startedAt.toEpochMilliseconds(),
            StoredConversation(session.transcript, exchanges = exchanges.toList()), null, session.levelTrack.toList(), "recordings/$conversationId", mode, brief)
        session.recurringErrors().forEach { c ->
            app.reviews.add(app.learnerId, module.code, ReviewService.Kind.ERROR, "${c.from}→${c.to}", c.from, "${c.to}\n${c.why}")
        }
    }

    fun finish() {
        if (mode != CorrectionsMode.AFTER_ACTION) {
            save(null)
            close()
            return
        }
        busy = true
        status = "Preparing your After Action Brief…"
        scope.launch {
            val brief = AfterActionBuilder.build(app.gateway, module.code, mode, activity, topic.title, persona?.let { "${it.name} (${it.roleTitle})" },
                (System.currentTimeMillis() - session.startedAt.toEpochMilliseconds()) / 1000, session.transcript, session.records,
                session.levelTrack.toList(), fluencyStats(fluency), cardLookup(app, module.code))
            save(brief)
            aab = brief
            busy = false
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
        if (topic.opener.isNotBlank()) SectionCard {
            Text(persona?.name ?: "Partner", fontWeight = FontWeight.SemiBold)
            Text(topic.opener, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleMedium)
            if (topic.source == "llm") Badge("AI-generated")
            TextButton(onClick = { scope.launch { speak(app, topic.opener, module.code, persona) } }) { Text("▶ Listen") }
        }
        exchanges.forEach { ex ->
            SectionCard {
                Text("You", fontWeight = FontWeight.SemiBold)
                Text(ex.learner, fontFamily = Fonts.forLanguage(module.code))
                if (mode == CorrectionsMode.LIVE) {
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
        if (!busy) SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasStt) Button(onClick = {
                    if (!recorder.recording) {
                        runCatching { recorder.start { level = it } }.onFailure { status = "Couldn't open the microphone: ${it.message}" }
                    } else {
                        val pcm = recorder.stop()
                        level = 0.0
                        busy = true
                        status = "Transcribing…"
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { runCatching { app.recognizer()!!.transcribe(pcm, module.sttLanguage).text }.getOrDefault("") }
                            busy = false
                            if (text.isBlank()) status = "I didn't catch anything." else send(text, pcm)
                        }
                    }
                }) { Text(if (recorder.recording) "■ Stop" else "● Record") }
                if (mode == CorrectionsMode.LIVE) OutlinedButton(enabled = exchanges.isNotEmpty(), onClick = { if (session.redoLast()) exchanges.removeAt(exchanges.lastIndex) }) { Text("Say it again") }
                TextButton(onClick = { finish() }) { Text(if (mode == CorrectionsMode.AFTER_ACTION) "End and show After Action Brief" else "End conversation") }
            }
            if (recorder.recording) LinearProgressIndicator(progress = { (level * 4).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("…or type") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
            TextButton(enabled = typed.isNotBlank(), onClick = { val s = typed; typed = ""; send(s, null) }) { Text("Send") }
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
