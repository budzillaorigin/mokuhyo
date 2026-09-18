package app.tsumugi.exam

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.exam.db.Exam_item
import app.tsumugi.exam.db.Exam_passage
import app.tsumugi.exam.jlpt.JlptBlueprints
import app.tsumugi.exam.opi.OpiRating
import app.tsumugi.grammar.GrammarService
import app.tsumugi.study.CollectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

data class AttemptSummary(val id: String, val exam: ExamKind, val level: String, val mode: ExamMode, val submittedAt: Instant, val summary: String, val scoring: AttemptScoring)

/** A past attempt with its items, for review with explanations. */
data class AttemptReview(val summary: AttemptSummary, val items: List<ReviewedItem>, val passages: Map<String, ExamPassage>)

data class ReviewedItem(val item: ExamItem, val answer: AnswerRecord)

/** What's in the item pool for one exam/level: type → item count. Drives which modes the UI offers. */
data class ExamCoverage(val exam: ExamKind, val level: String, val types: Map<String, Int>) {
    val total: Int get() = types.values.sum()
}

sealed interface BankImportResult {
    data class Imported(val bank: String, val passages: Int, val items: Int) : BankImportResult
    data class Invalid(val errors: List<String>) : BankImportResult
}

/**
 * Exam simulators (BRIEF §5.11): builds forms from the exam pack plus user-imported banks, stores attempts,
 * and turns missed items into SRS work. [pack] is null when exam.sqlite isn't installed; imported banks still work.
 */
