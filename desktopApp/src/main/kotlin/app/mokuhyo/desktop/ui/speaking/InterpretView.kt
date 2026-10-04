package app.mokuhyo.desktop.ui.speaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.mokuhyo.desktop.ui.RadioRow
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.exam.Skill
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.opi.Chunk
import app.mokuhyo.opi.Chunker
import app.mokuhyo.opi.InterpretDirection
import app.mokuhyo.opi.InterpretResult
import app.mokuhyo.opi.InterpretSession
import app.mokuhyo.opi.InterpretVariant
import app.mokuhyo.opi.SourceLine
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Degrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speaking → Interpret (BRIEF_PHASE8 N-01): consecutive interpretation of track dialogue lines in either direction,
 * a radio relay over the degraded radio channel, or timed sight translation of a notice. Feedback (accuracy,
 * completeness, register, what was missed, a better version) comes at the end, After-action style.
 */
@Composable
fun InterpretSetup(app: AppGraph, module: LanguageModule, close: () -> Unit) {
    var direction by remember { mutableStateOf(InterpretDirection.TO_ENGLISH) }
    var variant by remember { mutableStateOf(InterpretVariant.CONSECUTIVE) }
    var pauseSec by remember { mutableStateOf(5f) }
    var started by remember { mutableStateOf<List<Chunk>?>(null) }
    val track = remember(module.code) { app.lexicon(module.code) }
    started?.let { chunks ->
        InterpretRun(app, module, chunks, direction, variant, pauseSec.toInt(), close)
        return
    }
    Page("Interpret", module.nameEnglish) {
        SectionCard("Direction") {
            InterpretDirection.entries.forEach { d ->
                RadioRow(direction == d, { direction = d }) { Text(if (d == InterpretDirection.TO_ENGLISH) "${module.nameEnglish} → English" else "English → ${module.nameEnglish}") }
            }
        }
        SectionCard("Kind of drill") {
            InterpretVariant.entries.forEach { v ->
                RadioRow(variant == v, { variant = v }) {
                    Column {
                        Text(v.title)
                        Text(when (v) {
                            InterpretVariant.CONSECUTIVE -> "Hear a chunk of 1–3 sentences, take notes in the pause, then say it in the other language."
                            InterpretVariant.RADIO_RELAY -> "The same over a noisy radio channel, with radio language and brevity words."
                            InterpretVariant.SIGHT -> "A notice appears on screen for a short time; read it and say it in the other language."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (variant != InterpretVariant.SIGHT) {
                Text("Note-taking pause after each chunk: ${pauseSec.toInt()} s")
                Slider(pauseSec, { pauseSec = it }, valueRange = 0f..20f, steps = 19, modifier = Modifier.width(300.dp))
            }
        }
        val chunks = remember(module.code, direction, variant) { buildChunks(app, module, track, direction, variant) }
        if (chunks.isEmpty()) EmptyState("No material yet", "Interpretation uses the Counter-UAS & Base Defense dialogues and notices, which aren't installed for ${module.nameEnglish}.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = chunks.isNotEmpty() && app.languageModel() != null, onClick = { started = chunks }) { Text("Start (${chunks.size} chunks)") }
            TextButton(onClick = close) { Text("Back") }
        }
        if (app.languageModel() == null) Text("Grading needs an AI model (Settings → AI).", style = MaterialTheme.typography.bodySmall)
    }
}

private fun buildChunks(app: AppGraph, module: LanguageModule, track: app.mokuhyo.lexicon.Track?, direction: InterpretDirection, variant: InterpretVariant): List<Chunk> {
    val dialogues = track?.dialogues.orEmpty().shuffled()
    return when (variant) {
        InterpretVariant.SIGHT -> {
            val notices = app.exam(module.code)?.passagesFor(Skill.READING).orEmpty().filter { it.track == "cuas-base-defense" && it.level in setOf("1", "1+", "2") }.shuffled()
            notices.take(3).flatMap { p -> Chunker.chunks(Chunker.sentences(p.body).chunked(2).map { SourceLine(it.joinToString(" "), "") }, module.code, InterpretDirection.TO_ENGLISH, p.id) }
                .take(8).let { cs -> if (direction == InterpretDirection.TO_ENGLISH) cs else cs } // sight translation reads the target-language notice
        }
        else -> {
            val d = if (variant == InterpretVariant.RADIO_RELAY) dialogues.filter { it.scenario in setOf("drone-sighting-report", "request-qrf", "airspace-coordination", "weapons-status-coordination") }.ifEmpty { dialogues } else dialogues
            d.take(2).flatMap { dl -> Chunker.chunks(dl.lines.map { SourceLine(it.text, it.english, it.voice) }, module.code, direction, dl.id) }.take(10)
        }
    }
}

@Composable
private fun InterpretRun(app: AppGraph, module: LanguageModule, chunks: List<Chunk>, direction: InterpretDirection, variant: InterpretVariant, pauseSec: Int, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val effectiveDirection = if (variant == InterpretVariant.SIGHT) InterpretDirection.TO_ENGLISH else direction
    val session = remember { InterpretSession(module.code, effectiveDirection, variant, chunks, app.gateway, gradeAtEnd = true) }
    val conversationId = remember { app.conversations.newId() }
    var phase by remember { mutableStateOf("ready") } // ready | playing | pause | speak | grading | done
    var countdown by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<AudioIO.Recording?>(null) }
    val results = remember { mutableStateListOf<InterpretResult>() }
    val hasStt = remember { app.recognizer() != null }
    val chunk = session.current

    fun play(c: Chunk) = scope.launch {
        phase = "playing"
        withContext(Dispatchers.IO) {
            val spoken = app.speech.synthesize(c.source, c.sourceLang)
            if (spoken != null) {
                val wav = if (variant == InterpretVariant.RADIO_RELAY) AudioIO.wav(Degrade.apply(AudioIO.toPcm16kMono(spoken.wav), Degrade.Preset.RADIO, 0.45, seed = c.id.hashCode()))
                else spoken.wav
                runCatching { AudioIO.play(wav, app.settings.get(Settings.Key.OUTPUT_DEVICE)) }
            }
        }
        if (pauseSec > 0) {
            phase = "pause"
            countdown = pauseSec
            while (countdown > 0) { delay(1000); countdown-- }
        }
        phase = "speak"
    }

    fun submit(text: String) = scope.launch {
        session.render(text)
        typed = ""
        notes = ""
        if (session.finished) {
            phase = "grading"
            results.addAll(withContext(Dispatchers.Default) { session.finish() })
            app.conversations.save(conversationId, app.learnerId, module.code, "INTERPRET", "${variant.title} · ${effectiveDirection.title}",
                System.currentTimeMillis(), StoredConversation(results.flatMap { listOf(Turn(Speaker.PARTNER, it.chunk.source), Turn(Speaker.LEARNER, it.rendering)) },
                    interpret = results.toList()), null, emptyList(), "recordings/$conversationId")
            phase = "done"
        } else {
            phase = "ready"
        }
    }

    LaunchedEffect(variant) { if (variant == InterpretVariant.SIGHT && chunk != null && phase == "ready") { phase = "speak" } }

    Page("Interpret · ${variant.title}", "${module.nameEnglish} · ${effectiveDirection.title} · chunk ${minOf(results.size + (if (session.finished) 0 else chunks.indexOf(chunk) + 1), chunks.size)} of ${chunks.size}") {
        Badge("After action — feedback at the end", MaterialTheme.colorScheme.tertiaryContainer)
        if (phase == "done") {
            InterpretResults(app, module, results, session.meanScore(), conversationId)
            Button(onClick = close) { Text("Done") }
            return@Page
        }
        if (phase == "grading") {
            SectionCard { Text("Grading your renderings…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            return@Page
        }
        val c = chunk ?: return@Page
        SectionCard {
            when (phase) {
                "ready" -> Button(onClick = { play(c) }) { Text(if (variant == InterpretVariant.RADIO_RELAY) "▶ Receive transmission" else "▶ Play the chunk") }
                "playing" -> Text("Listen…")
                "pause" -> Text("Notes: $countdown s")
                else -> {}
            }
            if (variant == InterpretVariant.SIGHT && phase == "speak") {
                var left by remember(c.id) { mutableIntStateOf(45) }
                LaunchedEffect(c.id) { while (left > 0) { delay(1000); left-- } }
                if (left > 0) {
                    Text("Read ($left s):", style = MaterialTheme.typography.bodySmall)
                    Text(c.source, fontFamily = Fonts.forLanguage(module.code), style = MaterialTheme.typography.titleLarge)
                } else Text("Time's up — render it now.", fontWeight = FontWeight.SemiBold)
            }
            if (phase == "pause" || phase == "speak") OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 60.dp),
                label = { Text("Notes (not graded)") })
            if (phase == "speak") {
                val target = session.targetLang
                Text("Now say it in ${if (target == "en") "English" else module.nameEnglish}.", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasStt) Button(onClick = {
                        val r = recording
                        if (r == null) {
                            recording = runCatching { AudioIO.record(app.settings.get(Settings.Key.INPUT_DEVICE), onLevel = {}) }.getOrNull()
                        } else {
                            val pcm = r.stop()
                            recording = null
                            phase = "grading"
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    runCatching { app.recognizer()!!.transcribe(pcm, if (target == "en") "en" else module.sttLanguage).text }.getOrDefault("")
                                }
                                phase = "speak"
                                if (text.isNotBlank()) submit(text)
                            }
                        }
                    }) { Text(if (recording != null) "■ Stop" else "● Record") }
                    if (variant != InterpretVariant.SIGHT) TextButton(onClick = { play(c) }) { Text("Replay") }
                }
                OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("…or type your rendering") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (target == "en") null else Fonts.forLanguage(module.code)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = typed.isNotBlank(), onClick = { submit(typed) }) { Text("Next chunk") }
                    TextButton(onClick = { submit("") }) { Text("Skip") }
                }
            }
        }
    }
}

