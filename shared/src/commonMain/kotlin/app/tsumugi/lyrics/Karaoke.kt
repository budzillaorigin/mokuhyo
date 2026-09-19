package app.tsumugi.lyrics

import app.tsumugi.jp.Kana
import app.tsumugi.jp.Mora
import app.tsumugi.media.Cue
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.srs.AnswerChecker
import app.tsumugi.srs.Verdict
import kotlinx.serialization.Serializable

/** A timed word of a lyric line: [start, end) in the line text. */
@Serializable
data class LyricWord(val start: Int, val end: Int, val startMs: Long, val endMs: Long, val reading: String? = null)

/** A word the learner chose to hide in cloze mode: [start, end) in the line text, with its reading when known. */
@Serializable
data class ClozeSpan(val start: Int, val end: Int, val reading: String? = null)

/**
 * One lyric line. Times are null for plain lyrics before alignment. [translation] is the learner's own
 * ([translationSource] "user") or a model's ("llm", with [translationEngine]; shown with the AI-generated badge,
 * rule 10).
 */
@Serializable
data class LyricLine(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val translationSource: String? = null,
    val translationEngine: String? = null,
    val cloze: List<ClozeSpan> = emptyList(),
) {
    val aiTranslated: Boolean get() = translationSource == SOURCE_LLM

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_LLM = "llm"
    }
}

/**
 * Where playback is: [lineIndex] is the last line that has started (-1 before the first), [active] whether it is
 * still being sung, [wordIndex] the last started word of that line (-1 when the line has no word times).
 */
data class KaraokePosition(
    val lineIndex: Int,
    val wordIndex: Int,
    val active: Boolean,
    /** 0..1 through the line, for a sweep highlight. */
    val lineProgress: Double,
    /** 0..1 through the current word. */
    val wordProgress: Double,
)

