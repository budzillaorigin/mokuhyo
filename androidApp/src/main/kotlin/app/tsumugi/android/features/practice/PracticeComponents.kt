package app.tsumugi.android.features.practice

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.SpeechInput
import app.tsumugi.android.platform.SpeechResult
import app.tsumugi.android.platform.rememberMicPermission
import app.tsumugi.android.ui.japanese
import app.tsumugi.api.AppGraph
import kotlinx.coroutines.launch

/** A ViewModel per screen instance ([key] includes the route's nonce), created with the Application. */
@Composable
inline fun <reified VM : ViewModel> keyedViewModel(key: String, crossinline create: (Application) -> VM): VM {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(key = key) { create(app) }
}

@Composable
fun rememberGraph(): AppGraph = (LocalContext.current.applicationContext as TsumugiApplication).graph

/** CLAUDE.md rule 10: every model-written sentence, item or explanation carries this badge. */
@Composable
fun AiBadge(engine: String? = null, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            if (engine.isNullOrBlank()) "AI-generated" else "AI-generated · $engine",
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** An explanatory card with an optional action (e.g. "Open AI settings"). */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

const val PRACTICE_PACK_MISSING =
    "The speaking & listening practice pack isn't installed in this build. Build it with `uv run packs/build_practice.py` " +
        "in tools/ (see docs/CONTENT_PACKS.md), then rebuild the app."

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** JLPT level filter chips (null = all levels). */
@Composable
fun JlptFilter(selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected == null, { onSelect(null) }, { Text("All") })
        (5 downTo 1).forEach { level -> FilterChip(selected == level, { onSelect(level) }, { Text("N$level") }) }
    }
}

/**
 * Hold-to-talk microphone button. Pressing starts [input]; releasing stops it and hands the result to [onResult].
 * Screen readers get a single "tap to start / tap to stop" action instead of the hold gesture.
 */
@Composable
fun MicButton(input: SpeechInput, onResult: (SpeechResult) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onError: (String) -> Unit = {}) {
    val permission = rememberMicPermission()
    val scope = rememberCoroutineScope()
    val state by input.state.collectAsState()
    val listening = state == SpeechInput.State.Listening
    val busy = state == SpeechInput.State.Transcribing
    fun begin() = scope.launch { input.start()?.let(onError) }
    fun end() = scope.launch { if (input.state.value == SpeechInput.State.Listening) onResult(input.stop()) }
    val color = when {
        !enabled || busy -> MaterialTheme.colorScheme.surfaceVariant
        listening -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color)
                .semantics {
                    role = Role.Button
                    contentDescription = if (listening) "Stop recording" else "Record your answer"
                    onClick {
                        if (!permission.granted) permission.request() else if (listening) end() else begin()
                        true
                    }
                }
                .pointerInput(enabled, permission.granted) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        if (!permission.granted) {
                            permission.request()
                            return@detectTapGestures
                        }
                        val starting = begin()
                        tryAwaitRelease()
                        starting.join() // a quick tap must not leave the microphone running
                        end()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (listening) "●" else "🎤", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            when {
                !permission.granted -> "Tap to allow the microphone"
                busy -> "Listening back…"
                listening -> "Release to finish"
                else -> "Hold to speak"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Mic plus a typed fallback. [onSubmit] gets the text and the recording (null when typed). */
@Composable
fun SpeakOrType(
    input: SpeechInput,
    enabled: Boolean,
    onSubmit: (String, SpeechResult?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Or type in Japanese",
) {
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MicButton(
            input,
            enabled = enabled,
            onError = { error = it },
            onResult = { r ->
                error = r.error
                if (r.transcript.isNotBlank()) onSubmit(r.transcript, r)
            },
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                typed, { typed = it }, Modifier.weight(1f),
                placeholder = { Text(placeholder) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.japanese(),
            )
            Button(
                onClick = { onSubmit(typed.trim(), null); typed = "" },
                enabled = enabled && typed.isNotBlank(),
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Send") }
        }
    }
}

fun percent(ratio: Double): String = "${(ratio * 100).toInt()}%"
