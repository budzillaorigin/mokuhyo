package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.ai.HardwareInfo
import app.mokuhyo.ai.Tier
import app.mokuhyo.ai.TierAdvisor
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.lang.Languages
import app.mokuhyo.settings.Settings

private enum class Step(val title: String) {
    WELCOME("Welcome"), LANGUAGES("Languages"), HARDWARE("This computer"), TIER("AI model"), AUDIO("Microphone and speakers")
}

/** First run (BRIEF §9): welcome → languages → hardware scan → tier (skippable) → audio check → Home. */
@Composable
fun FirstRun(app: AppGraph, onDone: () -> Unit) {
    var step by remember { mutableStateOf(Step.WELCOME) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(app.chosenLanguages()) } }
    var tier by remember { mutableStateOf(app.tier) }
    val hw by app.hardware.collectAsState()
    LaunchedEffect(Unit) { app.probeHardware() }

    Page("Set up Mokuhyo", "Step ${step.ordinal + 1} of ${Step.entries.size} — ${step.title}") {
        when (step) {
            Step.WELCOME -> SectionCard {
                Text("Practice reading, listening and speaking the way proficiency tests work: DLPT-style passages with English questions, and an interview simulator.", style = MaterialTheme.typography.bodyLarge)
                Text("Everything runs on this computer. After one AI model download, no internet connection is needed, and nothing you do leaves this machine unless you export it.")
                Disclaimer()
            }
            Step.LANGUAGES -> LanguagePicker(chosen)
            Step.HARDWARE -> HardwareSummary(hw)
            Step.TIER -> {
                val info = hw
                if (info == null) Text("Still checking this computer…") else {
                    TierPicker(app, info, tier) { tier = it }
                    Spacer(Modifier.height(8.dp))
                    Text("You can change this later in Settings → AI. The download continues in the background.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Step.AUDIO -> AudioCheck(app)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != Step.WELCOME) OutlinedButton(onClick = { step = Step.entries[step.ordinal - 1] }) { Text("Back") }
            if (step == Step.TIER) {
                TextButton(onClick = {
                    app.settings.put(Settings.Key.READING_ONLY, "true")
                    step = Step.AUDIO
                }) { Text("Reading/Listening only for now") }
            }
            Spacer(Modifier.weight(1f))
            val canContinue = when (step) {
                Step.LANGUAGES -> chosen.isNotEmpty()
                Step.HARDWARE -> hw != null
                Step.TIER -> tier != null
                else -> true
            }
            Button(enabled = canContinue, onClick = {
                when (step) {
                    Step.LANGUAGES -> app.setChosenLanguages(chosen.toList())
                    Step.TIER -> tier?.let { t ->
                        app.chooseTier(t)
                        app.settings.remove(Settings.Key.READING_ONLY)
                        app.chosenModel()?.let { m -> if (app.modelFile(m) == null) app.downloads.start(m) }
                    }
                    else -> Unit
                }
                if (step == Step.AUDIO) {
                    app.settings.put(Settings.Key.FIRST_RUN_DONE, "true")
                    onDone()
                } else {
                    if (step == Step.HARDWARE && tier == null) tier = hw?.let { TierAdvisor.recommended(it, app.manifest.models) }
                    step = Step.entries[step.ordinal + 1]
                }
            }) { Text(if (step == Step.AUDIO) "Finish" else "Continue") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePicker(chosen: MutableList<String>) {
    SectionCard("Which languages are you preparing?") {
        Text("Pick one or more. Your data is kept separately for each; switch any time from the title bar.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Languages.all.forEach { l ->
                FilterChip(
                    selected = l.code in chosen,
                    onClick = { if (l.code in chosen) chosen.remove(l.code) else chosen.add(l.code) },
                    label = { Text("${l.nameEnglish} · ${l.nameNative}") },
                )
            }
        }
    }
}

@Composable
fun HardwareSummary(hw: HardwareInfo?) {
    SectionCard("This computer") {
        if (hw == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Text("Checking memory, graphics and disk space…")
            }
        } else {
            Text("Memory: ${gb(hw.ramBytes)} · CPU cores: ${hw.cpuCores} · Free disk: ${hw.freeDiskBytes?.let { gb(it) } ?: "unknown"}")
            if (hw.gpus.isEmpty()) Text("Graphics: no GPU the AI engine can use; it will run on the CPU.")
            hw.gpus.forEach { g ->
                Text("Graphics: ${g.name} (${g.backend}) · " + if (g.unifiedMemory) "shares system memory" else "${gb(g.memoryBytes)} video memory")
            }
        }
    }
}

/** Tier choice in Settings. */
@Composable
fun TierSettings(app: AppGraph) {
    val hw by app.hardware.collectAsState()
    var tier by remember { mutableStateOf(app.tier) }
    LaunchedEffect(Unit) { app.probeHardware() }
    val info = hw
    if (info == null) Text("Checking this computer…") else TierPicker(app, info, tier) { t: Tier ->
        tier = t
        app.chooseTier(t)
    }
}
