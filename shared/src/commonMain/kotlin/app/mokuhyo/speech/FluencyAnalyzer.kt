package app.mokuhyo.speech

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Language-neutral fluency feedback (BRIEF §6.4), honest about what it measures: speaking rate, pause ratio and
 * false starts from the recording and its transcript, and token alignment against a target for shadowing and
 * read-aloud drills. No phoneme-level claims; the 0–100 composite is labelled a heuristic in the UI.
 */
object FluencyAnalyzer {
    data class Report(
        /** English-equivalent words per minute of speaking time. */
        val wordsPerMinute: Double,
        /** Share of the recording that is silence between speech (0..1). */
        val pauseRatio: Double,
        /** Long pauses (> 1 s) inside the answer. */
        val longPauses: Int,
        /** Repeated words and abandoned fragments ("I go— I went"). */
        val falseStarts: Int,
        /** Target tokens matched in order (0..1); null when there is no target text. */
        val targetMatch: Double?,
        val composite: Int,
    )

    /**
     * [samples] 16 kHz mono PCM16 of the learner's answer; [words] its word tokens (already folded); [target] the
     * text they were asked to say, as folded tokens (read-aloud/shadowing); [tokenFactor] tokens per English word.
     */
    fun analyze(samples: ShortArray, words: List<String>, target: List<String>? = null, tokenFactor: Double = 1.0): Report {
        val frame = 320 // 20 ms at 16 kHz
        val energies = (0 until samples.size / frame).map { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / frame)
        }
        val threshold = max(300.0, (energies.sorted().getOrNull((energies.size * 0.9).toInt()) ?: 0.0) * 0.12)
        val voiced = energies.map { it >= threshold }
        val first = voiced.indexOfFirst { it }
        val last = voiced.indexOfLast { it }
        val span = if (first < 0) emptyList() else voiced.subList(first, last + 1)
        val silentFrames = span.count { !it }
        var longPauses = 0
        var run = 0
        for (v in span) {
            if (!v) run++ else {
                if (run >= 50) longPauses++
                run = 0
            }
        }
        val speakingSeconds = span.size * 0.02
        val englishWords = words.size / tokenFactor
        val wpm = if (speakingSeconds > 0.5) englishWords / speakingSeconds * 60 else 0.0
        val pauseRatio = if (span.isEmpty()) 1.0 else silentFrames.toDouble() / span.size
        val falseStarts = words.zipWithNext().count { (a, b) -> a == b || (b.length > 2 && a.length in 1 until b.length && b.startsWith(a)) }
        val match = target?.let { alignment(it, words) }
        val composite = composite(wpm, pauseRatio, longPauses, falseStarts, words.size, match)
        return Report(wpm, pauseRatio, longPauses, falseStarts, match, composite)
    }

    /** Share of [target] tokens found in order in [said] (LCS / |target|). */
    fun alignment(target: List<String>, said: List<String>): Double {
        if (target.isEmpty()) return 0.0
        val dp = Array(target.size + 1) { IntArray(said.size + 1) }
        for (i in 1..target.size) for (j in 1..said.size) {
            dp[i][j] = if (target[i - 1] == said[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp[target.size][said.size].toDouble() / target.size
    }

    private fun composite(wpm: Double, pauseRatio: Double, longPauses: Int, falseStarts: Int, words: Int, match: Double?): Int {
        if (words == 0) return 0
        // Rate: 60–160 wpm is the comfortable conversational band for learners.
        val rate = when {
            wpm >= 160 -> 1.0
            wpm >= 60 -> 0.6 + 0.4 * (wpm - 60) / 100
            else -> 0.6 * wpm / 60
        }
        val pauses = (1.0 - pauseRatio * 1.4).coerceIn(0.0, 1.0) - 0.05 * longPauses
        val starts = 1.0 - (falseStarts.toDouble() / max(1, words)) * 4
        val parts = listOfNotNull(rate to 0.35, pauses.coerceIn(0.0, 1.0) to 0.35, starts.coerceIn(0.0, 1.0) to 0.3, match?.let { it to 0.5 })
        val total = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        return (total * 100).toInt().coerceIn(0, 100)
    }
}
