package app.tsumugi.speech

import app.tsumugi.jp.Mora
import app.tsumugi.jp.PitchAccent
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One word of the target sentence: its reading (kana), its accent downstep from the pitch data (0 = heiban;
 * null when unknown), and whether a particle follows (so the high/low of that particle can be judged too —
 * that is what tells heiban from odaka).
 */
data class WordTarget(val reading: String, val downstep: Int?, val followedByParticle: Boolean = false)

enum class PitchVerdict { MATCH, FLAT, WRONG_DROP, UNCLEAR, UNKNOWN_ACCENT }

data class WordFeedback(
    val reading: String,
    val verdict: PitchVerdict,
    /** Expected pattern with ↑ at rises and ↓ at drops, e.g. "は↑し↓(が)". */
    val expectedMarks: String,
    /** What the pitch track suggests, same notation; empty when unclear. */
    val observedMarks: String,
)

data class PronunciationReport(
    /** Share of target morae the recognizer heard as written (null without a transcript). */
    val moraAccuracy: Double?,
    val moraAlignment: MoraAlignmentResult?,
    /** Share of judged words whose high/low pattern matched (null when no word could be judged). */
    val pitchAccuracy: Double?,
    val rateMoraPerSec: Double,
    val pauses: List<Span>,
    val fluencyScore: Int,
    /** Weighted 0..100 combination of the available sub-scores (see [PronunciationAnalyzer]). */
    val composite: Int,
    val subScores: Map<String, Int>,
    val words: List<WordFeedback>,
    /** Plain-language caveats shown with the scores. Always includes the "heuristic" caveat. */
    val notes: List<String>,
)

data class ShadowingReport(
    val timingScore: Int,
    val intonationScore: Int,
    val overall: Int,
    /** Attempt length / reference length (voiced parts only). */
    val durationRatio: Double,
    val notes: List<String>,
)

/**
 * Honest, heuristic pronunciation feedback (BRIEF §5.10). It combines three independent signals:
 * - **mora accuracy**: which morae speech recognition heard as written ([MoraAlignment]);
 * - **pitch**: an F0 track ([Yin]) spread over the morae in time, compared word by word with the dictionary
 *   accent (high/low per mora, including a following particle);
 * - **fluency**: speaking rate (morae per second of voiced audio) and long pauses.
 *
 * The composite weights mora 0.5, pitch 0.3, fluency 0.2, renormalized over the sub-scores that exist. None of
 * this is phoneme-level assessment: the recognizer may "correct" what was said, and mora timing is estimated,
 * not measured. [PronunciationReport.notes] says so to the learner.
 */
object PronunciationAnalyzer {
    private const val MIN_VOICED_MS = 300L
    private const val FLAT_SEMITONES = 1.5f
    private const val WEIGHT_MORA = 0.5
    private const val WEIGHT_PITCH = 0.3
    private const val WEIGHT_FLUENCY = 0.2

    fun analyze(
        targetReading: List<WordTarget>,
        transcriptKana: String?,
        pcm16k: FloatArray,
        sttSegments: List<Pair<LongRange, String>>? = null,
    ): PronunciationReport {
        val notes = mutableListOf("Heuristic feedback: compares recognized kana and a pitch track, not individual sounds.")
        val segments = VoiceActivity.segments(pcm16k)
        val voicedMs = VoiceActivity.voicedMs(segments)
        val targetText = targetReading.joinToString("") { it.reading }
        val totalMorae = Mora.count(MoraAlignment.normalize(targetText))

        val alignment = transcriptKana?.let { MoraAlignment.align(targetText, it) }
        if (alignment == null) notes += "No transcript, so mora accuracy isn't scored."

        val pauses = VoiceActivity.pauses(segments)
        val rate = if (voicedMs > 0) totalMorae / (voicedMs / 1000.0) else 0.0

        var words = emptyList<WordFeedback>()
        var pitchAccuracy: Double? = null
        if (voicedMs < MIN_VOICED_MS) {
            notes += "Too little voiced audio to judge pitch or fluency — try recording again, a little closer."
        } else {
            val track = Yin.track(pcm16k)
            val spans = moraSpans(targetReading, segments, sttSegments)
            words = judgeWords(targetReading, track, spans)
            val judged = words.filter { it.verdict != PitchVerdict.UNCLEAR && it.verdict != PitchVerdict.UNKNOWN_ACCENT }
            if (judged.isNotEmpty()) pitchAccuracy = judged.count { it.verdict == PitchVerdict.MATCH }.toDouble() / judged.size
            if (words.any { it.verdict == PitchVerdict.UNCLEAR }) notes += "Some words were too short or quiet for a pitch reading."
            if (sttSegments == null) notes += "Mora timing is estimated from the recording, so pitch per mora is approximate."
        }

        val fluency = if (voicedMs < MIN_VOICED_MS) 0 else fluencyScore(rate, pauses.size, totalMorae)
        val sub = linkedMapOf<String, Int>()
        alignment?.let { sub["mora"] = (it.accuracy * 100).roundToInt() }
        pitchAccuracy?.let { sub["pitch"] = (it * 100).roundToInt() }
        if (voicedMs >= MIN_VOICED_MS) sub["fluency"] = fluency
        val weights = mapOf("mora" to WEIGHT_MORA, "pitch" to WEIGHT_PITCH, "fluency" to WEIGHT_FLUENCY)
        val totalWeight = sub.keys.sumOf { weights.getValue(it) }
        val composite = if (totalWeight == 0.0) 0 else (sub.entries.sumOf { (k, v) -> v * weights.getValue(k) } / totalWeight).roundToInt().coerceIn(0, 100)

        return PronunciationReport(alignment?.accuracy, alignment, pitchAccuracy, rate, pauses, fluency, composite, sub, words, notes)
    }

