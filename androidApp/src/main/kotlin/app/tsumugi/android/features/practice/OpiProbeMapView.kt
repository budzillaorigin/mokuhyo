package app.tsumugi.android.features.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.opi.OpiProbeMap
import app.tsumugi.exam.opi.OpiTurnOutcome
import app.tsumugi.exam.opi.OpiTurnRecord
import app.tsumugi.practice.OpiDomain

private val Sustained = Color(0xFF2E7D32)
private val Partial = Color(0xFFF9A825)
private val Breakdown = Color(0xFFC62828)

@Composable
private fun OpiTurnOutcome.color(): Color = when (this) {
    OpiTurnOutcome.SUSTAINED -> Sustained
    OpiTurnOutcome.PARTIAL -> Partial
    OpiTurnOutcome.BREAKDOWN -> Breakdown
    OpiTurnOutcome.NOT_RATED -> MaterialTheme.colorScheme.outline
}

@Composable
fun OpiDomain.localized(): String = stringResource(
    when (this) {
        OpiDomain.PERSONAL -> R.string.opi_domain_personal
        OpiDomain.FAMILY -> R.string.opi_domain_family
        OpiDomain.WORK -> R.string.opi_domain_work
        OpiDomain.DAILY_LIFE -> R.string.opi_domain_daily_life
        OpiDomain.TRAVEL -> R.string.opi_domain_travel
        OpiDomain.CURRENT_EVENTS -> R.string.opi_domain_current_events
        OpiDomain.HYPOTHETICAL -> R.string.opi_domain_hypothetical
        OpiDomain.ABSTRACT -> R.string.opi_domain_abstract
        OpiDomain.SITUATION -> R.string.opi_domain_situation
    },
)

@Composable
private fun OpiTurnOutcome.label(): String = stringResource(
    when (this) {
        OpiTurnOutcome.SUSTAINED -> R.string.opi_outcome_sustained
        OpiTurnOutcome.PARTIAL -> R.string.opi_outcome_partial
        OpiTurnOutcome.BREAKDOWN -> R.string.opi_outcome_breakdown
        OpiTurnOutcome.NOT_RATED -> R.string.opi_outcome_not_rated
    },
)

