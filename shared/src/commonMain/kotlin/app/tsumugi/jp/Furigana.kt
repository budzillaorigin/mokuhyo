package app.tsumugi.jp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A run of text with its reading. [rt] is null when the run is already kana (no ruby needed). */
@Serializable
data class FuriganaSegment(val ruby: String, val rt: String? = null)

object Furigana {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes JmdictFurigana-style JSON: `[{"ruby":"漢","rt":"かん"},{"ruby":"字","rt":"じ"}]`. */
    fun parseSegments(json: String): List<FuriganaSegment> =
        this.json.decodeFromString<List<FuriganaSegment>>(json)

    /**
     * Fallback alignment when no curated furigana exists: kana runs in [text] anchor against [reading]
     * (hiragana/katakana-insensitive) and the reading between anchors goes to the kanji runs.
     * If there is no unique alignment, returns one segment covering the whole text.
     */
    fun align(text: String, reading: String): List<FuriganaSegment> {
        if (text.isEmpty()) return emptyList()
        if (Kana.isAllKana(text)) return listOf(FuriganaSegment(text))

        val runs = runs(text)
        val target = Kana.toHiragana(reading)
        val solutions = ArrayList<List<String>>(2)
        search(runs, 0, target, 0, ArrayList(), solutions)

        if (solutions.size != 1) return listOf(FuriganaSegment(text, reading))
        val readings = solutions.single()
        // Map folded offsets back onto the original reading so katakana readings keep their script.
        var offset = 0
        return runs.mapIndexed { i, run ->
            val len = readings[i].length
            val rt = reading.substring(offset, offset + len)
            offset += len
            if (run.isKana) FuriganaSegment(run.text) else FuriganaSegment(run.text, rt)
        }
    }

    private class Run(val text: String, val isKana: Boolean)

    private fun runs(text: String): List<Run> {
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var kana: Boolean? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val len = if (c.isHighSurrogate() && i + 1 < text.length) 2 else 1
            val isKana = len == 1 && Kana.isKana(c)
            if (kana != null && kana != isKana) {
                out += Run(sb.toString(), kana)
                sb.clear()
            }
            kana = isKana
            sb.append(text, i, i + len)
            i += len
        }
        if (kana != null) out += Run(sb.toString(), kana)
        return out
    }

    private fun search(
        runs: List<Run>,
        index: Int,
        target: String,
        pos: Int,
        acc: ArrayList<String>,
        solutions: MutableList<List<String>>,
    ) {
        if (solutions.size > 1) return
        if (index == runs.size) {
            if (pos == target.length) solutions += acc.toList()
            return
        }
        val run = runs[index]
        if (run.isKana) {
            val folded = Kana.toHiragana(run.text)
            if (target.startsWith(folded, pos)) {
                acc += folded
                search(runs, index + 1, target, pos + folded.length, acc, solutions)
                acc.removeAt(acc.lastIndex)
            }
            return
        }
        // A non-kana run reads as at least one character; leave room for the kana runs after it.
        for (end in pos + 1..target.length) {
            acc += target.substring(pos, end)
            search(runs, index + 1, target, end, acc, solutions)
            acc.removeAt(acc.lastIndex)
            if (solutions.size > 1) return
        }
    }
}
