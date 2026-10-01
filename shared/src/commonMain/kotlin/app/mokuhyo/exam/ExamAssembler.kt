package app.mokuhyo.exam

import kotlin.random.Random

/** One item on a form, with the score group it counts toward (the ILR level). */
data class FormItem(val item: ExamItem, val group: String, val typeTitle: String)

/** A timed part of a form. [minutes] null = untimed (practice). */
data class FormSection(val title: String, val minutes: Int?, val items: List<FormItem>, val listening: Boolean)

/** Where the pool had fewer items than the blueprint asks for (after excluding recently seen passages). */
data class Shortfall(val level: String, val wanted: Int, val got: Int)

data class ExamForm(
    val exam: ExamKind,
    val language: String,
    val level: String,
    val mode: ExamMode,
    val sections: List<FormSection>,
    val passages: Map<String, ExamPassage>,
    val shortfalls: List<Shortfall>,
) {
    val items: List<FormItem> get() = sections.flatMap { it.items }
    val isEmpty: Boolean get() = items.isEmpty()
    val totalMinutes: Int? get() = sections.mapNotNull { it.minutes }.takeIf { it.size == sections.size }?.sum()
    val passageIds: List<String> get() = items.mapNotNull { it.item.passageId }.distinct()
}

/**
 * Builds test forms and practice sets from a language's item pool (BRIEF §5.1). Pure: the caller supplies the
 * pool, passages and the learner's recently seen passages.
 * - Items sharing a passage stay together, in bank order.
 * - Verified items are preferred over unreviewed ones.
 * - Passages seen in the learner's last N test forms are never reused (a shortfall is reported instead).
 * - Answer keys are balanced: choices are reordered so the correct answers spread evenly over A–D.
 * - Levels run easiest first, as on the real test.
 */
object ExamAssembler {
    fun test(
        blueprint: ExamBlueprint,
        skill: Skill,
        length: FormLength,
        pool: List<ExamItem>,
        passages: Map<String, ExamPassage>,
        recentPassages: Set<String>,
        random: Random,
    ): ExamForm {
        val section = blueprint.section(skill)
        val wanted = section.itemsFor(length)
        val byLevel = pool.filter { it.exam == skill.exam && it.passageId !in recentPassages }.groupBy { it.level }
        val shortfalls = mutableListOf<Shortfall>()
        val picked = section.levels.flatMap { level ->
            val n = wanted[level] ?: 0
            val got = pick(byLevel[level].orEmpty(), n, random)
            if (got.size < n) shortfalls += Shortfall(level, n, got.size)
            got.map { FormItem(it, level, "ILR $level") }
        }
        val balanced = balanceKeys(picked, random)
        val formSection = FormSection(skill.exam.title, section.minutesFor(length), balanced, skill == Skill.LISTENING)
        return ExamForm(
            skill.exam, blueprint.language, "", length.mode, listOf(formSection).filter { it.items.isNotEmpty() },
            passagesFor(balanced.map { it.item }, passages), shortfalls,
        )
    }

    /**
     * A practice set: [passageCount] passage groups at [level] (optionally one [textType]), untimed, unseen passages
     * first (passages in [seen] only when nothing new is left).
     */
    fun practice(
        language: String,
        skill: Skill,
        level: String,
        textType: String?,
        pool: List<ExamItem>,
        passages: Map<String, ExamPassage>,
        seen: Set<String>,
        random: Random,
        passageCount: Int = 1,
    ): ExamForm {
        val candidates = pool.filter { it.exam == skill.exam && it.level == level && (textType == null || passages[it.passageId]?.textType == textType) }
        val groups = candidates.groupBy { it.passageId ?: "item:${it.id}" }
        val fresh = groups.filterKeys { it !in seen }.ifEmpty { groups }
        val chosen = fresh.values.shuffled(random).sortedByDescending { g -> g.all { it.verified } }.take(passageCount).flatten()
        val items = balanceKeys(chosen.map { FormItem(it, level, "ILR $level") }, random)
        val section = FormSection("${skill.exam.title} practice", null, items, skill == Skill.LISTENING)
        return ExamForm(skill.exam, language, level, ExamMode.PRACTICE, listOf(section).filter { it.items.isNotEmpty() }, passagesFor(chosen, passages), emptyList())
    }

    /** Text types in [pool] for [skill] at [level], most items first (the practice filter's options). */
    fun textTypes(skill: Skill, level: String, pool: List<ExamItem>, passages: Map<String, ExamPassage>): List<Pair<String, Int>> =
        pool.filter { it.exam == skill.exam && it.level == level }.mapNotNull { passages[it.passageId]?.textType }
            .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /**
     * Picks up to [count] items, keeping passage groups whole. A group is skipped when it would overshoot, unless
     * nothing fits yet (a long passage with more questions than asked is still better than none).
     */
    internal fun pick(candidates: List<ExamItem>, count: Int, random: Random): List<ExamItem> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val groups = candidates.groupBy { it.passageId ?: "item:${it.id}" }.values
            .shuffled(random)
            .sortedByDescending { group -> group.all { it.verified } }
        val picked = mutableListOf<ExamItem>()
        for (group in groups) {
            if (picked.size >= count) break
            if (picked.size + group.size <= count || picked.isEmpty()) picked += group
        }
        return picked
    }

    /**
     * Reorders each item's choices so correct answers are spread evenly over the positions (BRIEF §5.1 "balances key
     * positions"): target positions are dealt round-robin in a shuffled order, and the right answer is swapped there.
     */
    fun balanceKeys(items: List<FormItem>, random: Random): List<FormItem> {
        val deck = ArrayList<Int>()
        return items.map { f ->
            val n = f.item.choices.size
            if (n < 2) return@map f
            if (deck.isEmpty()) deck += (0 until 4).shuffled(random)
            val target = deck.removeAt(0).coerceAtMost(n - 1)
            val choices = f.item.choices.toMutableList()
            val current = f.item.answer
            if (current != target) {
                val tmp = choices[target]
                choices[target] = choices[current]
                choices[current] = tmp
            }
            f.copy(item = f.item.copy(choices = choices, answer = target))
        }
    }

    private fun passagesFor(items: List<ExamItem>, passages: Map<String, ExamPassage>): Map<String, ExamPassage> =
        items.mapNotNull { it.passageId }.distinct().mapNotNull { id -> passages[id]?.let { id to it } }.toMap()
}
