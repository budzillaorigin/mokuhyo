package app.mokuhyo.desktop.ui.speaking

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
import androidx.compose.material3.Checkbox
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
import app.mokuhyo.desktop.ui.Badge
import app.mokuhyo.desktop.ui.Disclaimer
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.Page
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.opi.OpiPack
import app.mokuhyo.opi.OpiProbeMap
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.OpiSession
import app.mokuhyo.opi.OpiTurnOutcome
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.GenerateTopics
import app.mokuhyo.opi.Topic
import app.mokuhyo.opi.TopicDomain
import app.mokuhyo.opi.TopicExchange
import app.mokuhyo.opi.TopicSession
import app.mokuhyo.opi.Turn
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

/** Speaking (BRIEF §2, §6): interview practice, interview test, topic conversation. */
@Composable
fun SpeakingScreen(app: AppGraph) {
    val lang by app.language.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val module = remember(lang) { app.languages.module(lang) }
    val pack = remember(lang) { app.opi(lang) }
    var active by remember(lang) { mutableStateOf(false) }
    if (active) {
        when (tab) {
            0, 1 -> InterviewView(app, module, pack!!, test = tab == 1) { active = false }
            else -> TopicView(app, module, pack!!) { active = false }
        }
        return
    }
    Page("Speaking", "${module.nameEnglish} · interviews are voice-first; you can also type") {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Interview practice") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Interview test") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Topic conversation") })
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
            Text(if (tab == 2) "Topic conversation needs a model. Download one in Settings → AI." else
                "Interviews use the scripted question bank, and at the end you rate yourself against the ILR descriptions. With a model, the interviewer adapts and rates you.")
        }
        if (!hasStt) Text("Speech recognition isn't available, so you'll type your answers.", color = MaterialTheme.colorScheme.error)
        when (tab) {
            0 -> SectionCard("Interview practice") {
                Text("A short interview (about 10 questions): warm-up, level checks, probes, a role-play and a wind-down. The questions adapt to your answers. You can show the question text if you didn't catch it.")
                Button(onClick = { active = true }) { Text("Start interview") }
            }
            1 -> SectionCard("Interview test") {
                Text("A full 20–30 minute interview with no hints: the questions are spoken only and the transcript stays hidden until the end. Your rating counts toward your speaking estimate.")
                Button(enabled = hasModel, onClick = { active = true }) { Text("Start test") }
                if (!hasModel) Text("The test needs an AI model for the rating.", style = MaterialTheme.typography.bodySmall)
                Disclaimer()
            }
            else -> SectionCard("Topic conversation") {
                Text("Talk about a topic. After each turn you get corrections, a natural rewrite and vocabulary notes, and the partner adjusts to your level.")
                Button(enabled = hasModel, onClick = { active = true }) { Text("Choose a topic") }
            }
        }
    }
}

/** Push-to-talk recorder shared by both modes; returns 16 kHz PCM. */
private class Recorder(private val app: AppGraph) {
    private var rec: AudioIO.Recording? = null
    val recording: Boolean get() = rec != null

    fun start(onLevel: (Double) -> Unit) {
        rec = AudioIO.record(app.settings.get(Settings.Key.INPUT_DEVICE), onLevel)
    }

