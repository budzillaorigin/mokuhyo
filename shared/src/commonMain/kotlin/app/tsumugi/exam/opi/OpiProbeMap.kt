package app.tsumugi.exam.opi

import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.practice.OpiDomain

/** How an answer held up against the level its question aimed at (the same length heuristic as the adaptation). */
enum class OpiTurnOutcome {
    /** At least the target level's typical answer length: sustained at that level. */
    SUSTAINED,

    /** Between half and all of it: partly sustained. */
    PARTIAL,

    /** Under half of it: breakdown at the target level. */
    BREAKDOWN,

    /** Warm-up, role-play and wind-down answers aren't rated, and neither is a question left unanswered. */
    NOT_RATED,
    ;

    companion object {
        fun judge(answerLength: Int, target: IlrLevel): OpiTurnOutcome {
            val typical = OpiSession.TYPICAL_LENGTH[target] ?: return NOT_RATED
            return when {
                answerLength >= typical -> SUSTAINED
                answerLength * 2 >= typical -> PARTIAL
                else -> BREAKDOWN
            }
        }
    }
}

/** One interviewer question and what came of it. [answerLength]/[levelAfter]/[outcome] are set once answered. */
data class OpiTurnRecord(
    val index: Int,
    val phase: OpiPhase,
    val question: String,
    /** DLI topic domain of a scripted question; null for model-written questions. */
    val domain: OpiDomain?,
    /** The level the question aimed at: probes one above the working level, everything else at it. */
    val targetLevel: IlrLevel,
    val levelBefore: IlrLevel,
    val answerLength: Int? = null,
    val levelAfter: IlrLevel? = null,
    val outcome: OpiTurnOutcome = OpiTurnOutcome.NOT_RATED,
) {
    val isProbe: Boolean get() = phase == OpiPhase.PROBE
    val isLevelCheck: Boolean get() = phase == OpiPhase.LEVEL_CHECK
    val rated: Boolean get() = outcome != OpiTurnOutcome.NOT_RATED
}

/**
 * The "level check → probe" visualizer data (BRIEF_V2 §6.16): the working level turn by turn, which turns checked the
 * floor and which probed above it, and where speech broke down. Built from [OpiSession.turns]; pure, so the results
 * screen and tests use the same numbers. Like the adaptation it rests on answer length, so it's practice feedback,
 * not a rating.
 */
data class OpiProbeMap(
    val turns: List<OpiTurnRecord>,
    /** Highest level where a level check or probe was sustained (the floor the candidate showed). */
    val floor: IlrLevel?,
    /** Lowest level where a probe or level check broke down (the ceiling the interview found), if any. */
    val ceiling: IlrLevel?,
    /** Rated turns that broke down, in order. */
    val breakdowns: List<OpiTurnRecord>,
    /** Per level that was aimed at: (sustained, partial, breakdown) counts, lowest level first. */
    val byLevel: List<LevelTally>,
    /** Topic domains asked, in order of first use. */
    val domains: List<OpiDomain>,
) {
    data class LevelTally(val level: IlrLevel, val sustained: Int, val partial: Int, val breakdown: Int) {
        val total: Int get() = sustained + partial + breakdown
    }

    /** Working level after each answered turn, for the level line (x = turn index). */
    val levelTrack: List<Pair<Int, IlrLevel>> get() = turns.mapNotNull { t -> t.levelAfter?.let { t.index to it } }

    val probes: List<OpiTurnRecord> get() = turns.filter { it.isProbe }
    val levelChecks: List<OpiTurnRecord> get() = turns.filter { it.isLevelCheck }

    /** DLI domains the interview never reached (for "next time, try …"). */
    val missingDliDomains: List<OpiDomain> get() = OpiDomain.dli.filter { it !in domains }

    companion object {
        fun from(turns: List<OpiTurnRecord>): OpiProbeMap {
            val rated = turns.filter { it.rated }
            val floor = rated.filter { it.outcome == OpiTurnOutcome.SUSTAINED }.maxOfOrNull { it.targetLevel }
            val ceiling = rated.filter { it.outcome == OpiTurnOutcome.BREAKDOWN }.minOfOrNull { it.targetLevel }
            val byLevel = rated.groupBy { it.targetLevel }.entries.sortedBy { it.key.ordinal }.map { (level, list) ->
                LevelTally(
                    level,
                    list.count { it.outcome == OpiTurnOutcome.SUSTAINED },
                    list.count { it.outcome == OpiTurnOutcome.PARTIAL },
                    list.count { it.outcome == OpiTurnOutcome.BREAKDOWN },
                )
            }
            return OpiProbeMap(
                turns = turns,
                floor = floor,
                ceiling = ceiling,
                breakdowns = rated.filter { it.outcome == OpiTurnOutcome.BREAKDOWN },
                byLevel = byLevel,
                domains = turns.mapNotNull { it.domain }.distinct(),
            )
        }
    }
}
