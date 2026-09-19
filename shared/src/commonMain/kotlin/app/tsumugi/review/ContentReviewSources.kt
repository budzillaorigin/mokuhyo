package app.tsumugi.review

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A stable key for id-less text (OPI prompts, grammar example sentences used as drill lines): FNV-1a 32 over the UTF-8
 * bytes of the (NFC, as stored in the packs) text, as 8 lowercase hex digits. `tools/items/review.py` computes the
 * same (`review_key`) to find the text in the source files (D-247).
 */
fun reviewKey(text: String): String {
    var h = 0x811C9DC5u
    for (b in text.encodeToByteArray()) {
        h = (h xor (b.toUInt() and 0xFFu)) * 0x01000193u
    }
    return h.toString(16).padStart(8, '0')
}

/**
 * Onomatopoeia feel lines drafted by an LLM (`source = 'llm'` in the dictionary pack's `onomatopoeia` table, Phase 12).
 * The feel lines are editable; the display shows the word's forms, type, theme, JMdict glosses and its Tatoeba examples.
 * `review.py --ingest` applies verdicts to tools/packs/onomatopoeia/entries.json by JMdict entry id (D-248).
 */
class OnomatopoeiaReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.ONOMATOPOEIA)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        if (!driver.hasTable("onomatopoeia")) return@withContext emptyList()
        class Row(val id: Long, val text: String, val variants: List<String>, val type: String, val theme: String, val gloss: List<String>, val feel: String, val feelJa: String, val examples: List<Long>)
        val rows = driver.rows("SELECT entry_id, text, variants, type, theme, gloss, feel, feel_ja, examples FROM onomatopoeia WHERE source = 'llm' ORDER BY ord") { c ->
            Row(
                c.getLong(0)!!, c.getString(1)!!, jsonList(c.getString(2)), c.getString(3)!!, c.getString(4)!!, jsonList(c.getString(5)),
                c.getString(6)!!, c.getString(7)!!, jsonList(c.getString(8)).mapNotNull { it.toLongOrNull() },
            )
        }
        val ids = rows.flatMap { it.examples }.distinct()
        val sentences = if (ids.isEmpty() || !driver.hasTable("sentence")) emptyMap() else
            ids.chunked(500).flatMap { chunk ->
                driver.rows("SELECT id, ja, en FROM sentence WHERE id IN (${chunk.joinToString(",")})") { c -> c.getLong(0)!! to "${c.getString(1)}\n  ${c.getString(2)}" }
            }.toMap()
        rows.map { r ->
            ReviewCandidate(
                ReviewKind.ONOMATOPOEIA, r.id.toString(), "${r.text} · ${r.type.lowercase()} · ${r.theme}",
                mapOf("feel" to r.feel, "feel_ja" to r.feelJa),
                blocks(
                    if (r.variants.isEmpty()) "" else "Also: ${r.variants.joinToString("、")}",
                    "JMdict: ${r.gloss.joinToString("; ")}",
                    section("Examples (Tatoeba)", r.examples.mapNotNull { sentences[it] }),
                ),
                "llm",
            )
        }
    }
}

/**
 * Sound series derived by `tools/packs/build_phonetics.py` (`source = 'derived'` in the dictionary pack's
 * `phonetic_series`, Phase 13, D-282). Not AI content, but labeled "derived" until a human confirms the family. The
 * members (one string of kanji, head first) and readings (space-separated on'yomi) are editable; the display lists
 * each member's on'yomi and how it matched. `review.py --ingest` applies verdicts to tools/packs/phonetics/series.json by
 * the phonetic component.
 */
class PhoneticSeriesReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(ReviewKind.PHONETIC_SERIES)

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        if (!driver.hasTable("phonetic_series")) return@withContext emptyList()
        driver.rows("SELECT phonetic, readings, members FROM phonetic_series WHERE source = 'derived' ORDER BY size DESC, phonetic") { c ->
            val id = c.getString(0)!!
            val readings = jsonList(c.getString(1))
            val members = (runCatching { Json.parseToJsonElement(c.getString(2)!!) as? JsonArray }.getOrNull() ?: JsonArray(emptyList()))
                .mapNotNull { it as? JsonObject }
            ReviewCandidate(
                ReviewKind.PHONETIC_SERIES, id, "$id · ${readings.joinToString("・")} · ${members.size} kanji",
                mapOf("members" to members.joinToString("") { it.str("k") }, "readings" to readings.joinToString(" ")),
                blocks(
                    section("Members", members.map { m -> "${m.str("k")}  ${jsonList(m["on"]?.toString()).joinToString("・")}  (${m.str("match")})" }),
                    "Derived from KanjiVG component trees and KANJIDIC2 on'yomi; not reviewed yet.",
                ),
                "derived",
            )
        }
    }
}

/**
 * Everything in tracks.sqlite still `source = 'llm'` (Phase 12 interest and domain tracks): words, kanji hints,
 * role-plays, dialogues, drills, can-do situations, cultural tasks and ILR readings. Ids are the source ids, or
 * `<track>:<JMdict id>` for words and `<track>:<kanji>` for kanji. `review.py --ingest` applies verdicts to
 * tools/packs/tracks/<track>[.<part>].json, setting source "verified" and verified true (D-246).
 */
