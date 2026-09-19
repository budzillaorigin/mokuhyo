package app.tsumugi.api

import app.tsumugi.audio.AudioPackEntry
import app.tsumugi.immersion.SourceMinutes
import app.tsumugi.media.PcmSource
import app.tsumugi.media.SentenceHit
import app.tsumugi.media.SubtitleGenerationException
import app.tsumugi.reader.LearnerFurigana
import app.tsumugi.reader.LearnerLevel
import app.tsumugi.reader.ReaderToken
import app.tsumugi.review.ReviewCandidate
import app.tsumugi.review.ReviewKind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Types for the Swift adapters in [SwiftSupport] (Phase 10). Callback interfaces instead of suspend ones, boxed
 * progress instead of `(Int, Int) -> Unit`, and no `Pair` or clashing type names (`Verdict`, `LearnerLevel`) in
 * anything Swift has to spell. No logic lives here.
 */

/**
 * The platform's audio decoder for one media file, callback style (like `LocalSttBridge`): [read] delivers
 * [startMs, endMs) as 16 kHz mono floats in [-1, 1], or an error message. Swift implements it over AVAssetReader.
 */
interface PcmWindowReader {
    fun durationMs(): Long
    fun read(startMs: Long, endMs: Long, onDone: (FloatArray?, String?) -> Unit)
}

/** [PcmWindowReader] as the [PcmSource] the subtitle generator pulls from. */
internal class CallbackPcmSource(private val reader: PcmWindowReader) : PcmSource {
    override val durationMs: Long get() = reader.durationMs()

    override suspend fun read(startMs: Long, endMs: Long): ShortArray = suspendCancellableCoroutine { c ->
        reader.read(startMs, endMs) { samples, error ->
            if (!c.isActive) return@read
            if (samples != null) {
                c.resume(ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() })
            } else {
                c.resumeWithException(SubtitleGenerationException(error ?: "couldn't decode the audio"))
            }
        }
    }
}

/** Push progress (Notion, AnkiConnect) as one object, so Swift closures never see boxed primitives. */
data class PushProgress(val done: Int, val total: Int)

/** "Furigana only above my level" for the reader: the learner's level wrapped so Swift never names `LearnerLevel`. */
class LearnerFuriganaFilter internal constructor(private val level: LearnerLevel) {
    /** The JLPT level used (5 = N5), or null when unknown. */
    val jlpt: Int? get() = level.jlpt

    fun show(token: ReaderToken): Boolean = LearnerFurigana.show(token, level)
}

/** One row of the content-review queue: the candidate and its verdict so far ([verdictCode] null = undecided). */
data class ReviewEntry(
    val candidate: ReviewCandidate,
    val verdictCode: String?,
    val notes: String,
    val edits: Map<String, String>,
)

/** Unverified candidates and decided verdicts of one kind (content review list). */
data class ReviewKindCount(val kind: ReviewKind, val total: Int, val decided: Int)

// --- Phase 11 iOS UI and audio packs (D-197): flat rows, so Swift never spells nested, clashing or `set`-named members.

/** An installed audio pack for Settings → Audio packs. [setId] is the set's id ("exam", "pitch", …). */
data class AudioPackRow(
    val setId: String,
    val version: String,
    val clips: Int,
    val bytesOnDisk: Long,
    val credits: List<String>,
)

/** One pack listed by a server's `audio-manifest.json`, with the version installed here (null = not installed). */
data class AudioManifestRow(
    val entry: AudioPackEntry,
    val setId: String,
    val file: String,
    val version: String,
    val bytes: Long,
    val clips: Int,
    val audioSeconds: Long,
    val credits: List<String>,
    val installedVersion: String?,
)

/** An audio install in progress; [phase] is "DOWNLOADING", "COPYING" or "EXTRACTING"; [bytesTotal] ≤ 0 = unknown. */
data class AudioProgressRow(val setId: String, val phase: String, val bytesDone: Long, val bytesTotal: Long, val fraction: Double)

/** One day of the immersion log, its date as ISO text (Swift never names kotlinx `LocalDate`). */
data class ImmersionDayRow(
    val date: String,
    val activeMinutes: Int,
    val passiveMinutes: Int,
    val totalMinutes: Int,
    val targetMinutes: Int,
    val targetMet: Boolean,
    val bySource: List<SourceMinutes>,
)

/**
 * Dictionary sentence search as flat lists (the sealed `OnlineExamplesResult` flattened). [onlineEnabled] false: the
 * optional source is off; [onlineFailure] set: it is on but didn't answer.
 */
data class SentenceSearchRows(
    val library: List<SentenceHit>,
    val tatoeba: List<SentenceHit>,
    val online: List<SentenceHit>,
    val onlineEnabled: Boolean,
    val onlineFailure: String?,
    val onlineSourceName: String,
)

// --- Phase 12 iOS UI (D-260…D-269): flat rows, so Swift never spells nested, sealed or clashing types. -----------

/** One read-along sentence with its text and place in the story's audio ([startMs]/[endMs] -1 when untimed). */
data class ReadAlongRow(
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val speaker: String,
    val voice: String,
    val clipKey: String,
    val startMs: Long,
    val endMs: Long,
)

/** A story's read-along: [timed] = every line has a pre-rendered clip (D-207); otherwise the system voice reads it. */
data class ReadAlongPlan(val storyId: String, val timed: Boolean, val totalMs: Long, val lines: List<ReadAlongRow>)

/**
 * `SummaryGradeResult` flattened. [graded] true: the scores, [feedback] and [engine] are set (always AI-generated).
 * False: [reason] says why nothing was graded.
 */
data class SummaryGradeRow(
    val graded: Boolean,
    val content: Int,
    val accuracy: Int,
    val language: Int,
    val total: Int,
    val corrected: String,
    val feedback: String,
    val engine: String,
    val reason: String,
)

/** One run of an email drill's body: text ([blank] -1) or a slot ([blank] ≥ 0, [text] empty). */
data class EmailSegmentRow(val text: String, val blank: Int)

/** An onomatopoeia type for the filter: [code] is the enum name. */
data class OnomatopoeiaTypeRow(val code: String, val labelJa: String, val labelEn: String)

/** One turn of the OPI probe map. Levels are labels ("2+") with [targetRank]/[afterRank] for the chart (-1 = none). */
data class OpiProbeTurnRow(
    val index: Int,
    val phaseTitle: String,
    val isProbe: Boolean,
    val isLevelCheck: Boolean,
    val question: String,
    /** English domain title ("Current events"), "" for model-written questions. */
    val domain: String,
    val target: String,
    val targetRank: Int,
    val before: String,
    val after: String,
    val afterRank: Int,
    /** -1 when unanswered. */
    val answerLength: Int,
    /** SUSTAINED | PARTIAL | BREAKDOWN | NOT_RATED. */
    val outcome: String,
)

data class OpiProbeLevelRow(val level: String, val rank: Int, val sustained: Int, val partial: Int, val breakdown: Int)

/** `OpiProbeMap` flattened ([floor]/[ceiling] "" when none). [levelLabels] are every ILR label in rank order. */
data class OpiProbeRows(
    val floor: String,
    val ceiling: String,
    val turns: List<OpiProbeTurnRow>,
    val levels: List<OpiProbeLevelRow>,
    val levelLabels: List<String>,
    val domains: List<String>,
    val missingDomains: List<String>,
)