    /** 60% speaking rate (2 → 6 morae/s maps 0 → 100) and 40% long pauses (one per 8 morae is acceptable). */
    internal fun fluencyScore(rate: Double, longPauses: Int, morae: Int): Int {
        val rateScore = ((rate - 2.0) / 4.0).coerceIn(0.0, 1.0)
        val allowed = max(1.0, morae / 8.0)
        // Up to the allowance costs nothing; each further allowance's worth of pauses costs half the score.
        val pauseScore = (1.0 - (longPauses / allowed - 1.0).coerceAtLeast(0.0) / 2.0).coerceIn(0.0, 1.0)
        return (100 * (0.6 * rateScore + 0.4 * pauseScore)).roundToInt()
    }

    /**
     * Time span (ms) of each target mora. With recognizer segments whose mora counts add up, each segment's time
     * is split over its morae; otherwise the voiced time is split evenly over all morae in order.
     */
    internal fun moraSpans(words: List<WordTarget>, voiced: List<Span>, stt: List<Pair<LongRange, String>>?): List<Span> {
        val total = words.sumOf { Mora.count(MoraAlignment.normalize(it.reading)) }
        if (total == 0) return emptyList()
        if (stt != null) {
            val counts = stt.map { Mora.count(MoraAlignment.normalize(it.second)) }
            if (counts.sum() == total) {
                return stt.zip(counts).flatMap { (seg, n) ->
                    val (start, end) = seg.first.first to seg.first.last + 1
                    (0 until n).map { k -> Span(start + (end - start) * k / n, start + (end - start) * (k + 1) / n) }
                }
            }
        }
        val voicedTotal = VoiceActivity.voicedMs(voiced)
        if (voicedTotal <= 0) return emptyList()
        fun realTime(offset: Long): Long {
            var remaining = offset
            for (s in voiced) {
                if (remaining <= s.durationMs) return s.startMs + remaining
                remaining -= s.durationMs
            }
            return voiced.last().endMs
        }
        return (0 until total).map { k -> Span(realTime(voicedTotal * k / total), realTime(voicedTotal * (k + 1) / total)) }
    }

    internal fun judgeWords(words: List<WordTarget>, track: F0Track, spans: List<Span>): List<WordFeedback> {
        val semis = track.semitones()
        fun pitchAt(span: Span?): Float {
            span ?: return Float.NaN
            val from = (span.startMs / 10).toInt().coerceAtLeast(0)
            val to = min(semis.size, ((span.endMs + 9) / 10).toInt())
            val values = (from until to).map { semis[it] }.filter { !it.isNaN() }.sorted()
            return if (values.size < 2) Float.NaN else values[values.size / 2]
        }
        val moraeByWord = words.map { Mora.split(MoraAlignment.normalize(it.reading)) }
        var index = 0
        return words.mapIndexed { w, word ->
            val morae = moraeByWord[w]
            val start = index
            index += morae.size
            val downstep = word.downstep
            if (downstep == null) return@mapIndexed WordFeedback(word.reading, PitchVerdict.UNKNOWN_ACCENT, "", "")
            val expected = PitchAccent(downstep, morae.size).heights()
            val nextMora = moraeByWord.getOrNull(w + 1)?.firstOrNull()
            val withParticle = word.followedByParticle && nextMora != null
            val labels = morae + if (withParticle) listOf("($nextMora)") else emptyList()
            val expectedPoints = if (withParticle) expected else expected.dropLast(1)
            val values = labels.indices.map { pitchAt(spans.getOrNull(start + it)) }
            val expectedMarks = marks(labels, expectedPoints)
            val known = values.count { !it.isNaN() }
            if (labels.size < 2 || known < max(2, (labels.size * 0.6).roundToInt())) {
                return@mapIndexed WordFeedback(word.reading, PitchVerdict.UNCLEAR, expectedMarks, "")
            }
            val present = values.filter { !it.isNaN() }
            val lo = present.min()
            val hi = present.max()
            if (hi - lo < FLAT_SEMITONES) return@mapIndexed WordFeedback(word.reading, PitchVerdict.FLAT, expectedMarks, labels.joinToString(""))
            val mid = lo + (hi - lo) / 2
            val observed = values.mapIndexed { i, v -> if (v.isNaN()) expectedPoints[i] else v >= mid }
            val mismatches = observed.indices.filter { observed[it] != expectedPoints[it] }
            val verdict = when {
                mismatches.isEmpty() -> PitchVerdict.MATCH
                mismatches == listOf(0) && labels.size >= 3 -> PitchVerdict.MATCH // the initial rise is often small
                else -> PitchVerdict.WRONG_DROP
            }
            WordFeedback(word.reading, verdict, expectedMarks, marks(labels, observed))
        }
    }

