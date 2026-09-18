package app.tsumugi.exam.dlpt

/** ILR proficiency levels used by DLPT practice and the OPI simulator (lower range + upper-range tail). */
enum class IlrLevel(val label: String) {
    L0("0"),
    L0_PLUS("0+"),
    L1("1"),
    L1_PLUS("1+"),
    L2("2"),
    L2_PLUS("2+"),
    L3("3"),
    L3_PLUS("3+"),
    L4("4"),
    ;

    companion object {
        private val byLabel = entries.associateBy { it.label }
        fun parse(label: String?): IlrLevel? = label?.trim()?.let { byLabel[it] }

        /** Levels the lower-range DLPT practice packs cover. */
        val lowerRange: List<IlrLevel> = listOf(L0_PLUS, L1, L1_PLUS, L2, L2_PLUS, L3)
    }
}

/**
 * ILR estimate for a DLPT practice attempt (BRIEF §5.11): the highest level L where
 * - accuracy on items written at L is ≥ 70% over at least [minPerLevel] items,
 * - accuracy over all items at L and below is ≥ 70% across at least [minSustained] items (the "sustained" rule),
 * - every lower level with at least [minPerLevel] items stays at ≥ 60% (the floor of consistent performance).
 *
 * If no level meets the 20-item rule (short slices), [Estimate.provisional] reports what the same rule gives
 * without it, flagged as not confident. Practice only; not the DLPT's own scoring (see DECISIONS D-029).
 */
object IlrEstimator {
    const val THRESHOLD = 0.7
    const val FLOOR = 0.6
    const val MIN_SUSTAINED = 20
    const val MIN_PER_LEVEL = 5
    const val CONFIDENT_PER_LEVEL = 10

    data class Result(val level: IlrLevel, val correct: Boolean, val textType: String, val timeMs: Long)

    data class Tally(val key: String, val correct: Int, val total: Int) {
        val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    }

    data class Estimate(
        val level: IlrLevel?,
        val provisional: IlrLevel?,
        val confident: Boolean,
        val byLevel: List<Tally>,
        val byTextType: List<Tally>,
        val meanTimeMs: Long,
        val weakTextTypes: List<String>,
    )

    fun estimate(
        results: List<Result>,
        minSustained: Int = MIN_SUSTAINED,
        minPerLevel: Int = MIN_PER_LEVEL,
    ): Estimate {
        val byLevel = results.groupBy { it.level }
        val levels = IlrLevel.entries.filter { byLevel.containsKey(it) }
        val tallies = levels.map { l -> byLevel.getValue(l).let { Tally(l.label, it.count { r -> r.correct }, it.size) } }

        fun qualifies(level: IlrLevel, sustained: Int): Boolean {
            val at = byLevel[level].orEmpty()
            if (at.size < minPerLevel || at.count { it.correct } < THRESHOLD * at.size) return false
            val cumulative = results.filter { it.level <= level }
            if (cumulative.size < sustained || cumulative.count { it.correct } < THRESHOLD * cumulative.size) return false
            return levels.filter { it < level }.all { lower ->
                val list = byLevel.getValue(lower)
                list.size < minPerLevel || list.count { it.correct } >= FLOOR * list.size
            }
        }

        val level = levels.lastOrNull { qualifies(it, minSustained) }
        val provisional = level ?: levels.lastOrNull { qualifies(it, 0) }
        val byText = results.groupBy { it.textType }
            .map { (type, list) -> Tally(type, list.count { it.correct }, list.size) }
            .sortedBy { it.key }
        return Estimate(
            level = level,
            provisional = provisional,
            confident = level != null && byLevel.getValue(level).size >= CONFIDENT_PER_LEVEL,
            byLevel = tallies,
            byTextType = byText,
            meanTimeMs = if (results.isEmpty()) 0 else results.sumOf { it.timeMs } / results.size,
            weakTextTypes = byText.filter { it.total >= 3 && it.accuracy < FLOOR }.map { it.key },
        )
    }
}
