package app.mokuhyo.desktop.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.exam.Formats
import app.mokuhyo.lang.Direction
import app.mokuhyo.lang.LanguageModule

/**
 * Renders an authentic-format passage (BRIEF_PHASE8 N-08). Right-to-left languages lay the whole layout out RTL.
 * Returns false when the passage has no (valid) format, so the caller shows the plain text instead.
 */
@Composable
fun FormattedPassage(module: LanguageModule, format: String?, data: kotlinx.serialization.json.JsonElement?): Boolean {
    val p = Formats.parse(format, data) ?: return false
    val dir = if (module.script.direction == Direction.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    val font = Fonts.forLanguage(module.code)
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        when (p) {
            is Formats.SignageP -> Signage(p.v, font)
            is Formats.BadgeFormP -> BadgeForm(p.v, font)
            is Formats.ChatP -> ChatThread(p.v, font)
            is Formats.ShiftLogP -> ShiftLog(p.v, font)
            is Formats.NoticeP -> Notice(p.v, font)
            is Formats.BoardP -> Board(p.v, font)
        }
    }
    return true
}

@Composable
private fun Signage(s: Formats.Signage, font: FontFamily) {
    val (bg, fg) = when (s.kind) {
        "warning" -> Color(0xFFF4C430) to Color.Black
        "prohibition" -> Color(0xFFB71C1C) to Color.White
        "mandatory" -> Color(0xFF0D47A1) to Color.White
        else -> Color(0xFF1B5E20) to Color.White
    }
    Box(Modifier.widthIn(max = 520.dp).background(bg, RoundedCornerShape(6.dp)).border(4.dp, fg, RoundedCornerShape(6.dp)).padding(20.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.lines.forEachIndexed { i, l ->
                Text(l, color = fg, fontFamily = font, fontWeight = if (i == 0) FontWeight.Black else FontWeight.SemiBold,
                    fontSize = if (i == 0) 28.sp else 18.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BadgeForm(f: Formats.BadgeForm, font: FontFamily) {
    Column(Modifier.widthIn(max = 560.dp).background(Color(0xFFFAFAF5)).border(1.dp, Color.DarkGray).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(f.title, color = Color.Black, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        HorizontalDivider(color = Color.DarkGray)
        f.fields.forEach { fld ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fld.label, color = Color.DarkGray, fontFamily = font, modifier = Modifier.weight(0.4f), fontSize = 13.sp)
                Text(fld.value.ifBlank { "________________" }, color = Color(0xFF0D2A6B), fontFamily = font, modifier = Modifier.weight(0.6f))
            }
        }
        if (f.footer.isNotBlank()) Text(f.footer, color = Color.DarkGray, fontFamily = font, fontSize = 12.sp)
    }
}

@Composable
private fun ChatThread(c: Formats.Chat, font: FontFamily) {
    val accent = when (c.app) { "line" -> Color(0xFF06C755); "whatsapp" -> Color(0xFF25D366); else -> Color(0xFF2196F3) }
    Column(Modifier.widthIn(max = 420.dp).background(Color(0xFFECE5DD), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (c.title.isNotBlank()) Text(c.title, color = Color.Black, fontFamily = font, fontWeight = FontWeight.SemiBold)
        c.messages.forEach { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.me) Arrangement.End else Arrangement.Start) {
                Column(Modifier.widthIn(max = 300.dp).background(if (m.me) accent.copy(alpha = 0.35f) else Color.White, RoundedCornerShape(10.dp)).padding(8.dp)) {
                    if (!m.me) Text(m.from, color = accent, fontFamily = font, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(m.text, color = Color.Black, fontFamily = font)
                    if (m.time.isNotBlank()) Text(m.time, color = Color.Gray, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ShiftLog(l: Formats.ShiftLog, font: FontFamily) {
    Column(Modifier.widthIn(max = 640.dp).background(Color(0xFFFFFDE7)).border(1.dp, Color(0xFF9E9D24)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${l.unit} · ${l.date}", color = Color.Black, fontFamily = font, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = Color(0xFF9E9D24))
        l.entries.forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e.time, color = Color(0xFF5D4037), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(e.entry, color = Color.Black, fontFamily = font, modifier = Modifier.weight(1f))
                if (e.initials.isNotBlank()) Text(e.initials, color = Color.DarkGray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Notice(n: Formats.Notice, font: FontFamily) {
    Column(Modifier.widthIn(max = 640.dp).background(Color.White).border(2.dp, Color(0xFF37474F)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(n.issuer, color = Color(0xFF37474F), fontFamily = font, fontSize = 13.sp)
        Text(n.title, color = Color.Black, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        n.paragraphs.forEach { Text(it, color = Color.Black, fontFamily = font) }
        if (n.date.isNotBlank() || n.contact.isNotBlank()) Text(listOf(n.date, n.contact).filter { it.isNotBlank() }.joinToString(" · "),
            color = Color.DarkGray, fontFamily = font, fontSize = 12.sp)
    }
}

@Composable
private fun Board(b: Formats.Board, font: FontFamily) {
    Column(Modifier.widthIn(max = 720.dp).background(Color(0xFF111111)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(b.title, color = Color(0xFFFFC107), fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row { b.columns.forEach { Text(it, color = Color(0xFFBDBDBD), fontFamily = font, fontSize = 12.sp, modifier = Modifier.weight(1f)) } }
        b.rows.forEach { r ->
            Row { r.forEach { Text(it, color = Color(0xFFFFEB3B), fontFamily = font, modifier = Modifier.weight(1f)) } }
        }
    }
}

