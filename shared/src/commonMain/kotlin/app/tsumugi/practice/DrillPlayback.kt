package app.tsumugi.practice

import kotlin.math.roundToLong

/**
 * Timing for hands-free speaking drills (BRIEF_V2 §6.10, Swotter format): prompt → pause (the learner answers aloud)
 * → model answer → pause (the learner repeats it) → next item. The platform plays the steps (English prompt with
 * system TTS, the answer from the audio pack or TTS, silences in between); the timing lives here so both apps agree.
 *
 * [PauseMode.PROPORTIONAL] scales the answer pause to the length of the model answer, so a long sentence gets more
 * time than a short one; [PauseMode.FIXED] uses [fixedPauseMs] for every item.
 */
data class DrillTiming(
    val pauseMode: PauseMode = PauseMode.PROPORTIONAL,
    /** FIXED: the answer pause for every item. */
    val fixedPauseMs: Long = 4_000,
    /** PROPORTIONAL: answer pause = answer duration × [pauseFactor] + [basePauseMs], clamped to min/max. */
    val pauseFactor: Double = 1.5,
    val basePauseMs: Long = 1_000,
    val minPauseMs: Long = 1_500,
    val maxPauseMs: Long = 15_000,
    /** Pause after the model answer for the learner to repeat it; off = go straight to the next item. */
    val repeat: Boolean = true,
    /** Repeat pause = answer duration × [repeatFactor] + [basePauseMs]/2, clamped to min/max. */
    val repeatFactor: Double = 1.2,
    /** Silence between items. */
    val gapMs: Long = 800,
) {
    enum class PauseMode { FIXED, PROPORTIONAL }

    init {
        require(fixedPauseMs >= 0 && basePauseMs >= 0 && gapMs >= 0) { "durations must not be negative" }
        require(minPauseMs in 0..maxPauseMs) { "minPauseMs must be between 0 and maxPauseMs" }
        require(pauseFactor >= 0 && repeatFactor >= 0) { "factors must not be negative" }
    }

    /** Silence for answering an item whose model answer lasts [answerMs]. */
    fun answerPause(answerMs: Long): Long = when (pauseMode) {
        PauseMode.FIXED -> fixedPauseMs
        PauseMode.PROPORTIONAL -> (answerMs * pauseFactor + basePauseMs).roundToLong().coerceIn(minPauseMs, maxPauseMs)
    }

    /** Silence for repeating the model answer (0 when [repeat] is off). */
    fun repeatPause(answerMs: Long): Long =
        if (!repeat) 0 else (answerMs * repeatFactor + basePauseMs / 2.0).roundToLong().coerceIn(minPauseMs, maxPauseMs)

    companion object {
        /** Presets the settings screen offers; any custom value is fine too. */
        val SHORT = DrillTiming(pauseFactor = 1.0, basePauseMs = 600)
        val DEFAULT = DrillTiming()
        val LONG = DrillTiming(pauseFactor = 2.2, basePauseMs = 1_500)
    }
}

enum class DrillStepKind {
    /** Speak the English cue (system TTS). */
    PROMPT,

    /** Silence: the learner says the answer. */
    ANSWER_PAUSE,

    /** Play the model answer (audio-pack clip [DrillItem.audioKey], else TTS). */
    ANSWER,

    /** Silence: the learner repeats the model answer. */
    REPEAT_PAUSE,

    /** Silence between items. */
    GAP,
}

/** One step of a drill run; [durationMs] is exact for silences and an estimate (or the clip length) for speech. */
data class DrillStep(val itemIndex: Int, val kind: DrillStepKind, val durationMs: Long) {
    val isSilence: Boolean get() = kind == DrillStepKind.ANSWER_PAUSE || kind == DrillStepKind.REPEAT_PAUSE || kind == DrillStepKind.GAP
}

data class DrillPlan(val set: DrillSet, val timing: DrillTiming, val steps: List<DrillStep>) {
    val totalMs: Long get() = steps.sumOf { it.durationMs }

    /** Index of the first step of item [itemIndex] (for skip/back), or -1. */
    fun firstStepOf(itemIndex: Int): Int = steps.indexOfFirst { it.itemIndex == itemIndex }
}

