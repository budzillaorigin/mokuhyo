package app.tsumugi.exam.jlpt

import kotlin.math.roundToInt

/**
 * JLPT scaled scoring (BRIEF §5.11). The real JLPT equates raw scores per administration and never publishes
 * the tables, so this is a documented linear approximation: each score group's scaled score is
 * `round(correct / administered × groupMax)`. Groups are 0–60 each (N1–N3: language, reading, listening);
 * N4/N5 combine language knowledge and reading into one 0–120 group. Pass = total ≥ the level's pass mark and
 * every group ≥ its sectional minimum (19, or 38 for the combined group). An attempt missing a whole group
 * (a drill) is scored but never reported as a pass.
 */
object JlptScoring {
    const val LISTENING = "listening"
    const val LANGUAGE_READING = "language_reading"

    data class Answer(val type: String, val group: String, val correct: Boolean)

    data class GroupScore(
        val group: String,
        val correct: Int,
        val administered: Int,
        val scaled: Int,
        val scaledMax: Int,
        val minimum: Int,
    ) {
        val taken: Boolean get() = administered > 0
        val metMinimum: Boolean get() = taken && scaled >= minimum
    }

    data class TypeScore(val type: String, val correct: Int, val total: Int) {
        val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    }

    data class Score(
        val level: Int,
        val groups: List<GroupScore>,
        val byType: List<TypeScore>,
        val passMark: Int,
    ) {
        val total: Int get() = groups.sumOf { it.scaled }
        val complete: Boolean get() = groups.all { it.taken }
        val passed: Boolean get() = complete && total >= passMark && groups.all { it.metMinimum }
    }

    fun scale(raw: Int, max: Int, scaledMax: Int): Int = if (max <= 0) 0 else (raw.toDouble() / max * scaledMax).roundToInt()

    fun score(blueprint: LevelBlueprint, answers: List<Answer>, rules: ScoringRules = ScoringRules()): Score {
        val byGroup = answers.groupBy { it.group }
        val groups = blueprint.groups.map { group ->
            val list = byGroup[group].orEmpty()
            val combined = group == LANGUAGE_READING
            val max = if (combined) rules.combinedMax else rules.sectionMax
            val correct = list.count { it.correct }
            GroupScore(
                group = group,
                correct = correct,
                administered = list.size,
                scaled = scale(correct, list.size, max),
                scaledMax = max,
                minimum = if (combined) rules.combinedMinimum else rules.sectionMinimum,
            )
        }
        val typeOrder = blueprint.sections.flatMap { s -> s.items.map { it.type } }
        val byType = answers.groupBy { it.type }
            .map { (type, list) -> TypeScore(type, list.count { it.correct }, list.size) }
            .sortedBy { typeOrder.indexOf(it.type).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return Score(blueprint.level, groups, byType, blueprint.passMark)
    }
}
