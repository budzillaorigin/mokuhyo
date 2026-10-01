package app.mokuhyo.desktop.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.lang.DictEntry
import app.mokuhyo.lang.Direction
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.lang.ReadingAids
import app.mokuhyo.lang.Token
import app.mokuhyo.srs.ReviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reading aids the learner toggled (per session). */
data class AidState(val ruby: Boolean = false, val romanization: Boolean = false, val vowelMarks: Boolean = true)

/**
 * Target-language text with tap-to-define (BRIEF §2 Reading practice): clicking a word shows its dictionary
 * entries (lemma first) and "Add to review". Right-to-left for Arabic and Persian; bundled fonts for every script;
 * optional furigana/pinyin above words, romanization below, Arabic short vowels on/off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PassageText(app: AppGraph, module: LanguageModule, text: String, aids: AidState, tapToDefine: Boolean, context: String = text) {
    val shown = if (!aids.vowelMarks) module.readingAids.stripVowelMarks(text) else text
    var tokens by remember(shown) { mutableStateOf<List<Token>>(emptyList()) }
    LaunchedEffect(shown) { tokens = withContext(Dispatchers.Default) { module.segment(shown) } }
    var selected by remember(shown) { mutableStateOf<Token?>(null) }
    val font = Fonts.forLanguage(module.code)
    val rtl = module.script.direction == Direction.RTL
    val style = TextStyle(fontFamily = font, fontSize = 20.sp, lineHeight = 34.sp, textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
        color = MaterialTheme.colorScheme.onSurface)
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (aids.ruby && ReadingAids.Aid.RUBY in module.readingAids.available) {
                // Ruby layout: each token a small reading over the word (CJK has no spaces, so token wrapping reads naturally).
                FlowRow {
                    tokens.forEach { t ->
                        Column(Modifier.padding(horizontal = 1.dp).pointerInput(t) { detectTapGestures { if (tapToDefine && t.isWord) selected = t } }) {
                            Text(module.readingAids.ruby(t) ?: " ", fontSize = 11.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(t.text, style = style.copy(background = if (selected == t) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Unspecified))
                        }
                    }
                }
            } else {
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val annotated = buildAnnotatedString {
                    var last = 0
                    tokens.forEach { t ->
                        if (t.start > last) append(shown.substring(last, t.start))
                        if (t == selected) withStyle(SpanStyle(background = MaterialTheme.colorScheme.secondaryContainer)) { append(t.text) } else append(t.text)
                        last = t.end
                    }
                    if (last < shown.length) append(shown.substring(last))
                }
                BasicText(
                    if (tokens.isEmpty()) buildAnnotatedString { append(shown) } else annotated,
                    style = style,
                    onTextLayout = { layout = it },
                    modifier = Modifier.pointerInput(tokens, tapToDefine) {
                        detectTapGestures { pos ->
                            if (!tapToDefine) return@detectTapGestures
                            val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                            selected = tokens.firstOrNull { it.isWord && offset >= it.start && offset < it.end }
                        }
                    },
                )
            }
            if (aids.romanization) module.readingAids.romanize(shown)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    selected?.let { token ->
        Popup(onDismissRequest = { selected = null }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
            DefinitionCard(app, module, token, context) { selected = null }
        }
    }
}

@Composable
private fun DefinitionCard(app: AppGraph, module: LanguageModule, token: Token, context: String, close: () -> Unit) {
    var entries by remember(token) { mutableStateOf<List<DictEntry>?>(null) }
    var added by remember(token) { mutableStateOf(false) }
    LaunchedEffect(token) {
        entries = withContext(Dispatchers.IO) {
            val dict = module.dictionary ?: return@withContext emptyList()
            module.lemma(token).flatMap { dict.lookup(it, 3) }.distinctBy { it.id }.take(4)
        }
    }
    Surface(shadowElevation = 8.dp, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = 420.dp).padding(8.dp)) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(token.text, fontFamily = Fonts.forLanguage(module.code), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                token.reading?.takeIf { it != token.text }?.let { Text(it, fontFamily = Fonts.forLanguage(module.code), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            when (val list = entries) {
                null -> Text("Looking up…")
                else -> if (module.dictionary == null) {
                    Text("No dictionary pack is installed for ${module.nameEnglish}.", style = MaterialTheme.typography.bodySmall)
                } else if (list.isEmpty()) {
                    Text("Not in the dictionary.", style = MaterialTheme.typography.bodySmall)
                } else list.forEach { e ->
                    Column {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = Fonts.forLanguage(module.code), fontWeight = FontWeight.SemiBold)) { append(e.headword) }
                            e.reading?.let { append("  "); append(it) }
                            if (e.pos.isNotBlank()) { append("  · "); append(e.pos) }
                        })
                        Text(e.shortGloss, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !added, onClick = {
                    val e = entries?.firstOrNull()
                    app.reviews.add(app.learnerId, module.code, ReviewService.Kind.WORD, e?.let { "dict:${it.id}" } ?: "word:${token.text}",
                        e?.headword ?: token.text, e?.shortGloss ?: "(look up later)", context.take(240))
                    added = true
                }) { Text(if (added) "Added to review" else "Add to review") }
                TextButton(onClick = close) { Text("Close") }
            }
        }
    }
}

/** Toggle chips for the language's reading aids. */
@Composable
fun AidToggles(module: LanguageModule, aids: AidState, onChange: (AidState) -> Unit) {
    val available = module.readingAids.available
    if (available.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ReadingAids.Aid.RUBY in available) FilterChip(aids.ruby, { onChange(aids.copy(ruby = !aids.ruby)) },
            label = { Text(if (module.code == "ja") "Furigana" else "Pinyin") })
        if (ReadingAids.Aid.ROMANIZATION in available) FilterChip(aids.romanization, { onChange(aids.copy(romanization = !aids.romanization)) },
            label = { Text("Romanization") })
        if (ReadingAids.Aid.VOWEL_MARKS in available) FilterChip(aids.vowelMarks, { onChange(aids.copy(vowelMarks = !aids.vowelMarks)) },
            label = { Text("Short vowels") })
    }
}
