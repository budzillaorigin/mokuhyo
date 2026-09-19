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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What can be reviewed. [code] is the `kind` in the verdicts JSON that `tools/items/review.py --ingest` reads, and
 * names which source file it lives in and how its flag flips (docs/CONTENT_PACKS.md "Reviewing content", D-245…D-249).
 * A candidate's `id` is stable across pack rebuilds: the source id, or for id-less content a derived key
 * (`<track>:<JMdict id>`, `<track>:<kanji>`, `<ilr>:<reviewKey(prompt)>`, `g:<point>:<reviewKey(sentence)>`,
 * `d:<dialogue>:<line>`).
 */
enum class ReviewKind(val code: String, val label: String) {
    GRAMMAR_POINT("grammar_point", "Grammar point"),
    GRAMMAR_JA("grammar_ja", "Grammar point (Japanese explanation)"),
    EXAM_PASSAGE("exam_passage", "Exam passage"),
    EXAM_ITEM("exam_item", "Exam item"),
    DIALOGUE("dialogue", "Dialogue"),
    SCENARIO("scenario", "Role-play scenario"),
    OPI_QUESTION("opi_question", "OPI question"),
    DRILL_ITEM("drill_item", "Speaking drill line"),
    READER_PASSAGE("reader_passage", "Graded passage"),
    ONOMATOPOEIA("onomatopoeia", "Onomatopoeia feel"),
    PHONETIC_SERIES("phonetic_series", "Sound series (derived)"),
    TRACK_WORD("track_word", "Track word"),
    TRACK_KANJI("track_kanji", "Track kanji hint"),
    TRACK_SCENARIO("track_scenario", "Track role-play"),
    TRACK_DIALOGUE("track_dialogue", "Track dialogue"),
    TRACK_DRILL("track_drill", "Track drill"),
    TRACK_SITUATION("track_situation", "Track situation"),
    TRACK_TASK("track_task", "Track cultural task"),
    TRACK_READING("track_reading", "Track reading"),
    KANA_MNEMONIC("kana_mnemonic", "Kana mnemonic"),
    TRANSLATION_PASSAGE("translation_passage", "Translation passage"),
    EXPRESSION_CLUSTER("expression_cluster", "Expression cluster"),
    POEM_ANNOTATION("poem_annotation", "Poem paraphrase and gloss"),
    CIRCLE_TEXT("circle_text", "Reading-circle summary"),
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
// Each source reads an installed pack with raw queries on its own read-only connection (D-118), lists what is still
// "llm" (or, for exam banks, `verified = 0`, D-034) and renders everything a reviewer needs on the phone into
// [ReviewCandidate.display]. Kinds whose tables a pack predates are skipped, never an error.

/**
 * Runs a raw read on a pack driver (the pack schemas aren't ours to extend with queries here).
 *
 * [map] must only read the cursor. It must not issue another query on [this]: the native (iOS) driver hands out one
 * reader connection per pack, so a nested query waits forever for the connection this cursor holds. Read the rows
 * into a list first, then query per row (the JVM driver tolerates nesting, which is why this only shows on a device).
 */
internal fun <T> SqlDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> =
    executeQuery(null, sql, { c ->
        val out = ArrayList<T>()
        while (c.next().value) out += map(c)
        QueryResult.Value(out)
    }, 0).value

/** True when the pack has [table] (packs built before a phase lack its tables). */
internal fun SqlDriver.hasTable(table: String): Boolean =
    rows("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ${sqlText(table)}") { true }.isNotEmpty()

/** A SQL string literal. */
internal fun sqlText(s: String): String = "'" + s.replace("'", "''") + "'"

internal fun jsonList(raw: String?): List<String> =
    raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
        ?.map { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()

/** Lines of `[label]:` followed by indented [rows], or "" when there are none. */
internal fun section(label: String, rows: List<String>): String =
    if (rows.isEmpty()) "" else "$label:\n" + rows.joinToString("\n") { "  " + it.replace("\n", "\n  ") }

/** Non-empty [parts] joined by blank lines. */
internal fun blocks(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString("\n\n")

/** A multiple-choice question with its key marked `*`. */
internal fun choiceBlock(stem: String, choices: List<String>, answer: Int, explanation: String = ""): String =
    "Q: $stem\n" + choices.mapIndexed { i, s -> "${if (i == answer) "*" else " "} ${'A' + i}. $s" }.joinToString("\n") +
        if (explanation.isNotBlank()) "\nWhy: $explanation" else ""

/** Grammar points with `source = 'llm'`, and their Japanese explanations with `source = 'llm'` (grammar pack). */
class GrammarReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.GRAMMAR_POINT, ReviewKind.GRAMMAR_JA)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        val examples = driver.rows("SELECT point_id, ja, en, source FROM grammar_example ORDER BY point_id, ord") { c ->
            c.getString(0)!! to "• ${c.getString(1)}\n  ${c.getString(2)}${if (c.getString(3) == "llm") "  (AI)" else ""}"
        }.groupBy({ it.first }, { it.second })
        val points = driver.rows("SELECT id, jlpt, title, structure, meaning, nuance, mistakes, source FROM grammar_point WHERE source = 'llm' ORDER BY jlpt DESC, ord") { c ->
            val id = c.getString(0)!!
            ReviewCandidate(
                ReviewKind.GRAMMAR_POINT, id, "N${c.getLong(1)} · ${c.getString(2)}",
                mapOf("title" to c.getString(2)!!, "structure" to c.getString(3)!!, "meaning" to c.getString(4)!!, "nuance" to c.getString(5)!!),
                blocks(jsonList(c.getString(6)).joinToString("\n") { "! $it" }, section("Examples", examples[id].orEmpty())), c.getString(7)!!,
            )
        }
        if (!driver.hasTable("grammar_point_ja")) return@withContext points
        val japanese = driver.rows(
            "SELECT j.point_id, p.jlpt, p.title, p.structure, p.meaning, p.nuance, j.meaning_ja, j.nuance_ja, j.source " +
                "FROM grammar_point_ja j JOIN grammar_point p ON p.id = j.point_id WHERE j.source = 'llm' ORDER BY p.jlpt DESC, p.ord",
        ) { c ->
            ReviewCandidate(
                ReviewKind.GRAMMAR_JA, c.getString(0)!!, "N${c.getLong(1)} · ${c.getString(2)} (日本語)",
                mapOf("meaning_ja" to c.getString(6)!!, "nuance_ja" to c.getString(7)!!),
                blocks("Structure: ${c.getString(3)}", "English meaning: ${c.getString(4)}", "English nuance: ${c.getString(5)}"),
                c.getString(8)!!,
            )
        }
        points + japanese
    }
}