class ExamService(
    private val pack: ExamDatabase?,
    private val db: TsumugiDatabase,
    private val deviceId: String,
    private val grammar: suspend () -> GrammarService?,
    private val dictionary: suspend () -> DictionaryRepository?,
    private val collection: CollectionService,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.examAttemptQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var blueprintCache: JlptBlueprints? = null
    private var userBankCache: List<ExamBankFile>? = null

    @Throws(Exception::class)
    suspend fun blueprints(): JlptBlueprints? = io {
        blueprintCache ?: pack?.examQueries?.meta(BLUEPRINT_KEY)?.executeAsOneOrNull()
            ?.let { runCatching { JlptBlueprints.parse(it) }.getOrNull() }
            ?.also { blueprintCache = it }
    }

    @Throws(Exception::class)
    suspend fun coverage(): List<ExamCoverage> = io {
        val counts = mutableMapOf<Pair<ExamKind, String>, MutableMap<String, Int>>()
        pack?.examQueries?.coverage()?.executeAsList()?.forEach { row ->
            val exam = ExamBankValidator.examOf(row.exam) ?: return@forEach
            counts.getOrPut(exam to row.level) { mutableMapOf() }.let { it[row.type] = (it[row.type] ?: 0) + row.n.toInt() }
        }
        userBanks().flatMap { it.items }.forEach { i ->
            val exam = ExamBankValidator.examOf(i.exam) ?: return@forEach
            counts.getOrPut(exam to i.level) { mutableMapOf() }.let { it[i.type] = (it[i.type] ?: 0) + 1 }
        }
        counts.map { (k, v) -> ExamCoverage(k.first, k.second, v) }.sortedWith(compareBy({ it.exam.ordinal }, { it.level }))
    }

    @Throws(Exception::class)
    suspend fun jlptMock(level: Int, seed: Long = clock.now().toEpochMilliseconds()): ExamSession? {
        val bp = blueprints()?.level(level) ?: return null
        val (pool, passages) = pool(ExamKind.JLPT, listOf("N$level"))
        return ExamSession(ExamAssembler.jlptMock(level, bp, pool, passages, Random(seed)), bp, clock)
    }

    @Throws(Exception::class)
    suspend fun jlptSection(level: Int, sectionId: String, seed: Long = clock.now().toEpochMilliseconds()): ExamSession? {
        val bp = blueprints()?.level(level) ?: return null
        val (pool, passages) = pool(ExamKind.JLPT, listOf("N$level"))
        return ExamSession(ExamAssembler.jlptSection(level, bp, sectionId, pool, passages, Random(seed)), bp, clock)
    }

    @Throws(Exception::class)
    suspend fun jlptTypeDrill(level: Int, type: String, seed: Long = clock.now().toEpochMilliseconds()): ExamSession? {
        val bp = blueprints()?.level(level) ?: return null
        val (pool, passages) = pool(ExamKind.JLPT, listOf("N$level"))
        return ExamSession(ExamAssembler.jlptTypeDrill(level, bp, type, pool, passages, Random(seed)), bp, clock)
    }

    /** DLPT reading or listening; [minutes] = 180 (full length), 60 or 30. */
    @Throws(Exception::class)
    suspend fun dlpt(exam: ExamKind, minutes: Int, seed: Long = clock.now().toEpochMilliseconds()): ExamSession {
        val (pool, passages) = pool(exam, app.tsumugi.exam.dlpt.IlrLevel.lowerRange.map { it.label })
        return ExamSession(ExamAssembler.dlpt(exam, minutes, pool, passages, Random(seed)), null, clock)
    }

    /** Stores a finished attempt; returns its id. */
    @Throws(Exception::class)
    suspend fun save(result: ExamResult): String = io {
        val id = kotlin.uuid.Uuid.random().toString()
        q.insertAttempt(
            id, result.form.exam.name, result.form.level, result.form.mode.name,
            result.startedAt.toEpochMilliseconds(), result.submittedAt.toEpochMilliseconds(),
            json.encodeToString(ListSerializer(AnswerRecord.serializer()), result.answers),
            json.encodeToString(AttemptScoring.serializer(), result.scoring),
            result.summary, deviceId,
        )
        id
    }

    /**
     * Stores an OPI practice interview: the transcript (JSON list of {speaker, text}) in `answers`, the rating in
     * `scoring` (factor scores as 1–5 tallies, next steps as weak areas). Always labeled a practice estimate.
     */
    @Throws(Exception::class)
    suspend fun saveOpi(startedAt: Instant, transcript: List<Pair<String, String>>, rating: OpiRating): String = io {
        val id = kotlin.uuid.Uuid.random().toString()
        val lines = JsonArray(transcript.map { (speaker, text) -> JsonObject(mapOf("speaker" to JsonPrimitive(speaker), "text" to JsonPrimitive(text))) })
        val factors = listOfNotNull(
            rating.functions?.let { AttemptScoring.Tally("functions", it, 5) },
            rating.accuracy?.let { AttemptScoring.Tally("accuracy", it, 5) },
            rating.vocabulary?.let { AttemptScoring.Tally("vocabulary", it, 5) },
            rating.fluency?.let { AttemptScoring.Tally("fluency", it, 5) },
        )
        val scoring = AttemptScoring(ilr = rating.ilr?.label, byType = factors, weakAreas = rating.nextSteps)
        val how = if (rating.engine != null) "practice estimate" else "self-rated"
        val summary = "OPI · " + (rating.ilr?.let { "ILR ${it.label} ($how)" } ?: "not rated")
        q.insertAttempt(
            id, ExamKind.OPI.name, "", ExamMode.INTERVIEW.name, startedAt.toEpochMilliseconds(), clock.now().toEpochMilliseconds(),
            lines.toString(), json.encodeToString(AttemptScoring.serializer(), scoring), summary, deviceId,
        )
        id
    }

    /** Transcript of a stored OPI interview as (speaker, text), speaker = "LEARNER" | "PARTNER". */
    @Throws(Exception::class)
    suspend fun opiTranscript(id: String): List<Pair<String, String>> = io {
        val row = q.attemptById(id).executeAsOneOrNull() ?: return@io emptyList()
        runCatching {
            json.parseToJsonElement(row.answers).let { it as JsonArray }.map { line ->
                val o = line as JsonObject
                (o["speaker"] as JsonPrimitive).content to (o["text"] as JsonPrimitive).content
            }
        }.getOrDefault(emptyList())
    }

    @Throws(Exception::class)
    suspend fun history(exam: ExamKind? = null, limit: Int = 50): List<AttemptSummary> = io {
        val rows = if (exam == null) q.allAttempts(limit.toLong()).executeAsList() else q.attempts(exam.name, limit.toLong()).executeAsList()
        rows.mapNotNull { summaryOf(it.id, it.exam, it.level, it.mode, it.submitted_at, it.summary, it.scoring) }
    }

    @Throws(Exception::class)
    suspend fun attempt(id: String): AttemptReview? {
        val row = io { q.attemptById(id).executeAsOneOrNull() } ?: return null
        val summary = summaryOf(row.id, row.exam, row.level, row.mode, row.submitted_at, row.summary, row.scoring) ?: return null
        val answers = runCatching { json.decodeFromString(ListSerializer(AnswerRecord.serializer()), row.answers) }.getOrDefault(emptyList())
        val items = itemsByIds(answers.map { it.itemId })
        val passages = passagesByIds(items.values.mapNotNull { it.passageId })
        return AttemptReview(summary, answers.mapNotNull { a -> items[a.itemId]?.let { ReviewedItem(it, a) } }, passages)
    }

    /**
     * Adds the grammar points ("g:") and dictionary words ("v:") behind missed items to reviews. Returns how many
     * were added; refs that aren't in the installed packs are skipped.
     */
    @Throws(Exception::class)
    suspend fun addToSrs(refs: List<String>): Int {
        var added = 0
        val grammarRefs = refs.filter { it.startsWith("g:") }.map { it.removePrefix("g:") }
        grammar()?.let { g ->
            val points = grammarRefs.mapNotNull { g.point(it)?.point }
            if (points.isNotEmpty()) {
                g.learn(points)
                added += points.size
            }
        }
        dictionary()?.let { d ->
            refs.filter { it.startsWith("v:") }.mapNotNull { it.removePrefix("v:").toLongOrNull() }.forEach { id ->
                val entry = d.entry(id)?.entry ?: return@forEach
                if (!collection.isInReviews(id)) {
                    collection.addToReviews(entry)
                    added++
                }
            }
        }
        return added
    }

    /** Imports a user item bank (JSON). Invalid banks are rejected with every problem listed. */
    @Throws(Exception::class)
    suspend fun importBank(text: String): BankImportResult {
        val bank = runCatching { json.decodeFromString(ExamBankFile.serializer(), text) }.getOrElse {
            return BankImportResult.Invalid(listOf("Not an item bank: ${it.message?.take(200)}"))
        }
        val id = if (bank.bank.startsWith(USER_PREFIX)) bank.bank else USER_PREFIX + bank.bank
        val errors = ExamBankValidator.validate(bank, blueprints())
        if (errors.isNotEmpty()) return BankImportResult.Invalid(errors)
        io { q.putBank(id, bank.title, json.encodeToString(ExamBankFile.serializer(), bank.copy(bank = id)), clock.now().toEpochMilliseconds()) }
        userBankCache = null
        return BankImportResult.Imported(id, bank.passages.size, bank.items.size)
    }

    @Throws(Exception::class)
    suspend fun userBanks(): List<ExamBankFile> = io {
        userBankCache ?: q.banks().executeAsList()
            .mapNotNull { runCatching { json.decodeFromString(ExamBankFile.serializer(), it.json) }.getOrNull() }
            .also { userBankCache = it }
    }

    @Throws(Exception::class)
    suspend fun deleteBank(id: String) {
        io { q.deleteBank(id) }
        userBankCache = null
    }

    private suspend fun pool(exam: ExamKind, levels: List<String>): Pair<List<ExamItem>, Map<String, ExamPassage>> {
        val packItems = io { levels.flatMap { level -> pack?.examQueries?.itemsFor(exam.name, level)?.executeAsList().orEmpty().map { it.toModel() } } }
        val user = userBanks()
        val userItems = user.flatMap { b -> b.items.filter { it.exam == exam.name && it.level in levels }.map { it.toModel(b.bank) } }
        val items = packItems + userItems
        val ids = items.mapNotNull { it.passageId }.toSet()
        val passages = passagesByIds(ids.toList())
        return items to passages
    }

    private suspend fun itemsByIds(ids: List<String>): Map<String, ExamItem> {
        val fromPack = io { ids.chunked(CHUNK).flatMap { chunk -> pack?.examQueries?.itemsByIds(chunk)?.executeAsList().orEmpty() }.map { it.toModel() } }
        val wanted = ids.toSet()
        val fromUser = userBanks().flatMap { b -> b.items.filter { it.id in wanted }.map { it.toModel(b.bank) } }
        return (fromPack + fromUser).associateBy { it.id }
    }

    private suspend fun passagesByIds(ids: List<String>): Map<String, ExamPassage> {
        val fromPack = io { ids.distinct().chunked(CHUNK).flatMap { chunk -> pack?.examQueries?.passagesByIds(chunk)?.executeAsList().orEmpty() }.map { it.toModel() } }
        val wanted = ids.toSet()
        val fromUser = userBanks().flatMap { b -> b.passages.filter { it.id in wanted }.map { it.toModel() } }
        return (fromPack + fromUser).associateBy { it.id }
    }

    private fun summaryOf(id: String, exam: String, level: String, mode: String, submittedAt: Long, summary: String, scoring: String): AttemptSummary? {
        val kind = ExamKind.entries.firstOrNull { it.name == exam } ?: return null
        val m = ExamMode.entries.firstOrNull { it.name == mode } ?: return null
        val s = runCatching { json.decodeFromString(AttemptScoring.serializer(), scoring) }.getOrDefault(AttemptScoring())
        return AttemptSummary(id, kind, level, m, Instant.fromEpochMilliseconds(submittedAt), summary, s)
    }

    private fun scriptOf(text: String): List<ScriptLine> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(ScriptLine.serializer()), text) }.getOrDefault(emptyList())

    private fun stringsOf(text: String): List<String> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), text) }.getOrDefault(emptyList())

    private fun Exam_item.toModel() = ExamItem(
        id, bank, ExamKind.valueOf(exam), level, type, passage_id, stem, stringsOf(choices), answer.toInt(), explanation,
        scriptOf(script), stringsOf(refs), source, verified != 0L,
    )

    private fun Exam_passage.toModel() = ExamPassage(id, ExamKind.valueOf(exam), level, text_type, title, body, scriptOf(script), source, verified != 0L)

    private fun ExamBankFile.ItemJson.toModel(bank: String) = ExamItem(
        id, bank, ExamKind.valueOf(exam), level, type, passageId, stem, choices, answer, explanation, script, refs, source, verified,
    )

    private fun ExamBankFile.PassageJson.toModel() = ExamPassage(id, ExamKind.valueOf(exam), level, textType, title, body, script, source, verified)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val BLUEPRINT_KEY = "jlpt_blueprints"
        const val USER_PREFIX = "user:"
        private const val CHUNK = 500
    }
}
