package app.tsumugi.android.features.practice

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

private fun MinimalPairCategory.label() = when (this) {
    MinimalPairCategory.LENGTH -> "Vowel length"
    MinimalPairCategory.GEMINATION -> "Small っ"
    MinimalPairCategory.VOICING -> "Voicing"
    MinimalPairCategory.NASAL -> "ん"
    MinimalPairCategory.PITCH -> "Pitch accent"
}

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
        target?.let { w -> scope.launch { voices.say(if (category == MinimalPairCategory.PITCH) w.text else w.reading) } }
    }
    LaunchedEffect(pair, playA) { if (pair != null) play() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MinimalPairCategory.entries.forEach { c -> FilterChip(c == category, { category = c }, { Text(c.label()) }) }
        }
        if (total > 0) Text("Accuracy: $right / $total (${percent(right.toDouble() / total)})", style = MaterialTheme.typography.titleMedium)
        when {
            missing -> Notice(PRACTICE_PACK_MISSING)
            pairs == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            pairs!!.isEmpty() -> Text("No pairs in this category in the installed pack.")
            pair == null -> {
                Text("Round done: $right of $total.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { pairs = pairs!!.shuffled(); index = 0; right = 0; total = 0 }) { Text("Again") }
            }
            else -> {
                Text("Which word do you hear?", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = ::play) { Text("▶ Play again") }
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
                            Text("${if (isA) "A" else "B"}  ${word.text}（${word.reading}）", style = MaterialTheme.typography.titleMedium.japanese())
                            if (answer != null) {
                                Text(
                                    word.gloss + (word.accent?.let { " · accent [$it]" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (answer != null) {
                    Button(onClick = { index++; answer = null; playA = Random.nextBoolean() }) { Text("Next") }
                }
            }
        }
        Text(
            "Audio is the device's text-to-speech voice (or your VOICEVOX server), so fine distinctions such as pitch may be " +
                "approximate.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
