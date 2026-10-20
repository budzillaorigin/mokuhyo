package app.mokuhyo.speech

import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.VoiceSpec
import kotlinx.serialization.Serializable

/** The last measured headline for a language (from a timed session on this computer). */
@Serializable
data class MeasuredLatency(val p50Ms: Long, val p95Ms: Long, val turns: Int, val measuredAt: Long)

/**
 * What a spoken interview in one language would run on, on this computer (BRIEF_INTERVIEW_SPEED N4; D-042 listed the
 * panel as outstanding): the live voice and where it comes from, speech recognition, the model, and the last measured
 * reply speed. Without an approved offline voice the language says so before an interview starts, and offers guided
 * text practice rather than a spoken test that can't speak.
 */
data class LanguageReadiness(
    val language: String,
    val voice: VoiceSpec?,
    val asr: String?,
    val model: String?,
    val measured: MeasuredLatency?,
) {
    enum class VoiceSource(val label: String) { PIPER("bundled Piper voice"), OS("operating-system voice"), OTHER("voice"), NONE("no offline voice") }

    val voiceSource: VoiceSource get() = when (voice?.engine) {
        null -> VoiceSource.NONE
        "piper" -> VoiceSource.PIPER
        "os" -> VoiceSource.OS
        else -> VoiceSource.OTHER
    }

    /** A spoken interview is possible: something can say the questions. */
    val spoken: Boolean get() = voice != null

    val name: String get() = Languages.of(language)?.nameEnglish ?: language

    /** One line per item, for the panel. */
    fun lines(): List<String> = listOf(
        "Voice: " + (voice?.let { "${voiceSource.label} · ${it.id}" } ?: "none — spoken interviews aren't available; guided text practice is offered instead"),
        "Speech recognition: " + (asr ?: "not installed — answers are typed"),
        "Model: " + (model ?: "none — guided (scripted) questions"),
        "Last measured: " + (measured?.let { "first audio p50 ${secs(it.p50Ms)}, p95 ${secs(it.p95Ms)} over ${it.turns} turns" } ?: "not measured on this computer yet"),
    )

    companion object {
        /** Every launch language, in the registry's order. */
        fun all(voices: (String) -> List<VoiceSpec>, asr: String?, model: String?, measured: Map<String, MeasuredLatency>): List<LanguageReadiness> =
            Languages.all.map { l -> LanguageReadiness(l.code, runCatching { voices(l.code).firstOrNull() }.getOrNull(), asr, model, measured[l.code]) }

        private fun secs(ms: Long) = "${kotlin.math.round(ms / 100.0) / 10} s"
    }
}