/** The karaoke model (BRIEF_V2 §6.3): current line and word from the playback position. Pure; call per frame. */
object Karaoke {
    fun at(lines: List<LyricLine>, positionMs: Long): KaraokePosition {
        var lineIndex = -1
        for (i in lines.indices) {
            val s = lines[i].startMs ?: continue
            if (s <= positionMs) lineIndex = i else break
        }
        if (lineIndex < 0) return KaraokePosition(-1, -1, false, 0.0, 0.0)
        val line = lines[lineIndex]
        val start = line.startMs!!
        val end = lineEnd(lines, lineIndex)
        val active = positionMs < end
        val lineProgress = if (end <= start) 1.0 else ((positionMs - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
        val wordIndex = line.words.indexOfLast { it.startMs <= positionMs }
        val wordProgress = line.words.getOrNull(wordIndex)?.let { w ->
            if (w.endMs <= w.startMs) 1.0 else ((positionMs - w.startMs).toDouble() / (w.endMs - w.startMs)).coerceIn(0.0, 1.0)
        } ?: 0.0
        return KaraokePosition(lineIndex, wordIndex, active, lineProgress, wordProgress)
    }

    /** A line's end: its own, else the next timed line's start, else its last word's end, else start + 5 s. */
    fun lineEnd(lines: List<LyricLine>, index: Int): Long {
        val line = lines[index]
        line.endMs?.let { return it }
        for (j in index + 1 until lines.size) lines[j].startMs?.let { return it }
        return line.words.lastOrNull()?.endMs ?: ((line.startMs ?: 0L) + 5_000)
    }

    /**
     * Spreads [startMs, endMs) over [spans] (start, end, reading) in proportion to their morae (rule 7: timing is
     * phonological), counting characters when a span has no reading. Used for line-level LRC and aligned lyrics,
     * where only the line is timed (D-163).
     */
    fun distribute(text: String, spans: List<Triple<Int, Int, String?>>, startMs: Long, endMs: Long): List<LyricWord> {
        if (spans.isEmpty() || endMs <= startMs) return emptyList()
        val weights = spans.map { (s, e, reading) ->
            val kana = reading ?: text.substring(s, e).takeIf { Kana.isAllKana(it) }
            (kana?.let { Mora.count(Kana.toHiragana(it)) } ?: (e - s)).coerceAtLeast(1)
        }
        val total = weights.sum().toDouble()
        var acc = 0
        return spans.mapIndexed { i, (s, e, reading) ->
            val from = startMs + ((endMs - startMs) * (acc / total)).toLong()
            acc += weights[i]
            val to = startMs + ((endMs - startMs) * (acc / total)).toLong()
            LyricWord(s, e, from, to, reading)
        }
    }
}

/**
 * Timing plain lyrics from Whisper segments (BRIEF_V2 §6.3, DECISIONS D-163). The lyric text and the transcript
 * are folded (NFC, katakana → hiragana, letters and digits only) and aligned character by character with edit
 * distance; every exact character match becomes an anchor carrying the time of that transcript character (each
 * segment's time spread evenly over its characters). A line runs from its first anchor to its last. Lines with no
 * anchor (Whisper wrote kanji where the lyrics have kana, a line it missed) are spread evenly over the gap between
 * their timed neighbours. Segment-level accuracy; good enough to follow along and to tap a line to replay it.
 */
object LyricsAligner {
    /** (start, end) per line, in [lines] order; blank lines get null. */
    fun align(lines: List<String>, segments: List<Cue>): List<Pair<Long, Long>?> {
        val segs = segments.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (segs.isEmpty()) return lines.map { null }
        // Lyric characters and which line each came from.
        val lyric = StringBuilder()
        val lineOf = ArrayList<Int>()
        lines.forEachIndexed { i, l ->
            for (c in fold(l)) {
                lyric.append(c)
                lineOf += i
            }
        }
        val heard = StringBuilder()
        val timeOf = ArrayList<Long>()
        for (s in segs) {
            val f = fold(s.text)
            f.forEachIndexed { k, c ->
                heard.append(c)
                timeOf += s.startMs + ((s.endMs - s.startMs) * (k + 0.5) / f.length).toLong()
            }
        }
        val anchors = matches(lyric.toString(), heard.toString())
        val first = LongArray(lines.size) { -1 }
        val last = LongArray(lines.size) { -1 }
        for ((i, j) in anchors) {
            val line = lineOf[i]
            if (first[line] < 0) first[line] = timeOf[j]
            last[line] = timeOf[j]
        }
        val charMs = if (heard.isEmpty()) 250L else ((segs.last().endMs - segs.first().startMs) / heard.length).coerceIn(80, 1000)
        val out = arrayOfNulls<Pair<Long, Long>>(lines.size)
        val content = lines.indices.filter { fold(lines[it]).isNotEmpty() }
        for (i in content) if (first[i] >= 0) out[i] = (first[i] - charMs / 2).coerceAtLeast(0) to (last[i] + charMs / 2)
        // Keep anchored lines in order: a line that starts before its predecessor (a repeated chorus matched
        // against the wrong repetition) is dropped and interpolated instead.
        var prevStart = -1L
        for (i in content) {
            val r = out[i] ?: continue
            if (r.first < prevStart) out[i] = null else prevStart = r.first
        }
        // Interpolate runs of untimed lines between timed neighbours.
        var k = 0
        while (k < content.size) {
            if (out[content[k]] != null) {
                k++
                continue
            }
            var e = k
            while (e < content.size && out[content[e]] == null) e++
            val from = if (k > 0) out[content[k - 1]]!!.second else segs.first().startMs
            val to = if (e < content.size) out[content[e]]!!.first else segs.last().endMs
            val span = (to - from).coerceAtLeast(0)
            val n = e - k
            for (x in 0 until n) {
                val s = from + span * x / n
                val t = from + span * (x + 1) / n
                out[content[k + x]] = s to maxOf(t, s + 1)
            }
            k = e
        }
        // No overlaps: a line ends where the next begins.
        for (x in 0 until content.size - 1) {
            val a = out[content[x]]!!
            val b = out[content[x + 1]]!!
            if (a.second > b.first) out[content[x]] = a.first to maxOf(b.first, a.first + 1)
        }
        return out.toList()
    }

    internal fun fold(s: String): String = Kana.toHiragana(normalizeNfc(s)).filter { it.isLetterOrDigit() }

    /** Index pairs (i in a, j in b) of equal characters on one minimal edit-distance alignment. */
    internal fun matches(a: String, b: String): List<Pair<Int, Int>> {
        val n = a.length
        val m = b.length
        if (n == 0 || m == 0) return emptyList()
        // Full DP table (lyrics are a few thousand characters at most: ≤ ~10^7 cells of 2 bytes).
        val d = Array(n + 1) { ShortArray(m + 1) }
        for (i in 0..n) d[i][0] = minOf(i, Short.MAX_VALUE.toInt()).toShort()
        for (j in 0..m) d[0][j] = minOf(j, Short.MAX_VALUE.toInt()).toShort()
        for (i in 1..n) {
            val row = d[i]
            val up = d[i - 1]
            for (j in 1..m) {
                val sub = up[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                row[j] = minOf(sub, up[j] + 1, row[j - 1] + 1).coerceAtMost(Short.MAX_VALUE.toInt()).toShort()
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val same = a[i - 1] == b[j - 1]
            when {
                same && d[i][j] == d[i - 1][j - 1] -> {
                    out += (i - 1) to (j - 1)
                    i--; j--
                }
                d[i][j].toInt() == d[i - 1][j - 1] + 1 -> { i--; j-- }
                d[i][j].toInt() == d[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return out.asReversed()
    }
}

enum class ClozeState { HIDDEN, DUE, CORRECT, CLOSE, WRONG, REVEALED }

/** A hidden word. [sungAtMs]: when it has been sung (it becomes DUE then); null = due right away (untimed lyrics). */
data class ClozeBlank(
    val id: Int,
    val lineIndex: Int,
    val start: Int,
    val end: Int,
    val answer: String,
    val reading: String?,
    val sungAtMs: Long?,
    val state: ClozeState,
)

data class ClozeAnswer(val blank: ClozeBlank, val verdict: Verdict, val expected: String, val given: String) {
    val accepted: Boolean get() = verdict == Verdict.CORRECT || verdict == Verdict.CLOSE
}

/**
 * Cloze mode (BRIEF_V2 §6.3, AxTongue's mechanic): the chosen words stay hidden until they are sung; then the
 * player can pause and the learner types them. Answers are kana/kanji tolerant: the written form, its reading in
 * hiragana, katakana or romaji all count (AnswerChecker.checkReading), and one typo in a 4+ character answer is
 * CLOSE.
 */
class ClozeSession(private val lines: List<LyricLine>) {
    private val blanksMutable: MutableList<ClozeBlank> = ArrayList()

    init {
        var id = 0
        lines.forEachIndexed { li, line ->
            for (c in line.cloze.sortedBy { it.start }) {
                if (c.start !in 0 until c.end || c.end > line.text.length) continue
                val sung = line.words.filter { it.start < c.end && it.end > c.start }.maxOfOrNull { it.endMs }
                    ?: line.startMs?.let { Karaoke.lineEnd(lines, li) }
                blanksMutable += ClozeBlank(id++, li, c.start, c.end, line.text.substring(c.start, c.end), c.reading, sung, ClozeState.HIDDEN)
            }
        }
    }

    val blanks: List<ClozeBlank> get() = blanksMutable.toList()

    /** Moves blanks whose words have been sung to DUE; returns the ones that just became due (pause here). */
    fun update(positionMs: Long): List<ClozeBlank> {
        val due = ArrayList<ClozeBlank>()
        for (i in blanksMutable.indices) {
            val b = blanksMutable[i]
            if (b.state == ClozeState.HIDDEN && (b.sungAtMs == null || positionMs >= b.sungAtMs)) {
                blanksMutable[i] = b.copy(state = ClozeState.DUE)
                due += blanksMutable[i]
            }
        }
        return due
    }

    fun answer(id: Int, given: String): ClozeAnswer {
        val i = blanksMutable.indexOfFirst { it.id == id }
        require(i >= 0) { "no blank $id" }
        val b = blanksMutable[i]
        val verdict = check(b.answer, b.reading, given)
        val state = when (verdict) {
            Verdict.CORRECT -> ClozeState.CORRECT
            Verdict.CLOSE -> ClozeState.CLOSE
            else -> ClozeState.WRONG
        }
        blanksMutable[i] = b.copy(state = state)
        return ClozeAnswer(blanksMutable[i], verdict, b.answer, given)
    }

    fun reveal(id: Int): ClozeBlank {
        val i = blanksMutable.indexOfFirst { it.id == id }
        require(i >= 0) { "no blank $id" }
        blanksMutable[i] = blanksMutable[i].copy(state = ClozeState.REVEALED)
        return blanksMutable[i]
    }

    /** The line with still-hidden (HIDDEN/DUE) blanks masked as ＿ per character. */
    fun masked(lineIndex: Int): String {
        val text = lines[lineIndex].text
        val hidden = blanksMutable.filter { it.lineIndex == lineIndex && (it.state == ClozeState.HIDDEN || it.state == ClozeState.DUE) }
        if (hidden.isEmpty()) return text
        val chars = text.toCharArray()
        for (b in hidden) for (k in b.start until b.end) chars[k] = '＿'
        return chars.concatToString()
    }

    /** (accepted, answered). */
    val score: Pair<Int, Int>
        get() = blanksMutable.count { it.state == ClozeState.CORRECT || it.state == ClozeState.CLOSE } to
            blanksMutable.count { it.state == ClozeState.CORRECT || it.state == ClozeState.CLOSE || it.state == ClozeState.WRONG }

    companion object {
        fun check(answer: String, reading: String?, given: String): Verdict {
            val g = normalizeNfc(given.trim()).filterNot { it.isWhitespace() }
            if (g.isEmpty()) return Verdict.WRONG
            val a = normalizeNfc(answer).filterNot { it.isWhitespace() }
            if (g == a) return Verdict.CORRECT
            val readings = listOfNotNull(reading, a.takeIf { Kana.isAllKana(it) })
            if (readings.isNotEmpty() && AnswerChecker.checkReading(g, readings).verdict == Verdict.CORRECT) return Verdict.CORRECT
            val foldedGiven = Kana.toHiragana(g)
            val targets = listOf(Kana.toHiragana(a)) + readings.map { Kana.toHiragana(it) }
            if (targets.any { it == foldedGiven }) return Verdict.CORRECT
            if (targets.any { it.length >= 4 && AnswerChecker.damerauLevenshtein(it, foldedGiven) <= 1 }) return Verdict.CLOSE
            return Verdict.WRONG
        }
    }
}
