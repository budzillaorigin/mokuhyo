package app.tsumugi.android.features.me

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.AppLanguage
import app.tsumugi.android.platform.AppLanguages
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/** In-app language (G-15): System, English or 日本語. Changing it recreates the screen in the new language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePicker() {
    val context = LocalContext.current
    val current = remember { AppLanguages.current(context) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { lang ->
                FilterChip(
                    selected = lang == current,
                    onClick = { if (lang != current) context.findActivity()?.let { AppLanguages.set(it, lang) } },
                    label = { Text(stringResource(lang.label)) },
                )
            }
        }
        Text(stringResource(R.string.settings_language_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** Developer options: the content-review screen (G-16) is hidden unless this device turns it on. */
@Composable
fun DeveloperOptions() {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = runCatching { graph.deviceSettings.bool(DEV_CONTENT_REVIEW, false) }.getOrDefault(false) }
    Text(stringResource(R.string.settings_developer), style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch) { v ->
            on = v
            scope.launch { graph.deviceSettings.put(DEV_CONTENT_REVIEW, v.toString()) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_content_review))
            Text(stringResource(R.string.settings_content_review_hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, onCheckedChange = null)
    }
}

/**
 * The optional Immersion Kit example source (BRIEF_V2 §6.2, D-162): off by default on every device, with the terms
 * note. Nothing is requested while it's off; results are shown live and never stored.
 */
@Composable
fun ImmersionKitToggle() {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = runCatching { graph.onlineExamples.enabled() }.getOrDefault(false) }
    Text(stringResource(R.string.settings_examples), style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch) { v ->
            on = v
            scope.launch { runCatching { graph.onlineExamples.setEnabled(v) } }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_immersion_kit, graph.onlineExamples.sourceName))
            Text(stringResource(R.string.settings_immersion_kit_terms), style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, onCheckedChange = null)
    }
}
