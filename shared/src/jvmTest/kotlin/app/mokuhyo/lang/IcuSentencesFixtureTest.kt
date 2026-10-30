package app.mokuhyo.lang

import app.mokuhyo.testing.repoFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The segmentation cases shared with tools/voices/textsplit.py (QC 2026-10-30 V1): the JVM's ICU sentence path must
 * not end a sentence inside a number, abbreviation, initial, ellipsis or quote either.
 */
class IcuSentencesFixtureTest {
    private val fixture = Json.parseToJsonElement(repoFile("tools/voices/fixtures/segmentation_cases.json").readText()).jsonObject
    private val numeric = Regex("""\d+(?:[.,:]\d+)+""")

    private fun assertNoEndInside(text: String, ends: List<Int>, spans: List<String>, what: String) {
        for (span in spans) {
            val at = text.indexOf(span)
            assertTrue(at >= 0, "$what: $span")
            assertTrue(ends.none { it > at && it < at + span.length }, "$what: a sentence ends inside \"$span\" (ends $ends)")
        }
    }

    @Test
    fun corpusLinesKeepTheirNumbersWhole() {
        val corpus = fixture.getValue("corpus").jsonArray
        assertEquals(18, corpus.size)
        for (row in corpus.map { it.jsonObject }) {
            val text = row.getValue("text").jsonPrimitive.content
            val ends = IcuSentences(row.getValue("lang").jsonPrimitive.content).ends(text, final = true)
            assertNoEndInside(text, ends, numeric.findAll(text).map { it.value }.toList(), row.getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun caseClassesKeepProtectedSpansAndSentenceCounts() {
        for (case in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
            val text = case.getValue("text").jsonPrimitive.content
            val what = "${case.getValue("class").jsonPrimitive.content} ${case.getValue("lang").jsonPrimitive.content}: $text"
            val ends = IcuSentences(case.getValue("lang").jsonPrimitive.content).ends(text, final = true)
            assertNoEndInside(text, ends, case.getValue("protect").jsonArray.map { it.jsonPrimitive.content }, what)
            assertEquals(case.getValue("sentences").jsonPrimitive.int, ends.size, "$what (ends $ends)")
        }
    }
}
