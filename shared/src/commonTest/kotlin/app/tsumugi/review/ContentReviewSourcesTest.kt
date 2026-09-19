package app.tsumugi.review

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import app.tsumugi.tracks.db.TracksDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Phase 12 content kinds in the in-app review (D-245…D-249): every type listed, with stable ids and details. */
class ContentReviewSourcesTest {
    private fun SqlDriver.sql(vararg statements: String) = statements.forEach { execute(null, it, 0) }

    private val tracks = inMemoryDriver(TracksDatabase.Schema).also { d ->
        d.sql(
            "INSERT INTO track VALUES ('gaming', 0, 'Gaming', 'ゲーム', 'd', '', 5, 2, '', '{}', 'CC BY-SA 4.0', 'x', 'llm')",
            "INSERT INTO track VALUES ('business', 1, 'Business', 'ビジネス', 'd', '', 5, 2, '', '{}', 'CC BY-SA 4.0', 'x', 'llm')",
            "INSERT INTO track_word VALUES ('business', 0, 1000, '会議', 'かいぎ', 'meeting', 'office', '', 4, '', '', 'llm')",
            "INSERT INTO track_word VALUES ('gaming', 0, 2000, '攻略', 'こうりゃく', 'walkthrough', 'play', '', NULL, '', '', 'llm')",
            "INSERT INTO track_word VALUES ('gaming', 1, 2001, '勝利', 'しょうり', 'victory', 'play', '', 3, '', '', 'verified')",
            "INSERT INTO track_kanji VALUES ('gaming', 0, '攻', 'attack', '[\"工\",\"攵\"]', 'craft + strike', 'Strike with craft.', '[2000]', 'llm')",
            "INSERT INTO track_kanji VALUES ('gaming', 1, '略', 'abbreviate', '[]', '', '', '[2000]', 'derived')",
            "INSERT INTO track_scenario VALUES ('gaming-sc-001', 'gaming', 0, 'Lobby', 'ロビー', 5, '0+', 'online', 'A lobby', 'player', 'host', 'casual', '[\"greet\",\"ready\"]', '[]', '[\"よろしく\"]', 'prompt', 'llm')",
            "INSERT INTO track_scripted_turn VALUES ('gaming-sc-001', 0, 'よろしく！', 'Hi!', 'greet back', 'よろしくお願いします。')",
            "INSERT INTO track_dialogue VALUES ('gaming-dl-001', 'gaming', 0, 'Raid', 5, '', 'raid', '[{\"id\":\"A\",\"name\":\"ケン\"},{\"id\":\"B\",\"name\":\"ユミ\"}]', 'llm')",
            "INSERT INTO track_dialogue_line VALUES ('gaming-dl-001', 0, 'A', '行こう。', 'Let''s go.', '[]', '[]')",
            "INSERT INTO track_dialogue_question VALUES ('gaming-dl-001', 0, 'Who leads?', '[\"Ken\",\"Yumi\"]', 0)",
            "INSERT INTO track_drill VALUES ('gaming-mean-001', 'gaming', 0, 'meaning', 'play', 4, '{\"word\":\"攻略\",\"choices\":[\"walkthrough\",\"attack\",\"win\"],\"answer\":0,\"explanation\":\"攻略 is a strategy guide.\",\"entryId\":2000}', 'llm')",
            "INSERT INTO track_situation VALUES ('business-sit-001', 'business', 0, 'Meetings', '会議', '[{\"en\":\"I can open a meeting.\",\"ja\":\"会議を始められる。\"}]', 'llm')",
            "INSERT INTO track_task VALUES ('business-task-001', 'business', 0, 'Exchange cards', '名刺交換', '{\"place\":\"Office\",\"before\":[\"Prepare cards\"],\"during\":[\"Bow\"],\"after\":[\"Keep the card\"],\"phrases\":[\"よろしくお願いします\"],\"etiquette\":[]}', 'llm')",
            "INSERT INTO track_reading VALUES ('business-rd-001', 'business', 0, 'Memo', '2', 'memo', '本文です。', '[{\"question\":\"What?\",\"choices\":[\"a\",\"b\",\"c\"],\"answer\":2}]', 'verified')",
        )
    }

