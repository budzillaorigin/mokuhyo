package app.tsumugi.exam

import app.tsumugi.exam.dlpt.DlptRange
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.jlpt.JlptItemType
import app.tsumugi.exam.jlpt.LevelBlueprint
import app.tsumugi.exam.jlpt.SectionBlueprint
import kotlin.random.Random

/** One item on a form, with the score group it counts toward (JLPT group, or the ILR level for DLPT). */
data class FormItem(val item: ExamItem, val group: String, val typeTitle: String)

/** A timed part of a form. [minutes] null = untimed (drills). */
data class FormSection(val title: String, val minutes: Int?, val items: List<FormItem>, val listening: Boolean)

/** Where the bank had fewer items than the blueprint asks for. */
data class Shortfall(val type: String, val wanted: Int, val got: Int)

data class ExamForm(
    val exam: ExamKind,
    val level: String,
    val mode: ExamMode,
    val sections: List<FormSection>,
    val passages: Map<String, ExamPassage>,
    val shortfalls: List<Shortfall>,
) {
    val items: List<FormItem> get() = sections.flatMap { it.items }
    val isEmpty: Boolean get() = items.isEmpty()
    val totalMinutes: Int? get() = sections.mapNotNull { it.minutes }.takeIf { it.size == sections.size }?.sum()
}

/**
 * Builds test forms from the item pool (BRIEF §5.11 modes). Items that share a passage are always kept
 * together, in bank order; verified items are preferred over unreviewed ones. Pure: the caller fetches
 * the pool and passages.
 */
object ExamAssembler {
    const val TYPE_DRILL_SIZE = 10
    const val DLPT_MINUTES_PER_ITEM = 3
    const val DLPT_FULL_MINUTES = 180

    fun jlptMock(level: Int, blueprint: LevelBlueprint, pool: List<ExamItem>, passages: Map<String, ExamPassage>, random: Random): ExamForm =
        jlpt(level, ExamMode.MOCK, blueprint.sections, pool, passages, random)

    fun jlptSection(level: Int, blueprint: LevelBlueprint, sectionId: String, pool: List<ExamItem>, passages: Map<String, ExamPassage>, random: Random): ExamForm =
        jlpt(level, ExamMode.SECTION, blueprint.sections.filter { it.id == sectionId }, pool, passages, random)

    fun jlptTypeDrill(level: Int, blueprint: LevelBlueprint, type: String, pool: List<ExamItem>, passages: Map<String, ExamPassage>, random: Random, size: Int = TYPE_DRILL_SIZE): ExamForm {
        val spec = blueprint.sections.flatMap { it.items }.firstOrNull { it.type == type }
        val group = spec?.group ?: "language"
        val kind = JlptItemType.of(type)
        val picked = pick(pool.filter { it.type == type }, size, random)
        val section = FormSection(spec?.title ?: kind?.title ?: type, null, picked.map { FormItem(it, group, spec?.title ?: type) }, kind?.listening == true)
        return ExamForm(
            ExamKind.JLPT, "N$level", ExamMode.TYPE, listOf(section), passagesFor(picked, passages),
            if (picked.size < size) listOf(Shortfall(type, size, picked.size)) else emptyList(),
        )
    }

    private fun jlpt(
        level: Int,
        mode: ExamMode,
        sections: List<SectionBlueprint>,
        pool: List<ExamItem>,
        passages: Map<String, ExamPassage>,
        random: Random,
    ): ExamForm {
        val byType = pool.groupBy { it.type }
        val shortfalls = mutableListOf<Shortfall>()
        val formSections = sections.map { section ->
            val items = section.items.flatMap { spec ->
                val picked = pick(byType[spec.type].orEmpty(), spec.count, random)
                if (picked.size < spec.count) shortfalls += Shortfall(spec.type, spec.count, picked.size)
                picked.map { FormItem(it, spec.group, spec.title) }
            }
            FormSection(section.title, section.minutes, items, section.isListening)
        }.filter { it.items.isNotEmpty() }
        return ExamForm(ExamKind.JLPT, "N$level", mode, formSections, passagesFor(formSections.flatMap { s -> s.items.map { it.item } }, passages), shortfalls)
    }

    /**
     * DLPT form: [minutes] / 3 items spread evenly over the [range]'s ILR levels (lower 0+–3, upper 3–4), easiest
     * first (like the real test). Mode follows the length: 180 → FULL, 60 → SLICE_60, anything shorter → SLICE_30.
     *
     * [textTypes] (BRIEF_V2 §6.16) keeps only items whose passage has one of those text types (news, editorial,
     * liaison, …); empty = all. Levels the filter leaves empty are skipped, so a narrow filter gives a shorter form
     * and the shortfalls say where.
     */
    fun dlpt(
        exam: ExamKind,
        minutes: Int,
        pool: List<ExamItem>,
        passages: Map<String, ExamPassage>,
        random: Random,
        range: DlptRange = DlptRange.LOWER,
        textTypes: Set<String> = emptySet(),
    ): ExamForm {
        require(exam.isDlpt)
        val target = (minutes / DLPT_MINUTES_PER_ITEM).coerceAtLeast(1)
        val byLevel = filterByTextType(pool, passages, textTypes).groupBy { IlrLevel.parse(it.level) }
        val levels = range.levels.filter { !byLevel[it].isNullOrEmpty() }
        val shortfalls = mutableListOf<Shortfall>()
        val items = if (levels.isEmpty()) emptyList() else {
            val per = (target + levels.size - 1) / levels.size
            levels.flatMap { level ->
                val picked = pick(byLevel.getValue(level), per, random)
                if (picked.size < per) shortfalls += Shortfall(level.label, per, picked.size)
                picked.map { FormItem(it, level.label, "ILR ${level.label}") }
            }
        }
        val mode = when {
            minutes >= DLPT_FULL_MINUTES -> ExamMode.FULL
            minutes >= 60 -> ExamMode.SLICE_60
            else -> ExamMode.SLICE_30
        }
        val section = FormSection(exam.title, minutes, items, exam == ExamKind.DLPT_LISTENING)
        val level = if (range == DlptRange.LOWER) "" else range.name
        return ExamForm(exam, level, mode, listOf(section).filter { it.items.isNotEmpty() }, passagesFor(items.map { it.item }, passages), shortfalls)
    }

    /** Items whose passage's text type is in [textTypes] (all items when it's empty). Items without a passage go. */
    fun filterByTextType(pool: List<ExamItem>, passages: Map<String, ExamPassage>, textTypes: Set<String>): List<ExamItem> =
        if (textTypes.isEmpty()) pool else pool.filter { item -> passages[item.passageId]?.textType in textTypes }

    /** Text types in [pool] with how many items each has, most items first (the DLPT text-type filter's options). */
    fun textTypeCounts(pool: List<ExamItem>, passages: Map<String, ExamPassage>): List<Pair<String, Int>> =
        pool.mapNotNull { passages[it.passageId]?.textType?.takeIf { t -> t.isNotBlank() } }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /**
     * Picks up to [count] items, keeping passage groups whole. A group is skipped when it would overshoot,
     * unless nothing fits yet (a long passage with more questions than asked is still better than none).
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

    private fun passagesFor(items: List<ExamItem>, passages: Map<String, ExamPassage>): Map<String, ExamPassage> =
        items.mapNotNull { it.passageId }.distinct().mapNotNull { id -> passages[id]?.let { id to it } }.toMap()
}