/**
 * The "level check → probe" map after an interview (BRIEF_V2 §6.16, D-229, D-257): each question at the level it
 * aimed at (circle = level check, diamond = probe, small dot = other phases), colored by how the answer held up, with
 * the working level as a line; then per-level tallies, where speech broke down, and the topic domains covered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpiProbeMapView(map: OpiProbeMap) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.opi_map_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.opi_map_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (map.turns.none { it.rated }) {
                Text(stringResource(R.string.opi_map_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    listOfNotNull(
                        map.floor?.let { stringResource(R.string.opi_map_floor, it.label) },
                        map.ceiling?.let { stringResource(R.string.opi_map_ceiling, it.label) } ?: stringResource(R.string.opi_map_no_breakdown),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                ProbeChart(map)
                Legend()
                map.byLevel.forEach { t -> TallyRow(t) }
            }
            if (map.breakdowns.isNotEmpty()) {
                Text(stringResource(R.string.opi_map_breakdowns), style = MaterialTheme.typography.titleSmall)
                map.breakdowns.forEach { t ->
                    Column {
                        Text(
                            stringResource(R.string.opi_map_turn, t.index + 1, t.targetLevel.label) + (t.domain?.let { " · " + it.localized() } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = Breakdown,
                        )
                        JaText(t.question, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(stringResource(R.string.opi_map_domains), style = MaterialTheme.typography.titleSmall)
            if (map.domains.isEmpty()) {
                Text(stringResource(R.string.opi_map_no_domains), style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    map.domains.forEach { Tag(it.localized()) }
                }
            }
            if (map.missingDliDomains.isNotEmpty()) {
                Text(
                    stringResource(R.string.opi_map_try_next, map.missingDliDomains.map { it.localized() }.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProbeChart(map: OpiProbeMap) {
    val turns = map.turns
    val levels = (turns.map { it.targetLevel } + turns.mapNotNull { it.levelAfter } + turns.map { it.levelBefore })
    val low = levels.minOf { it.ordinal }
    val high = levels.maxOf { it.ordinal }.coerceAtLeast(low + 1)
    val span = (low..high).toList()
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val colors = turns.map { it.outcome.color() }
    val summary = describe(turns)
    val rowHeight = 28.dp
    Row(Modifier.fillMaxWidth()) {
        // Y axis labels, highest level on top.
        Column(Modifier.width(40.dp).clearAndSetSemantics {}) {
            span.reversed().forEach { o ->
                Box(Modifier.height(rowHeight), contentAlignment = Alignment.CenterStart) {
                    Text(IlrLevel.entries[o].label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Canvas(Modifier.weight(1f).height(rowHeight * span.size).semantics { contentDescription = summary }) {
            val rowPx = size.height / span.size
            fun y(level: IlrLevel) = (high - level.ordinal) * rowPx + rowPx / 2
            val step = if (turns.size <= 1) 0f else (size.width - 24.dp.toPx()) / (turns.size - 1)
            fun x(i: Int) = 12.dp.toPx() + step * i
            span.forEach { o ->
                val yy = (high - o) * rowPx + rowPx / 2
                drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            // Working level after each answer.
            val track = turns.mapIndexedNotNull { i, t -> t.levelAfter?.let { Offset(x(i), y(it)) } }
            for (k in 0 until track.size - 1) drawLine(lineColor, track[k], track[k + 1], strokeWidth = 2.dp.toPx())
            turns.forEachIndexed { i, t ->
                val c = Offset(x(i), y(t.targetLevel))
                val color = colors[i]
                when {
                    t.isProbe -> {
                        val r = 7.dp.toPx()
                        val p = Path().apply {
                            moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
                        }
                        drawPath(p, color)
                    }
                    t.isLevelCheck -> drawCircle(color, 6.dp.toPx(), c)
                    else -> drawCircle(outlineColor, 3.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

private fun describe(turns: List<OpiTurnRecord>): String =
    turns.filter { it.rated }.joinToString("; ") { "${it.index + 1}: ${if (it.isProbe) "probe" else "level check"} ILR ${it.targetLevel.label} ${it.outcome.name.lowercase()}" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(Size(12f, 12f), CircleShape, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.opi_phase_level_check))
        LegendItem(Size(12f, 12f), RoundedCornerShape(2.dp), MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.opi_phase_probe), diamond = true)
        listOf(OpiTurnOutcome.SUSTAINED, OpiTurnOutcome.PARTIAL, OpiTurnOutcome.BREAKDOWN).forEach { o ->
            LegendItem(Size(12f, 12f), CircleShape, o.color(), o.label())
        }
    }
}

@Composable
private fun LegendItem(size: Size, shape: androidx.compose.ui.graphics.Shape, color: Color, label: String, diamond: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (diamond) {
            Canvas(Modifier.size(size.width.dp, size.height.dp).clearAndSetSemantics {}) {
                val c = center
                val r = this.size.width / 2
                drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }, color)
            }
        } else {
            Surface(Modifier.size(size.width.dp, size.height.dp).clip(shape).clearAndSetSemantics {}, color = color) {}
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TallyRow(t: OpiProbeMap.LevelTally) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.opi_map_tally, t.level.label, t.sustained, t.partial, t.breakdown),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).clearAndSetSemantics {}) {
            if (t.sustained > 0) Box(Modifier.weight(t.sustained.toFloat()).height(8.dp)) { Surface(Modifier.fillMaxWidth().height(8.dp), color = Sustained) {} }
            if (t.partial > 0) Box(Modifier.weight(t.partial.toFloat()).height(8.dp)) { Surface(Modifier.fillMaxWidth().height(8.dp), color = Partial) {} }
            if (t.breakdown > 0) Box(Modifier.weight(t.breakdown.toFloat()).height(8.dp)) { Surface(Modifier.fillMaxWidth().height(8.dp), color = Breakdown) {} }
        }
    }
}