/**
 * Exam passages and items drafted by an LLM and still `verified = 0` (exam pack, JLPT and DLPT banks including the
 * upper-range and liaison banks; D-034: they keep `source` and flip `verified`). Rule-generated items aren't listed.
 */
class ExamReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.EXAM_PASSAGE, ReviewKind.EXAM_ITEM)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        val passages = driver.rows("SELECT id, exam, level, title, body, script, source, text_type FROM exam_passage WHERE verified = 0 AND source = 'llm' ORDER BY bank, id") { c ->
            ReviewCandidate(
                ReviewKind.EXAM_PASSAGE, c.getString(0)!!, "${c.getString(1)} ${c.getString(2)} · ${c.getString(3)}",
                mapOf("title" to c.getString(3)!!, "body" to c.getString(4)!!),
                blocks("Text type: ${c.getString(7)}", section("Script", scriptLines(c.getString(5)))), c.getString(6)!!,
            )
        }
        val items = driver.rows("SELECT id, exam, level, type, stem, choices, answer, explanation, source, passage_id, script FROM exam_item WHERE verified = 0 AND source = 'llm' ORDER BY bank, passage_id, ord") { c ->
            val choices = jsonList(c.getString(5))
            val answer = c.getLong(6)?.toInt() ?: -1
            ReviewCandidate(
                ReviewKind.EXAM_ITEM, c.getString(0)!!, "${c.getString(1)} ${c.getString(2)} · ${c.getString(3)}",
                mapOf("stem" to c.getString(4)!!, "explanation" to c.getString(7)!!),
                blocks(
                    choices.mapIndexed { i, s -> "${if (i == answer) "*" else " "} ${'A' + i}. $s" }.joinToString("\n"),
                    c.getString(9)?.let { "Passage: $it" }.orEmpty(), section("Script", scriptLines(c.getString(10))),
                ),
                c.getString(8)!!,
            )
        }
        passages + items
    }

    private fun scriptLines(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?.mapNotNull { it as? JsonObject }
            ?.map { "${it.str("speaker")} (${it.str("voice")}): ${it.str("text")}" }.orEmpty()
}

internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

/**
 * The practice pack: dialogues (scripted and natural), role-play scenarios, OPI questions (with their DLI domain) and
 * the speaking-drill lines (each a grammar point's own example or a dialogue line, D-247), all with `source = 'llm'`.
 */
class PracticeReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.DIALOGUE, ReviewKind.SCENARIO, ReviewKind.OPI_QUESTION, ReviewKind.DRILL_ITEM)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        dialogues() + scenarios() + opiQuestions() + drillItems()
    }

    private fun dialogues(): List<ReviewCandidate> =
        driver.rows("SELECT id, jlpt, title, topic, source, style, speakers FROM dialogue WHERE source = 'llm' ORDER BY ord") { c ->
            listOf(c.getString(0)!!, c.getLong(1).toString(), c.getString(2)!!, c.getString(3)!!, c.getString(4)!!, c.getString(5)!!, c.getString(6)!!)
        }.map { row ->
            val (id, jlpt, title, topic, source) = row
            val style = row[5]
            val names = speakerNames(row[6])
            val lines = driver.rows("SELECT speaker, ja, en FROM dialogue_line WHERE dialogue_id = ${sqlText(id)} ORDER BY ord") { c ->
                "${names[c.getString(0)] ?: c.getString(0)}: ${c.getString(1)}\n  ${c.getString(2)}"
            }
            val questions = driver.rows("SELECT question_en, choices, answer FROM dialogue_question WHERE dialogue_id = ${sqlText(id)} ORDER BY ord") { c ->
                choiceBlock(c.getString(0)!!, jsonList(c.getString(1)), c.getLong(2)!!.toInt())
            }
            ReviewCandidate(
                ReviewKind.DIALOGUE, id, "N$jlpt · $title", mapOf("title" to title, "topic" to topic),
                blocks("Style: $style", lines.joinToString("\n"), questions.joinToString("\n\n")), source,
            )
        }

    // Every column is read while the cursor is open; the turns query runs only after it closes, like dialogues()
    // above. A query issued from inside another query's cursor deadlocks the native driver (see rows()).
    private fun scenarios(): List<ReviewCandidate> =
        driver.rows("SELECT id, jlpt, title_en, title_ja, setting, learner_role, partner_role, source, ilr, register, goals, phrases FROM scenario WHERE source = 'llm' ORDER BY ord") { c ->
            listOf(
                c.getString(0)!!, c.getLong(1).toString(), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                c.getString(6), c.getString(7)!!, c.getString(8), c.getString(9), c.getString(10), c.getString(11),
            )
        }.map { row ->
            val id = row[0]!!
            ReviewCandidate(
                ReviewKind.SCENARIO, id, "N${row[1]} · ${row[2]}",
                mapOf(
                    "titleEn" to row[2]!!, "titleJa" to row[3]!!, "setting" to row[4]!!,
                    "learnerRole" to row[5]!!, "partnerRole" to row[6]!!,
                ),
                blocks(
                    "ILR ${row[8]} · ${row[9]}", section("Goals", jsonList(row[10])),
                    section("Phrases", jsonList(row[11])), section("Scripted turns", turns("scripted_turn", id)),
                ),
                row[7]!!,
            )
        }

    private fun turns(table: String, scenarioId: String): List<String> =
        driver.rows("SELECT partner_ja, partner_en, intent, sample_answer FROM $table WHERE scenario_id = ${sqlText(scenarioId)} ORDER BY ord") { c ->
            "${c.getString(0)}  (${c.getString(1)})\n→ ${c.getString(2)}: ${c.getString(3)}"
        }

    private fun opiQuestions(): List<ReviewCandidate> =
        driver.rows("SELECT ilr, ord, phase, prompt_ja, prompt_en, note, domain, source FROM opi_question WHERE source = 'llm'") { c ->
            val ilr = c.getString(0)!!
            val ja = c.getString(3)!!
            Triple(ILR_ORDER.indexOf(ilr), c.getLong(1)!!, ReviewCandidate(
                ReviewKind.OPI_QUESTION, "$ilr:${reviewKey(ja)}", "ILR $ilr · ${c.getString(2)} · $ja",
                mapOf("ja" to ja, "en" to c.getString(4)!!, "note" to c.getString(5)!!),
                "Phase: ${c.getString(2)}\nDomain: ${c.getString(6)!!.ifEmpty { "-" }}", c.getString(7)!!,
            ))
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }

    private fun drillItems(): List<ReviewCandidate> =
        driver.rows(
            "SELECT s.title, i.prompt_en, i.answer_ja, i.audio_key, i.ref, i.source FROM drill_item i JOIN drill_set s ON s.id = i.set_id " +
                "WHERE i.source = 'llm' ORDER BY s.ord, i.ord",
        ) { c ->
            val answer = c.getString(2)!!
            val ref = c.getString(4)!!
            val id = when {
                ref.startsWith("g:") -> "g:${ref.removePrefix("g:")}:${reviewKey(answer)}"
                ref.startsWith("d:") -> "d:${ref.removePrefix("d:")}:${c.getString(3)!!.substringAfterLast('/')}"
                else -> null
            }
            id?.let {
                ReviewCandidate(
                    ReviewKind.DRILL_ITEM, it, "${c.getString(0)} · $answer", mapOf("en" to c.getString(1)!!),
                    blocks(
                        "Answer: $answer",
                        if (ref.startsWith("g:")) "Example sentence of grammar point ${ref.removePrefix("g:")}"
                        else "Line ${c.getString(3)!!.substringAfterLast('/')} of dialogue ${ref.removePrefix("d:")}",
                    ),
                    c.getString(5)!!,
                )
            }
        }.filterNotNull().distinctBy { it.id }

    private companion object {
        val ILR_ORDER = listOf("0+", "1", "1+", "2", "2+", "3", "3+", "4")
    }
}

