package app.tsumugi.api

import app.tsumugi.media.PcmSource
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
