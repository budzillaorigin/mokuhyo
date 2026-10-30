package app.mokuhyo.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where two clips meet (QC 2026-10-30 V2). */
enum class Boundary(val wire: String) {
    /** The same line goes on: a chunk boundary inside one speaker's sentence or turn. */
    CONTINUATION("continuation"),

    /** The same speaker's next line. */
    SENTENCE_END("sentence_end"),

    /** Another speaker's line. */
    SPEAKER_TURN("speaker_turn"),
    ;

    companion object {
        fun between(speaker: String, next: String): Boundary = if (speaker == next) SENTENCE_END else SPEAKER_TURN
    }
}

/**
 * One boundary policy for every joined clip, prerecorded and runtime (QC 2026-10-30 V2): a target heard gap per
 * [Boundary]. A join measures each clip's edge silence ([EdgeSilence]) and inserts only what is missing; edges and
 * internal pauses are never trimmed, speakers never overlap, and nothing is added after the last clip. The values live
 * in one versioned config (`boundary_policy.json`) that tools/voices/boundary.py reads too; [version] goes into every
 * render cache key.
 */
class BoundaryPolicy(val version: String, private val gapsMs: Map<Boundary, Int>) {
    fun targetMs(b: Boundary): Int = gapsMs.getValue(b)

    /** Silence to insert where a clip ending in [trailingMs] of silence meets one starting with [leadingMs]. */
    fun insertMs(b: Boundary, trailingMs: Long, leadingMs: Long): Long = (targetMs(b) - trailingMs - leadingMs).coerceAtLeast(0)

    /** [clips] (mono PCM at [rate]) joined with [boundaries] (one fewer than clips). */
    fun join(clips: List<ShortArray>, boundaries: List<Boundary>, rate: Int): ShortArray {
        require(boundaries.size == (clips.size - 1).coerceAtLeast(0)) { "${clips.size} clips need ${clips.size - 1} boundaries" }
        val edges = clips.map { EdgeSilence.of(it, rate) }
        val gaps = boundaries.mapIndexed { i, b ->
            val trailing = if (edges[i].silent) 0 else edges[i].trailingMs
            val leading = if (edges[i + 1].silent) 0 else edges[i + 1].leadingMs
            (insertMs(b, trailing, leading) * rate / 1000).toInt()
        }
        val out = ShortArray(clips.sumOf { it.size } + gaps.sum())
        var o = 0
        clips.forEachIndexed { i, c ->
            c.copyInto(out, o)
            o += c.size + (gaps.getOrNull(i) ?: 0)
        }
        return out
    }

    companion object {
        fun parse(json: String): BoundaryPolicy {
            val o = Json.parseToJsonElement(json).jsonObject
            val gaps = o.getValue("gaps_ms").jsonObject
            return BoundaryPolicy(o.getValue("version").jsonPrimitive.content, Boundary.entries.associateWith { gaps.getValue(it.wire).jsonPrimitive.int })
        }
    }
}
