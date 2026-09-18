package app.tsumugi.study.activities

import app.tsumugi.practice.Dialogue
import app.tsumugi.practice.DialogueLine
import app.tsumugi.practice.Gap
import app.tsumugi.practice.MinimalPair
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.practice.Scenario
import app.tsumugi.practice.Speaker
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Mini-activities for speaking sessions (BRIEF §5.10 "Sessions", PomoSpeak/Laddrr games). Each one is small and
 * self-contained; the UI renders it and reports [ActivityResult]. Everything here works without an AI model.
 */
sealed interface Activity {
    val title: String

    /** Say a few turns of a scenario role-play (uses the model when set up, scripted turns otherwise). */
    data class RoleplayTurn(val scenario: Scenario) : Activity {
        override val title get() = "Role-play: ${scenario.titleEn}"
    }

    /** Listen to a dialogue line and repeat it (shadowing); scored by the pronunciation panel. */
    data class SentenceRepeat(val line: DialogueLine, val speaker: Speaker?) : Activity {
        override val title get() = "Repeat after me"
    }

    /** Hear one word of a minimal pair and pick which one it was. [playA] says which word the app speaks. */
    data class WhatDoYouHear(val pair: MinimalPair, val playA: Boolean) : Activity {
        override val title get() = "What do you hear?"
        val answer: String get() = if (playA) pair.a.text else pair.b.text
    }

    /** Hear a line and fill the missing word from four choices. */
    data class PickAWord(val line: DialogueLine, val speaker: Speaker?, val gap: Gap, val choices: List<String>) : Activity {
        override val title get() = "Pick the word"
        val prompt: String get() = line.japanese.substring(0, gap.start) + "＿＿" + line.japanese.substring(gap.end)
    }

    /** Listen to a whole short dialogue, then answer its comprehension questions. */
    data class StoryTime(val dialogue: Dialogue) : Activity {
        override val title get() = "Story time: ${dialogue.title}"
    }
}

data class ActivityResult(val activity: Activity, val correct: Boolean?, val score: Int? = null)

/**
 * A Pomodoro speaking session: a work block with a queue of activities, then a break. Time is the budget; the
 * queue is longer than the block so there is always a next activity.
 */
class PomodoroSession(
    val activities: List<Activity>,
    val work: Duration = 25.minutes,
    val rest: Duration = 5.minutes,
    private val clock: Clock = Clock.System,
) {
    val startedAt: Instant = clock.now()
    private val results = mutableListOf<ActivityResult>()
    var index: Int = 0
        private set

    val current: Activity? get() = if (onBreak) null else activities.getOrNull(index)
    val completed: List<ActivityResult> get() = results.toList()
    val remaining: Duration get() = (work - (clock.now() - startedAt)).coerceAtLeast(Duration.ZERO)
    val onBreak: Boolean get() = remaining == Duration.ZERO
    val breakRemaining: Duration get() = (work + rest - (clock.now() - startedAt)).coerceAtLeast(Duration.ZERO)
    val finished: Boolean get() = onBreak || index >= activities.size

    fun complete(correct: Boolean?, score: Int? = null) {
        val activity = current ?: return
        results += ActivityResult(activity, correct, score)
        index++
    }

    fun skip() {
        if (current != null) index++
    }

    /** Share of scored activities answered right (null before any). */
    val accuracy: Double? get() = results.mapNotNull { it.correct }.takeIf { it.isNotEmpty() }?.let { r -> r.count { it }.toDouble() / r.size }

    companion object {
        /**
         * Builds a mixed queue at [jlpt] from the practice pack: roughly one role-play, then alternating listening,
         * repeat and pick-a-word activities with minimal pairs sprinkled in. Returns null without the pack.
         */
        @Throws(Exception::class)
        suspend fun build(practice: PracticeRepository?, jlpt: Int, random: Random = Random.Default, work: Duration = 25.minutes, clock: Clock = Clock.System): PomodoroSession? {
            practice ?: return null
            val scenarios = practice.scenarios(jlpt).ifEmpty { practice.scenarios() }
            val dialogues = practice.dialogues(jlpt).ifEmpty { practice.dialogues() }.shuffled(random).take(4)
                .mapNotNull { practice.dialogue(it.id) }
            val pairs = practice.minimalPairs(limit = 200).shuffled(random).take(6)
            val queue = mutableListOf<Activity>()
            scenarios.randomOrNull(random)?.let { queue += Activity.RoleplayTurn(it) }
            val words = dialogues.flatMap { d -> d.lines.flatMap { l -> l.gaps.map { it.text } } }.distinct()
            for ((i, dialogue) in dialogues.withIndex()) {
                val lines = dialogue.lines.shuffled(random)
                lines.firstOrNull()?.let { queue += Activity.SentenceRepeat(it, dialogue.speaker(it.speaker)) }
                lines.firstOrNull { it.gaps.isNotEmpty() }?.let { line ->
                    val gap = line.gaps.random(random)
                    val distractors = words.filter { it != gap.text }.shuffled(random).take(3)
                    if (distractors.size == 3) {
                        queue += Activity.PickAWord(line, dialogue.speaker(line.speaker), gap, (distractors + gap.text).shuffled(random))
                    }
                }
                pairs.getOrNull(i * 2)?.let { queue += Activity.WhatDoYouHear(it, random.nextBoolean()) }
                pairs.getOrNull(i * 2 + 1)?.let { queue += Activity.WhatDoYouHear(it, random.nextBoolean()) }
                if (dialogue.questions.isNotEmpty() && i % 2 == 1) queue += Activity.StoryTime(dialogue)
            }
            return PomodoroSession(queue, work, clock = clock)
        }
    }
}
