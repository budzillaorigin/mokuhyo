package app.tsumugi.tracks

import app.tsumugi.coverage.KnownWords
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.WordState
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.ItemKind
import app.tsumugi.jp.WordClass
import app.tsumugi.practice.Register
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathStatus
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.LessonSession
import app.tsumugi.study.LessonState
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import app.tsumugi.tracks.db.Track_dialogue
import app.tsumugi.tracks.db.Track_dialogue_line
import app.tsumugi.tracks.db.Track_dialogue_question
import app.tsumugi.tracks.db.Track_drill
import app.tsumugi.tracks.db.Track_kanji
import app.tsumugi.tracks.db.Track_link
import app.tsumugi.tracks.db.Track_reading
import app.tsumugi.tracks.db.Track_scenario
import app.tsumugi.tracks.db.Track_scripted_turn
import app.tsumugi.tracks.db.Track_situation
import app.tsumugi.tracks.db.Track_task
import app.tsumugi.tracks.db.Track_word
import app.tsumugi.tracks.db.TracksDatabase
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.tsumugi.tracks.db.Track as TrackRow

/** BRIEF_V2 §6.5 interest and domain tracks: pack reading, selection, Today lessons, drills and keigo (D-210…D-219). */
class TracksTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val settings = SettingsRepository(db, clock)
    private val dictionary = DictionaryRepository(DictionaryFixture.create())
    private val knowledge = LearnerKnowledge(db, srs)
    private val knownWords = KnownWords(db, knowledge, { dictionary }, clock)
    private val collection = CollectionService(db, srs, { null }, clock)
    private val pack = TracksDatabase(inMemoryDriver(TracksDatabase.Schema)).also { fill(it) }
    private val repo = TrackRepository(pack)
    private val service = TrackService(settings, knowledge, { repo }, { dictionary }) { entry, context -> collection.addToReviews(entry, context) }

    // --- pack ------------------------------------------------------------------------------------------------

    @Test
    fun packRowsBecomeTracksWordsKanjiAndPracticeModels() = runTest {
        val tracks = repo.tracks()
        assertEquals(listOf("gaming", "daily-life"), tracks.map { it.id })
        val gaming = tracks.first()
        assertEquals(3, gaming.count("words"))
        assertEquals("N5–N2", gaming.levelLabel)
        assertTrue(gaming.isAiGenerated)
        assertEquals("N3–N1 · ILR 2–3", repo.track("daily-life")!!.levelLabel)

        val words = repo.words("gaming")
        assertEquals(listOf("食べる", "猫", "寿司"), words.map { it.text })
        assertEquals(listOf(2), words.first().accents)
        assertEquals(emptyList(), words[1].accents)
        val lessons = repo.lessons("gaming", size = 2)
        assertEquals(listOf("food" to 1, "pets" to 1, "food" to 1), lessons.map { it.topic to it.words.size })

        val kanji = repo.kanji("gaming").single()
        assertEquals(listOf("人", "良"), kanji.components)
        assertEquals(listOf(DictionaryFixture.TABERU), kanji.wordIds)

        // Scenarios and dialogues come back as the practice models the role-play and listening screens use.
        val scenario = assertNotNull(repo.scenario("gaming-sc-001"))
        assertEquals(Register.CASUAL, scenario.register)
        assertEquals("猫", scenario.vocabulary.single().text)
        assertEquals(listOf("パーティーに入る?"), repo.scriptedTurns("gaming-sc-001").map { it.partnerJa })
        assertEquals(listOf("gaming-sc-001"), repo.scenarios("gaming").map { it.id })
        val dialogue = assertNotNull(repo.dialogue("gaming-dl-001"))
        assertEquals(2, dialogue.lines.size)
        assertEquals("female", dialogue.speaker("A")!!.voice)
        assertEquals(1, dialogue.questions.single().answer)
        assertEquals(listOf("gaming-dl-001"), repo.dialogues("gaming").map { it.id })

        assertEquals(7, repo.drills("gaming").size)
        assertIs<KeigoDrill>(repo.drill("gaming-keigo-001"))
        assertEquals(1, repo.drills("gaming", DrillType.PERFORM).size)
        assertNull(repo.drill("gaming-new-001"), "a drill type from a newer pack is skipped")

        val situation = repo.situations("daily-life").single()
        assertEquals("daily-life-sit-001/1", situation.canDoId(1))
        assertEquals(2, situation.canDo.size)
        assertEquals("a hot-spring inn", repo.tasks("daily-life").single().place)
        assertEquals("2+", repo.readings("daily-life").single().ilr)
        assertEquals("https://www.mod.go.jp/msdf/", repo.links("daily-life").single().url)
    }

    // --- selection and lessons -------------------------------------------------------------------------------

    @Test
    fun selectionIsASyncedSettingAndChangesAnyTime() = runTest {
        assertEquals(emptyList(), service.selected())
        assertEquals(listOf(false, false), service.onboardingOptions().map { it.selected })
        service.chooseInOnboarding(listOf("gaming", "daily-life", "gaming"))
        assertEquals(listOf("gaming", "daily-life"), service.selected())
        assertNotNull(settings.get(TrackService.SELECTED), "stored in the synced settings table")
        service.deselect("gaming")
        service.select("gaming")
        assertEquals(listOf("daily-life", "gaming"), service.selected())
        service.switchTo("gaming")
        assertEquals(listOf("gaming"), service.selected())
        val summary = service.tracks().first { it.track.id == "gaming" }
        assertTrue(summary.selected)
        assertEquals(3, summary.wordsLeft)
    }

    @Test
    fun trackWordsJoinTodaysLessonsAlongsideThePath() = runTest {
        val status = PathStatus(3, 60, 0.5, availableLessons = 10, dueReviews = 0, stageCounts = emptyMap())
        assertEquals(10, service.adjust(status)!!.availableLessons, "no track selected: nothing changes")
        val base = LessonSession(listOf(pathItem("p1"), pathItem("p2")), Random(1)) { }
        assertEquals(base, service.mixInto(base, 4), "no track: the path batch as it is")
        assertEquals(0, service.share(4))

        service.switchTo("gaming")
        knownWords.markKnown(listOf(DictionaryFixture.TABERU))
        assertEquals(2, service.available(), "食べる is known")
        assertEquals(12, service.adjust(status)!!.availableLessons, "track lessons come on top of the path's")
        assertEquals(2, service.share(4))
        assertEquals(1, service.share(2))
        assertEquals(0, service.share(1))

        val completedPath = mutableListOf<String>()
        val path = LessonSession(listOf(pathItem("p1"), pathItem("p2")), Random(1)) { done -> completedPath += done.map { it.id } }
        val session = assertNotNull(service.mixInto(path, 4, Random(2)))
        val items = assertIs<LessonState.Presenting>(session.state.value).items.map { it.id }
        assertEquals(listOf("p1", "jmdict:${DictionaryFixture.NEKO}", "p2", "jmdict:${DictionaryFixture.SUSHI}"), items)
        session.startQuiz()
        while (true) {
            val q = session.state.value as? LessonState.Quizzing ?: break
            session.submit(q.question.expected.first())
            session.next()
        }
        assertIs<LessonState.Complete>(session.state.value)
        assertEquals(listOf("p1", "p2"), completedPath.sorted(), "path items complete on the path")
        assertEquals(WordState.LEARNING, knowledge.snapshot().word(DictionaryFixture.NEKO), "track words joined reviews")
        assertEquals(0, service.available())
        assertEquals(path, service.mixInto(path, 4), "nothing left: the path batch alone")

        // Without a path batch (path finished or pack missing), the track fills the whole batch.
        service.switchTo("daily-life")
        assertNull(service.mixInto(null, 4), "daily-life's only word is already studied")
    }

    @Test
    fun wordsRoundRobinAcrossSelectedTracks() = runTest {
        assertEquals(listOf(1, 10, 2, 20, 3), TrackService.roundRobin(listOf(listOf(1, 2, 3), listOf(10, 20))).toList())
        service.chooseInOnboarding(listOf("daily-life", "gaming"))
        // 猫 is in both tracks; it comes once.
        assertEquals(listOf("猫", "食べる", "寿司"), service.remaining().map { it.text })
    }

    @Test
    fun canDoChecksAreStoredAsASyncedSetting() = runTest {
        service.setCanDo("daily-life-sit-001/0", true)
        service.setCanDo("daily-life-sit-001/1", true)
        service.setCanDo("daily-life-sit-001/0", false)
        assertEquals(setOf("daily-life-sit-001/1"), service.canDoDone())
    }

    @Test
    fun noPackMeansHonestEmptyStates() = runTest {
        val none = TrackService(settings, knowledge, { null }, { dictionary }) { _, _ -> "" }
        assertTrue(none.tracks().isEmpty())
        none.switchTo("gaming")
        assertEquals(0, none.available())
        assertNull(none.mixInto(null, 4))
    }

    // --- drills ----------------------------------------------------------------------------------------------

    @Test
    fun keigoRulesCoverSpecialVerbsAndRegularPatterns() {
        fun f(plain: String, reading: String?, cls: WordClass?, t: KeigoTarget, form: KeigoForm = KeigoForm.MASU) =
            KeigoRules.forms(plain, reading, cls, t, form)
        assertTrue("おっしゃいます" in f("言う", "いう", WordClass.GODAN, KeigoTarget.SONKEIGO))
        assertTrue("申します" in f("言う", "いう", WordClass.GODAN, KeigoTarget.KENJOGO))
        assertTrue("申し上げます" in f("言う", "いう", WordClass.GODAN, KeigoTarget.KENJOGO))
        val matsu = f("待つ", "まつ", WordClass.GODAN, KeigoTarget.SONKEIGO)
        assertTrue("お待ちになります" in matsu && "待たれます" in matsu && "おまちになります" in matsu)
        val matsuHumble = f("待つ", "まつ", WordClass.GODAN, KeigoTarget.KENJOGO)
        assertTrue("お待ちします" in matsuHumble && "お待ちいたします" in matsuHumble)
        assertTrue("ご説明します" in f("説明する", "せつめいする", WordClass.SURU, KeigoTarget.KENJOGO))
        val setsumei = f("説明する", null, WordClass.SURU, KeigoTarget.SONKEIGO)
        assertTrue("ご説明になります" in setsumei && "説明なさいます" in setsumei && "説明されます" in setsumei)
        assertTrue("お電話します" in f("電話する", null, WordClass.SURU, KeigoTarget.KENJOGO))
        assertTrue("ご存じです" in f("知っている", null, null, KeigoTarget.SONKEIGO))
        val iku = f("行く", "いく", WordClass.GODAN, KeigoTarget.KENJOGO, KeigoForm.MASU_PAST)
        assertTrue("参りました" in iku && "伺いました" in iku)
        assertTrue("いらっしゃいます" in f("行く", "いく", WordClass.GODAN, KeigoTarget.SONKEIGO))
        assertFalse("お行きになります" in f("行く", "いく", WordClass.GODAN, KeigoTarget.SONKEIGO), "no regular form for special verbs")
        assertTrue("ご覧になりました" in f("見る", "みる", WordClass.ICHIDAN, KeigoTarget.SONKEIGO, KeigoForm.MASU_PAST))
        assertTrue("拝見します" in f("見る", "みる", WordClass.ICHIDAN, KeigoTarget.KENJOGO))
        assertTrue("召し上がる" in f("食べる", "たべる", WordClass.ICHIDAN, KeigoTarget.SONKEIGO, KeigoForm.DICTIONARY))
        assertTrue("いただいて" in f("食べる", "たべる", WordClass.ICHIDAN, KeigoTarget.KENJOGO, KeigoForm.TE))
        assertTrue("ございます" in f("ある", "ある", WordClass.GODAN, KeigoTarget.TEINEIGO))
        assertTrue("書きます" in f("書く", "かく", WordClass.GODAN, KeigoTarget.TEINEIGO))
        assertTrue("なさいます" in f("する", "する", WordClass.SURU, KeigoTarget.SONKEIGO))
        assertTrue("いたします" in f("する", "する", WordClass.SURU, KeigoTarget.KENJOGO))
    }

    @Test
    fun keigoDrillAcceptsListedAndRuleFormsLeniently() = runTest {
        val drill = assertIs<KeigoDrill>(repo.drill("gaming-keigo-001"))
        assertEquals(KeigoTarget.SONKEIGO, drill.target)
        assertEquals(KeigoForm.MASU, drill.form)
        assertTrue(drill.check("お待ちになります").correct)
        assertTrue(drill.check(" おまちになります。").correct, "kana, spaces and punctuation don't matter")
        assertTrue(drill.check("待たれます").correct, "a rule form the author didn't list")
        assertTrue(drill.check("オマチニナリマス").correct, "katakana folds to hiragana")
        assertFalse(drill.check("お待ちします").correct, "humble is wrong for the manager's action")
        assertFalse(drill.check("").correct)
        assertEquals(listOf("お待ちになります"), drill.check("x").expected)
    }

    @Test
    fun emailFillInChoiceAndUsageDrillsCheckAnswers() = runTest {
        val email = assertIs<EmailDrill>(repo.drill("gaming-email-001"))
        assertEquals(
            listOf(EmailSegment.Text("山田様\n"), EmailSegment.Slot(0), EmailSegment.Text("。\n"), EmailSegment.Slot(1), EmailSegment.Text("。")),
            email.segments,
        )
        assertTrue(email.check(0, "いつもお世話になっております").correct)
        assertFalse(email.check(1, "よろしく").correct)
        assertEquals("山田様\nいつもお世話になっております。\nよろしくお願いいたします。", email.filled)

        val fill = assertIs<FillInDrill>(repo.drill("gaming-fi-001"))
        assertEquals("毎日" to "を食べます。", fill.parts)
        assertTrue(fill.check("寿司").correct)
        assertFalse(fill.check("猫").correct)
        assertEquals("毎日寿司を食べます。", fill.completed)

        val syn = assertIs<SynonymDrill>(repo.drill("gaming-syn-001"))
        assertTrue(syn.antonym)
        assertTrue(syn.check(1).correct)
        assertFalse(syn.check(0).correct)
        val use = assertIs<UsageDrill>(repo.drill("gaming-use-001"))
        assertTrue(use.check(false).correct)
        assertFalse(use.check(true).correct)
        val meaning = assertIs<MeaningDrill>(repo.drill("gaming-mean-001"))
        assertTrue(meaning.check(2).correct)
        assertEquals(listOf("cat"), meaning.check(0).expected)
    }

    @Test
    fun memorizeAndPerformFadesPromptsRoundByRound() = runTest {
        val drill = assertIs<PerformDrill>(repo.drill("gaming-perf-001"))
        assertEquals(listOf(1, 3), drill.learnerLines)
        assertEquals("お○○○○○○。", PerformanceSession.mask("おじゃまします。", FadeLevel.INITIAL))
        assertEquals("おじゃま○○○。", PerformanceSession.mask("おじゃまします。", FadeLevel.HALF))
        assertEquals("よろ○○、はじめ○○○。", PerformanceSession.mask("よろしく、はじめまして。", FadeLevel.HALF))
        assertEquals("おじゃまします。", PerformanceSession.mask("おじゃまします。", FadeLevel.FULL))
        assertNull(PerformanceSession.mask("はい。", FadeLevel.CUE_ONLY))

        val session = PerformanceSession(drill)
        assertEquals("お邪魔します。", session.prompts()[1].japanese)
        assertEquals("いらっしゃい。どうぞ。", session.prompts()[0].japanese, "partner lines always show")
        assertTrue(session.deliver(1, "お邪魔します").passed)
        assertFalse(session.deliver(3, "ありがとう").passed)
        assertEquals(FadeLevel.FULL, session.nextRound(), "a missed line repeats the round")
        session.deliver(1, "お邪魔します")
        session.selfRate(3, gotIt = true)
        assertEquals(FadeLevel.HALF, session.nextRound())
        assertEquals("お邪魔○○○。", session.prompts()[1].japanese)
        for (level in listOf(FadeLevel.INITIAL, FadeLevel.CUE_ONLY)) {
            drill.learnerLines.forEach { session.deliver(it, drill.lines[it].ja) }
            assertEquals(level, session.nextRound())
        }
        assertNull(session.prompts()[1].japanese)
        assertEquals("bow slightly", session.prompts()[1].stage)
        drill.learnerLines.forEach { session.deliver(it, drill.lines[it].ja) }
        session.nextRound()
        assertTrue(session.finished)
    }

    @Test
    fun lessonsSplitAtTopicChangesAndSize() {
        fun w(ord: Int, topic: String) = TrackWord("t", ord, ord.toLong(), "語$ord", "ご", "", topic, "", null, emptyList(), "", "llm")
        val lessons = TrackRepository.lessonsOf("t", listOf(w(0, "a"), w(1, "a"), w(2, "a"), w(3, "b")), size = 2)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), lessons.map { l -> l.words.map { it.ord } })
        assertEquals(listOf(0, 1, 2), lessons.map { it.index })
    }

    // --- fixture ---------------------------------------------------------------------------------------------

    private fun pathItem(id: String) = PathItem(id, ItemKind.KANJI, 1, "日", "日", "sun", listOf("sun"), listOf("にち"), emptyList(), null, null, 5, emptyList())

    private fun fill(db: TracksDatabase) {
        val q = db.tracksQueries
        q.insertTrack(TrackRow("gaming", 0, "Gaming & VTuber", "ゲーム", "Games.", "Kanji Adventures", 5, 2, "", """{"words":3,"kanji":1}""", "CC BY-SA 4.0", "Tsumugi contributors", "llm"))
        q.insertTrack(TrackRow("daily-life", 1, "Daily-life admin", "生活", "Admin.", "", 3, 1, "2-3", """{"words":1}""", "CC BY-SA 4.0", "Tsumugi contributors", "llm"))
        q.insertWord(Track_word("gaming", 0, DictionaryFixture.TABERU, "食べる", "たべる", "to eat", "food", "", 5, "2", "", "llm"))
        q.insertWord(Track_word("gaming", 1, DictionaryFixture.NEKO, "猫", "ねこ", "cat", "pets", "", 5, "", "", "llm"))
        q.insertWord(Track_word("gaming", 2, DictionaryFixture.SUSHI, "寿司", "すし", "sushi", "food", "", null, "", "", "llm"))
        q.insertWord(Track_word("daily-life", 0, DictionaryFixture.NEKO, "猫", "ねこ", "cat", "pets", "", 5, "", "", "llm"))
        q.insertKanji(Track_kanji("gaming", 0, "食", "eat", """["人","良"]""", "A person and good food.", "Eat to heal your HP.", """[${DictionaryFixture.TABERU}]""", "llm"))
        q.insertScenario(
            Track_scenario(
                "gaming-sc-001", "gaming", 0, "Co-op lobby", "ロビー", 4, "1", "online", "A lobby.", "Player", "Host", "casual",
                """["Join"]""", """[{"text":"猫","reading":"ねこ","entryId":${DictionaryFixture.NEKO}}]""", """["よろしく"]""", "prompt", "llm",
            ),
        )
        q.insertTurn(Track_scripted_turn("gaming-sc-001", 0, "パーティーに入る?", "Join the party?", "Say yes", "うん、入る!"))
        q.insertDialogue(Track_dialogue("gaming-dl-001", "gaming", 0, "Stream", 4, "", "streaming", """[{"id":"A","name":"佐藤","voice":"female","age":"young"},{"id":"B","name":"ケン","voice":"male","age":"adult"}]""", "llm"))
        q.insertLine(Track_dialogue_line("gaming-dl-001", 0, "A", "こんにちは。", "Hello.", "[]", """["こんにちは。"]"""))
        q.insertLine(Track_dialogue_line("gaming-dl-001", 1, "B", "猫がいます。", "There's a cat.", """[{"text":"猫","start":0,"end":1,"entryId":${DictionaryFixture.NEKO}}]""", """["猫が","います。"]"""))
        q.insertQuestion(Track_dialogue_question("gaming-dl-001", 0, "What is there?", """["A dog","A cat"]""", 1))
        fun drill(id: String, type: String, payload: String) = q.insertDrill(Track_drill(id, "gaming", 0, type, "", null, payload, "llm"))
        drill(
            "gaming-keigo-001", "keigo",
            """{"plain":"待つ","target":"sonkeigo","form":"masu","sentence":"部長が（　　）。","answers":["お待ちになります"],"explanation":"お〜になる","verb":"待つ","verbReading":"まつ","verbClass":"GODAN","entryId":1}""",
        )
        drill(
            "gaming-email-001", "email",
            """{"title":"Thanks","situation":"After a visit","subject":"御礼","body":"山田様\n｛1｝。\n｛2｝。","blanks":[{"answers":["いつもお世話になっております"],"choices":["いつもお世話になっております","こんにちは"],"hint":"opening"},{"answers":["よろしくお願いいたします"],"hint":"closing"}]}""",
        )
        drill("gaming-fi-001", "fill_in", """{"sentence":"毎日（　　）を食べます。","answers":["寿司"],"choices":["寿司","猫"],"word":"寿司","entryId":${DictionaryFixture.SUSHI},"en":"I eat sushi every day.","explanation":"You eat sushi."}""")
        drill("gaming-syn-001", "synonym", """{"relation":"antonym","word":"行く","entryId":${DictionaryFixture.IKU},"choices":["歩く","来る","走る"],"answer":1,"explanation":"行く ↔ 来る"}""")
        drill("gaming-use-001", "usage", """{"word":"猫","entryId":${DictionaryFixture.NEKO},"sentence":"猫を飲みます。","correct":false,"explanation":"You don't drink cats."}""")
        drill("gaming-mean-001", "meaning", """{"word":"猫","entryId":${DictionaryFixture.NEKO},"choices":["dog","bird","cat"],"answer":2,"explanation":"猫 = cat"}""")
        drill(
            "gaming-perf-001", "perform",
            """{"title":"Visiting","titleJa":"訪問","setting":"A genkan.","register":"polite","speakers":[{"id":"A","name":"母","voice":"female","age":"senior"},{"id":"B","name":"ケン","voice":"male","age":"adult"}],"learner":"B","staging":["Bow at the door."],"lines":[{"speaker":"A","ja":"いらっしゃい。どうぞ。","en":"Welcome. Come in."},{"speaker":"B","ja":"お邪魔します。","en":"Sorry to intrude.","stage":"bow slightly"},{"speaker":"A","ja":"どうぞ、こちらへ。","en":"This way."},{"speaker":"B","ja":"ありがとうございます。","en":"Thank you."}]}""",
        )
        drill("gaming-new-001", "karaoke", "{}")
        q.insertSituation(Track_situation("daily-life-sit-001", "daily-life", 0, "City hall", "市役所", """[{"en":"I can ask.","ja":"聞ける。"},{"en":"I can fill a form.","ja":"書ける。"}]""", "llm"))
        q.insertTask(Track_task("daily-life-task-001", "daily-life", 0, "Onsen", "温泉", """{"place":"a hot-spring inn","before":["Read"],"during":["Wash"],"after":["Reflect"],"phrases":["いいお湯でした"],"etiquette":["No towels in the bath"]}""", "llm"))
        q.insertReading(Track_reading("daily-life-rd-001", "daily-life", 0, "Notice", "2+", "notice", "本文です。", """[{"question":"Q?","choices":["a","b","c"],"answer":0}]""", "llm"))
        q.insertLink(Track_link("daily-life", 0, "JMSDF", "https://www.mod.go.jp/msdf/", "link only"))
    }
}