    private val dictionary = inMemoryDriver(DictionaryDatabase.Schema).also { d ->
        d.sql(
            "INSERT INTO sentence VALUES (7, '雨がざあざあ降る。', 'It is pouring.', NULL)",
            "INSERT INTO onomatopoeia VALUES (100, 1, 'ざあざあ', '[\"ザアザア\"]', 'GIONGO', 'weather', '[\"pouring (rain)\"]', 'Heavy rain drumming on a roof', '', '[7]', 'llm')",
            "INSERT INTO onomatopoeia VALUES (101, 2, 'ぴかぴか', '[]', 'GITAIGO', 'appearance', '[\"shiny\"]', '', '', '[]', 'rule')",
            "INSERT INTO onomatopoeia VALUES (102, 3, 'どきどき', '[]', 'GIJOUGO', 'feelings', '[\"pounding\"]', 'A racing heart', '胸が高鳴る', '[]', 'verified')",
        )
    }

    private val grammar = inMemoryDriver(GrammarDatabase.Schema).also { d ->
        d.sql(
            "INSERT INTO grammar_point VALUES ('n5-wa', 5, 1, 'は', 'Noun + は', 'topic marker', 'Marks the topic.', '[]', '[]', '{}', 'verified')",
            "INSERT INTO grammar_point VALUES ('n5-ga', 5, 2, 'が', 'Noun + が', 'subject marker', 'Marks the subject.', '[]', '[]', '{}', 'llm')",
            "INSERT INTO grammar_example VALUES ('n5-ga', 0, '猫がいる。', 'There is a cat.', 1, 2, 'llm', NULL)",
            "INSERT INTO grammar_point_ja VALUES ('n5-wa', '話題を示す。', '文の話題を表す。', 'llm')",
            "INSERT INTO grammar_point_ja VALUES ('n5-ga', '主語を示す。', '主語を表す。', 'verified')",
        )
    }

    private val practice = inMemoryDriver(PracticeDatabase.Schema).also { d ->
        d.sql(
            "INSERT INTO opi_question VALUES ('1', 0, 'WARM_UP', 'お元気ですか。', 'How are you?', 'Memorized.', 'llm', 'personal')",
            "INSERT INTO opi_question VALUES ('0+', 0, 'WARM_UP', 'こんにちは。お名前は？', 'Hello. Your name?', 'One word is fine.', 'llm', 'personal')",
            "INSERT INTO opi_question VALUES ('0+', 1, 'PROBE', '今日は何曜日ですか。', 'What day is it?', '', 'verified', '')",
            "INSERT INTO dialogue VALUES ('nat-n5-cafe', 1, 'Cafe', 5, 'food', '[{\"id\":\"A\",\"name\":\"さき\"},{\"id\":\"B\",\"name\":\"けん\"}]', 'llm', 'natural')",
            "INSERT INTO dialogue_line VALUES ('nat-n5-cafe', 0, 'A', 'えっと、コーヒーにする。', 'Um, I''ll have coffee.', '[]', '[]', '[[0,4]]', 0)",
            "INSERT INTO dialogue_question VALUES ('nat-n5-cafe', 0, 'What does Saki order?', '[\"Tea\",\"Coffee\"]', 1)",
            "INSERT INTO scenario VALUES ('s1', 1, 'At the clinic', '病院で', 4, '1', 'health', 'A clinic', 'patient', 'nurse', 'polite', '[\"say symptoms\",\"book\"]', '[]', '[\"頭が痛いです\"]', 'prompt', 'llm')",
            "INSERT INTO scripted_turn VALUES ('s1', 0, 'どうしましたか。', 'What happened?', 'say symptoms', '頭が痛いです。', '[]')",
            "INSERT INTO drill_set VALUES ('drill-g-n5-1', 0, 'N5 grammar drill 1', 5, 'grammar', 'Say it', 'llm')",
            "INSERT INTO drill_item VALUES ('drill-g-n5-1', 0, 'There is a cat.', '猫がいる。', 'grammar/n5-ga/0', 'g:n5-ga', 'llm')",
            "INSERT INTO drill_item VALUES ('drill-g-n5-1', 1, 'It is a dog.', '犬です。', 'grammar/n5-wa/3', 'g:n5-wa', 'tatoeba')",
            "INSERT INTO drill_set VALUES ('drill-nat-n5', 1, 'N5 natural conversation lines', 5, 'dialogue', 'Say each line', 'llm')",
            "INSERT INTO drill_item VALUES ('drill-nat-n5', 0, 'Um, I''ll have coffee.', 'えっと、コーヒーにする。', 'dialogue/nat-n5-cafe/0', 'd:nat-n5-cafe', 'llm')",
        )
    }

