package app.tsumugi.practice

import app.tsumugi.practice.db.Dialogue_line
import app.tsumugi.practice.db.Dialogue_question
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
        q.insertTurn(Scripted_turn("konbini", 1, "袋はご利用ですか。", "Would you like a bag?", "Decline", "いいえ、大丈夫です。"))
        q.insertTurn(Scripted_turn("konbini", 0, "温めますか。", "Shall I heat it?", "Answer", "はい、お願いします。"))
        q.insertOpiQuestion(Opi_question("1", 0, "WARM_UP", "自己紹介をしてください。", "Introduce yourself.", "", "llm"))
        q.insertOpiQuestion(Opi_question("1", 1, "PROBE", "先週末、何をしましたか。", "Last weekend?", "past narration", "llm"))
        q.insertOpiChecklist(Opi_checklist("1", 0, "I can handle simple face-to-face situations."))
        q.insertDialogue(
            DialogueRow(
                "n5-morning", 0, "Good morning", 5, "greetings",
                """[{"id":"A","name":"田中","voice":"female","age":"adult"},{"id":"B","name":"リー","voice":"male","age":"adult"}]""",
                "llm",
            ),
        )
        q.insertDialogue(DialogueRow("n4-cold", 1, "A cold", 4, "health", "[]", "llm"))
        q.insertLine(
            Dialogue_line(
                "n5-morning", 0, "B", "今日は寒いですね。", "It's cold today.",
                """[{"text":"寒い","start":3,"end":5,"entryId":1293190}]""", """["今日は","寒いですね。"]""",
            ),
        )
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
        assertEquals(listOf("温めますか。", "袋はご利用ですか。"), repo.scriptedTurns("konbini").map { it.partnerJa })
    }

    @Test
    fun opiBankGroupsByPhase() = runTest {
        val bank = repo.opiBank("1")
        assertEquals(2, bank.questions.size)
        assertEquals("先週末、何をしましたか。", bank.phase(OpiPhase.PROBE).single().promptJa)
        assertEquals(1, bank.checklist.size)
        assertTrue(repo.opiBank("3").questions.isEmpty())
    }

    @Test
    fun dialogueLoadsLinesGapsAndQuestions() = runTest {
        assertEquals(listOf("n4-cold"), repo.dialogues(4).map { it.id })
        assertEquals(2, repo.dialogues().size)
        val d = assertNotNull(repo.dialogue("n5-morning"))
        assertEquals("female", d.speaker("A")?.voice)
        val line = d.lines.single()
        val gap = line.gaps.single()
        assertEquals("寒い", line.japanese.substring(gap.start, gap.end))
        assertEquals(line.japanese, line.chunks.joinToString(""))
        assertEquals("Cold", d.questions.single().correctChoice)
        assertNull(repo.dialogue("missing"))
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