    /** "は↑し↓(が)": ↑ between a low and a high mora, ↓ between a high and a low one. */
    internal fun marks(labels: List<String>, heights: List<Boolean>): String = buildString {
        labels.forEachIndexed { i, label ->
            if (i > 0) {
                if (!heights[i - 1] && heights[i]) append('↑')
                if (heights[i - 1] && !heights[i]) append('↓')
            }
            append(label)
        }
    }

    // --- Shadowing ------------------------------------------------------------------------------------

    /**
     * Compares a shadowing attempt with the reference audio (both 16 kHz): contours of pitch (semitones relative
     * to each speaker's own median, so voices of different height compare fairly) and energy are aligned with
     * DTW. Timing reflects overall length and how far the alignment strays from even pacing; intonation is the
     * mean pitch difference along the alignment.
     */
    fun shadowingCompare(reference: FloatArray, attempt: FloatArray): ShadowingReport {
        val notes = mutableListOf("Compares the melody and rhythm of your recording with the model, not individual sounds.")
        val ref = contour(capped(reference))
        val att = contour(capped(attempt))
        if (ref == null || att == null) {
            notes += "Not enough voiced audio to compare — try recording again."
            return ShadowingReport(0, 0, 0, 0.0, notes)
        }
        // Both contours shrink by the same factor, so the duration ratio and pacing are unchanged (F-22).
        val factor = downsampleFactor(max(ref.pitch.size, att.pitch.size))
        val alignment = align(downsample(ref, factor), downsample(att, factor))
        val ratio = att.pitch.size.toDouble() / ref.pitch.size
        val timing = (100 * (1.0 - min(1.0, abs(ln(ratio)) / ln(2.0) * 0.5 + alignment.meanDeviation * 4))).roundToInt().coerceIn(0, 100)
        val intonation = (100 * (1.0 - min(1.0, alignment.meanPitchDiff / 4.0))).roundToInt().coerceIn(0, 100)
        if (ratio > 1.4) notes += "Your recording is noticeably slower than the model."
        if (ratio < 0.7) notes += "Your recording is noticeably faster than the model."
        if (reference.size > MAX_SHADOW_SAMPLES || attempt.size > MAX_SHADOW_SAMPLES) {
            notes += "Only the first ${MAX_SHADOW_SAMPLES / Audio.SAMPLE_RATE} seconds were compared."
        }
        return ShadowingReport(timing, intonation, (timing + intonation) / 2, ratio, notes)
    }

    /** Longest recording compared (60 s); longer input is cut, never loaded into the alignment. */
    internal const val MAX_SHADOW_SAMPLES = 60 * 16_000

    /** Frames aligned at full resolution (20 s of 10 ms frames); longer contours are averaged down to this. */
    internal const val MAX_DTW_FRAMES = 2_000

    internal fun downsampleFactor(frames: Int): Int = (frames + MAX_DTW_FRAMES - 1) / MAX_DTW_FRAMES

    private fun capped(signal: FloatArray): FloatArray =
        if (signal.size <= MAX_SHADOW_SAMPLES) signal else signal.copyOf(MAX_SHADOW_SAMPLES)

    internal class Alignment(val meanPitchDiff: Double, val meanDeviation: Double, val steps: Int)

