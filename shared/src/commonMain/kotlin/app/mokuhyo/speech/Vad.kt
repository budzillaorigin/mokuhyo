package app.mokuhyo.speech

import kotlin.math.abs
import kotlin.math.max

/**
 * Voice-activity detection for push-to-talk (BRIEF_PHASE8 N-00b), fed one RMS level (0..1) per [frameMs] frame:
 * learns the noise floor from the quietest recent frames, marks speech when the level clears
 * `max(floor × 3, minLevel)`, auto-stops after [silenceStopMs] of silence once at least [minSpeechMs] of speech was
 * heard, and reports "no audio" when nothing above the floor arrives within [noAudioMs] (a muted or wrong microphone,
 * or an OS privacy block). Pure logic; the capture thread calls [feed].
 */
class Vad(
    val frameMs: Int = 100,
    val silenceStopMs: Int = 1_500,
    val minSpeechMs: Int = 400,
    val noAudioMs: Int = 4_000,
    val minLevel: Double = 0.012,
) {
    enum class Event { SPEECH_START, AUTO_STOP, NO_AUDIO }

    private var floor = Double.NaN
    private var frames = 0
    private var speechMs = 0
    private var silenceMs = 0
    private var started = false
    private var done = false

    val heardSpeech: Boolean get() = started && speechMs >= minSpeechMs

    fun feed(rms: Double): Event? {
        if (done) return null
        frames++
        if (floor.isNaN()) floor = rms
        val threshold = max(floor * 3, minLevel)
        val loud = rms >= threshold
        // Noise floor from quiet frames only: fast down, slow up; speech never drags it upward.
        if (!loud) floor = if (rms < floor) rms else floor + (rms - floor) * 0.05
        if (loud) {
            speechMs += frameMs
            silenceMs = 0
            if (!started && speechMs >= minOf(minSpeechMs, 2 * frameMs)) {
                started = true
                return Event.SPEECH_START
            }
        } else if (started) {
            silenceMs += frameMs
        } else if (speechMs > 0) {
            speechMs = 0 // a blip shorter than the start window is not speech
        }
        if (started && speechMs >= minSpeechMs && silenceMs >= silenceStopMs) {
            done = true
            return Event.AUTO_STOP
        }
        if (!started && frames * frameMs >= noAudioMs) {
            done = true
            return Event.NO_AUDIO
        }
        return null
    }
}

/** Gain normalization for quiet microphones: scales a recording's peak to about −3 dBFS, at most [maxGain]×. */
object Gain {
    fun normalize(pcm: ShortArray, target: Double = 0.7, maxGain: Double = 10.0): ShortArray {
        val peak = pcm.maxOfOrNull { abs(it.toInt()) } ?: return pcm
        if (peak == 0) return pcm
        val gain = minOf(target * 32767 / peak, maxGain)
        if (gain <= 1.05) return pcm
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
