package app.mokuhyo.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC2EA), onPrimary = Color(0xFF0E1A33), secondary = Color(0xFFFF8A78), onSecondary = Color(0xFF3B0A03),
    background = Color(0xFF111522), surface = Color(0xFF111522), surfaceVariant = Color(0xFF232A3B),
    primaryContainer = Color(0xFF2A3A60), onPrimaryContainer = Color(0xFFDCE3F2),
    secondaryContainer = Color(0xFF6B2418), onSecondaryContainer = Color(0xFFFFDAD3),
)

/** Always dark (D-027): the light scheme was uncomfortably bright on Windows, where the app followed the OS. */
@Composable
fun MokuhyoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Typography(), content = content)
}
