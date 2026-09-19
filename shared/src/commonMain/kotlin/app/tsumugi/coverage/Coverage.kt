package app.tsumugi.coverage

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * How much of a text the learner can read (BRIEF_V2 §6.1 coverage overlay): "You know 74% of the words · 92% of the
 * kanji · 11 new words to reach 95%". Word figures count occurrences of content words (function words never
 * count, D-150); kanji figures count kanji occurrences.
 */
data class TextCoverage(
    val wordTokens: Int,
    val knownTokens: Int,
    val learningTokens: Int,
    val uniqueWords: Int,
    val knownWords: Int,
    val learningWords: Int,
    val kanjiTokens: Int,
    val knownKanjiTokens: Int,
    val uniqueKanji: Int,
    val knownKanji: Int,
    /** Unknown or learning words to learn, most frequent first, to reach [TARGET] word coverage (0 when already there). */
    val newWordsTo95: Int,
) {
    /** Share of word occurrences known (Guru+ or marked known), 0..1. */
    val knownRatio: Double get() = if (wordTokens == 0) 0.0 else knownTokens.toDouble() / wordTokens

    /** Share of word occurrences known or being learned. */
    val knownOrLearningRatio: Double get() = if (wordTokens == 0) 0.0 else (knownTokens + learningTokens).toDouble() / wordTokens

    /** Share of kanji occurrences known, 0..1. */
    val kanjiRatio: Double get() = if (kanjiTokens == 0) 1.0 else knownKanjiTokens.toDouble() / kanjiTokens

    val knownPercent: Int get() = (knownRatio * 100).toInt()
    val kanjiPercent: Int get() = (kanjiRatio * 100).toInt()

    /** "You know 74% of the words · 92% of the kanji · 11 new words to reach 95%" (English; the apps localize). */
    val summary: String
        get() = buildString {
            append("You know $knownPercent% of the words · $kanjiPercent% of the kanji")
            if (newWordsTo95 > 0) append(" · $newWordsTo95 new words to reach 95%")
        }

    companion object {
        const val TARGET = 0.95
    }
}

/** Words needed to reach each coverage level from scratch (media deck statistics, BRIEF_V2 §6.1). */
data class CoverageThresholds(val words80: Int, val words90: Int, val words95: Int, val words98: Int)

/** The coverage arithmetic, pure and shared by the overlay, media decks and frequency decks. */
object CoverageMath {
    /** The learner's coverage of [profile] under [knowledge]. */
    fun coverage(profile: TextProfile, knowledge: KnowledgeSnapshot): TextCoverage {
        var tokens = 0
        var known = 0
        var learning = 0
        var unique = 0
        var knownUnique = 0
        var learningUnique = 0
        val unknownCounts = ArrayList<Int>()
        for (w in profile.words) {
            if (w.function) continue
            tokens += w.count
            unique++
            when (knowledge.word(w.entryId, w.lemma)) {
                WordState.KNOWN -> { known += w.count; knownUnique++ }
                WordState.LEARNING -> { learning += w.count; learningUnique++; unknownCounts += w.count }
                WordState.UNKNOWN -> unknownCounts += w.count
            }
        }
        var kanjiTokens = 0
        var knownKanjiTokens = 0
        var knownKanji = 0
        for ((k, n) in profile.kanji) {
            kanjiTokens += n
            if (knowledge.kanji(k) == WordState.KNOWN) {
                knownKanjiTokens += n
                knownKanji++
            }
        }
        return TextCoverage(
            wordTokens = tokens, knownTokens = known, learningTokens = learning, uniqueWords = unique,
            knownWords = knownUnique, learningWords = learningUnique, kanjiTokens = kanjiTokens,
            knownKanjiTokens = knownKanjiTokens, uniqueKanji = profile.kanji.size, knownKanji = knownKanji,
            newWordsTo95 = wordsToReach(TextCoverage.TARGET, tokens, known, unknownCounts),
        )
    }

    /** How many of the [missing] word counts (learned most frequent first) lift [known] of [total] to [target]. */
    fun wordsToReach(target: Double, total: Int, known: Int, missing: List<Int>): Int {
        if (total == 0) return 0
        val needed = ceil(target * total - 1e-9).toInt()
        var have = known
        if (have >= needed) return 0
        var n = 0
        for (c in missing.sortedDescending()) {
            have += c
            n++
            if (have >= needed) return n
        }
        return n
    }

    /** Words needed for 80/90/95/98% coverage of [profile] starting from nothing. */
    fun thresholds(profile: TextProfile): CoverageThresholds {
        val counts = profile.words.filterNot { it.function }.map { it.count }
        val total = counts.sum()
        fun at(t: Double) = wordsToReach(t, total, 0, counts)
        return CoverageThresholds(at(0.80), at(0.90), at(0.95), at(0.98))
    }

    /**
     * Share of the word occurrences in [profiles] (the learner's own media) that belong to [entryIds] (a deck): "Core
     * 2k covers 81% of your library".
     */
    fun deckCoverageOf(entryIds: Set<Long>, profiles: Collection<TextProfile>): Double {
        var total = 0
        var hit = 0
        for (p in profiles) for (w in p.words) {
            if (w.function) continue
            total += w.count
            if (w.entryId in entryIds) hit += w.count
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    internal fun percent(x: Double): Int = (x * 100).roundToInt()
}