/** `{"A": "Name", …}` from a pack's speakers JSON (`[{id, name, voice, age}]`). */
internal fun speakerNames(raw: String?): Map<String, String> =
    raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
        ?.mapNotNull { it as? JsonObject }?.associate { it.str("id") to it.str("name") }.orEmpty()

/**
 * Graded-reader stories still unverified in readers.sqlite (Phase 12). Title and body are editable; the display lists
 * the vocabulary and the questions with their keys, so the owner checks them in the same pass. `review.py --ingest`
 * applies verdicts to the story files in tools/packs/readers/stories (source "verified" and verified true, D-246).
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
            val questions = driver.rows("SELECT stem, choices, answer, explanation FROM reader_question WHERE story_id = ${sqlText(id)} ORDER BY ord") { c ->
                val choices = jsonList(c.getString(1))
                val key = c.getLong(2)!!.toInt()
                "Q: ${c.getString(0)}\n" + choices.mapIndexed { i, ch -> (if (i == key) "  * " else "    ") + ch }.joinToString("\n") +
                    c.getString(3)!!.let { if (it.isBlank()) "" else "\n  Why: $it" }
            }
            val vocab = driver.rows("SELECT word, reading, gloss FROM reader_vocab WHERE story_id = ${sqlText(id)} ORDER BY ord") { c ->
                "${c.getString(0)}【${c.getString(1)}】 ${c.getString(2)}"
            }
            ReviewCandidate(
                ReviewKind.READER_PASSAGE, id, "$level · $genre · $title ($titleEn)", mapOf("title" to title, "body" to body),
                blocks(questions.joinToString("\n\n"), section("Vocabulary", vocab)), source,
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
