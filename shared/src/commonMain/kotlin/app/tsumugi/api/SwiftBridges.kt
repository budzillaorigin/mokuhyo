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

// --- Phase 13 iOS UI (D-300…D-309): flat rows, so Swift never spells sealed, nested or enum-keyed types, `Pair`s,
// `IntRange`s, `Duration`s, or members named `register` (a C keyword in the Objective-C header). ---------------------

/** One line of the pitch-test stats: [label] is a pattern (平板 …), a mora count ("3") or a question type code. */
data class PitchStatRow(val key: String, val label: String, val attempts: Int, val correct: Int) {
    /** 0…100, or -1 without attempts. */
    val percent: Int get() = if (attempts == 0) -1 else (correct * 100 + attempts / 2) / attempts
}

/** A confusable pattern pair: answers expecting one of them, and how often the other one was chosen. */
data class PitchPairRow(val a: String, val b: String, val attempts: Int, val confusions: Int) {
    val percent: Int get() = if (attempts == 0) -1 else (confusions * 100 + attempts / 2) / attempts
}

/** `PitchStats` flattened: total, per pattern, per mora length, per question type, pairs, and the next start level. */
data class PitchStatsRows(
    val total: PitchStatRow,
    val byPattern: List<PitchStatRow>,
    val byMoraCount: List<PitchStatRow>,
    val byMode: List<PitchStatRow>,
    val pairs: List<PitchPairRow>,
    val level: Int,
)

/**
 * A node of the kanji explorer graph with its layout position (0…1 on both axes). [kindCode] KANJI | COMPONENT |
 * CONTAINER | SERIES | WORD; [roleCode] SEMANTIC | PHONETIC | FORM or ""; [bucket] 0…4, 5 = unknown; [entryId] -1 for
 * characters.
 */
data class ExplorerNodeRow(
    val id: String,
    val label: String,
    val kindCode: String,
    val isFocus: Boolean,
    val bucket: Int,
    val roleCode: String,
    val reading: String,
    val gloss: String,
    val entryId: Long,
    val x: Double,
    val y: Double,
)

/** An edge; [kindCode] PART | USED_IN | SOUND | WORD. */
data class ExplorerEdgeRow(val from: String, val to: String, val kindCode: String)

/** A laid-out one-hop graph. [hidden] neighbors were left out by the node cap. */
data class ExplorerGraphRows(val focusId: String, val nodes: List<ExplorerNodeRow>, val edges: List<ExplorerEdgeRow>, val hidden: Int)

/** One direct part of a kanji with its role ([roleCode] SEMANTIC | PHONETIC | FORM, "" = unknown for older packs). */
data class ComponentRoleRow(
    val component: String,
    val roleCode: String,
    val position: String,
    val reading: String,
    val match: String,
    val seriesId: String,
    val derived: Boolean,
)

/** A stored game round. [gameCode] REFLEX | ATOM. */
data class GameScoreEntryRow(val gameCode: String, val score: Int, val correct: Int, val total: Int, val bestStreak: Int, val day: String)

/** Atom after a tap. [resultCode] PLACED | WRONG | SOLVED | TIMED_OUT. */
data class AtomTapRow(val resultCode: String, val assembled: List<String>, val points: Int, val mistakes: Int, val score: Int)

/**
 * A grammar construction in a reader sentence. [spanStarts]/[spanEnds] are offsets in the sentence text (end
 * exclusive). [stageCode] is the SRS stage name or "" when not in reviews. [practiceCode] ADD_TO_REVIEWS | EXERCISE | NONE.
 */
data class ConstructionRow(
    val pointId: String,
    val title: String,
    val structure: String,
    val jlpt: Int,
    val explanation: String,
    val japanese: Boolean,
    val aiGenerated: Boolean,
    val japaneseMissing: Boolean,
    val spanStarts: List<Int>,
    val spanEnds: List<Int>,
    val stageCode: String,
    val practiceCode: String,
)

/**
 * "Practice this point" flattened. [outcomeCode] ADDED | EXERCISE | UNAVAILABLE. [exerciseKind] CLOZE | BUILD |
 * FILL_HINT | MEANING_CHOICE | PRODUCTION, "" without an exercise.
 */
data class GrammarPracticeRow(
    val outcomeCode: String,
    val exercise: app.tsumugi.grammar.GrammarExercise?,
    val exerciseKind: String,
    val reason: String,
)

