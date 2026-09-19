package app.tsumugi.android.features.practice

import app.tsumugi.audio.AudioKeys
import app.tsumugi.audio.AudioSet
import app.tsumugi.audio.PairSide as AudioPairSide

import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.JaText
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.japanese
import app.tsumugi.practice.MinimalPair
import app.tsumugi.practice.MinimalPairCategory
import app.tsumugi.practice.PairWord
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
private fun MinimalPairCategory.label() = stringResource(
    when (this) {
        MinimalPairCategory.LENGTH -> R.string.pairs_length
        MinimalPairCategory.GEMINATION -> R.string.pairs_gemination
        MinimalPairCategory.VOICING -> R.string.pairs_voicing
        MinimalPairCategory.NASAL -> R.string.pairs_nasal
        MinimalPairCategory.PITCH -> R.string.pron_pitch
    },
)

/** Ear training (BRIEF §5.9): hear one word of a pair, pick A or B. Pairs come from JMdict + Kanjium, no LLM. */
@Composable
fun MinimalPairsScreen() {
    val graph = rememberGraph()
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(MinimalPairCategory.LENGTH) }
    var pairs by remember { mutableStateOf<List<MinimalPair>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var playA by remember { mutableStateOf(true) }
    var answer by remember { mutableStateOf<Boolean?>(null) } // picked A?
    var right by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(category) {
        val practice = graph.practice()
        missing = practice == null
        pairs = practice?.minimalPairs(category, 50)?.shuffled()
        index = 0; right = 0; total = 0; answer = null
        playA = Random.nextBoolean()
    }
    val pair = pairs?.getOrNull(index)
    val target: PairWord? = pair?.let { if (playA) it.a else it.b }
    fun play() {
        // Pitch pairs share their kana, so speak the written form to get the right accent from the voice.
        // Rule 20: the pre-rendered pair clip (accent set explicitly, D-093) when installed, else TTS.
        val p = pair
        target?.let { w -> scope.launch { voices.sayClip(p?.let { AudioKeys.minimalPair(it.id, if (playA) AudioPairSide.A else AudioPairSide.B) }, if (category == MinimalPairCategory.PITCH) w.text else w.reading) } }
    }
    LaunchedEffect(pair, playA) { if (pair != null) play() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MinimalPairCategory.entries.forEach { c -> FilterChip(c == category, { category = c }, { Text(c.label()) }) }
        }
        if (total > 0) Text(stringResource(R.string.pairs_accuracy, right, total, percent(right.toDouble() / total)), style = MaterialTheme.typography.titleMedium)
        when {
            missing -> PracticePackMissing()
            pairs == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            pairs!!.isEmpty() -> Text(stringResource(R.string.pairs_none))
            pair == null -> {
                Text(stringResource(R.string.pairs_round_done, right, total), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { pairs = pairs!!.shuffled(); index = 0; right = 0; total = 0 }) { Text(stringResource(R.string.action_again)) }
            }
            else -> {
                Text(stringResource(R.string.pairs_question), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = ::play) { PlayLabel(stringResource(R.string.play_again)) }
                listOf(true to pair.a, false to pair.b).forEach { (isA, word) ->
                    val colors = when {
                        answer == null -> ButtonDefaults.buttonColors()
                        isA == playA -> ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        isA == answer -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            if (answer == null) {
                                answer = isA
                                total++
                                if (isA == playA) right++
                            }
                        },
                        colors = colors,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Column {
                            JaText("${if (isA) "A" else "B"}  ${word.text}（${word.reading}）", style = MaterialTheme.typography.titleMedium)
                            if (answer != null) {
                                Text(
                                    word.gloss + (word.accent?.let { stringResource(R.string.pairs_accent, it.toString()) } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (answer != null) {
                    Button(onClick = { index++; answer = null; playA = Random.nextBoolean() }) { Text(stringResource(R.string.action_next)) }
                }
            }
        }
        PairAudioNote()
    }
}

/** Where the pair audio comes from: the bundled pre-rendered pack (D-097), or TTS with its caveat. */
@Composable
fun PairAudioNote() {
    val graph = rememberGraph()
    var packed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { packed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.audio.isInstalled(AudioSet.MINIMAL_PAIRS) } }
    when (packed) {
        true -> Text(stringResource(R.string.pairs_pack_note), style = MaterialTheme.typography.bodySmall)
        false -> Text(stringResource(R.string.pairs_tts_note), style = MaterialTheme.typography.bodySmall)
        null -> Unit
    }
}
