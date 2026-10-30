package app.mokuhyo.speech

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
 * QC 2026-10-30 V2: one boundary policy. The fixture table is the one tools/voices/test_boundary.py checks, so the
 * render tools and the app join identical inputs identically.
 */
class BoundaryPolicyTest {
    private val fixture = Json.parseToJsonElement(repoFile("tools/voices/fixtures/boundary_cases.json").readText()).jsonObject

    private fun clip(lead: Int, voiced: Int, trail: Int) = ShortArray(lead + voiced + trail) { if (it in lead until lead + voiced) 1000 else 0 }

    private fun heardGaps(pcm: ShortArray): List<Int> {
        val voiced = pcm.indices.filter { kotlin.math.abs(pcm[it].toInt()) >= EdgeSilence.THRESHOLD }
        return voiced.zipWithNext().filter { (a, b) -> b - a > 1 }.map { (a, b) -> b - a - 1 }
    }

    @Test
    fun fixtureJoinsGiveTheConfiguredGapAndNoTrailingPad() {
        val policy = BoundaryPolicy.parse(fixture.getValue("policy").toString())
        val rate = fixture.getValue("rate").jsonPrimitive.int
        for (case in fixture.getValue("joins").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val clips = case.getValue("clips").jsonArray.map { c -> c.jsonArray.map { it.jsonPrimitive.int }.let { (a, b, c2) -> clip(a, b, c2) } }
            val kinds = case.getValue("boundaries").jsonArray.map { b -> Boundary.entries.first { it.wire == b.jsonPrimitive.content } }
            val out = policy.join(clips, kinds, rate)
            assertEquals(case.getValue("heard_gaps_ms").jsonArray.map { it.jsonPrimitive.int }, heardGaps(out), name)
            assertEquals(case.getValue("total_ms").jsonPrimitive.int, out.size, name)
        }
    }

    @Test
    fun theShippedPolicyLoadsWithEveryBoundaryType() {
        val p = BoundaryPolicies.default
        assertTrue(p.version.isNotBlank())
        assertTrue(p.targetMs(Boundary.CONTINUATION) <= p.targetMs(Boundary.SENTENCE_END))
        assertTrue(p.targetMs(Boundary.SENTENCE_END) < p.targetMs(Boundary.SPEAKER_TURN))
    }
}
