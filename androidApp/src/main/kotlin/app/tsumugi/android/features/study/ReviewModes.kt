package app.tsumugi.android.features.study

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.AudioFilePlayer
import app.tsumugi.android.platform.Voices
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.rememberBitmap
import app.tsumugi.cards.PersonalCardRender
import app.tsumugi.grammar.ProductionGrade
import app.tsumugi.srs.StudyItem
import app.tsumugi.study.MinimalPairPrompt
import kotlinx.coroutines.launch

/*
 * The grammar review variety (G-05 / D-103), minimal-pair cards (G-06 / D-105) and personal picture/audio cards
 * (G-12 / D-112) in the review session. Grading lives in the shared ReviewSession; these are the answer controls.
 */

/** MEANING_CHOICE: four meanings; submits the chosen index. */
@Composable
fun MeaningChoiceAnswer(choices: List<String>, enabled: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.review_choose_meaning), style = MaterialTheme.typography.bodyMedium)
        choices.forEachIndexed { i, choice ->
            OutlinedButton(onClick = { onPick(i) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(choice, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** PRODUCTION: write the sentence in Japanese. Grading may take a few seconds with an on-device model. */
@Composable
fun ProductionAnswer(resetKey: Any, grading: Boolean, onSubmit: (String) -> Unit) {
    var text by remember(resetKey) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(),
            enabled = !grading,
            label = { Text(stringResource(R.string.production_label)) },
            placeholder = { Text(stringResource(R.string.production_placeholder)) },
            minLines = 2,
            textStyle = MaterialTheme.typography.titleLarge.japanese(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Button(onClick = { onSubmit(text.trim()) }, enabled = !grading && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_check))
        }
        if (grading) {
            Text(stringResource(R.string.production_grading), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/**
 * The production grade: the model answer (always), the rule checks, and the model's rubric and feedback with the
 * AI badge (rule 10). Without a model the learner grades themselves against the model answer.
 */
@Composable
fun ProductionDetails(p: ProductionGrade) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.production_model_answer), style = MaterialTheme.typography.titleSmall)
            JaText(p.modelAnswer, style = MaterialTheme.typography.titleLarge)
            val checks = listOfNotNull(
                stringResource(if (p.checks.japanese) R.string.production_check_japanese else R.string.production_check_no_japanese),
                p.checks.constructionFound?.let { stringResource(if (it) R.string.production_check_construction else R.string.production_check_no_construction) },
                p.checks.formConsistent?.let { stringResource(if (it) R.string.production_check_form else R.string.production_check_no_form) },
            )
            checks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            val rubric = p.rubric
            if (rubric != null) {
                Text(stringResource(R.string.production_rubric, rubric.meaning, rubric.grammar, rubric.form), style = MaterialTheme.typography.bodyMedium)
                if (rubric.corrected.isNotBlank()) {
                    Text(stringResource(R.string.production_corrected), style = MaterialTheme.typography.titleSmall)
                    JaText(rubric.corrected, style = MaterialTheme.typography.bodyLarge)
                }
                if (rubric.feedback.isNotBlank()) Text(rubric.feedback, style = MaterialTheme.typography.bodyMedium)
                AiBadge(p.engine)
            } else {
                p.unavailable?.let { Text(stringResource(R.string.production_unavailable, it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** What to say for a minimal pair: pitch pairs share their kana, so the written form gives the voice the accent. */
private fun spoken(prompt: MinimalPairPrompt, a: Boolean): String {
    val side = if (a) prompt.pair.a else prompt.pair.b
    return if (prompt.pair.a.reading == prompt.pair.b.reading) side.text else side.reading
}

/** MINIMAL_PAIR: hear one word (TTS until the audio pack, rule 20), pick A or B. */
@Composable
fun MinimalPairAnswer(prompt: MinimalPairPrompt, voices: Voices, resetKey: Any, enabled: Boolean, onSubmit: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    fun play() = scope.launch { voices.say(spoken(prompt, prompt.playA)) }
    LaunchedEffect(resetKey) { voices.say(spoken(prompt, prompt.playA)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pairs_question), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { play() }) { PlayLabel(stringResource(R.string.play_again)) }
        listOf("a" to prompt.pair.a, "b" to prompt.pair.b).forEach { (key, side) ->
            Button(onClick = { onSubmit(key) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("${key.uppercase()} · ${side.text}（${side.reading}）", style = MaterialTheme.typography.titleMedium.japanese())
            }
        }
        Text(stringResource(R.string.pairs_tts_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** After answering: both words with glosses, which one played, and replay for each. */
@Composable
fun MinimalPairReveal(prompt: MinimalPairPrompt, voices: Voices) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(true to prompt.pair.a, false to prompt.pair.b).forEach { (isA, side) ->
            val played = isA == prompt.playA
            OutlinedButton(onClick = { scope.launch { voices.say(spoken(prompt, isA)) } }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${if (isA) "A" else "B"} · ${side.text}（${side.reading}）${if (side.gloss.isNotBlank()) " — ${side.gloss}" else ""}" +
                        if (played) "  ${stringResource(R.string.pairs_played)}" else "",
                    style = MaterialTheme.typography.bodyLarge.japanese(),
                )
            }
        }
    }
}

/** A personal (Fluent Forever) card: a CUSTOM item whose context says so (D-112). */
@Composable
fun isPersonal(item: StudyItem): Boolean {
    val graph = rememberGraph()
    return remember(item.id) { graph.personalCards.contextOf(item) != null }
}

/** One face of a personal card: the picture (front), the word and note, and the learner's own recording. */
@Composable
fun PersonalCardFace(cardId: String, front: Boolean, voices: Voices) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var render by remember(cardId) { mutableStateOf<PersonalCardRender?>(null) }
    LaunchedEffect(cardId) { render = runCatching { graph.personalCards.render(cardId) }.getOrNull() }
    val r = render ?: return LinearProgressIndicator(Modifier.fillMaxWidth())
    val face = if (front) r.front else r.back
    val bitmap = rememberBitmap(face.imagePath)
    fun playAudio() = scope.launch {
        val path = face.audioPath
        if (path != null) AudioFilePlayer.play(path) else face.speakFallback?.let { voices.say(it) }
    }
    LaunchedEffect(cardId, front) { if (front && face.imagePath == null && face.text == null) playAudio().join() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bitmap?.let {
            Image(
                it.asImageBitmap(), contentDescription = stringResource(R.string.personal_picture_description),
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit,
            )
        }
        face.text?.let { JaText(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center) }
        face.reading?.let { JaText(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
        face.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (face.audioPath != null || face.speakFallback != null) {
            OutlinedButton(onClick = { playAudio() }) {
                PlayLabel(stringResource(if (face.audioPath != null) R.string.personal_play_mine else R.string.personal_play_tts))
            }
        }
        if (r.missing.isNotEmpty()) {
            Text(stringResource(R.string.personal_missing, r.missing.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