    private val exam = inMemoryDriver(ExamDatabase.Schema).also { d ->
        d.sql(
            "INSERT INTO exam_passage VALUES ('dl-3-news-001', 'dlpt-listening-upper', 'DLPT_LISTENING', '3', 'news', 'ニュース', '', '[{\"speaker\":\"アナウンサー\",\"voice\":\"female\",\"text\":\"本日は…\"}]', 'llm', 0)",
            "INSERT INTO exam_item VALUES ('dl-3-news-001-q1', 'dlpt-listening-upper', 'DLPT_LISTENING', '3', 'main_idea', 'dl-3-news-001', 1, 'What is the news about?', '[\"a\",\"b\",\"c\",\"d\"]', 2, 'The anchor says c.', '', '[]', 'llm', 0)",
            "INSERT INTO exam_item VALUES ('jg-1', 'jlpt-generated', 'JLPT', 'N5', 'kanji_reading', NULL, 1, '語', '[\"a\",\"b\",\"c\",\"d\"]', 0, 'rule', '', '[]', 'generated', 0)",
        )
    }

    @Test
    fun reviewKeyIsFnv1aOverUtf8() {
        // The same values are asserted in tools/items/test_review_kinds.py.
        assertEquals("811c9dc5", reviewKey(""))
        assertEquals("e40c292c", reviewKey("a"))
        assertEquals("4852482c", reviewKey("こんにちは。お名前は？"))
    }

    @Test
    fun tracksListEveryUnverifiedKindWithStableIds() = runTest {
        val candidates = TracksReviewSource(tracks).candidates()
        val ids = candidates.map { it.kind to it.id }
        assertEquals(
            listOf(
                ReviewKind.TRACK_WORD to "gaming:2000", ReviewKind.TRACK_KANJI to "gaming:攻",
                ReviewKind.TRACK_SCENARIO to "gaming-sc-001", ReviewKind.TRACK_DIALOGUE to "gaming-dl-001",
                ReviewKind.TRACK_DRILL to "gaming-mean-001",
                ReviewKind.TRACK_WORD to "business:1000", ReviewKind.TRACK_SITUATION to "business-sit-001",
                ReviewKind.TRACK_TASK to "business-task-001",
            ),
            ids, "grouped by track in display order; verified and derived rows are left out",
        )
        val word = candidates.first { it.id == "gaming:2000" }
        assertEquals(mapOf("gloss" to "walkthrough", "note" to ""), word.fields)
        assertTrue("Gaming · 攻略【こうりゃく】" == word.title, word.title)
        val kanji = candidates.first { it.kind == ReviewKind.TRACK_KANJI }
        assertEquals("Strike with craft.", kanji.fields["hint"])
        assertTrue("工 + 攵" in kanji.display, kanji.display)
        val scenario = candidates.first { it.kind == ReviewKind.TRACK_SCENARIO }
        assertEquals(setOf("titleEn", "titleJa", "setting", "learnerRole", "partnerRole"), scenario.fields.keys)
        assertTrue("よろしく！  (Hi!)" in scenario.display && "greet" in scenario.display, scenario.display)
        val dialogue = candidates.first { it.kind == ReviewKind.TRACK_DIALOGUE }
        assertTrue("ケン: 行こう。" in dialogue.display && "* A. Ken" in dialogue.display, dialogue.display)
        val drill = candidates.first { it.kind == ReviewKind.TRACK_DRILL }
        assertEquals(mapOf("explanation" to "攻略 is a strategy guide."), drill.fields)
        assertTrue("* A. walkthrough" in drill.display && "entryId" !in drill.display, drill.display)
        val task = candidates.first { it.kind == ReviewKind.TRACK_TASK }
        assertEquals("Office", task.fields["place"])
        assertTrue("Prepare cards" in task.display, task.display)
        assertTrue("会議を始められる。" in candidates.first { it.kind == ReviewKind.TRACK_SITUATION }.display)
    }

    @Test
    fun onomatopoeiaFeelLinesShowGlossesAndExamples() = runTest {
        val c = OnomatopoeiaReviewSource(dictionary).candidates().single()
        assertEquals(ReviewKind.ONOMATOPOEIA to "100", c.kind to c.id)
        assertEquals(mapOf("feel" to "Heavy rain drumming on a roof", "feel_ja" to ""), c.fields)
        assertTrue("pouring (rain)" in c.display && "雨がざあざあ降る。" in c.display && "ザアザア" in c.display, c.display)
        // A pack built before Phase 12 has no onomatopoeia table: nothing to review, no error.
        assertTrue(OnomatopoeiaReviewSource(inMemoryDriver(ExamDatabase.Schema)).candidates().isEmpty())
    }