    fun stop(): ShortArray = rec?.stop().also { rec = null } ?: ShortArray(0)
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Saves a turn's audio under recordings/<conversation>/ and indexes it. */
private fun saveRecording(app: AppGraph, conversationId: String, turn: Int, pcm: ShortArray): String {
    val dir = File(app.dataDir, "recordings/$conversationId").apply { mkdirs() }
    val wav = AudioIO.wav(pcm)
    val file = File(dir, "turn-%02d.wav".format(turn))
    file.writeBytes(wav)
    app.conversations.addRecording(conversationId, turn, "recordings/$conversationId/${file.name}", pcm.size / 16L, sha256(wav))
    return file.path
}

private suspend fun speak(app: AppGraph, text: String, lang: String) = withContext(Dispatchers.IO) {
    app.speech.synthesize(text, lang)?.let { runCatching { AudioIO.play(it.wav, app.settings.get(Settings.Key.OUTPUT_DEVICE)) } }
}

private fun wordCounter(module: LanguageModule): (String) -> Int {
    val factor = when (module.code) { "ja", "ko" -> 1.5; "zh-Hans" -> 1.2; "ar" -> 0.85; "ru" -> 0.9; else -> 1.0 }
    return { s -> (module.segment(s).count { it.isWord } / factor).toInt() }
}

@Composable
private fun InterviewView(app: AppGraph, module: LanguageModule, pack: OpiPack, test: Boolean, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conversationId = remember { app.conversations.newId() }
    val session = remember {
        OpiSession(module.code, pack.profile, pack.questions, pack.rolePlays, app.gateway, wordCounter(module), IlrLevel.L1, test = test)
    }
    val lines = remember { mutableStateListOf<Pair<Turn, String>>() } // (turn, english gloss)
    var status by remember { mutableStateOf("Press Start when you're ready.") }
    var busy by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0.0) }
    var typed by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<OpiRating?>(null) }
    var finished by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val recorder = remember { Recorder(app) }
    val fluency = remember { mutableStateListOf<FluencyAnalyzer.Report>() }
    val hasStt = remember { app.recognizer() != null }

    fun nextQuestion() {
        busy = true
        status = "The interviewer is thinking…"
        job = scope.launch {
            val line = withContext(Dispatchers.Default) { session.next() }
            if (line == null) {
                finished = true
                status = "The interview is over. Rating…"
                rating = withContext(Dispatchers.Default) { session.rate() }
                saveInterview(app, conversationId, module.code, session, rating, test, lines.map { it.second })
                busy = false
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
        lines += Turn(Speaker.LEARNER, text) to ""
        session.answer(text)
        if (pcm != null && pcm.isNotEmpty()) {
            saveRecording(app, conversationId, turnIndex, pcm)
            val words = module.segment(text).filter { it.isWord }.map { module.normalizeForCompare(it.text) }
            fluency += FluencyAnalyzer.analyze(pcm, words, tokenFactor = if (module.code in setOf("ja", "ko")) 1.5 else 1.0)
        }
        nextQuestion()
    }

    Page(if (test) "Interview test" else "Interview practice", "${module.nameEnglish} · phase: ${session.phase.wireName.replace('_', ' ')}" +
        if (!test) " · working level ILR ${session.workingLevel.label}" else "") {
        if (rating != null) {
            InterviewResults(app, module, session, rating!!, lines, fluency, pack, conversationId, test)
            Button(onClick = close) { Text("Done") }
            return@Page
        }
        SectionCard {
            Text(status, style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!started) {
                Button(onClick = { started = true; nextQuestion() }) { Text("Start") }
            } else if (!busy && !finished) {
                val last = lines.lastOrNull()
                if (last?.first?.speaker == Speaker.PARTNER) {
                    if (!test) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(showText, { showText = it })
                        Text("Show the question text")
                    }
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
                        TextButton(onClick = { session.stop(); finished = true; busy = true; status = "Rating…"
                            scope.launch {
                                rating = withContext(Dispatchers.Default) { session.rate() }
                                saveInterview(app, conversationId, module.code, session, rating, test, lines.map { it.second })
                                busy = false
                            }
                        }) { Text("End interview") }
                    }
                }
            }
            if (busy && job != null) TextButton(onClick = { job?.cancel(); busy = false; status = "Stopped." }) { Text("Cancel") }
        }
        if (!test) lines.filter { it.first.speaker == Speaker.LEARNER }.lastOrNull()?.let { (turn, _) ->
            SectionCard("You said") { Text(turn.text, fontFamily = Fonts.forLanguage(module.code)) }
        }
    }
}