object DrillPlayback {
    /**
     * The steps for [set]. [answerMs] gives the real length of an item's model-answer clip when the audio pack is
     * installed (AudioClipInfo.ms); otherwise the length is estimated from the text ([estimateJapaneseMs]).
     */
    fun plan(set: DrillSet, timing: DrillTiming = DrillTiming.DEFAULT, answerMs: (DrillItem) -> Long? = { null }): DrillPlan {
        val steps = mutableListOf<DrillStep>()
        set.items.forEachIndexed { i, item ->
            val answer = answerMs(item)?.takeIf { it > 0 } ?: estimateJapaneseMs(item.answerJa)
            if (i > 0 && timing.gapMs > 0) steps += DrillStep(i, DrillStepKind.GAP, timing.gapMs)
            steps += DrillStep(i, DrillStepKind.PROMPT, estimateEnglishMs(item.promptEn))
            steps += DrillStep(i, DrillStepKind.ANSWER_PAUSE, timing.answerPause(answer))
            steps += DrillStep(i, DrillStepKind.ANSWER, answer)
            if (timing.repeat) steps += DrillStep(i, DrillStepKind.REPEAT_PAUSE, timing.repeatPause(answer))
        }
        return DrillPlan(set, timing, steps)
    }

    /**
     * Rough spoken length of Japanese text at a learner-friendly pace: ~7.5 morae per second, a kanji counted as two
     * morae (the typical on/kun reading length), small ゃゅょぁぃぅぇぉ folded into the previous mora, and a short
     * pause per 、/。/？/！. Only used when the clip's real length isn't known.
     */
    fun estimateJapaneseMs(text: String): Long {
        var morae = 0.0
        var pauses = 0
        for (c in text) {
            when {
                c in SMALL_KANA -> Unit
                c in "、，" -> pauses++
                c in "。？！?!…" -> pauses += 2
                c == 'ー' || c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' -> morae += 1
                c.isKanji() -> morae += 2
                c.isLetterOrDigit() -> morae += 1
            }
        }
        return (morae * MS_PER_MORA).roundToLong() + pauses * PUNCT_PAUSE_MS / 2 + LEAD_MS
    }

    /** Rough spoken length of an English cue: ~2.7 words per second. */
    fun estimateEnglishMs(text: String): Long {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return words * MS_PER_WORD + LEAD_MS
    }

    private fun Char.isKanji(): Boolean = this in '一'..'鿿' || this in '㐀'..'䶿' || this == '々'

    private const val SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"
    private const val MS_PER_MORA = 133.0
    private const val MS_PER_WORD = 370L
    private const val PUNCT_PAUSE_MS = 250L
    private const val LEAD_MS = 300L
}

/**
 * A position in a [DrillPlan] for the hands-free player: the platform plays [current], then calls [advance] when the
 * step ends (or [skipItem]/[previousItem] on a remote-control/headset command). Pure state, no clocks.
 */
class DrillCursor(val plan: DrillPlan, start: Int = 0) {
    var index: Int = start.coerceIn(0, plan.steps.size)
        private set

    val current: DrillStep? get() = plan.steps.getOrNull(index)
    val finished: Boolean get() = index >= plan.steps.size
    val itemIndex: Int get() = current?.itemIndex ?: plan.set.items.size

    /** Time left from the start of the current step. */
    val remainingMs: Long get() = plan.steps.drop(index).sumOf { it.durationMs }

    fun advance(): DrillStep? {
        if (!finished) index++
        return current
    }

    /** Jumps to the next item's prompt (skipping the rest of this one). */
    fun skipItem(): DrillStep? {
        val next = plan.firstStepOf(itemIndex + 1)
        index = if (next < 0) plan.steps.size else promptOf(itemIndex + 1)
        return current
    }

    /** Restarts the current item, or goes to the previous one when already at its prompt. */
    fun previousItem(): DrillStep? {
        val here = promptOf(itemIndex)
        index = if (index > here || itemIndex == 0) here else promptOf(itemIndex - 1)
        return current
    }

    private fun promptOf(item: Int): Int =
        plan.steps.indexOfFirst { it.itemIndex == item && it.kind == DrillStepKind.PROMPT }.takeIf { it >= 0 } ?: plan.steps.size
}
