package app.tsumugi.practice

import app.tsumugi.practice.db.Dialogue_line
import app.tsumugi.practice.db.Dialogue_question
import app.tsumugi.practice.db.Drill_item
import app.tsumugi.practice.db.Drill_set
import app.tsumugi.practice.db.Minimal_pair
import app.tsumugi.practice.db.Opi_checklist
import app.tsumugi.practice.db.Opi_question
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.practice.db.Scenario as ScenarioRow
import app.tsumugi.practice.db.Scripted_turn
import app.tsumugi.practice.db.Dialogue as DialogueRow
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeRepositoryTest {

    /** Rows shaped like build_practice.py output. Test data only. */
    private val pack = PracticeDatabase(inMemoryDriver(PracticeDatabase.Schema)).also { db ->
        val q = db.practiceQueries
        q.insertMeta("pack_version", "1")
        q.insertScenario(
            ScenarioRow(
                "konbini", 0, "At the convenience store", "コンビニで", 5, "0+", "daily", "A convenience store.",
                "Customer", "Clerk", "polite", """["Pay","Say thanks"]""",
                """[{"text":"弁当","reading":"べんとう","entryId":1487470}]""", """["袋はいりません"]""",
                "You are role-playing…", "llm",
            ),
        )
        q.insertScenario(
            ScenarioRow(
                "job-interview", 1, "Job interview", "面接", 3, "2", "work", "An interview.", "Applicant",
                "Interviewer", "keigo", """["Introduce yourself","Ask a question"]""", "[]", "[]", "…", "verified",
            ),
        )
        q.insertTurn(Scripted_turn("konbini", 1, "袋はご利用ですか。", "Would you like a bag?", "Decline", "いいえ、大丈夫です。", """["大丈夫です。","いりません。"]"""))
        q.insertTurn(Scripted_turn("konbini", 0, "温めますか。", "Shall I heat it?", "Answer", "はい、お願いします。", "[]"))
        q.insertOpiQuestion(Opi_question("1", 0, "WARM_UP", "自己紹介をしてください。", "Introduce yourself.", "", "llm", "personal"))
        q.insertOpiQuestion(Opi_question("1", 1, "PROBE", "先週末、何をしましたか。", "Last weekend?", "past narration", "llm", ""))
        q.insertOpiChecklist(Opi_checklist("1", 0, "I can handle simple face-to-face situations."))
        q.insertDialogue(
            DialogueRow(
                "n5-morning", 0, "Good morning", 5, "greetings",
                """[{"id":"A","name":"田中","voice":"female","age":"adult"},{"id":"B","name":"リー","voice":"male","age":"adult","hint":"calm"}]""",
                "llm", "scripted",
            ),
        )
        q.insertDialogue(DialogueRow("n4-cold", 1, "A cold", 4, "health", "[]", "llm", "scripted"))
        q.insertDialogue(DialogueRow("nat-n5-lunch", 2, "Lunch", 5, "food", "[]", "llm", "natural"))
        q.insertLine(
            Dialogue_line(
                "n5-morning", 0, "B", "今日は寒いですね。", "It's cold today.",
                """[{"text":"寒い","start":3,"end":5,"entryId":1293190}]""", """["今日は","寒いですね。"]""", "[]", 0,
            ),
        )
        // Built from "{えっと、}ラーメン、{あ、}うどんにする。": two filler spans, and a backchannel that overlaps.
        q.insertLine(
            Dialogue_line(
                "nat-n5-lunch", 0, "A", "えっと、ラーメン、あ、うどんにする。", "Um, ramen, oh, I'll have udon.", "[]",
                """["えっと、","ラーメン、","あ、","うどんにする。"]""", "[[0,4],[9,11]]", 0,
            ),
        )
        q.insertLine(Dialogue_line("nat-n5-lunch", 1, "B", "うん。", "Mm-hm.", "[]", """["うん。"]""", "[]", 1))
        q.insertDrillSet(Drill_set("drill-g-n5-1", 0, "N5 grammar drill 1", 5, "grammar", "Say it in Japanese", "derived"))
        q.insertDrillSet(Drill_set("drill-nat-n3", 1, "N3 natural conversation lines", 3, "dialogue", "", "llm"))
        q.insertDrillItem(Drill_item("drill-g-n5-1", 1, "Where is the station?", "駅はどこですか。", "grammar/n5-doko/0", "g:n5-doko", "tatoeba"))
        q.insertDrillItem(Drill_item("drill-g-n5-1", 0, "This is a pen.", "これはペンです。", "grammar/n5-wa/0", "g:n5-wa", "tatoeba"))
        q.insertQuestion(Dialogue_question("n5-morning", 0, "Weather?", """["Hot","Cold"]""", 1))
        fun pair(id: Long, cat: String, a: String, b: String, rank: Long) =
            q.insertPair(Minimal_pair(id, cat, 10 + id, a, a, 0, "a", 20 + id, b, b, null, "b", rank))
        pair(0, "LENGTH", "とり", "とおり", 5)
        pair(1, "PITCH", "はし", "はし", 1)
        pair(2, "LENGTH", "ゆき", "ゆうき", 9)
    }
    private val repo = PracticeRepository(pack)

    @Test
    fun scenariosFilterByLevelAndDecodeJson() = runTest {
        assertEquals(listOf("konbini", "job-interview"), repo.scenarios().map { it.id })
        val n5 = repo.scenarios(5).single()
        assertEquals(Register.POLITE, n5.register)
        assertEquals(1487470L, n5.vocabulary.single().entryId)
        assertEquals(listOf("Pay", "Say thanks"), n5.goals)
        assertTrue(n5.isAiGenerated)
        val interview = assertNotNull(repo.scenario("job-interview"))
        assertEquals(Register.KEIGO, interview.register)
        assertTrue(!interview.isAiGenerated)
        assertNull(repo.scenario("missing"))
    }

    @Test
    fun scriptedTurnsAreOrdered() = runTest {
        val turns = repo.scriptedTurns("konbini")
        assertEquals(listOf("温めますか。", "袋はご利用ですか。"), turns.map { it.partnerJa })
        assertEquals(emptyList(), turns[0].accept)
        assertEquals(listOf("いいえ、大丈夫です。", "大丈夫です。", "いりません。"), turns[1].acceptableAnswers)
    }

    @Test
    fun opiBankGroupsByPhase() = runTest {
        val bank = repo.opiBank("1")
        assertEquals(2, bank.questions.size)
        assertEquals("先週末、何をしましたか。", bank.phase(OpiPhase.PROBE).single().promptJa)
        assertEquals(OpiDomain.PERSONAL, bank.phase(OpiPhase.WARM_UP).single().domain)
        assertNull(bank.phase(OpiPhase.PROBE).single().domain)
        assertEquals(1, bank.checklist.size)
        assertTrue(repo.opiBank("3").questions.isEmpty())
    }

    @Test
    fun dialogueLoadsLinesGapsAndQuestions() = runTest {
        assertEquals(listOf("n4-cold"), repo.dialogues(4).map { it.id })
        assertEquals(3, repo.dialogues().size)
        val d = assertNotNull(repo.dialogue("n5-morning"))
        assertEquals("female", d.speaker("A")?.voice)
        assertEquals("", d.speaker("A")?.hint)
        assertEquals("calm", d.speaker("B")?.hint)
        assertEquals(DialogueStyle.SCRIPTED, d.style)
        val line = d.lines.single()
        val gap = line.gaps.single()
        assertEquals("寒い", line.japanese.substring(gap.start, gap.end))
        assertEquals(line.japanese, line.chunks.joinToString(""))
        assertEquals("Cold", d.questions.single().correctChoice)
        assertNull(repo.dialogue("missing"))
    }

    @Test
    fun naturalDialoguesMarkFillersAndOverlaps() = runTest {
        assertEquals(DialogueStyle.NATURAL, repo.dialogues(5).single { it.id == "nat-n5-lunch" }.style)
        val d = assertNotNull(repo.dialogue("nat-n5-lunch"))
        assertEquals(DialogueStyle.NATURAL, d.style)
        val line = d.lines[0]
        assertEquals(listOf(FillerSpan(0, 4), FillerSpan(9, 11)), line.fillers)
        assertEquals(
            listOf(
                LineSegment("えっと、", true), LineSegment("ラーメン、", false), LineSegment("あ、", true), LineSegment("うどんにする。", false),
            ),
            line.segments(),
        )
        assertEquals(line.japanese, line.segments().joinToString("") { it.text })
        assertEquals("ラーメン、うどんにする。", line.withoutFillers)
        assertTrue(!line.overlap && d.lines[1].overlap)
        assertEquals(listOf(LineSegment("うん。", false)), d.lines[1].segments())
    }

    @Test
    fun drillSetsLoadInOrder() = runTest {
        assertEquals(listOf("drill-g-n5-1", "drill-nat-n3"), repo.drillSets().map { it.id })
        val n3 = repo.drillSets(3).single()
        assertEquals(DrillKind.DIALOGUE, n3.kind)
        assertTrue(n3.isAiGenerated)
        val set = assertNotNull(repo.drillSet("drill-g-n5-1"))
        assertEquals(DrillKind.GRAMMAR, set.summary.kind)
        assertEquals(listOf("これはペンです。", "駅はどこですか。"), set.items.map { it.answerJa })
        assertEquals("grammar/n5-wa/0", set.items[0].audioKey)
        assertTrue(!set.summary.isAiGenerated && !set.items[0].isAiGenerated)
        assertNull(repo.drillSet("missing"))
    }

    @Test
    fun minimalPairsByCategoryAndRank() = runTest {
        assertEquals(listOf(1L, 0L, 2L), repo.minimalPairs(limit = 10).map { it.id })
        val length = repo.minimalPairs(MinimalPairCategory.LENGTH, limit = 1).single()
        assertEquals("とおり", length.b.reading)
        assertEquals(0, length.a.accent)
        assertNull(length.b.accent)
        assertEquals("1", repo.packVersion())
    }
}