    @Test
    fun grammarJapaneseExplanationsAndExamples() = runTest {
        val all = GrammarReviewSource(grammar).candidates()
        assertEquals(listOf(ReviewKind.GRAMMAR_POINT to "n5-ga", ReviewKind.GRAMMAR_JA to "n5-wa"), all.map { it.kind to it.id })
        val ja = all.last()
        assertEquals(mapOf("meaning_ja" to "話題を示す。", "nuance_ja" to "文の話題を表す。"), ja.fields)
        assertTrue("topic marker" in ja.display, "the English text is shown for comparison")
        assertTrue("猫がいる。" in all.first().display, all.first().display)
    }

    @Test
    fun practiceListsOpiQuestionsAndDrillLines() = runTest {
        val all = PracticeReviewSource(practice).candidates()
        val opi = all.filter { it.kind == ReviewKind.OPI_QUESTION }
        assertEquals(listOf("0+:4852482c", "1:${reviewKey("お元気ですか。")}"), opi.map { it.id }, "ILR order, verified ones left out")
        assertEquals(mapOf("ja" to "こんにちは。お名前は？", "en" to "Hello. Your name?", "note" to "One word is fine."), opi.first().fields)
        assertTrue("Domain: personal" in opi.first().display)
        val drills = all.filter { it.kind == ReviewKind.DRILL_ITEM }
        assertEquals(listOf("g:n5-ga:${reviewKey("猫がいる。")}", "d:nat-n5-cafe:0"), drills.map { it.id }, "Tatoeba lines aren't AI-drafted")
        assertEquals(mapOf("en" to "There is a cat."), drills.first().fields)
        val dialogue = all.single { it.kind == ReviewKind.DIALOGUE }
        assertTrue("Style: natural" in dialogue.display && "さき: えっと" in dialogue.display && "* B. Coffee" in dialogue.display, dialogue.display)
        val scenario = all.single { it.kind == ReviewKind.SCENARIO }
        assertTrue("どうしましたか。" in scenario.display && "say symptoms" in scenario.display, scenario.display)
        assertEquals("patient", scenario.fields["learnerRole"])
    }

    @Test
    fun examListsOnlyAiDraftedItemsWithTheirScript() = runTest {
        val all = ExamReviewSource(exam).candidates()
        assertEquals(listOf("dl-3-news-001", "dl-3-news-001-q1"), all.map { it.id }, "rule-generated items aren't AI content")
        assertTrue("アナウンサー (female): 本日は…" in all.first().display, all.first().display)
        assertTrue("* C. c" in all.last().display && "Passage: dl-3-news-001" in all.last().display, all.last().display)
    }

    @Test
    fun verdictsOfTheNewKindsExportWithTheirCodes() = runTest {
        val service = ContentReviewService(TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema)), {
            listOf(TracksReviewSource(tracks), OnomatopoeiaReviewSource(dictionary), GrammarReviewSource(grammar), PracticeReviewSource(practice))
        }, TestClock())
        val queue = service.queue().map { it.first }
        service.decide(queue.first { it.id == "gaming:2000" }, Verdict.EDIT, edits = mapOf("gloss" to "strategy guide"))
        service.decide(queue.first { it.id == "100" }, Verdict.ACCEPT)
        service.decide(queue.first { it.kind == ReviewKind.GRAMMAR_JA }, Verdict.REJECT, notes = "too formal")
        service.decide(queue.first { it.kind == ReviewKind.DRILL_ITEM }, Verdict.ACCEPT)
        val file = Json.decodeFromString(VerdictsFile.serializer(), service.exportJson("owner"))
        assertEquals(
            setOf("track_word:gaming:2000:edit", "onomatopoeia:100:accept", "grammar_ja:n5-wa:reject", "drill_item:g:n5-ga:${reviewKey("猫がいる。")}:accept"),
            file.verdicts.map { "${it.kind}:${it.id}:${it.verdict}" }.toSet(),
        )
        assertEquals(mapOf("gloss" to "strategy guide"), file.verdicts.first { it.kind == "track_word" }.edits)
        assertTrue(ReviewKind.entries.all { ReviewKind.of(it.code) == it })
    }
}