class TracksReviewSource(private val driver: SqlDriver) : ReviewSource {
    override val kinds = setOf(
        ReviewKind.TRACK_WORD, ReviewKind.TRACK_KANJI, ReviewKind.TRACK_SCENARIO, ReviewKind.TRACK_DIALOGUE,
        ReviewKind.TRACK_DRILL, ReviewKind.TRACK_SITUATION, ReviewKind.TRACK_TASK, ReviewKind.TRACK_READING,
    )

    override suspend fun candidates(): List<ReviewCandidate> = withContext(Dispatchers.IO) {
        if (!driver.hasTable("track")) return@withContext emptyList()
        val titles = driver.rows("SELECT id, title_en FROM track ORDER BY ord") { c -> c.getString(0)!! to c.getString(1)!! }
        val order = titles.map { it.first }
        val names = titles.toMap()
        fun t(id: String) = names[id] ?: id
        val all = words(::t) + kanji(::t) + scenarios(::t) + dialogues(::t) + drills(::t) + situations(::t) + tasks(::t) + readings(::t)
        // Group by track in display order, keeping each track's kinds in the order above.
        all.withIndex().sortedWith(compareBy({ order.indexOf(it.value.first) }, { it.index })).map { it.value.second }
    }

    private fun words(t: (String) -> String) =
        driver.rows("SELECT track_id, entry_id, text, reading, gloss, topic, category, jlpt, note FROM track_word WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            val track = c.getString(0)!!
            track to ReviewCandidate(
                ReviewKind.TRACK_WORD, "$track:${c.getLong(1)}", "${t(track)} · ${c.getString(2)}【${c.getString(3)}】",
                mapOf("gloss" to c.getString(4)!!, "note" to c.getString(8)!!),
                listOfNotNull(
                    c.getString(5)?.takeIf { it.isNotBlank() }?.let { "Topic: $it" },
                    c.getString(6)?.takeIf { it.isNotBlank() }?.let { "Category: $it" },
                    c.getLong(7)?.let { "JMdict JLPT N$it" },
                    "JMdict entry ${c.getLong(1)}",
                ).joinToString("\n"),
                "llm",
            )
        }

    private fun kanji(t: (String) -> String) =
        driver.rows("SELECT track_id, literal, keyword, components, breakdown, hint, words FROM track_kanji WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            val track = c.getString(0)!!
            track to ReviewCandidate(
                ReviewKind.TRACK_KANJI, "$track:${c.getString(1)}", "${t(track)} · ${c.getString(1)} ${c.getString(2)}",
                mapOf("keyword" to c.getString(2)!!, "breakdown" to c.getString(4)!!, "hint" to c.getString(5)!!),
                "Components: ${jsonList(c.getString(3)).joinToString(" + ")}\nUsed by ${jsonList(c.getString(6)).size} track words",
                "llm",
            )
        }

    private fun scenarios(t: (String) -> String) =
        driver.rows("SELECT id, track_id, title_en, title_ja, jlpt, ilr, setting, learner_role, partner_role, register, goals, phrases FROM track_scenario WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            listOf(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getString(3)!!, c.getLong(4).toString(), c.getString(5)!!, c.getString(6)!!, c.getString(7)!!, c.getString(8)!!, c.getString(9)!!, c.getString(10)!!, c.getString(11)!!)
        }.map { r ->
            val turns = driver.rows("SELECT partner_ja, partner_en, intent, sample_answer FROM track_scripted_turn WHERE scenario_id = ${sqlText(r[0])} ORDER BY ord") { c ->
                "${c.getString(0)}  (${c.getString(1)})\n→ ${c.getString(2)}: ${c.getString(3)}"
            }
            r[1] to ReviewCandidate(
                ReviewKind.TRACK_SCENARIO, r[0], "${t(r[1])} · N${r[4]} · ${r[2]}",
                mapOf("titleEn" to r[2], "titleJa" to r[3], "setting" to r[6], "learnerRole" to r[7], "partnerRole" to r[8]),
                blocks("ILR ${r[5]} · ${r[9]}", section("Goals", jsonList(r[10])), section("Phrases", jsonList(r[11])), section("Scripted turns", turns)),
                "llm",
            )
        }

    private fun dialogues(t: (String) -> String) =
        driver.rows("SELECT id, track_id, title, jlpt, topic, speakers FROM track_dialogue WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            listOf(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getLong(3).toString(), c.getString(4)!!, c.getString(5)!!)
        }.map { r ->
            val names = speakerNames(r[5])
            val lines = driver.rows("SELECT speaker, ja, en FROM track_dialogue_line WHERE dialogue_id = ${sqlText(r[0])} ORDER BY ord") { c ->
                "${names[c.getString(0)] ?: c.getString(0)}: ${c.getString(1)}\n  ${c.getString(2)}"
            }
            val questions = driver.rows("SELECT question_en, choices, answer FROM track_dialogue_question WHERE dialogue_id = ${sqlText(r[0])} ORDER BY ord") { c ->
                choiceBlock(c.getString(0)!!, jsonList(c.getString(1)), c.getLong(2)!!.toInt())
            }
            r[1] to ReviewCandidate(
                ReviewKind.TRACK_DIALOGUE, r[0], "${t(r[1])} · N${r[3]} · ${r[2]}", mapOf("title" to r[2], "topic" to r[4]),
                blocks(lines.joinToString("\n"), questions.joinToString("\n\n")), "llm",
            )
        }

