package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.mokuhyo.update.UpdateResult
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.DownloadCenter
import app.mokuhyo.lang.Direction
import app.mokuhyo.lang.Languages

enum class Destination(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    READING("Reading", Icons.AutoMirrored.Outlined.MenuBook),
    LISTENING("Listening", Icons.Outlined.Headphones),
    SPEAKING("Speaking", Icons.Outlined.Mic),
    REVIEW("Review", Icons.Outlined.Replay),
    HISTORY("History", Icons.Outlined.History),
    REPORT("Report", Icons.Outlined.Assessment),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** The main window content (BRIEF §9): left rail, title bar with the language switcher and download pill. */
@Composable
fun Shell(app: AppGraph, start: Destination = Destination.HOME, screens: Screens = Screens.default) {
    var destination by remember { mutableStateOf(start) }
    val lang by app.language.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                Destination.entries.forEach { d ->
                    NavigationRailItem(
                        selected = destination == d,
                        onClick = { destination = d },
                        icon = { Icon(d.icon, contentDescription = d.title) },
                        label = { Text(d.title) },
                    )
                }
            }
            Column(Modifier.fillMaxSize()) {
                TitleBar(app, lang)
                HorizontalDivider()
                // Target-language content flips for Arabic and Persian; the app chrome stays left-to-right.
                val rtl = Languages.of(lang)?.direction == Direction.RTL
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        screens.render(destination, app, rtl) { destination = it }
                    }
                }
            }
        }
    }
}

/** Indirection so later phases plug in real screens without touching the shell. */
fun interface Screens {
    @Composable
    fun render(destination: Destination, app: AppGraph, rtl: Boolean, navigate: (Destination) -> Unit)

    companion object {
        var default: Screens = Screens { d, app, _, navigate -> PlaceholderScreens(d, app, navigate) }
    }
}

/** Opens a release page in the system browser (no-op when the platform has no browser integration). */
internal fun openUrl(url: String) {
    runCatching {
        val d = java.awt.Desktop.getDesktop()
        if (java.awt.Desktop.isDesktopSupported() && d.isSupported(java.awt.Desktop.Action.BROWSE)) d.browse(java.net.URI(url))
    }
}

@Composable
private fun TitleBar(app: AppGraph, lang: String) {
    var open by remember { mutableStateOf(false) }
    val downloads by app.downloads.states.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mokuhyo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f))
        downloads.values.filterIsInstance<DownloadCenter.State.Running>().firstOrNull()?.let { DownloadPill(it) }
        val update by app.updates.state.collectAsState()
        (update as? UpdateResult.Available)?.let { u ->
            TextButton(onClick = { openUrl(u.release.url) }) { Text("Mokuhyo ${u.version} is available") }
        }
        Box {
            TextButton(onClick = { open = true }) {
                Icon(Icons.Outlined.Translate, contentDescription = null)
                Text("  " + (Languages.of(lang)?.nameEnglish ?: lang) + " · ")
                Text(Languages.of(lang)?.nameNative ?: "", fontFamily = Fonts.forLanguage(lang))
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                val chosen = app.chosenLanguages()
                (chosen.mapNotNull { Languages.of(it) } + Languages.all.filter { it.code !in chosen }).forEach { l ->
                    DropdownMenuItem(text = {
                        Row {
                            Text(l.nameEnglish + " · ")
                            Text(l.nameNative, fontFamily = Fonts.forLanguage(l.code))
                        }
                    }, onClick = {
                        app.setLanguage(l.code)
                        if (l.code !in chosen) app.setChosenLanguages(chosen + l.code)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun DownloadPill(state: DownloadCenter.State.Running) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.verifying) "Verifying ${state.model.name}…" else "Downloading ${state.model.name} ${(state.fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
            )
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.width(80.dp))
        }
    }
}
