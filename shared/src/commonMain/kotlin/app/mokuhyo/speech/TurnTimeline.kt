package app.mokuhyo.speech

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * One measured moment in a spoken turn (BRIEF_INTERVIEW_SPEED N1). A turn runs from the learner's last speech to the
 * partner's reply: capture → speech recognition → model → speech output → playback, plus cancellation and the
 * checkpoint. [wire] is the name in reports; wires are never renamed, so stored reports keep their meaning. Each output
 * marker says what it really measures (QC 2026-10-30 V12; docs/BENCHMARK.md lists them).
 */
enum class TurnStage(val wire: String) {
    /** The last audible learner speech (back-dated from the VAD's silence tail). The headline metric starts here. */
    LAST_SPEECH("last_speech"),
    CAPTURE_STOP("capture_stop"),
    VAD_ENDPOINT("vad_endpoint"),
    ASR_QUEUED("asr_queued"),
    ASR_START("asr_start"),
    ASR_END("asr_end"),
    LLM_QUEUED("llm_queued"),
    LLM_START("llm_start"),
    LLM_FIRST_TOKEN("llm_first_token"),
    LLM_VALIDATED("llm_validated"),
    TTS_START("tts_start"),
    /** The first PCM of an utterance from an engine that streams PCM. WAV-only engines never mark it (see [TTS_SYNTH_DONE]). */
    TTS_FIRST_PCM("tts_first_pcm"),
    /** The output device opened and playback began: a proxy for first sound, before any leading silence or device delay. */
    OUTPUT_START("output_start"),
    OUTPUT_END("output_end"),
    CANCEL_REQUESTED("cancel_requested"),
    CANCEL_EFFECTIVE("cancel_effective"),
    CHECKPOINT_SAVED("checkpoint_saved"),
    /** A whole utterance's audio is synthesized (a WAV engine's only "ready" moment; formerly marked as first PCM). */
    TTS_SYNTH_DONE("tts_synth_done"),
    /** The first audible sample reaches the device: device open plus the clip's leading silence (from the PCM, not acoustics). */
    OUTPUT_AUDIBLE("output_audible"),
    /** The last audible sample of an utterance that played to the end: playback end minus trailing silence. */
    SPEECH_END("speech_end"),
    /** The output buffer ran dry mid-utterance (a stall the learner hears as a gap). */
    OUTPUT_UNDERRUN("output_underrun"),
    ;

