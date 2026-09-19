package app.tsumugi.onomatopoeia

import app.tsumugi.jp.Kana
import kotlin.random.Random

/**
 * The onomatopoeia quiz (BRIEF_V2 §6.8, DECISIONS D-237): pick the word for a described scene, or the scene for a
 * word. Only words with a feel line take part, because the feel line is the scene. It's pure; [OnomatopoeiaRepository]
 * supplies the pool.
 *
 * Distractors never share a gloss or a reading with the target, so near-synonyms (two kinds of rain) don't make two
 * answers right. Where the target's theme has an eligible word, one distractor comes from it so the choice isn't
 * trivial. The rest come from other themes.
 */
object OnomatopoeiaQuiz {
    const val CHOICES = 4

    /**
     * Up to [count] questions over [pool]. [kind] null alternates the two kinds. [theme] limits the targets (distractors
     * may come from anywhere). A target appears at most once per quiz.
     */
    fun questions(
        pool: List<OnomatopoeiaWord>,
        count: Int,
        random: Random = Random.Default,
        kind: OnomatopoeiaQuizKind? = null,
        theme: String? = null,
    ): List<OnomatopoeiaQuestion> {
        val usable = pool.filter { it.hasFeel }.distinctBy { key(it.text) }
        val targets = usable.filter { theme == null || it.theme == theme }.shuffled(random).take(count)
        return targets.mapIndexedNotNull { i, target ->
            val k = kind ?: OnomatopoeiaQuizKind.entries[i % 2]
            question(target, usable, k, random)
        }
    }

    /** One question about [target], or null when [pool] has too few unambiguous distractors. */
    fun question(target: OnomatopoeiaWord, pool: List<OnomatopoeiaWord>, kind: OnomatopoeiaQuizKind, random: Random): OnomatopoeiaQuestion? {
        val eligible = pool.filter { it.hasFeel && it.entryId != target.entryId && !confusable(target, it) }
        val sameTheme = eligible.filter { it.theme == target.theme }.shuffled(random).take(1)
        val others = eligible.filter { it.theme != target.theme }.shuffled(random)
        val distractors = (sameTheme + others).distinctBy { key(it.text) }.distinctBy { it.feel }.take(CHOICES - 1)
        if (distractors.size < CHOICES - 1) return null
        val options = (distractors + target).shuffled(random)
        return OnomatopoeiaQuestion(
            kind = kind,
            target = target,
            prompt = if (kind == OnomatopoeiaQuizKind.WORD_FOR_SCENE) target.feel else target.text,
            promptJa = if (kind == OnomatopoeiaQuizKind.WORD_FOR_SCENE) target.feelJa else target.text,
            choices = options.map { if (kind == OnomatopoeiaQuizKind.WORD_FOR_SCENE) it.text else it.feel },
            options = options,
            answer = options.indexOf(target),
        )
    }

    /** Two words are confusable when they share a reading (kana folded) or any gloss. */
    fun confusable(a: OnomatopoeiaWord, b: OnomatopoeiaWord): Boolean {
        val readingsA = (listOf(a.text) + a.variants).map(::key).toSet()
        if ((listOf(b.text) + b.variants).any { key(it) in readingsA }) return true
        val glossesA = a.glosses.map { it.lowercase().trim() }.toSet()
        return b.glosses.any { it.lowercase().trim() in glossesA }
    }

    private fun key(text: String) = Kana.toHiragana(text)
}
