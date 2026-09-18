package app.tsumugi.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.content.Context
import android.provider.Settings
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

/**
 * Japanese learner text tagged with the ja-JP locale as a span. Compose turns the span into a `LocaleSpan` in the
 * accessibility tree, so TalkBack reads it with a Japanese voice even when the UI language is English. (A locale
 * set only on the TextStyle changes the glyph shapes but isn't passed to TalkBack.)
 */
fun ja(text: String): AnnotatedString = buildAnnotatedString { withStyle(SpanStyle(localeList = JapaneseLocale)) { append(text) } }

/** [ja] for text that already carries spans (underlines, highlights, diffs). */
fun ja(text: AnnotatedString): AnnotatedString = buildAnnotatedString { withStyle(SpanStyle(localeList = JapaneseLocale)) { append(text) } }

/** A [Text] for Japanese learner content: Japanese glyph shapes and Japanese speech for TalkBack. */
@Composable
fun JaText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(ja(text), modifier, color = color, fontSize = fontSize, textAlign = textAlign, maxLines = maxLines, style = style.japanese())
}

/** A "▶ label" button caption whose triangle is visual only, so TalkBack reads just the label. */
@Composable
fun PlayLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("▶ ", Modifier.clearAndSetSemantics {})
        Text(text)
    }
}

/** True when the user turned animations off (Settings → Accessibility → Remove animations sets the scale to 0). */
fun Context.animationsDisabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun rememberAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    return remember { context.animationsDisabled() }
}
