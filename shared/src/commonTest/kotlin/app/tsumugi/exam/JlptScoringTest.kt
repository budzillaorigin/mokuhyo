package app.tsumugi.exam

import app.tsumugi.exam.jlpt.JlptBlueprints
import app.tsumugi.exam.jlpt.JlptScoring
import app.tsumugi.exam.jlpt.JlptItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JlptScoringTest {

    private val blueprints = JlptBlueprints.parse(BLUEPRINT_JSON)

    private fun answers(level: Int, correctByGroup: Map<String, Double>): List<JlptScoring.Answer> {
        val bp = blueprints.level(level)!!
        return bp.sections.flatMap { s -> s.items }.flatMap { spec ->
            val share = correctByGroup.getValue(spec.group)
            (0 until spec.count).map { i -> JlptScoring.Answer(spec.type, spec.group, correct = i < (spec.count * share).toInt()) }
        }
    }

    @Test
    fun parsesAllLevelsWithPublishedTimings() {
        assertEquals(listOf(5, 4, 3, 2, 1), blueprints.levels.map { it.level }.sortedDescending())
        assertEquals(listOf(20, 40, 30), blueprints.level(5)!!.sections.map { it.minutes })
        assertEquals(listOf(25, 55, 35), blueprints.level(4)!!.sections.map { it.minutes })
        assertEquals(listOf(30, 70, 40), blueprints.level(3)!!.sections.map { it.minutes })
        assertEquals(listOf(105, 50), blueprints.level(2)!!.sections.map { it.minutes })
        assertEquals(listOf(110, 55), blueprints.level(1)!!.sections.map { it.minutes })
        assertEquals(listOf(100, 90, 95, 90, 80), (1..5).map { blueprints.level(it)!!.passMark })
    }

    @Test
    fun everyBlueprintTypeIsKnown() {
        blueprints.levels.flatMap { l -> l.sections.flatMap { it.items } }.forEach { assertNotNull(JlptItemType.of(it.type), it.type) }
    }

    @Test
    fun perfectScoreIs180AndPasses() {
        for (level in 1..5) {
            val score = JlptScoring.score(blueprints.level(level)!!, answers(level, mapOf("language" to 1.0, "reading" to 1.0, "listening" to 1.0, "language_reading" to 1.0)))
            assertEquals(180, score.total, "N$level")
            assertTrue(score.passed)
        }
    }

    @Test
    fun n5CombinesLanguageAndReadingInto120() {
        val score = JlptScoring.score(blueprints.level(5)!!, answers(5, mapOf("language_reading" to 1.0, "listening" to 0.0)))
        assertEquals(listOf("language_reading", "listening"), score.groups.map { it.group })
        assertEquals(120, score.groups[0].scaledMax)
        assertEquals(38, score.groups[0].minimum)
        assertEquals(120, score.groups[0].scaled)
        assertEquals(0, score.groups[1].scaled)
        assertFalse(score.passed, "listening below its sectional minimum fails even with 120 total")
    }

    @Test
    fun n3HasThreeSixtyPointSections() {
        val score = JlptScoring.score(blueprints.level(3)!!, answers(3, mapOf("language" to 0.5, "reading" to 0.5, "listening" to 0.5)))
        assertEquals(listOf("language", "reading", "listening"), score.groups.map { it.group })
        assertTrue(score.groups.all { it.scaledMax == 60 && it.minimum == 19 })
    }

    @Test
    fun passMarkAndSectionalMinimums() {
        // N1 needs 100 total and 19 in each section.
        val bp = blueprints.level(1)!!
        val high = JlptScoring.score(bp, answers(1, mapOf("language" to 0.9, "reading" to 0.9, "listening" to 0.9)))
        assertTrue(high.passed)
        val lopsided = JlptScoring.score(bp, answers(1, mapOf("language" to 1.0, "reading" to 1.0, "listening" to 0.2)))
        assertTrue(lopsided.total >= 100)
        assertFalse(lopsided.passed)
        assertEquals(listOf("listening"), lopsided.groups.filter { !it.metMinimum }.map { it.group })
    }

    @Test
    fun scalingIsLinearAndRounded() {
        assertEquals(30, JlptScoring.scale(raw = 10, max = 20, scaledMax = 60))
        assertEquals(20, JlptScoring.scale(raw = 1, max = 3, scaledMax = 60))
        assertEquals(0, JlptScoring.scale(raw = 0, max = 0, scaledMax = 60))
    }

    @Test
    fun partialAttemptsScaleOverAdministeredItems() {
        // A section drill only administers some items; unanswered groups score 0 of 0 and are reported as not taken.
        val bp = blueprints.level(3)!!
        val only = listOf(JlptScoring.Answer("grammar_form", "language", true), JlptScoring.Answer("grammar_form", "language", false))
        val score = JlptScoring.score(bp, only)
        assertEquals(30, score.groups.first { it.group == "language" }.scaled)
        assertFalse(score.groups.first { it.group == "reading" }.taken)
        assertFalse(score.complete)
        assertFalse(score.passed, "an incomplete attempt never reports a pass")
    }

    @Test
    fun byTypeBreakdown() {
        val score = JlptScoring.score(
            blueprints.level(3)!!,
            listOf(
                JlptScoring.Answer("kanji_reading", "language", true),
                JlptScoring.Answer("kanji_reading", "language", false),
                JlptScoring.Answer("task", "listening", true),
            ),
        )
        val kr = score.byType.first { it.type == "kanji_reading" }
        assertEquals(1, kr.correct)
        assertEquals(2, kr.total)
    }

    companion object {
        /** Trimmed copy of tools/items/jlpt_blueprints.json (same structure; the real file is bundled in exam.sqlite). */
        val BLUEPRINT_JSON = """
        {"scoring":{"sectionMax":60,"combinedMax":120,"sectionMinimum":19,"combinedMinimum":38},
         "levels":[
          {"level":1,"passMark":100,"sections":[
            {"id":"language_reading","title":"言語知識・読解","minutes":110,"scoreGroups":["language","reading"],"items":[
              {"type":"kanji_reading","title":"漢字読み","count":6,"group":"language"},
              {"type":"grammar_form","title":"文法形式の判断","count":10,"group":"language"},
              {"type":"comprehension_mid","title":"内容理解（中文）","count":9,"group":"reading"},
              {"type":"info_retrieval","title":"情報検索","count":2,"group":"reading"}]},
            {"id":"listening","title":"聴解","minutes":55,"scoreGroups":["listening"],"items":[
              {"type":"task","title":"課題理解","count":5,"group":"listening"},
              {"type":"quick_response","title":"即時応答","count":11,"group":"listening"}]}]},
          {"level":2,"passMark":90,"sections":[
            {"id":"language_reading","title":"言語知識・読解","minutes":105,"scoreGroups":["language","reading"],"items":[
              {"type":"word_formation","title":"語形成","count":3,"group":"language"},
              {"type":"thematic","title":"主張理解","count":3,"group":"reading"}]},
            {"id":"listening","title":"聴解","minutes":50,"scoreGroups":["listening"],"items":[
              {"type":"integrated_listening","title":"統合理解","count":4,"group":"listening"}]}]},
          {"level":3,"passMark":95,"sections":[
            {"id":"vocabulary","title":"文字・語彙","minutes":30,"scoreGroups":["language"],"items":[
              {"type":"kanji_reading","title":"漢字読み","count":8,"group":"language"},
              {"type":"orthography","title":"表記","count":6,"group":"language"}]},
            {"id":"grammar_reading","title":"文法・読解","minutes":70,"scoreGroups":["language","reading"],"items":[
              {"type":"grammar_form","title":"文法形式の判断","count":13,"group":"language"},
              {"type":"sentence_assembly","title":"文の組み立て","count":5,"group":"language"},
              {"type":"comprehension_short","title":"内容理解（短文）","count":4,"group":"reading"}]},
            {"id":"listening","title":"聴解","minutes":40,"scoreGroups":["listening"],"items":[
              {"type":"task","title":"課題理解","count":6,"group":"listening"},
              {"type":"utterance","title":"発話表現","count":4,"group":"listening"}]}]},
          {"level":4,"passMark":90,"sections":[
            {"id":"vocabulary","title":"文字・語彙","minutes":25,"scoreGroups":["language_reading"],"items":[
              {"type":"context","title":"文脈規定","count":10,"group":"language_reading"}]},
            {"id":"grammar_reading","title":"文法・読解","minutes":55,"scoreGroups":["language_reading"],"items":[
              {"type":"text_grammar","title":"文章の文法","count":5,"group":"language_reading"}]},
            {"id":"listening","title":"聴解","minutes":35,"scoreGroups":["listening"],"items":[
              {"type":"point","title":"ポイント理解","count":7,"group":"listening"}]}]},
          {"level":5,"passMark":80,"sections":[
            {"id":"vocabulary","title":"文字・語彙","minutes":20,"scoreGroups":["language_reading"],"items":[
              {"type":"kanji_reading","title":"漢字読み","count":12,"group":"language_reading"},
              {"type":"paraphrase","title":"言い換え類義","count":5,"group":"language_reading"}]},
            {"id":"grammar_reading","title":"文法・読解","minutes":40,"scoreGroups":["language_reading"],"items":[
              {"type":"grammar_form","title":"文法形式の判断","count":16,"group":"language_reading"},
              {"type":"comprehension_short","title":"内容理解（短文）","count":3,"group":"language_reading"}]},
            {"id":"listening","title":"聴解","minutes":30,"scoreGroups":["listening"],"items":[
              {"type":"task","title":"課題理解","count":7,"group":"listening"},
              {"type":"utterance","title":"発話表現","count":5,"group":"listening"},
              {"type":"quick_response","title":"即時応答","count":6,"group":"listening"}]}]}
         ]}
        """.trimIndent()
    }
}
