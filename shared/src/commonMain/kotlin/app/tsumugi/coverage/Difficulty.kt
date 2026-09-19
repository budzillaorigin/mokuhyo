package app.tsumugi.coverage

import app.tsumugi.study.ImmersionCandidate
import app.tsumugi.study.ImmersionDifficulty
import app.tsumugi.study.SimpleImmersionDifficulty
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The §6.4 difficulty of a text (BRIEF_V2): one 0–100 score with a JLPT/ILR label. The formula is documented in
 * docs/CONTENT_PACKS.md "Difficulty score" and calibrated on the DLPT reading bank (DifficultyCalibrationTest).
 *
 * [textScore] is the text alone (vocabulary level, sentence length, kanji density, abstract vocabulary) and sets
 * the label. [score] adds the learner: it blends in the share of unknown words, so the same article scores lower
 * for someone who knows its vocabulary. Without learner data the two are equal.
 */
data class DifficultyScore(
    val score: Int,
    val textScore: Int,
    /** 5..1 (N5…N1); 0 = above N1. */
    val jlpt: Int,
    /** "0+", "1", "1+", "2", "2+", "3". */
    val ilr: String,
    val knownWordRatio: Double?,
    /** Components, each 0..1: JLPT band coverage, sentence length, kanji density, abstract vocabulary. */
    val vocabulary: Double,
    val sentenceLength: Double,
    val kanjiDensity: Double,
    val abstractness: Double,
) {
    /** "N3 · ILR 1+" ("above N1 · ILR 3"). */
    val label: String get() = "${if (jlpt == 0) "above N1" else "N$jlpt"} · ILR $ilr"

    /**
     * Continuous JLPT-like level of the text, for matching texts to learners: the middle of the N5 band is 5.0, the
     * middle of the N3 band 3.0, and so on down to 0.0 (above N1).
     */
    val level: Double get() = (5.5 - DifficultyScorer.bandPosition(textScore)).coerceIn(0.0, 5.0)
}

object DifficultyScorer {
    /** Component weights of the text score (sum 1), calibrated on the DLPT reading bank (D-156). */
    const val W_VOCABULARY = 0.20
    const val W_SENTENCE = 0.45
    const val W_KANJI = 0.05
    const val W_ABSTRACT = 0.30

    /**
     * Lower text-score bounds of the bands N4/ILR 1, N3/1+, N2/2, N1/2+ and above-N1/3 (N5/0+ starts at 0). Midpoints
     * between the DLPT bank's per-level means (DifficultyCalibrationTest prints them).
     */
    val CUTS = listOf(15, 19, 32, 46, 55)

    private val ILR = listOf("0+", "1", "1+", "2", "2+", "3")

    /**
     * Scores [profile]. [knownWordRatio] is the learner's known share of word occurrences
     * ([TextCoverage.knownRatio]); null scores the text alone.
     */
    fun score(profile: TextProfile, knownWordRatio: Double? = null): DifficultyScore {
        val v = vocabularyComponent(profile)
        val s = ((profile.averageSentenceLength - 10.0) / 40.0).coerceIn(0.0, 1.0)
        val k = ((profile.kanjiDensity - 0.25) / 0.25).coerceIn(0.0, 1.0)
        val a = (profile.abstractRatio / 0.08).coerceIn(0.0, 1.0)
        val text = (100 * (W_VOCABULARY * v + W_SENTENCE * s + W_KANJI * k + W_ABSTRACT * a)).roundToInt().coerceIn(0, 100)
        val band = CUTS.count { text >= it }
        val personal = if (knownWordRatio == null) text else {
            val unknown = ((1.0 - knownWordRatio) / 0.30).coerceIn(0.0, 1.0)
            (0.5 * text + 50 * unknown).roundToInt().coerceIn(0, 100)
        }
        return DifficultyScore(
            score = personal, textScore = text, jlpt = 5 - band, ilr = ILR[band], knownWordRatio = knownWordRatio,
            vocabulary = v, sentenceLength = s, kanjiDensity = k, abstractness = a,
        )
    }

    /**
     * Where [textScore] sits on the band scale: band index plus the fraction through the band (0.5 = the middle of the
     * N5 band, 2.5 = the middle of N3). The top band is treated as 15 points wide.
     */
    fun bandPosition(textScore: Int): Double {
        val bounds = listOf(0) + CUTS + (CUTS.last() + 15)
        val band = CUTS.count { textScore >= it }
        val lo = bounds[band]
        val hi = bounds[band + 1]
        return band + ((textScore - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
    }

    /**
     * JLPT band coverage as one number: for each level N5…N1, the share of content-word occurrences *not* on the
     * JLPT lists at that level or easier, averaged. All-N5 text → 0; text whose words are all off-list → 1.
     */
    internal fun vocabularyComponent(profile: TextProfile): Double {
        val content = profile.words.filterNot { it.function }
        val total = content.sumOf { it.count }
        if (total == 0) return 0.0
        return (5 downTo 1).sumOf { level ->
            val covered = content.sumOf { if (it.jlpt != null && it.jlpt >= level) it.count else 0 }
            1.0 - covered.toDouble() / total
        } / 5.0
    }
}

/**
 * Today's immersion matching on the §6.4 score (replaces [SimpleImmersionDifficulty], D-155): the distance between
 * the learner's JLPT level and the text's continuous level, harder texts counting 1.5×, minus half a level when the
 * learner already knows 90%+ of the words. Candidates without a score (dialogues, unprofiled documents) fall back to
 * the simple JLPT distance.
 */
class ScoredImmersionDifficulty(private val scores: Map<String, DifficultyScore>) : ImmersionDifficulty {
    override fun mismatch(candidate: ImmersionCandidate, learnerJlpt: Int): Double {
        val s = scores[candidate.id] ?: return SimpleImmersionDifficulty.mismatch(candidate, learnerJlpt)
        val diff = learnerJlpt - s.level
        var score = if (diff > 0) diff * 1.5 else abs(diff)
        if ((s.knownWordRatio ?: candidate.knownRatio ?: 0.0) >= 0.9) score -= 0.5
        return score.coerceAtLeast(0.0)
    }
}