@Composable
fun InterpretResults(app: AppGraph, module: LanguageModule, results: List<InterpretResult>, mean: Int?, conversationId: String) {
    SectionCard("After Action Brief — interpretation") {
        Text(mean?.let { "Overall: $it / 100 (accuracy counts double)" } ?: "Not graded (no model answered).", style = MaterialTheme.typography.titleMedium)
        Disclaimer()
    }
    results.forEachIndexed { i, r ->
        SectionCard("Chunk ${i + 1}") {
            Text(r.chunk.source, fontFamily = if (r.chunk.sourceLang == "en") null else Fonts.forLanguage(module.code))
            Text("You: ${r.rendering.ifBlank { "(skipped)" }}", fontFamily = Fonts.forLanguage(module.code))
            r.grade?.let { g ->
                Text("Accuracy ${g.accuracy}/5 · completeness ${g.completeness}/5 · register ${g.register}/5", fontWeight = FontWeight.SemiBold)
                g.omissions.forEach { Text("Missed: $it", style = MaterialTheme.typography.bodySmall) }
                g.distortions.forEach { Text("Changed: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Better: ${g.betterVersion}", fontFamily = Fonts.forLanguage(module.code), color = MaterialTheme.colorScheme.primary)
                if (g.note.isNotBlank()) Text(g.note, style = MaterialTheme.typography.bodySmall)
                r.engine?.let { Badge("AI-graded · $it") }
            }
            if (r.chunk.reference.isNotBlank()) Text("Reference: ${r.chunk.reference}", style = MaterialTheme.typography.bodySmall,
                fontFamily = Fonts.forLanguage(module.code))
            HorizontalDivider()
        }
    }
}
