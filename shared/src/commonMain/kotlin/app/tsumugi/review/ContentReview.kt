package app.tsumugi.review

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.kana.KanaGroup
import app.tsumugi.kana.KanaTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What can be reviewed. [code] is the `kind` in the verdicts JSON that `tools/items/review.py --ingest` reads, and
 * names which source file it lives in (docs/CONTENT_PACKS.md).
 */
enum class ReviewKind(val code: String, val label: String) {
    GRAMMAR_POINT("grammar_point", "Grammar point"),
    EXAM_PASSAGE("exam_passage", "Exam passage"),
    EXAM_ITEM("exam_item", "Exam item"),
    DIALOGUE("dialogue", "Dialogue"),
    SCENARIO("scenario", "Role-play scenario"),
    READER_PASSAGE("reader_passage", "Graded passage"),
    KANA_MNEMONIC("kana_mnemonic", "Kana mnemonic"),
    ;

    companion object {
        fun of(code: String): ReviewKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One unverified piece of content. [fields] are the editable text fields by their source-file names (e.g.
 * `meaning`, `nuance` for a grammar point; `stem`, `explanation` for an exam item), so an edit maps back 1:1.
 * [display] is a read-only rendering of everything else (choices, script lines) for context.
 */
data class ReviewCandidate(
    val kind: ReviewKind,
    val id: String,
    val title: String,
    val fields: Map<String, String>,
    val display: String,
    val source: String,
)

enum class Verdict(val code: String) {
    ACCEPT("accept"), EDIT("edit"), REJECT("reject");

    companion object {
        fun of(code: String): Verdict? = entries.firstOrNull { it.code == code }
    }
}

data class ReviewVerdict(
    val kind: ReviewKind,
    val id: String,
    val verdict: Verdict,
    val notes: String,
    /** Changed fields only (field → new text), for [Verdict.EDIT]. */
    val edits: Map<String, String>,
    val decidedAt: Long,
)

/** The file `tools/items/review.py --ingest` reads (format documented there and in D-118). */
@Serializable
data class VerdictsFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val reviewer: String,
    val exportedAt: String,
    val verdicts: List<VerdictEntry>,
) {
    companion object {
        const val FORMAT = "tsumugi-review-verdicts"
    }
}

@Serializable
data class VerdictEntry(
    val kind: String,
    val id: String,
    val verdict: String,
    val notes: String = "",
    val edits: Map<String, String> = emptyMap(),
    val decidedAt: String,
)

/** Loads unverified candidates of some kinds from an installed pack. */
interface ReviewSource {
    val kinds: Set<ReviewKind>

    @Throws(Exception::class)
    suspend fun candidates(): List<ReviewCandidate>
}

/**
 * The in-app content review (BRIEF_V2 G-16, DECISIONS D-118), behind a developer toggle in Me: it lists AI-drafted
 * content still unverified in the installed packs, records the owner's verdicts in a device-local table, and
 * exports them as JSON for `tools/items/review.py --ingest`, which applies them to the source files (the only
 * place `source`/`verified` flip, rule 10 and D-034). Nothing here changes a pack or a badge in the app; the badge
 * goes away when the rebuilt pack ships.
 */
class ContentReviewService(
    private val db: TsumugiDatabase,
    private val sources: suspend () -> List<ReviewSource>,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.contentReviewQueries
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }
    private val editsJson = Json

    /** Every unverified candidate, optionally of one kind, with its verdict so far (null = not reviewed yet). */
    @Throws(Exception::class)
    suspend fun queue(kind: ReviewKind? = null): List<Pair<ReviewCandidate, ReviewVerdict?>> {
        val all = sources().filter { kind == null || kind in it.kinds }.flatMap { it.candidates() }.filter { kind == null || it.kind == kind }
        val verdicts = verdicts().associateBy { it.kind to it.id }
        return all.map { it to verdicts[it.kind to it.id] }
    }

    /** Counts of unverified candidates and decided verdicts per kind, for the list screen. */
    @Throws(Exception::class)
    suspend fun summary(): Map<ReviewKind, Pair<Int, Int>> {
        val q = queue()
        return q.groupBy { it.first.kind }.mapValues { (_, v) -> v.size to v.count { it.second != null } }
    }

