package app.tsumugi.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList

private val Indigo = Color(0xFF3F4A8A)
private val Vermilion = Color(0xFFC8553D)

private val Light = lightColorScheme(primary = Indigo, secondary = Vermilion, tertiary = Color(0xFF5B7F5B))
private val Dark = darkColorScheme(primary = Color(0xFFB8C0FF), secondary = Color(0xFFFFB4A1), tertiary = Color(0xFFA9D1A9))

@Composable
fun TsumugiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Japanese locale so Han-unified characters render with Japanese glyph shapes (e.g. 直, 骨, 誤). */
val JapaneseLocale = LocaleList("ja-JP")

fun TextStyle.japanese(): TextStyle = copy(localeList = JapaneseLocale)