/** A checked answer: [accepted] and the expected answer to show. */
data class ExerciseCheckRow(val accepted: Boolean, val expected: String)

data class TranslationIssueRow(val kind: String, val attemptSpan: String, val referenceSpan: String, val note: String)

/** [kindCode] SAME | MISSING | EXTRA. */
data class DiffSegmentRow(val kindCode: String, val text: String)

/**
 * `TranslationGradeResult` flattened. [graded] true: an AI grade (always labeled, never official), saved as
 * [attemptId]. False: [reason] says why; the learner self-assesses on the rubric. [hasDiff] false when there is no
 * reference (imported text) or no attempt.
 */
data class TranslationGradeRow(
    val graded: Boolean,
    val reason: String,
    val attemptId: String,
    val accuracy: Int,
    val completeness: Int,
    val registerScore: Int,
    val naturalness: Int,
    val percent: Int,
    val feedback: String,
    val better: String,
    val issues: List<TranslationIssueRow>,
    val engine: String,
    val hasDiff: Boolean,
    val diff: List<DiffSegmentRow>,
    /** 0…100 share of the reference the attempt also has (a rough overlap, not a grade). */
    val overlapPercent: Int,
)

/** A stored attempt. [directionCode] JE | EJ; [sight] spoken; [aiGraded] shows the badge; [timeLimitMs] -1 = untimed. */
data class TranslationAttemptRow(
    val id: String,
    val passageId: String,
    val directionCode: String,
    val genre: String,
    val level: String,
    val sight: Boolean,
    val sourceText: String,
    val attemptText: String,
    val durationMs: Long,
    val timeLimitMs: Long,
    val overTime: Boolean,
    val aiGraded: Boolean,
    val accuracy: Int,
    val completeness: Int,
    val registerScore: Int,
    val naturalness: Int,
    val score: Int,
    val feedback: String,
    val better: String,
    val engine: String,
    val createdAt: Long,
)

data class SkillPointRow(val index: Int, val day: String, val score: Int, val genre: String, val directionCode: String, val aiGraded: Boolean)

data class LabeledScoreRow(val key: String, val score: Int)

/** The translation skill line on Me. [trend] is only meaningful when [hasTrend] (ten attempts or more). */
data class TranslationSkillRows(
    val points: List<SkillPointRow>,
    val daily: List<LabeledScoreRow>,
    val recentByDirection: List<LabeledScoreRow>,
    val recentByGenre: List<LabeledScoreRow>,
    val hasTrend: Boolean,
    val trend: Int,
    val attempts: Int,
)

data class CorrectionEditRow(val original: String, val replacement: String, val reason: String)

/**
 * One sentence's correction (labeled AI-generated when [engine] is set), or [unavailable] says why there is none.
 * Offsets are in the draft text.
 */
data class CorrectionRow(
    val start: Int,
    val end: Int,
    val sentence: String,
    val corrected: String,
    val isCorrect: Boolean,
    val unsure: Boolean,
    val explanation: String,
    val edits: List<CorrectionEditRow>,
    val engine: String,
    val unavailable: String,
)

/** A sentence of the register check. [registerCode] CASUAL | POLITE | FORMAL, or "" for a fragment. */
data class RegisterSentenceRow(val start: Int, val end: Int, val text: String, val registerCode: String, val markers: List<String>, val outlier: Boolean)

/** `RegisterReport` flattened; codes are "" when nothing was classified. */
data class RegisterRows(
    val sentences: List<RegisterSentenceRow>,
    val dominantCode: String,
    val expectedCode: String,
    val casual: Int,
    val polite: Int,
    val formal: Int,
    val mixed: Boolean,
)

/** A register rewrite (labeled when [engine] is set), or [unavailable]. */
data class RewriteRow(val sentence: String, val rewrite: String, val notes: String, val engine: String, val unavailable: String)

/** A dictionary word of a circle sentence ([entryId] -1 when none). */
data class CircleTokenRow(val surface: String, val start: Int, val end: Int, val entryId: Long, val dictionaryForm: String, val reading: String)

data class CircleGrammarRow(val pointId: String, val title: String)

/** Help for one circle sentence; [available] false without the dictionary pack. */
data class CircleHelpRows(val available: Boolean, val tokens: List<CircleTokenRow>, val grammar: List<CircleGrammarRow>)
