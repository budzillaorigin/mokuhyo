package app.mokuhyo.opi

import app.mokuhyo.exam.IlrLevel

/**
 * The "level check → probe" picture shown after an interview: the working level turn by turn, where answers were
 * sustained and where speech broke down. Built from [OpiSession.turns]; it rests on the same length heuristic as the
 * adaptation, so it is practice feedback, not a rating.
 */
data class OpiProbeMap(
    val turns: List<OpiTurnRecord>,
    val floor: IlrLevel?,
    val ceiling: IlrLevel?,
    val breakdowns: List<OpiTurnRecord>,
    val byLevel: List<LevelTally>,
    val domains: List<String>,
) {
    data class LevelTally(val level: IlrLevel, val sustained: Int, val partial: Int, val breakdown: Int) {
        val total: Int get() = sustained + partial + breakdown
    }

    val levelTrack: List<Pair<Int, IlrLevel>> get() = turns.mapNotNull { t -> t.levelAfter?.let { t.index to it } }

    companion object {
        fun from(turns: List<OpiTurnRecord>): OpiProbeMap {
            val rated = turns.filter { it.rated }
            return OpiProbeMap(
                turns = turns,
                floor = rated.filter { it.outcome == OpiTurnOutcome.SUSTAINED }.maxOfOrNull { it.targetLevel },
                ceiling = rated.filter { it.outcome == OpiTurnOutcome.BREAKDOWN }.minOfOrNull { it.targetLevel },
                breakdowns = rated.filter { it.outcome == OpiTurnOutcome.BREAKDOWN },
                byLevel = rated.groupBy { it.targetLevel }.entries.sortedBy { it.key.ordinal }.map { (level, list) ->
                    LevelTally(level, list.count { it.outcome == OpiTurnOutcome.SUSTAINED }, list.count { it.outcome == OpiTurnOutcome.PARTIAL },
                        list.count { it.outcome == OpiTurnOutcome.BREAKDOWN })
                },
                domains = turns.mapNotNull { it.domain }.distinct(),
            )
        }
    }
}
