package app.mokuhyo.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1B2A4A)
private val Vermilion = Color(0xFFD64533)
private val Cream = Color(0xFFF4EFE4)

private val Light = lightColorScheme(
    primary = Navy, onPrimary = Color.White, secondary = Vermilion, onSecondary = Color.White,
    background = Color(0xFFFBF9F5), surface = Color(0xFFFBF9F5), surfaceVariant = Cream,
    primaryContainer = Color(0xFFDCE3F2), onPrimaryContainer = Navy,
    secondaryContainer = Color(0xFFF8DDD8), onSecondaryContainer = Color(0xFF5C1A10),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC2EA), onPrimary = Color(0xFF0E1A33), secondary = Color(0xFFFF8A78), onSecondary = Color(0xFF3B0A03),
    background = Color(0xFF111522), surface = Color(0xFF111522), surfaceVariant = Color(0xFF232A3B),
    primaryContainer = Color(0xFF2A3A60), onPrimaryContainer = Color(0xFFDCE3F2),
    secondaryContainer = Color(0xFF6B2418), onSecondaryContainer = Color(0xFFFFDAD3),
)

/** [dark] null = follow the OS. */
@Composable
fun MokuhyoTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark ?: isSystemInDarkTheme()) Dark else Light, typography = Typography(), content = content)
}