    /**
     * Banded DTW over pitch (semitones / 4) and energy, keeping only two rows of the cost matrix: O(m) memory
     * instead of O(n·m) (F-22). The statistics the traceback used to collect — pitch difference and distance from
     * even pacing along the optimal path — are carried forward with each cell's cost instead, choosing the same
     * predecessor the traceback chose (diagonal, then up, then left on ties), so results are identical to the
     * full-matrix version.
     */
    internal fun align(refPitch: FloatArray, refEnergy: FloatArray, attPitch: FloatArray, attEnergy: FloatArray): Alignment {
        val n = refPitch.size
        val m = attPitch.size
        val band = max(abs(n - m), max(n, m) / 4) + 2
        val inf = Double.MAX_VALUE / 4
        var prevCost = DoubleArray(m + 1) { inf }.also { it[0] = 0.0 }
        var prevPitch = DoubleArray(m + 1)
        var prevDev = DoubleArray(m + 1)
        var prevSteps = IntArray(m + 1)
        var curCost = DoubleArray(m + 1)
        var curPitch = DoubleArray(m + 1)
        var curDev = DoubleArray(m + 1)
        var curSteps = IntArray(m + 1)
        for (i in 1..n) {
            curCost.fill(inf)
            curPitch.fill(0.0)
            curDev.fill(0.0)
            curSteps.fill(0)
            val center = (i.toLong() * m / n).toInt()
            val jFrom = max(1, center - band)
            val jTo = min(m, center + band)
            for (j in jFrom..jTo) {
                val pitchDiff = abs(refPitch[i - 1] - attPitch[j - 1])
                val d = pitchDiff / 4.0 + abs(refEnergy[i - 1] - attEnergy[j - 1])
                val diag = prevCost[j - 1]
                val up = prevCost[j]
                val left = curCost[j - 1]
                val best = minOf(diag, up, left)
                curCost[j] = d + best
                // Same tie order as the old traceback: diagonal, then up, then left.
                val dev = abs(i.toDouble() / n - j.toDouble() / m)
                when (best) {
                    diag -> {
                        curPitch[j] = prevPitch[j - 1] + pitchDiff
                        curDev[j] = prevDev[j - 1] + dev
                        curSteps[j] = prevSteps[j - 1] + 1
                    }
                    up -> {
                        curPitch[j] = prevPitch[j] + pitchDiff
                        curDev[j] = prevDev[j] + dev
                        curSteps[j] = prevSteps[j] + 1
                    }
                    else -> {
                        curPitch[j] = curPitch[j - 1] + pitchDiff
                        curDev[j] = curDev[j - 1] + dev
                        curSteps[j] = curSteps[j - 1] + 1
                    }
                }
            }
            prevCost = curCost.also { curCost = prevCost }
            prevPitch = curPitch.also { curPitch = prevPitch }
            prevDev = curDev.also { curDev = prevDev }
            prevSteps = curSteps.also { curSteps = prevSteps }
        }
        val steps = prevSteps[m]
        return Alignment(prevPitch[m] / max(1, steps), prevDev[m] / max(1, steps), steps)
    }

    private fun align(ref: Contour, att: Contour): Alignment = align(ref.pitch, ref.energy, att.pitch, att.energy)

    /** Averages every [factor] frames into one (the last group may be shorter). */
    private fun downsample(c: Contour, factor: Int): Contour {
        if (factor <= 1) return c
        fun avg(values: FloatArray) = FloatArray((values.size + factor - 1) / factor) { k ->
            val from = k * factor
            val to = min(values.size, from + factor)
            var sum = 0f
            for (x in from until to) sum += values[x]
            sum / (to - from)
        }
        return Contour(avg(c.pitch), avg(c.energy))
    }

    private class Contour(val pitch: FloatArray, val energy: FloatArray)

    /** Voiced-region contour: semitones (unvoiced frames interpolated) and energy in 0..1 (−40 dB → 0). */
    private fun contour(signal: FloatArray): Contour? {
        val segments = VoiceActivity.segments(signal)
        if (VoiceActivity.voicedMs(segments) < MIN_VOICED_MS) return null
        val from = (segments.first().startMs / 10).toInt()
        val to = (segments.last().endMs / 10).toInt()
        val track = Yin.track(signal)
        if (track.voicedCount < 5) return null
        val semis = track.semitones()
        val rms = Audio.rmsFrames(signal)
        val peak = rms.max().coerceAtLeast(1e-6f)
        val end = min(to, min(semis.size, rms.size))
        if (end - from < 5) return null
        val pitch = FloatArray(end - from) { semis[from + it] }
        fillGaps(pitch)
        val energy = FloatArray(end - from) { k ->
            val db = 20 * log10(max(rms[from + k], 1e-6f) / peak)
            ((db + 40f) / 40f).coerceIn(0f, 1f)
        }
        return Contour(pitch, energy)
    }

    /** Linear interpolation over NaN runs; edges hold the nearest voiced value. */
    private fun fillGaps(values: FloatArray) {
        val voiced = values.indices.filter { !values[it].isNaN() }
        if (voiced.isEmpty()) { values.fill(0f); return }
        for (k in 0 until voiced.first()) values[k] = values[voiced.first()]
        for (k in voiced.last() + 1 until values.size) values[k] = values[voiced.last()]
        voiced.zipWithNext().forEach { (a, b) ->
            for (k in a + 1 until b) values[k] = values[a] + (values[b] - values[a]) * (k - a) / (b - a)
        }
    }
}