    /** Records a verdict (replacing an earlier one). EDIT keeps only fields that really changed. */
    @Throws(Exception::class)
    suspend fun decide(candidate: ReviewCandidate, verdict: Verdict, notes: String = "", edits: Map<String, String> = emptyMap()): ReviewVerdict {
        val changed = edits.filter { (k, v) -> k in candidate.fields && candidate.fields[k] != v }
        val effective = if (verdict == Verdict.EDIT && changed.isEmpty()) Verdict.ACCEPT else verdict
        val now = clock.now().toEpochMilliseconds()
        val kept = if (effective == Verdict.EDIT) changed else emptyMap()
        withContext(Dispatchers.IO) {
            q.putVerdict(candidate.kind.code, candidate.id, effective.code, notes.trim(), editsJson.encodeToString(EDITS, kept), now)
        }
        return ReviewVerdict(candidate.kind, candidate.id, effective, notes.trim(), kept, now)
    }

    @Throws(Exception::class)
    suspend fun undo(kind: ReviewKind, id: String) = withContext(Dispatchers.IO) { q.removeVerdict(kind.code, id) }

    @Throws(Exception::class)
    suspend fun verdicts(): List<ReviewVerdict> = withContext(Dispatchers.IO) {
        q.allVerdicts().executeAsList().mapNotNull { r ->
            val kind = ReviewKind.of(r.kind) ?: return@mapNotNull null
            val verdict = Verdict.of(r.verdict) ?: return@mapNotNull null
            val edits = runCatching { editsJson.decodeFromString(EDITS, r.edits) }.getOrDefault(emptyMap())
            ReviewVerdict(kind, r.item_id, verdict, r.notes, edits, r.decided_at)
        }
    }

    /** The verdicts JSON for review.py (all verdicts so far). */
    @Throws(Exception::class)
    suspend fun exportJson(reviewer: String): String {
        val file = VerdictsFile(
            reviewer = reviewer.trim().ifEmpty { "owner" },
            exportedAt = clock.now().toString(),
            verdicts = verdicts().map {
                VerdictEntry(it.kind.code, it.id, it.verdict.code, it.notes, it.edits, Instant.fromEpochMilliseconds(it.decidedAt).toString())
            },
        )
        return json.encodeToString(VerdictsFile.serializer(), file)
    }

    /** After the build has ingested an export: forget the verdicts (the rebuilt packs no longer list those items). */
    @Throws(Exception::class)
    suspend fun clear() = withContext(Dispatchers.IO) { q.clearVerdicts() }

    private companion object {
        val EDITS = MapSerializer(String.serializer(), String.serializer())
    }
}

// --- Pack sources ---------------------------------------------------------------------------------------------

/** Runs a raw read on a pack driver (the pack schemas aren't ours to extend with queries here). */
private fun <T> SqlDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> =
    executeQuery(null, sql, { c ->
        val out = ArrayList<T>()
        while (c.next().value) out += map(c)
        QueryResult.Value(out)
    }, 0).value

private fun jsonList(raw: String?): List<String> =
    raw?.let { runCatching { Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonArray }.getOrNull() }
        ?.map { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()

/** Grammar points with `source = 'llm'` (grammar pack). */
class GrammarReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.GRAMMAR_POINT)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        driver.rows("SELECT id, jlpt, title, structure, meaning, nuance, mistakes, source FROM grammar_point WHERE source = 'llm' ORDER BY jlpt DESC, ord") { c ->
            ReviewCandidate(
                ReviewKind.GRAMMAR_POINT, c.getString(0)!!, "N${c.getLong(1)} · ${c.getString(2)}",
                mapOf("title" to c.getString(2)!!, "structure" to c.getString(3)!!, "meaning" to c.getString(4)!!, "nuance" to c.getString(5)!!),
                jsonList(c.getString(6)).joinToString("\n") { "! $it" }, c.getString(7)!!,
            )
        }
    }
}

