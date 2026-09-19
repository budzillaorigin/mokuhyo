package app.tsumugi.review

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentReviewTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))

    private val grammar = inMemoryDriver(GrammarDatabase.Schema).also { d ->
        d.execute(null, "INSERT INTO grammar_point VALUES ('n5-wa', 5, 1, 'は', 'Noun + は', 'topic marker', 'Marks the topic.', '[\"Using が instead\"]', '[]', '{}', 'llm')", 0)
        d.execute(null, "INSERT INTO grammar_point VALUES ('n5-ga', 5, 2, 'が', 'Noun + が', 'subject marker', 'Marks the subject.', '[]', '[]', '{}', 'verified')", 0)
    }
    private val exam = inMemoryDriver(ExamDatabase.Schema).also { d ->
        d.execute(null, "INSERT INTO exam_passage VALUES ('p1', 'b', 'JLPT', 'N4', 'short', 'お知らせ', '本文です。', '', 'llm', 0)", 0)
        d.execute(null, "INSERT INTO exam_item VALUES ('p1-q1', 'b', 'JLPT', 'N4', 'reading', 'p1', 1, '何のお知らせですか。', '[\"休み\",\"工事\",\"祭り\",\"試験\"]', 1, 'Line 1 says 工事.', '', '[]', 'llm', 0)", 0)
        d.execute(null, "INSERT INTO exam_item VALUES ('x-q1', 'b', 'JLPT', 'N4', 'vocab', NULL, 1, '語彙', '[\"a\",\"b\",\"c\",\"d\"]', 0, 'ok', '', '[]', 'llm', 1)", 0)
    }
    private val practice = inMemoryDriver(PracticeDatabase.Schema).also { d ->
        d.execute(null, "INSERT INTO dialogue VALUES ('d1', 1, 'At the station', 5, 'travel', '[]', 'llm', 'scripted')", 0)
        d.execute(null, "INSERT INTO dialogue_line VALUES ('d1', 1, 'A', 'すみません。', 'Excuse me.', '[]', '[]', '[]', 0)", 0)
    }

    private val service = ContentReviewService(db, {
        listOf(GrammarReviewSource(grammar), ExamReviewSource(exam), PracticeReviewSource(practice), KanaMnemonicReviewSource)
    }, clock)

    @Test
    fun onlyUnverifiedContentIsListed() = runTest {
        val queue = service.queue()
        val ids = queue.map { it.first.kind to it.first.id }
        assertTrue(ReviewKind.GRAMMAR_POINT to "n5-wa" in ids)
        assertTrue(ReviewKind.GRAMMAR_POINT to "n5-ga" !in ids, "already verified")
        assertTrue(ReviewKind.EXAM_PASSAGE to "p1" in ids)
        assertTrue(ReviewKind.EXAM_ITEM to "p1-q1" in ids)
        assertTrue(ReviewKind.EXAM_ITEM to "x-q1" !in ids)
        assertTrue(ReviewKind.DIALOGUE to "d1" in ids)
        assertEquals(92, queue.count { it.first.kind == ReviewKind.KANA_MNEMONIC })
        val item = queue.first { it.first.id == "p1-q1" }.first
        assertTrue(item.display.contains("* B. 工事"), item.display)
        assertEquals("何のお知らせですか。", item.fields["stem"])
        assertTrue(queue.first { it.first.id == "d1" }.first.display.contains("すみません。"))
        assertTrue(queue.all { it.second == null })
    }

    @Test
    fun verdictsAreRecordedAndExportedForReviewPy() = runTest {
        val queue = service.queue()
        val wa = queue.first { it.first.id == "n5-wa" }.first
        val q1 = queue.first { it.first.id == "p1-q1" }.first
        val d1 = queue.first { it.first.id == "d1" }.first
        service.decide(wa, Verdict.EDIT, edits = mapOf("meaning" to "topic marker (as for …)", "title" to "は", "bogus" to "x"))
        clock.now += kotlin.time.Duration.parse("1m")
        service.decide(q1, Verdict.ACCEPT, notes = "fine")
        service.decide(d1, Verdict.REJECT, notes = "unnatural line 3")
        // An EDIT with no real change is an ACCEPT.
        val a = service.decide(queue.first { it.first.id == "p1" }.first, Verdict.EDIT, edits = mapOf("title" to "お知らせ"))
        assertEquals(Verdict.ACCEPT, a.verdict)

        val summary = service.summary()
        assertEquals(1 to 1, summary[ReviewKind.GRAMMAR_POINT])

        val file = Json.decodeFromString(VerdictsFile.serializer(), service.exportJson("owner"))
        assertEquals(VerdictsFile.FORMAT, file.format)
        assertEquals("owner", file.reviewer)
        val byId = file.verdicts.associateBy { it.id }
        assertEquals("edit", byId["n5-wa"]!!.verdict)
        assertEquals(mapOf("meaning" to "topic marker (as for …)"), byId["n5-wa"]!!.edits, "only changed, known fields")
        assertEquals("grammar_point", byId["n5-wa"]!!.kind)
        assertEquals("accept", byId["p1-q1"]!!.verdict)
        assertEquals("fine", byId["p1-q1"]!!.notes)
        assertEquals("reject", byId["d1"]!!.verdict)

        service.undo(ReviewKind.DIALOGUE, "d1")
        assertNull(service.queue(ReviewKind.DIALOGUE).single().second)
        service.clear()
        assertTrue(service.verdicts().isEmpty())
    }
}