    companion object {
        fun of(wire: String): TurnStage? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why a partner line didn't come from the live model. A category only: reasons can quote model output (rule: no text). */
enum class FallbackKind(val wire: String) {
    NONE("none"), NO_MODEL("no_model"), DEADLINE("deadline"), INVALID("invalid"), CANCELLED("cancelled"), ERROR("error");

    companion object {
        /** Maps a gateway reason to its category; the reason itself is never stored. */
        fun ofReason(reason: String?): FallbackKind = when {
            reason == null -> NONE
            "no AI model" in reason -> NO_MODEL
            "deadline" in reason || "took too long" in reason -> DEADLINE
            "stopped" in reason || "cancel" in reason -> CANCELLED
            "failed checks" in reason || "valid JSON" in reason -> INVALID
            else -> ERROR
        }
    }
}

/** What ran a session: recorded once, shown in the report header. Identifiers and numbers only. */
data class TimelineSetup(
    val modelId: String? = null,
    /** The model file's SHA-256 (from the manifest), so reports from two machines compare like with like. */
    val modelHash: String? = null,
    /** A configured voice, used only when no utterance rendered; what actually spoke is logged per utterance. */
    val voiceId: String? = null,
    /** "cpu", "metal", "vulkan", "endpoint" … */
    val backend: String? = null,
    val llmThreads: Int? = null,
    val asrThreads: Int? = null,
    val ttsThreads: Int? = null,
)

/**
 * Monotonic timestamps per session and turn (BRIEF_INTERVIEW_SPEED N1). Deliberately has no way to take text or
 * audio: stages, numbers and identifiers only, so a timing export can never leak what was said.
 *
 * Turn 0 is the first question (cold or warm start, no learner speech); turn k is the learner's answer to question
 * k-1 and the partner's line k. [now] is monotonic nanoseconds; tests pass their own.
 *
 * Thread-safe enough for its use: marks arrive from the UI, the capture thread and the native callback thread, and
 * every mutation is a single synchronized block.
 */
class TurnTimeline(
    val sessionId: String,
    /** "OPI", "OPI_TEST", "TOPIC", "BENCH" … */
    val kind: String,
    val language: String,
    private val now: () -> Long = monotonicNanos(),
) {
    class Event(val turn: Int, val stage: TurnStage, val atNanos: Long, val meaningful: Boolean)

    class Turn(val index: Int) {
        var cold: Boolean = false
        var answerMs: Long? = null
        var fallback: FallbackKind = FallbackKind.NONE
        var llmAttempts: Int = 0
        var ttsSynthMs: Long = 0
        var ttsAudioMs: Long = 0
        var peakRssBytes: Long? = null
        /** Turns guided by a prerendered prompt (N5): counted separately from live-TTS turns. */
        var prerendered: Boolean = false
        /** `engine:voice` of each utterance in order, as it actually rendered (fallbacks included). */
        val voices: MutableList<String> = ArrayList()
        /** `requested->rendered` for every utterance another voice spoke (QC 2026-10-30 V5). */
        val voiceFallbacks: MutableList<String> = ArrayList()
    }

    private val lock = Any()
    private val origin = now()
    private val events = ArrayList<Event>()
    private val turns = ArrayList<Turn>()
    private val warmups = ArrayList<Pair<String, Long>>()

    @kotlin.concurrent.Volatile
    var setup: TimelineSetup = TimelineSetup()

    /** The turn that marks go to; -1 before [beginTurn]. */
    val current: Int get() = synchronized(lock) { turns.lastIndex }

    /** Starts the next turn and returns its index. */
    fun beginTurn(cold: Boolean = false): Int = synchronized(lock) {
        turns += Turn(turns.size).also { it.cold = cold }
        turns.lastIndex
    }

    /** Records [stage] for [turn] (the current one by default) at [atNanos] (now by default). Unknown turns are ignored. */
    fun mark(stage: TurnStage, turn: Int = current, atNanos: Long = now(), meaningful: Boolean = true) {
        synchronized(lock) {
            if (turn !in turns.indices) return
            events += Event(turn, stage, atNanos - origin, meaningful)
        }
    }

    /** Records [stage] as having happened [ms] milliseconds ago (the VAD reports the last speech after its silence tail). */
    fun markBefore(stage: TurnStage, ms: Long, turn: Int = current) = mark(stage, turn, now() - ms * 1_000_000)

    /** Records [stage] as happening [ms] milliseconds from now (the first audible sample after a clip's leading silence). */
    fun markAfter(stage: TurnStage, ms: Long, turn: Int = current) = mark(stage, turn, now() + ms * 1_000_000)

    /** Edits the current turn's numbers (answer length, fallback, TTS time…). */
    fun note(turn: Int = current, edit: Turn.() -> Unit) {
        synchronized(lock) { turns.getOrNull(turn)?.edit() }
    }

    /** A warm-up step's duration (N4 preflight): reported apart from answer latency, never inside a turn. */
    fun warmup(step: String, ms: Long) {
        synchronized(lock) { warmups += step to ms }
    }

    fun elapsedMs(): Long = (now() - origin) / 1_000_000

    fun events(): List<Event> = synchronized(lock) { events.toList() }

    /** The timeline as stored and exported: per-turn stage offsets and durations, no content. */
    fun snapshot(): TimingReport.Session = synchronized(lock) {
        TimingReport.Session(
            id = sessionId, kind = kind, language = language,
            modelId = setup.modelId, modelHash = setup.modelHash, backend = setup.backend,
            // The first voice that actually spoke; every utterance's own voice is in its turn (QC 2026-10-30 V12).
            voiceId = turns.firstNotNullOfOrNull { it.voices.firstOrNull() } ?: setup.voiceId,
            llmThreads = setup.llmThreads, asrThreads = setup.asrThreads, ttsThreads = setup.ttsThreads,
            warmupMs = warmups.associate { it.first to it.second },
            turns = turns.map { t ->
                val mine = events.filter { it.turn == t.index }
                TimingReport.Turn(
                    index = t.index, cold = t.cold, answerMs = t.answerMs, fallback = t.fallback.wire,
                    llmAttempts = t.llmAttempts, prerendered = t.prerendered,
                    ttsSynthMs = t.ttsSynthMs.takeIf { it > 0 }, ttsAudioMs = t.ttsAudioMs.takeIf { it > 0 },
                    peakRssMb = t.peakRssBytes?.let { it / (1024 * 1024) },
                    voices = t.voices.toList(),
                    voiceFallbacks = t.voiceFallbacks.toList(),
                    gapsMs = gaps(mine),
                    underruns = mine.count { it.stage == TurnStage.OUTPUT_UNDERRUN },
                    events = mine.sortedBy { it.atNanos }.map { TimingReport.Mark(it.stage.wire, it.atNanos / 1_000_000, it.meaningful) },
                    stages = durations(mine).let { d ->
                        // A guided turn's clip: from choosing it to its first sound (target p95 ≤ 300 ms, N5).
                        val pick = mine.filter { it.stage == TurnStage.TTS_START }.minOfOrNull { it.atNanos }
                        val sound = mine.filter { it.stage == TurnStage.OUTPUT_START && it.meaningful }.minOfOrNull { it.atNanos }
                        if (t.prerendered && pick != null && sound != null && sound >= pick) d + (PROMPT_START to (sound - pick) / 1_000_000) else d
                    },
                )
            },
        )
    }

    companion object {
        fun monotonicNanos(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeNanoseconds }
        }

        /** Stage durations in ms for one turn's [events]; a stage is missing when either end wasn't marked. */
        internal fun durations(events: List<Event>): Map<String, Long> {
            fun first(s: TurnStage) = events.filter { it.stage == s }.minOfOrNull { it.atNanos }
            fun last(s: TurnStage) = events.filter { it.stage == s }.maxOfOrNull { it.atNanos }
            val firstAudio = events.filter { it.stage == TurnStage.OUTPUT_START && it.meaningful }.minOfOrNull { it.atNanos }
            // The learner's last speech before the reply started (a retake after an earlier attempt supersedes it).
            val lastSpeech = events.filter { it.stage == TurnStage.LAST_SPEECH && (firstAudio == null || it.atNanos <= firstAudio) }.maxOfOrNull { it.atNanos }
            val out = LinkedHashMap<String, Long>()
            fun put(name: String, from: Long?, to: Long?) {
                if (from != null && to != null && to >= from) out[name] = (to - from) / 1_000_000
            }
            put(FIRST_AUDIO, lastSpeech, firstAudio)
            put("endpoint", lastSpeech, last(TurnStage.VAD_ENDPOINT) ?: last(TurnStage.CAPTURE_STOP))
            put("asr_queue", last(TurnStage.ASR_QUEUED), last(TurnStage.ASR_START))
            put("asr", last(TurnStage.ASR_START), last(TurnStage.ASR_END))
            put("llm_queue", first(TurnStage.LLM_QUEUED), first(TurnStage.LLM_START))
            put("llm_first_token", first(TurnStage.LLM_START), first(TurnStage.LLM_FIRST_TOKEN))
            put("llm_validated", first(TurnStage.LLM_START), last(TurnStage.LLM_VALIDATED))
            put("tts_first_pcm", first(TurnStage.TTS_START), first(TurnStage.TTS_FIRST_PCM))
            put("tts_synth", first(TurnStage.TTS_START), first(TurnStage.TTS_SYNTH_DONE))
            put("output_open", first(TurnStage.TTS_FIRST_PCM) ?: first(TurnStage.TTS_SYNTH_DONE), firstAudio)
            val audible = events.filter { it.stage == TurnStage.OUTPUT_AUDIBLE && it.meaningful }.minOfOrNull { it.atNanos }
            put(FIRST_AUDIBLE, lastSpeech, audible)
            put("leading_silence", firstAudio, audible)
            put("playback", firstAudio, last(TurnStage.OUTPUT_END))
            put("cancel", last(TurnStage.CANCEL_REQUESTED), last(TurnStage.CANCEL_EFFECTIVE))
            return out
        }

        /** Gaps the learner hears between consecutive utterances of a turn: one's speech end → the next one's first audible sample. */
        internal fun gaps(events: List<Event>): List<Long> {
            val ordered = events.filter { it.stage == TurnStage.SPEECH_END || it.stage == TurnStage.OUTPUT_AUDIBLE }.sortedBy { it.atNanos }
            return ordered.zipWithNext().filter { (a, b) -> a.stage == TurnStage.SPEECH_END && b.stage == TurnStage.OUTPUT_AUDIBLE }
                .map { (a, b) -> (b.atNanos - a.atNanos) / 1_000_000 }
        }

        /**
         * The headline: last learner speech → first meaningful partner audio (endpointing, ASR and queues included). Its end
         * is the device-open proxy, kept for comparison with earlier reports; [FIRST_AUDIBLE] adds the leading silence.
         */
        const val FIRST_AUDIO = "first_audio"

        /** Last learner speech → the reply's first audible sample (device open + leading silence). */
        const val FIRST_AUDIBLE = "first_audible"

        /** A prerendered prompt's start: clip chosen → first sound. */
        const val PROMPT_START = "prompt_start"
    }
}

/**
 * Carries the current session's timeline into deep layers (gateway, recognizer, model bridge) without threading it
 * through every signature. Only the foreground coroutine carries it, so background feedback never marks a turn.
 */
class TimelineTrace(val timeline: TurnTimeline, val turn: Int = timeline.current) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TimelineTrace>

    fun mark(stage: TurnStage, meaningful: Boolean = true) = timeline.mark(stage, turn, meaningful = meaningful)
}

/** Marks [stage] on the calling coroutine's timeline, if it carries one. */
suspend fun traceMark(stage: TurnStage) {
    currentCoroutineContext()[TimelineTrace]?.mark(stage)
}

/** The calling coroutine's trace, if any (to mark from a native callback thread later). */
suspend fun currentTrace(): TimelineTrace? = currentCoroutineContext()[TimelineTrace]
