package app.mokuhyo.desktop.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.mokuhyo.desktop.ui.CheckRow
import app.mokuhyo.desktop.ui.EmptyState
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.desktop.ui.SectionCard
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.numbers.NumberDrill
import app.mokuhyo.numbers.NumberItems
import app.mokuhyo.numbers.NumberKind
import app.mokuhyo.speech.AudioIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Listening → Numbers (BRIEF_PHASE8 N-02): times, dates, grids, bearings, call signs, tail and phone numbers,
 * frequencies, counts and the spelling alphabet, generated on this computer and spoken by the voice service. Type
 * what you hear; the drill gives more of the kinds you miss. No model and no network needed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NumbersDrillView(app: AppGraph, module: LanguageModule, presetKinds: List<NumberKind>? = null, limit: Int? = null, onDone: ((Int, Int) -> Unit)? = null) {
    val grammar = module.numbers
    if (grammar == null) {
        EmptyState("Numbers drill unavailable", "This platform has no number grammar for ${module.nameEnglish}.")
        return
    }
    val chosen = remember(module.code) { mutableStateListOf(*(presetKinds ?: NumberKind.entries).toTypedArray()) }
    var drill by remember(module.code, chosen.toList()) {
        mutableStateOf(NumberDrill(NumberItems(grammar, Random.Default), chosen.toList().ifEmpty { NumberKind.entries }, Random.Default))
    }
    var typed by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showText by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0) }
    var round by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val item = drill.current

    fun play() = scope.launch {
        withContext(Dispatchers.IO) { app.speech.synthesize(item.spoken, module.code, speed = speed)?.let { runCatching { AudioIO.play(it.wav) } } }
    }

    if (presetKinds == null) SectionCard("What to drill") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberKind.entries.forEach { k ->
                FilterChip(k in chosen, { if (k in chosen) { if (chosen.size > 1) chosen.remove(k) } else chosen.add(k) }, label = { Text(k.title) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed")
            listOf(0.8, 1.0, 1.2, 1.4).forEach { s -> FilterChip(speed == s, { speed = s }, label = { Text("${s}×") }) }
        }
    }
    if (limit != null && drill.answered >= limit) {
        onDone?.invoke(drill.right, drill.answered)
        SectionCard { Text("Done: ${drill.right} of ${drill.answered} right.") }
        return
    }
    SectionCard(item.kind.title) {
        Text(item.kind.instruction)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { play() }) { Text("▶ Play") }
            CheckRow(showText, { showText = it }) { Text("Show the text") }
        }
        if (showText) Text(item.spoken, fontFamily = Fonts.forLanguage(module.code))
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your answer") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = typed.isNotBlank(), onClick = {
                val answer = item.answer
                val written = item.written
                val ok = drill.answer(typed)
                last = ok to "$answer  ($written)"
                typed = ""
                round++
                if (ok) play()
            }) { Text("Check") }
            TextButton(onClick = { drill.answer(""); last = false to "${item.answer}  (${item.written})"; round++ }) { Text("Skip") }
        }
        last?.let { (ok, ans) ->
            Text(if (ok) "Right: $ans" else "The answer was $ans", fontWeight = FontWeight.SemiBold, fontFamily = Fonts.forLanguage(module.code),
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Text("${drill.right} of ${drill.answered} right" + drill.errors.entries.joinToString(", ", " · misses: ") { "${it.key.title} ${it.value}" }
            .takeIf { drill.errors.isNotEmpty() }.orEmpty(), style = MaterialTheme.typography.bodySmall)
        if (round > 0 && presetKinds == null) OutlinedButton(onClick = {
            drill = NumberDrill(NumberItems(grammar, Random.Default), chosen.toList(), Random.Default); last = null; round = 0
        }) { Text("Start over") }
    }
}