    private fun drills(t: (String) -> String) =
        driver.rows("SELECT id, track_id, type, topic, jlpt, payload FROM track_drill WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            val track = c.getString(1)!!
            val payload = runCatching { Json.parseToJsonElement(c.getString(5)!!) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            val fields = TRACK_DRILL_FIELDS.mapNotNull { k -> (payload[k] as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
            track to ReviewCandidate(
                ReviewKind.TRACK_DRILL, c.getString(0)!!, "${t(track)} · ${c.getString(2)}${c.getLong(4)?.let { " · N$it" }.orEmpty()} · ${c.getString(3)}",
                fields, renderPayload(payload, skip = fields.keys + DERIVED_KEYS), "llm",
            )
        }

    private fun situations(t: (String) -> String) =
        driver.rows("SELECT id, track_id, title_en, title_ja, can_do FROM track_situation WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            val track = c.getString(1)!!
            val canDo = (runCatching { Json.parseToJsonElement(c.getString(4)!!) as? JsonArray }.getOrNull() ?: JsonArray(emptyList()))
                .mapNotNull { it as? JsonObject }.map { "${it.str("ja")}\n  ${it.str("en")}" }
            track to ReviewCandidate(
                ReviewKind.TRACK_SITUATION, c.getString(0)!!, "${t(track)} · ${c.getString(2)}",
                mapOf("titleEn" to c.getString(2)!!, "titleJa" to c.getString(3)!!), section("Can-do", canDo), "llm",
            )
        }

    private fun tasks(t: (String) -> String) =
        driver.rows("SELECT id, track_id, title_en, title_ja, payload FROM track_task WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            val track = c.getString(1)!!
            val payload = runCatching { Json.parseToJsonElement(c.getString(4)!!) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            track to ReviewCandidate(
                ReviewKind.TRACK_TASK, c.getString(0)!!, "${t(track)} · ${c.getString(2)}",
                mapOf("titleEn" to c.getString(2)!!, "titleJa" to c.getString(3)!!, "place" to payload.str("place")),
                renderPayload(payload, skip = setOf("place")), "llm",
            )
        }

    private fun readings(t: (String) -> String) =
        driver.rows("SELECT id, track_id, title, ilr, genre, body, questions FROM track_reading WHERE source = 'llm' ORDER BY track_id, ord") { c ->
            val track = c.getString(1)!!
            val questions = (runCatching { Json.parseToJsonElement(c.getString(6)!!) as? JsonArray }.getOrNull() ?: JsonArray(emptyList()))
                .mapNotNull { it as? JsonObject }
                .map { q -> choiceBlock(q.str("question"), jsonList(q["choices"]?.toString()), (q["answer"] as? JsonPrimitive)?.intOrNull ?: -1, q.str("explanation")) }
            track to ReviewCandidate(
                ReviewKind.TRACK_READING, c.getString(0)!!, "${t(track)} · ILR ${c.getString(3)} · ${c.getString(2)}",
                mapOf("title" to c.getString(2)!!, "body" to c.getString(5)!!),
                blocks("Genre: ${c.getString(4)}", questions.joinToString("\n\n")), "llm",
            )
        }

    private companion object {
        /** Drill payload fields an edit may change; `tools/items/review.py` TRACK_DRILL_FIELDS is the same list. */
        val TRACK_DRILL_FIELDS = listOf("explanation", "en", "title", "titleJa", "situation", "subject", "body", "sentence", "plain", "setting")

        /** Payload keys the build derives (JMdict ids, verb class), shown nowhere. */
        val DERIVED_KEYS = setOf("entryId", "verb", "verbReading", "verbClass")
    }
}

/** A drill or task payload as readable lines: `key: value`, lists joined, choices with the key marked. */
internal fun renderPayload(payload: JsonObject, skip: Set<String>): String {
    val answer = (payload["answer"] as? JsonPrimitive)?.intOrNull
    return payload.filterKeys { it !in skip && it != "answer" }.map { (k, v) ->
        when {
            k == "choices" && v is JsonArray && answer != null ->
                "choices:\n" + v.mapIndexed { i, c -> "  ${if (i == answer) "*" else " "} ${'A' + i}. ${plain(c)}" }.joinToString("\n")
            v is JsonArray -> "$k:\n" + v.joinToString("\n") { "  - ${plain(it)}" }
            else -> "$k: ${plain(v)}"
        }
    }.joinToString("\n")
}

private fun plain(e: JsonElement): String = when (e) {
    is JsonPrimitive -> e.content
    is JsonObject -> e.entries.joinToString(" · ") { (k, v) -> "$k ${plain(v)}" }
    is JsonArray -> e.joinToString(" / ") { plain(it) }
}