/** Exam passages and items with `verified = 0` (exam pack; D-034: they keep `source` and flip `verified`). */
class ExamReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.EXAM_PASSAGE, ReviewKind.EXAM_ITEM)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        val passages = driver.rows("SELECT id, exam, level, title, body, script, source FROM exam_passage WHERE verified = 0 ORDER BY bank, id") { c ->
            ReviewCandidate(
                ReviewKind.EXAM_PASSAGE, c.getString(0)!!, "${c.getString(1)} ${c.getString(2)} · ${c.getString(3)}",
                mapOf("title" to c.getString(3)!!, "body" to c.getString(4)!!), c.getString(5).orEmpty(), c.getString(6)!!,
            )
        }
        val items = driver.rows("SELECT id, exam, level, type, stem, choices, answer, explanation, source FROM exam_item WHERE verified = 0 ORDER BY bank, passage_id, ord") { c ->
            val choices = jsonList(c.getString(5))
            val answer = c.getLong(6)?.toInt() ?: -1
            ReviewCandidate(
                ReviewKind.EXAM_ITEM, c.getString(0)!!, "${c.getString(1)} ${c.getString(2)} · ${c.getString(3)}",
                mapOf("stem" to c.getString(4)!!, "explanation" to c.getString(7)!!),
                choices.mapIndexed { i, s -> "${if (i == answer) "*" else " "} ${'A' + i}. $s" }.joinToString("\n"), c.getString(8)!!,
            )
        }
        passages + items
    }
}

/** Dialogues and role-play scenarios with `source = 'llm'` (practice pack). */
class PracticeReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.DIALOGUE, ReviewKind.SCENARIO)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        val dialogues = driver.rows("SELECT id, jlpt, title, topic, source FROM dialogue WHERE source = 'llm' ORDER BY ord") { c ->
            listOf(c.getString(0)!!, c.getLong(1).toString(), c.getString(2)!!, c.getString(3)!!, c.getString(4)!!)
        }.map { (id, jlpt, title, topic, source) ->
            val lines = driver.rows("SELECT speaker, ja, en FROM dialogue_line WHERE dialogue_id = '${id.replace("'", "''")}' ORDER BY ord") { c ->
                "${c.getString(0)}: ${c.getString(1)}  (${c.getString(2)})"
            }
            ReviewCandidate(ReviewKind.DIALOGUE, id, "N$jlpt · $title", mapOf("title" to title, "topic" to topic), lines.joinToString("\n"), source)
        }
        val scenarios = driver.rows("SELECT id, jlpt, title_en, title_ja, setting, learner_role, partner_role, source FROM scenario WHERE source = 'llm' ORDER BY ord") { c ->
            ReviewCandidate(
                ReviewKind.SCENARIO, c.getString(0)!!, "N${c.getLong(1)} · ${c.getString(2)}",
                mapOf("titleEn" to c.getString(2)!!, "titleJa" to c.getString(3)!!, "setting" to c.getString(4)!!),
                "Learner: ${c.getString(5)}\nPartner: ${c.getString(6)}", c.getString(7)!!,
            )
        }
        dialogues + scenarios
    }
}

/**
 * Graded-reader stories still unverified in readers.sqlite (Phase 12). Title and body are editable; the display lists
 * the questions with their keys, so the owner checks them in the same pass. `review.py --ingest` applies verdicts to
 * the story files in tools/packs/readers/stories.
 */
class ReadersReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.READER_PASSAGE)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        driver.rows("SELECT id, level, genre, title, title_en, body, source FROM reader_story WHERE source = 'llm' AND verified = 0 ORDER BY jlpt DESC, ord") { c ->
            List(7) { c.getString(it)!! }
        }.map { row ->
            val (id, level, genre, title, titleEn) = row
            val body = row[5]
            val source = row[6]
            val questions = driver.rows("SELECT stem, choices, answer FROM reader_question WHERE story_id = '${id.replace("'", "''")}' ORDER BY ord") { c ->
                val choices = jsonList(c.getString(1))
                val key = c.getLong(2)!!.toInt()
                "Q: ${c.getString(0)}\n" + choices.mapIndexed { i, ch -> (if (i == key) "  * " else "    ") + ch }.joinToString("\n")
            }
            ReviewCandidate(
                ReviewKind.READER_PASSAGE, id, "$level · $genre · $title ($titleEn)", mapOf("title" to title, "body" to body),
                questions.joinToString("\n\n"), source,
            )
        }
    }
}

/** The kana course's mnemonics (compiled into the app; `tools/items/review.py` records verdicts for them in a sidecar). */
object KanaMnemonicReviewSource : ReviewSource {
    override val kinds = setOf(ReviewKind.KANA_MNEMONIC)

    override suspend fun candidates(): List<ReviewCandidate> =
        KanaTable.all.filter { it.group == KanaGroup.BASE && it.mnemonicSource == "llm" }.map {
            ReviewCandidate(ReviewKind.KANA_MNEMONIC, it.kana, "${it.kana} (${it.romaji.first()})", mapOf("mnemonic" to it.mnemonic), it.script.name.lowercase(), it.mnemonicSource)
        }
}
