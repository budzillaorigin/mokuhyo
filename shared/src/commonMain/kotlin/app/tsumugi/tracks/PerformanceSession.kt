package app.tsumugi.tracks

import app.tsumugi.media.SubtitleQuiz

/** How much of the learner's line the prompt still shows. Each passed round fades one step. */
enum class FadeLevel {
    /** The whole line. */
    FULL,

    /** The first half of each phrase; the rest masked. */
    HALF,

    /** Only the first character of each phrase. */
    INITIAL,

    /** Nothing but the English meaning and the staging cue. */
    CUE_ONLY,
}

/** What the learner sees for one line in the current round. [japanese] is masked for the learner's lines. */
data class PerformPrompt(
    val lineIndex: Int,
    val speaker: String,
    val isLearner: Boolean,
    /** The Japanese to show, masked with ○ per hidden character; null at [FadeLevel.CUE_ONLY]. */
    val japanese: String?,
    val english: String,
    val stage: String?,
)

/** How one delivered line compared with the script. */
data class LineCheck(val lineIndex: Int, val similarity: Double, val passed: Boolean, val expected: String, val given: String, val selfRated: Boolean = false)

/**
 * Memorize-and-perform (BRIEF_V2 §6.5 Performing culture; Nihongo Now!-style): the learner plays [PerformDrill.learner]
 * through the script round after round, and the prompts for their lines fade FULL → HALF → INITIAL → CUE_ONLY. A
 * round is passed when every learner line was delivered well (speech transcript or typed text at ≥ [PASS] similarity
 * over kana-folded text, or a self-rating "I said it"); passing fades the next round one step. Evaluation stays
 * on-device: the transcript comes from the platform STT, and an AI check is optional on top.
 */
class PerformanceSession(val drill: PerformDrill, start: FadeLevel = FadeLevel.FULL) {
    var level: FadeLevel = start
        private set
    var round: Int = 0
        private set
    private val checks = HashMap<Int, LineCheck>()

    /** True once the CUE_ONLY round has been passed. */
    var finished: Boolean = false
        private set

    /** Every line of the script as the learner should see it this round (partner lines always in full). */
    fun prompts(): List<PerformPrompt> = drill.lines.mapIndexed { i, line ->
        val mine = line.speaker == drill.learner
        PerformPrompt(i, line.speaker, mine, if (mine) mask(line.ja, level) else line.ja, line.en, line.stage)
    }

    /** Grades the learner's delivery of line [lineIndex] (an STT transcript or typed text). */
    fun deliver(lineIndex: Int, given: String): LineCheck {
        val expected = drill.lines[lineIndex].ja
        require(drill.lines[lineIndex].speaker == drill.learner) { "line $lineIndex is not the learner's" }
        val sim = SubtitleQuiz.similarity(expected, given)
        return LineCheck(lineIndex, sim, sim >= PASS, expected, given).also { checks[lineIndex] = it }
    }

    /** Self-check without speech recognition: the learner compares with the script and says whether they got it. */
    fun selfRate(lineIndex: Int, gotIt: Boolean): LineCheck {
        val expected = drill.lines[lineIndex].ja
        return LineCheck(lineIndex, if (gotIt) 1.0 else 0.0, gotIt, expected, "", selfRated = true).also { checks[lineIndex] = it }
    }

    fun check(lineIndex: Int): LineCheck? = checks[lineIndex]

    /** Every learner line delivered and passed this round. */
    val roundPassed: Boolean get() = drill.learnerLines.all { checks[it]?.passed == true }

    /**
     * Ends the round: a passed round fades the prompts one level (or finishes after CUE_ONLY); a missed one repeats
     * at the same level. Returns the new level.
     */
    fun nextRound(): FadeLevel {
        if (roundPassed) {
            if (level == FadeLevel.CUE_ONLY) finished = true else level = FadeLevel.entries[level.ordinal + 1]
        }
        checks.clear()
        round++
        return level
    }

    companion object {
        const val PASS = 0.8
        const val MASK = '○'
        private val BREAKS = setOf('、', '。', '！', '？', '　', ' ', '「', '」', '…')

        /** [ja] with the tail of each phrase (split at punctuation) hidden for [level]; punctuation always shows. */
        fun mask(ja: String, level: FadeLevel): String? {
            if (level == FadeLevel.CUE_ONLY) return null
            if (level == FadeLevel.FULL) return ja
            val out = StringBuilder()
            var phrase = StringBuilder()
            fun flush() {
                val keep = when (level) {
                    FadeLevel.HALF -> (phrase.length + 1) / 2
                    else -> minOf(1, phrase.length)
                }
                phrase.forEachIndexed { i, c -> out.append(if (i < keep) c else MASK) }
                phrase = StringBuilder()
            }
            for (c in ja) {
                if (c in BREAKS) {
                    flush()
                    out.append(c)
                } else {
                    phrase.append(c)
                }
            }
            flush()
            return out.toString()
        }
    }
}