private fun saveInterview(app: AppGraph, id: String, lang: String, session: OpiSession, rating: OpiRating?, test: Boolean, english: List<String>) {
    app.conversations.save(
        id, app.learnerId, lang, if (test) "OPI_TEST" else "OPI", null, session.startedAt.toEpochMilliseconds(),
        StoredConversation(session.transcript, opiTurns = session.turns, english = english, engine = rating?.engine),
        rating, session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$id",
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
    app: AppGraph, module: LanguageModule, session: OpiSession, rating: OpiRating, lines: List<Pair<Turn, String>>,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(st in checked, { if (it) checked += st else checked -= st })
                        Text(st)
                    }
                }
            }
            Button(onClick = {
                selfRating = session.selfRate(pack.checklist, checked.toSet())
                app.conversations.save(conversationId, app.learnerId, module.code, if (test) "OPI_TEST" else "OPI", null,
                    session.startedAt.toEpochMilliseconds(), StoredConversation(session.transcript, opiTurns = session.turns),
                    selfRating, session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$conversationId")
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
    SectionCard("Transcript") {
        lines.forEachIndexed { i, (turn, english) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (turn.speaker == Speaker.PARTNER) "Interviewer" else "You", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                Column(Modifier.weight(1f)) {
                    Text(turn.text, fontFamily = Fonts.forLanguage(module.code))
                    if (english.isNotBlank()) Text(english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rec = session.turns.firstOrNull { it.answer == turn.text && turn.speaker == Speaker.LEARNER }
                    if (rec?.outcome == OpiTurnOutcome.BREAKDOWN) Text("breakdown at ILR ${rec.targetLevel.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
private fun TopicView(app: AppGraph, module: LanguageModule, pack: OpiPack, close: () -> Unit) {
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
    TopicConversation(app, module, pack, t, close)
}

@Composable
private fun TopicConversation(app: AppGraph, module: LanguageModule, pack: OpiPack, topic: Topic, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conversationId = remember { app.conversations.newId() }
    val session = remember { TopicSession(module.code, pack.profile, topic, app.gateway) }
    val exchanges = remember { mutableStateListOf<TopicExchange>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0.0) }
    val recorder = remember { Recorder(app) }
    val hasStt = remember { app.recognizer() != null }
    var opener by remember { mutableStateOf(topic.opener) }
    val added = remember { mutableStateListOf<String>() }

    if (opener.isBlank()) {
        // A typed-in topic: ask the model for an opener by sending the topic as the learner's first line.
        opener = "…"
    }

    fun send(text: String, pcm: ShortArray?) {
        busy = true
        status = "Your partner is replying…"
        scope.launch {
            val ex = withContext(Dispatchers.Default) { session.say(text) }
            if (ex == null) {
                status = "The model didn't answer. Check Settings → AI, or try again."
            } else {
                exchanges += ex
                if (pcm != null) saveRecording(app, conversationId, exchanges.size * 2 - 1, pcm)
                status = ""
                speak(app, ex.reply, module.code)
            }
            busy = false
        }
    }

    fun finish() {
        app.conversations.save(conversationId, app.learnerId, module.code, "TOPIC", topic.title, session.startedAt.toEpochMilliseconds(),
            StoredConversation(session.transcript, exchanges = exchanges.toList()), null, session.levelTrack.toList(), "recordings/$conversationId")
        session.recurringErrors().forEach { c ->
            app.reviews.add(app.learnerId, module.code, ReviewService.Kind.ERROR, "${c.from}→${c.to}", c.from, "${c.to}\n${c.why}")
        }
        close()
    }

    Page(topic.title, "${module.nameEnglish} · your rolling level: ILR ${session.rollingLevel.label}") {
        if (topic.opener.isNotBlank()) SectionCard {
            Text("Partner", fontWeight = FontWeight.SemiBold)
            Text(topic.opener, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleMedium)
            if (topic.source == "llm") Badge("AI-generated")
            TextButton(onClick = { scope.launch { speak(app, topic.opener, module.code) } }) { Text("▶ Listen") }
        }
        exchanges.forEach { ex ->
            SectionCard {
                Text("You", fontWeight = FontWeight.SemiBold)
                Text(ex.learner, fontFamily = Fonts.forLanguage(module.code))
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
                HorizontalDivider()
                Text("Partner", fontWeight = FontWeight.SemiBold)
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
                OutlinedButton(enabled = exchanges.isNotEmpty(), onClick = { if (session.redoLast()) exchanges.removeAt(exchanges.lastIndex) }) { Text("Say it again") }
                TextButton(onClick = { finish() }) { Text("End conversation") }
            }
            if (recorder.recording) LinearProgressIndicator(progress = { (level * 4).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("…or type") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
            TextButton(enabled = typed.isNotBlank(), onClick = { val s = typed; typed = ""; send(s, null) }) { Text("Send") }
        }
    }
}
