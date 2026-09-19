package app.tsumugi.review

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The linguist pack's AI-drafted content (Phase 13, D-279): translation passages with their references, poem
 * annotations (paraphrase, gloss, note, vocabulary; the poems themselves are public domain and never reviewed) and the
 * reading-circle summaries. `review.py --ingest` applies verdicts to the translation/passages batch files,
 * the literature/poems files and tools/packs/literature/circle.json (source "verified" + verified true).
 */
class LinguistReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.TRANSLATION_PASSAGE, ReviewKind.POEM_ANNOTATION, ReviewKind.CIRCLE_TEXT)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        passages() + poems() + circle()
    }

    private fun passages(): List<ReviewCandidate> {
        if (!driver.hasTable("translation_passage")) return emptyList()
        return driver.rows(
            "SELECT id, direction, genre, level, ilr, title, text, reference, register, key_points, notes, origin_kind, origin_ref, source " +
                "FROM translation_passage WHERE source = 'llm' AND verified = 0 ORDER BY ord",
        ) { c ->
            val origin = c.getString(11)!!
            ReviewCandidate(
                ReviewKind.TRANSLATION_PASSAGE, c.getString(0)!!,
                "${c.getString(2)} · ${c.getString(1)} · ${c.getString(3)} · ${c.getString(5)}",
                mapOf("title" to c.getString(5)!!, "text" to c.getString(6)!!, "reference" to c.getString(7)!!, "notes" to c.getString(10)!!),
                blocks(
                    "ILR ${c.getString(4)} · register: ${c.getString(8)}",
                    section("Key points", jsonList(c.getString(9))),
                    if (origin == "original") "" else "Excerpt: $origin ${c.getString(12).orEmpty()} (the source text is fixed; review the reference)",
                ),
                c.getString(13)!!,
            )
        }
    }

    private fun poems(): List<ReviewCandidate> {
        if (!driver.hasTable("poem")) return emptyList()
        return driver.rows("SELECT id, title, title_en, author, body, vocabulary, paraphrase, gloss, note, source FROM poem WHERE source = 'llm' AND verified = 0 ORDER BY ord") { c ->
            val vocab = runCatching { Json.parseToJsonElement(c.getString(5)!!) as JsonArray }.getOrNull()
                ?.mapNotNull { it as? JsonObject }?.map { "${it.str("word")}【${it.str("reading")}】 ${it.str("gloss")}" }.orEmpty()
            ReviewCandidate(
                ReviewKind.POEM_ANNOTATION, c.getString(0)!!, "${c.getString(3)} · ${c.getString(1)} (${c.getString(2)})",
                mapOf("titleEn" to c.getString(2)!!, "paraphrase" to c.getString(6)!!, "gloss" to c.getString(7)!!, "note" to c.getString(8)!!),
                blocks("Poem (public domain):\n" + c.getString(4)!!, section("Vocabulary", vocab)),
                c.getString(9)!!,
            )
        }
    }

    private fun circle(): List<ReviewCandidate> {
        if (!driver.hasTable("circle_text")) return emptyList()
        return driver.rows("SELECT id, title, title_en, author, level, summary_en, body, source FROM circle_text WHERE source = 'llm' ORDER BY ord") { c ->
            val body = c.getString(6)!!
            ReviewCandidate(
                ReviewKind.CIRCLE_TEXT, c.getString(0)!!, "${c.getString(3)} · ${c.getString(1)}",
                mapOf("titleEn" to c.getString(2)!!, "summaryEn" to c.getString(5)!!),
                "Level ${c.getString(4)}\n\n" + body.take(PREVIEW) + if (body.length > PREVIEW) "…" else "",
                c.getString(7)!!,
            )
        }
    }

    private companion object {
        const val PREVIEW = 600
    }
}

/**
 * Expression-thesaurus clusters in the dictionary pack (D-272, D-279): the English name and description are editable;
 * the display lists every expression with its nuance, register, strength and drafted example. `review.py --ingest`
 * applies verdicts to the tools/packs/thesaurus/clusters files.
 */
class ThesaurusReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.EXPRESSION_CLUSTER)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        if (!driver.hasTable("expression_cluster")) return@withContext emptyList()
        driver.rows("SELECT id, kind, ja, reading, en, description, source FROM expression_cluster WHERE source = 'llm' AND verified = 0 ORDER BY ord") { c ->
            List(7) { c.getString(it)!! }
        }.map { row ->
            val id = row[0]
            val expressions = driver.rows(
                "SELECT text, reading, register, intensity, nuance, example_ja, example_en, entry_id FROM expression WHERE cluster_id = ${sqlText(id)} ORDER BY ord",
            ) { c ->
                "${c.getString(0)}【${c.getString(1)}】 ${c.getString(2)} · ${"●".repeat(c.getLong(3)!!.toInt())}${if (c.getLong(7) == null) " · not in JMdict" else ""}\n" +
                    "${c.getString(4)}\n• ${c.getString(5)}\n  ${c.getString(6)}"
            }
            val plain = driver.rows("SELECT lemma FROM expression_plain WHERE cluster_id = ${sqlText(id)} ORDER BY lemma") { it.getString(0)!! }
            ReviewCandidate(
                ReviewKind.EXPRESSION_CLUSTER, id, "${row[1]} · ${row[2]} (${row[4]})",
                mapOf("en" to row[4], "description" to row[5]),
                blocks("Flags: ${plain.joinToString("、")}", section("Expressions", expressions)),
                row[6],
            )
        }
    }
}
