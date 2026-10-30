package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import kotlinx.coroutines.flow.Flow

/** What an engine can do with a [SpeechRequest] (QC 2026-10-30 V4). */
data class EngineCapabilities(
    /** Returns a complete WAV per request ([SpeechEngine.synthesize]); every shipped engine is this. */
    val wavOnly: Boolean = true,
    /** Streams PCM as it is generated ([SpeechEngine.stream]); a reply then plays through one output line. */
    val pcmStream: Boolean = false,
    /** Uses [SpeechRequest.preceding]/[SpeechRequest.following] to shape intonation across sentences. */
    val acceptsContext: Boolean = false,
) {
    companion object {
        val WAV_ONLY = EngineCapabilities()
    }
}

/** How an utterance is meant, for engines that can use it. Never spoken. */
enum class DeliveryIntent { STATEMENT, QUESTION, ACKNOWLEDGEMENT }

/**
 * One utterance to speak (QC 2026-10-30 V4): the voice identity, locale, the validated text to say, optionally the
 * text around it (so a context-aware engine needn't end every sentence like a reply's last) and its delivery intent.
 * The text is one sentence; nothing here splits on commas.
 */
data class SpeechRequest(
    val voice: VoiceSpec,
    val locale: String,
    val text: String,
    val preceding: String? = null,
    val following: String? = null,
    val intent: DeliveryIntent = DeliveryIntent.STATEMENT,
    val speed: Double = 1.0,
) {
    companion object {
        fun intentOf(text: String): DeliveryIntent =
            if (app.mokuhyo.speech.ReplyRepair.isQuestion(text)) DeliveryIntent.QUESTION else DeliveryIntent.STATEMENT
    }
}

/** A piece of 16-bit mono PCM at [rate] Hz. */
class PcmChunk(val samples: ShortArray, val rate: Int)
