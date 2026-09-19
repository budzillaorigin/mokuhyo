package app.tsumugi.coverage

import app.tsumugi.reader.ReaderSentence
import kotlin.math.abs
import kotlin.math.ln

/**
 * A sentence with exactly one unknown word (BRIEF_V2 §6.11 "1T" mining): everything else in it is known or being
 * learned, so learning [target] makes the whole sentence readable. Offsets: [start]/[end] in the document body (or
 * the joined subtitle text); [targetStart]/[targetEnd] inside [text]. Subtitle cues also carry [cueIndex] and times.
 */
data class OneTargetSentence(
    val text: String,
    val start: Int,
    val end: Int,
    val target: String,
    val targetLemma: String,
    val targetReading: String?,
    val entryId: Long,
    val targetStart: Int,
    val targetEnd: Int,
    /** Occurrences of the target word in the whole text: frequent words are worth mining first. */
    val occurrences: Int,
    val score: Double,
    val cueIndex: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** Finds and ranks 1T sentences; pure, given the analyzed sentences, the text's profile and the learner. */
object OneTargetFinder {
    /**
     * The 1T sentences among [sentences], one per target word (its best sentence), best first. Only dictionary
     * content words count: particles and auxiliaries ([ProfileWord.function]) and non-dictionary tokens (names the
     * dictionary doesn't know, symbols, numbers) never make a sentence "unknown". A sentence needs at least two content
     * words, so a lone word isn't offered as a sentence.
     */
    fun find(sentences: List<ReaderSentence>, profile: TextProfile, knowledge: KnowledgeSnapshot, limit: Int = 50): List<OneTargetSentence> {
        val words = profile.words.associateBy { it.entryId }
        val best = HashMap<Long, OneTargetSentence>()
        for (s in sentences) {
            val content = s.tokens.filter { t -> t.entryId != null && words[t.entryId]?.function != true }
            if (content.size < MIN_CONTENT_WORDS) continue
            val unknown = content.filter { t -> knowledge.word(t.entryId, t.dictionaryForm, t.surface) == WordState.UNKNOWN }
            if (unknown.map { it.entryId }.distinct().size != 1) continue
            val t = unknown.first()
            val id = t.entryId!!
            val info = words[id]
            val occurrences = info?.count ?: 1
            val length = s.text.count(TextProfiler::isJapanese)
            val score = 2.0 * ln(1.0 + occurrences) + globalWeight(info?.rank ?: ProfileWord.UNRANKED) - lengthPenalty(length)
            val candidate = OneTargetSentence(
                text = s.text.trim(), start = s.start, end = s.end, target = t.surface,
                targetLemma = t.dictionaryForm ?: t.surface, targetReading = t.lemmaReading ?: t.reading, entryId = id,
                targetStart = t.start - s.start - leadingSpace(s.text), targetEnd = t.end - s.start - leadingSpace(s.text),
                occurrences = occurrences, score = score,
            )
            val current = best[id]
            if (current == null || candidate.score > current.score) best[id] = candidate
        }
        return best.values.sortedWith(compareByDescending<OneTargetSentence> { it.score }.thenBy { it.start }).take(limit)
    }

    /** Global frequency weight from the dictionary rank: Tatoeba occurrences on a log scale, halved for rare words. */
    internal fun globalWeight(rank: Long): Double {
        val tatoeba = ProfileWord.tatoebaCount(rank)
        val bucketPenalty = if (ProfileWord.isCommonRank(rank)) 1.0 else 0.5
        return bucketPenalty * ln(1.0 + tatoeba) / 2.0
    }

    /** Sentences of 8–30 Japanese characters are ideal cards; shorter or longer ones lose a little. */
    private fun lengthPenalty(length: Int): Double = when {
        length < IDEAL_MIN -> (IDEAL_MIN - length) * 0.15
        length > IDEAL_MAX -> abs(length - IDEAL_MAX) * 0.05
        else -> 0.0
    }

    private fun leadingSpace(text: String): Int = text.length - text.trimStart().length

    private const val MIN_CONTENT_WORDS = 2
    private const val IDEAL_MIN = 8
    private const val IDEAL_MAX = 30
}
