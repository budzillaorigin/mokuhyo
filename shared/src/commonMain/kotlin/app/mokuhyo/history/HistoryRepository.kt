package app.mokuhyo.history

import app.mokuhyo.db.Attempt
import app.mokuhyo.db.Ilr_estimate
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.exam.AnswerRecord
import app.mokuhyo.exam.AttemptScoring
import app.mokuhyo.exam.ExamForm
import app.mokuhyo.exam.ExamMode
import app.mokuhyo.exam.ExamResult
import app.mokuhyo.exam.Skill
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What an attempt row's formJson holds: enough to review the attempt later even if the pack changes. */
@Serializable
data class StoredForm(
    val exam: String,
    val mode: String,
    val language: String,
    val level: String = "",
    val items: List<StoredItem>,
) {
    @Serializable
    data class StoredItem(
        val id: String,
        val passageId: String?,
        val level: String,
        val type: String,
        val stem: String,
        /** Choices in the order shown (the assembler reorders them to balance keys). */
        val choices: List<String>,
        val answer: Int,
        val explanation: String = "",
        val source: String = "llm",
    )

    val passageIds: List<String> get() = items.mapNotNull { it.passageId }.distinct()

    companion object {
        fun of(form: ExamForm) = StoredForm(
            form.exam.name, form.mode.name, form.language, form.level,
            form.items.map { f -> f.item.let { StoredItem(it.id, it.passageId, it.level, it.type, it.stem, it.choices, it.answer, it.explanation, it.source) } },
        )
    }
}

/** One ILR point on the trend (Home, Report). */
data class IlrPoint(val modality: String, val at: Long, val value: String, val confidence: Double, val provisional: Boolean, val sourceId: String)

/**
 * Attempts and ILR estimates (BRIEF §8.1). Append-only (CLAUDE.md rule 5): saving never overwrites another
 * attempt, deleting is a tombstone, and estimates are separate rows that are only ever added.
 */
class HistoryRepository(private val db: MokuhyoDatabase, private val clock: Clock = Clock.System) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Stores a finished attempt; a test also records an ILR estimate for its modality. Returns the attempt id. */
    fun saveAttempt(learnerId: String, skill: Skill, result: ExamResult, bank: String = "shipped"): String {
        val form = result.form
        val id = Uuid.random().toString()
        val test = form.mode != ExamMode.PRACTICE
        val ilr = result.scoring.ilr ?: result.scoring.ilrProvisional
        db.transaction {
            db.historyQueries.insertAttemptIfAbsent(
                Attempt(
                    id = id, learnerId = learnerId, lang = form.language, modality = skill.name, mode = if (test) "TEST" else "PRACTICE",
                    bank = bank, startedAt = result.startedAt.toEpochMilliseconds(), submittedAt = result.submittedAt.toEpochMilliseconds(),
                    formJson = json.encodeToString(StoredForm.serializer(), StoredForm.of(form)),
                    answersJson = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AnswerRecord.serializer()), result.answers),
                    scoreJson = json.encodeToString(AttemptScoring.serializer(), result.scoring),
                    ilrEstimate = ilr, provisional = if (result.scoring.ilr == null) 1 else 0, deleted = null,
                ),
            )
            // Only tests in the shipped bank feed the trend; practice and "generated on this computer" never do (rule 7).
            if (test && ilr != null && bank == "shipped") {
                db.historyQueries.insertEstimate(
                    Ilr_estimate(
                        id = Uuid.random().toString(), learnerId = learnerId, lang = form.language, modality = skill.name,
                        at = result.submittedAt.toEpochMilliseconds(), value_ = ilr, sourceKind = "ATTEMPT", sourceId = id,
                        confidence = if (result.scoring.ilrConfident) 0.8 else if (result.scoring.ilr != null) 0.5 else 0.3,
                        provisional = if (result.scoring.ilr == null) 1 else 0, deleted = null,
                    ),
                )
            }
        }
        return id
    }

    /** Records a speaking estimate from an OPI rating (Phase 4). */
    fun saveSpeakingEstimate(learnerId: String, lang: String, conversationId: String, value: String, confidence: Double, provisional: Boolean) {
        db.historyQueries.insertEstimate(
            Ilr_estimate(Uuid.random().toString(), learnerId, lang, "SPEAKING", clock.now().toEpochMilliseconds(), value, "CONVERSATION",
                conversationId, confidence, if (provisional) 1 else 0, null),
        )
    }

    fun attempts(learnerId: String, lang: String): List<Attempt> = db.historyQueries.attempts(learnerId, lang).executeAsList()

    fun form(attempt: Attempt): StoredForm = json.decodeFromString(StoredForm.serializer(), attempt.formJson)

    fun answers(attempt: Attempt): List<AnswerRecord> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AnswerRecord.serializer()), attempt.answersJson)

    fun scoring(attempt: Attempt): AttemptScoring? = attempt.scoreJson?.let { json.decodeFromString(AttemptScoring.serializer(), it) }

    /** Passages on the learner's last [forms] test forms of [skill] (the assembler never reuses them). */
    fun recentTestPassages(learnerId: String, lang: String, skill: Skill, forms: Int): Set<String> =
        attempts(learnerId, lang).filter { it.modality == skill.name && it.mode == "TEST" }.take(forms)
            .flatMap { form(it).passageIds }.toSet()

    /** Passages the learner has practised (practice prefers unseen ones). */
    fun practisedPassages(learnerId: String, lang: String, skill: Skill): Set<String> =
        attempts(learnerId, lang).filter { it.modality == skill.name }.flatMap { form(it).passageIds }.toSet()

    fun estimates(learnerId: String, lang: String): List<IlrPoint> = db.historyQueries.estimates(learnerId, lang).executeAsList().map {
        IlrPoint(it.modality, it.at, it.value_, it.confidence, it.provisional == 1L, it.sourceId)
    }

    /** Latest estimate per modality (READING, LISTENING, SPEAKING). */
    fun latest(learnerId: String, lang: String): Map<String, IlrPoint> = estimates(learnerId, lang).groupBy { it.modality }.mapValues { it.value.last() }

    fun tombstoneAttempt(id: String) = db.historyQueries.tombstoneAttempt(clock.now().toEpochMilliseconds(), id)
}
