package app.mokuhyo.ai

import app.mokuhyo.ai.prompts.CorrectSentence
import app.mokuhyo.ai.prompts.FreeTalkTurn
import app.mokuhyo.ai.prompts.GradeProduction
import app.mokuhyo.ai.prompts.GradeTranslation
import app.mokuhyo.ai.prompts.NaturalRewrite
import app.mokuhyo.ai.prompts.OpiInterviewerTurn
import app.mokuhyo.ai.prompts.OpiPhase
import app.mokuhyo.ai.prompts.OpiRate
import app.mokuhyo.ai.prompts.PromptLibrary
import app.mokuhyo.ai.prompts.RoleplayTurn
import app.mokuhyo.ai.prompts.Speaker
import app.mokuhyo.ai.prompts.TranslateSentence
import app.mokuhyo.ai.prompts.Turn
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden cases for every prompt in the library: a realistic good model answer must parse, validate and come back
 * as [AiResult.Ok] with the expected values; a typical bad answer (the failure modes small models actually show)
 * must be rejected by the task's validators. Each schema must also compile to a grammar.
 */
class PromptGoldenTest {
    private class Golden<I, O>(
        val task: PromptTask<I, O>,
        val input: I,
        val good: String,
        val bad: String,
        val badReason: String,
        val promptMentions: List<String>,
        val check: (O) -> Unit,
    )

    private val interview = listOf(
        Turn(Speaker.PARTNER, "お名前を教えてください。"),
        Turn(Speaker.LEARNER, "ブディです。アメリカから来ました。"),
        Turn(Speaker.PARTNER, "日本語はどのくらい勉強していますか。"),
        Turn(Speaker.LEARNER, "二年ぐらい勉強しています。毎日アニメを見て、単語を覚えます。"),
    )

    private val goldens: List<Golden<*, *>> = listOf(
        Golden(
            CorrectSentence(),
            CorrectSentence.Input("昨日、図書館で本を読みます。"),
            """{"is_correct":false,"corrected":"昨日、図書館で本を読みました。","confidence":0.92,"edits":[{"original":"読みます","replacement":"読みました","reason":"昨日 needs the past tense."}],"explanation":"The time word 昨日 means the verb must be past tense."}""",
            """{"is_correct":false,"corrected":"Yesterday I read a book at the library.","confidence":0.9,"edits":[{"original":"読みます","replacement":"read","reason":"x"}],"explanation":"Translated."}""",
            "not Japanese",
            listOf("N4 learner", "changing as little as possible"),
        ) { out ->
            assertEquals("昨日、図書館で本を読みました。", out.corrected)
            assertEquals(false, out.isUnsure)
        },
        Golden(
            CorrectSentence(),
            CorrectSentence.Input("ちょっと待ってて。"),
            """{"is_correct":true,"corrected":"ちょっと待ってて。","confidence":0.4,"edits":[],"explanation":"Casual but fine."}""",
            """{"is_correct":true,"corrected":"ちょっと待っていてください。","confidence":0.8,"edits":[],"explanation":"Fine."}""",
            "marked correct but the sentence was changed",
            listOf("confidence is 0 to 1"),
        ) { out -> assertTrue(out.isUnsure, "confidence < 0.5 means unsure") },
        Golden(
            NaturalRewrite(),
            NaturalRewrite.Input("私はあなたにこの本をあげることをしたいです。", NaturalRewrite.Register.CASUAL),
            """{"rewrite":"この本、あげたいんだけど。","notes":"Dropped 私は/あなたに (clear from context) and used plain form with んだけど."}""",
            """{"rewrite":"kono hon, agetai n da kedo.","notes":"Romaji."}""",
            "rewrite is not Japanese",
            listOf("casual plain form"),
        ) { out -> assertEquals("この本、あげたいんだけど。", out.rewrite) },
        Golden(
            RoleplayTurn(),
            RoleplayTurn.Input(
                scenario = "A small ramen shop in Tokyo at lunchtime.",
                partnerRole = "shop staff",
                learnerRole = "customer",
                goal = "Order a bowl of ramen and pay.",
                history = listOf(Turn(Speaker.PARTNER, "いらっしゃいませ。ご注文は？"), Turn(Speaker.LEARNER, "しょうゆラーメンをください。")),
            ),
            """{"reply":"しょうゆラーメンですね。お飲み物はいかがですか。","translation":"Shoyu ramen, right. Would you like a drink?","hint":"Say whether you want a drink, e.g. 水をください。","goal_reached":false}""",
            """{"reply":"Shoyu ramen, right. Anything to drink?","translation":"Shoyu ramen.","hint":"","goal_reached":false}""",
            "reply is not Japanese",
            listOf("You are the shop staff", "customer: しょうゆラーメンをください。"),
        ) { out -> assertEquals(false, out.goalReached) },
        Golden(
            TranslateSentence(),
            TranslateSentence.Input("I would like to reserve a table for two.", TranslateSentence.Direction.EN_TO_JA),
            """{"translation":"二人で席を予約したいのですが。","literal":"As two people, I want to reserve a seat, but...","notes":"〜のですが softens the request."}""",
            """{"translation":"I want to reserve a table.","literal":"","notes":""}""",
            "translation is not Japanese",
            listOf("into natural Japanese"),
        ) { out -> assertEquals("二人で席を予約したいのですが。", out.translation) },
        Golden(
            TranslateSentence(),
            TranslateSentence.Input("駅まで歩いて十分です。"),
            """{"translation":"It's a ten-minute walk to the station.","literal":"Station until, walking, ten minutes is.","notes":""}""",
            """{"translation":"駅まで十分です。","literal":"","notes":""}""",
            "translation is not English",
            listOf("into natural English"),
        ) { out -> assertTrue(out.translation.startsWith("It's")) },
        Golden(
            OpiInterviewerTurn(),
            OpiInterviewerTurn.Input(OpiPhase.LEVEL_CHECK, "Intermediate Mid", interview, turnsInPhase = 1),
            """{"utterance":"アニメで勉強するのはどうしてですか。","next_phase":"level_check","topic":"study habits"}""",
            """{"utterance":"お名前は？","next_phase":"warmup","topic":"name"}""",
            "goes back",
            listOf("Current phase: level_check", "Candidate: 二年ぐらい"),
        ) { out -> assertEquals(OpiPhase.LEVEL_CHECK, out.nextPhase) },
        Golden(
            OpiRate(),
            OpiRate.Input(interview),
            """{"level":"Intermediate Low","functions":2,"accuracy":3,"vocabulary":2,"fluency":2,"rationale":"The candidate answers simple personal questions in short, accurate sentences (二年ぐらい勉強しています) but does not yet connect ideas into paragraphs.","strengths":["accurate ています forms"],"next_steps":["join sentences with から and ので"]}""",
            """{"level":"Intermediate Low","functions":7,"accuracy":3,"vocabulary":2,"fluency":2,"rationale":"Good.","strengths":[],"next_steps":[]}""",
            "scores must be 1 to 5",
            listOf("ACTFL", "unofficial"),
        ) { out -> assertEquals("Intermediate Low", out.level) },
        Golden(
            FreeTalkTurn(),
            FreeTalkTurn.Input("N4", listOf(Turn(Speaker.PARTNER, "こんにちは。週末は何をしましたか。"), Turn(Speaker.LEARNER, "友達と映画を見ました。"))),
            """{"reply":"いいですね。どんな映画を見ましたか。","translation":"Nice. What kind of movie did you see?","topic":"weekend movies"}""",
            """{"reply":"Nice! What movie did you see?","translation":"いいですね。","topic":"movies"}""",
            "reply is not Japanese",
            listOf("N4 learner", "not a teacher", "Learner: 友達と映画を見ました。"),
        ) { out -> assertEquals("weekend movies", out.topic) },
        Golden(
            GradeProduction(),
            GradeProduction.Input(
                english = "I bought the ticket in advance.", modelAnswer = "切符を買っておいた。", grammarTitle = "〜ておく",
                grammarStructure = "Verb て-form + おく", grammarMeaning = "do in advance", answer = "切符を買っておきました。",
                constructionFound = true,
            ),
            """{"meaning":2,"grammar":2,"form":2,"corrected":"","feedback":"Correct: 買っておきました uses ておく in the polite past, which fits the sentence."}""",
            """{"meaning":2,"grammar":3,"form":2,"corrected":"","feedback":"Good."}""",
            "scores must be 0 to 2",
            listOf("〜ておく", "Model answer: 切符を買っておいた。", "Learner's answer: 切符を買っておきました。"),
        ) { out -> assertEquals(6, out.total) },
        Golden(
            GradeProduction(),
            GradeProduction.Input(
                english = "I bought the ticket in advance.", modelAnswer = "切符を買っておいた。", grammarTitle = "〜ておく",
                grammarStructure = "Verb て-form + おく", grammarMeaning = "do in advance", answer = "切符を買いました。",
                constructionFound = false,
            ),
            """{"meaning":1,"grammar":0,"form":2,"corrected":"切符を買っておきました。","feedback":"The sentence is grammatical, but it doesn't use ておく, so 'in advance' is lost."}""",
            """{"meaning":2,"grammar":2,"form":2,"corrected":"","feedback":"Perfect, well done with this one."}""",
            "construction is not in the answer",
            listOf("did not find 〜ておく"),
        ) { out -> assertEquals(0, out.grammar) },
        Golden(
            GradeTranslation(),
            GradeTranslation.Input(
                source = "市は来月から、深夜バスの運行を週末にも拡大すると発表した。",
                attempt = "The city announced it will extend late-night buses to weekends starting next month.",
                reference = "The city announced that, from next month, it will expand its late-night bus service to weekends as well.",
                japaneseToEnglish = true,
                genre = "news",
                level = "N3",
                keyPoints = listOf("from next month", "late-night bus service", "weekends as well"),
                register = "neutral news English",
            ),
            """{"accuracy":4,"completeness":3,"register":4,"naturalness":4,"feedback":"Accurate and natural news English. You dropped 'as well' (にも), which tells readers weekday service already exists.","issues":[{"kind":"omission","attempt_span":"","reference_span":"as well","note":"にも means the weekends are added to existing service."}],"better":"The city announced it will extend late-night buses to weekends as well, starting next month."}""",
            """{"accuracy":4,"completeness":4,"register":4,"naturalness":4,"feedback":"Perfect translation with no problems at all, well done.","issues":[{"kind":"mistranslation","attempt_span":"night trains","reference_span":"","note":"Wrong vehicle."}],"better":""}""",
            "quotes words the learner didn't write",
            listOf("Japanese-to-English translation", "not an official score", "Key points:\n- from next month", "Learner's translation:\nThe city announced"),
        ) { out ->
            assertEquals(15, out.total)
            assertEquals(94, out.percent)
            assertEquals("omission", out.issues.single().kind)
        },
        Golden(
            GradeTranslation(),
            GradeTranslation.Input(
                source = "Please submit the form by Friday.",
                attempt = "金曜日までに書類を出してください",
                reference = "",
                japaneseToEnglish = false,
                genre = "technical",
                level = "N4",
                spoken = true,
            ),
            """{"accuracy":4,"completeness":4,"register":3,"naturalness":3,"feedback":"The meaning is right. For a written notice, 提出してください is the more usual verb than 出してください.","issues":[{"kind":"register","attempt_span":"出してください","reference_span":"","note":"提出する is the standard word for submitting forms."}],"better":"金曜日までに書類を提出してください。"}""",
            """{"accuracy":4,"completeness":4,"register":3,"naturalness":3,"feedback":"The meaning is right and the register is fine for a notice.","issues":[],"better":"Please submit the form by Friday."}""",
            "better is not Japanese",
            listOf("English-to-Japanese", "There is no reference", "speech-to-text transcript"),
        ) { out -> assertEquals(88, out.percent) },
    )

    @Test
    fun everyTaskHasAGoldenCase() {
        assertEquals(
            listOf(
                "correct_sentence", "natural_rewrite", "translate_sentence", "roleplay_turn", "opi_interviewer_turn", "opi_rate",
                "free_talk_turn", "grade_production", "grade_translation",
            ),
            PromptLibrary.names,
        )
        assertEquals(PromptLibrary.names.toSet(), goldens.map { it.task.name }.toSet())
    }

    @Test
    fun goodAnswersPass() = runTest {
        for (g in goldens) runGood(g)
    }

    @Test
    fun badAnswersAreRejected() = runTest {
        for (g in goldens) runBad(g)
    }

    @Test
    fun promptsCarryTheirInputs() {
        for (g in goldens) checkPrompt(g)
    }

    @Test
    fun everySchemaCompilesToAGrammar() {
        for (t in PromptLibrary.all) {
            val gbnf = t.schema.toGbnf()
            assertTrue(gbnf.startsWith("root ::= obj"), t.name)
            val defined = gbnf.lines().map { it.substringBefore(" ::= ") }.toSet()
            val referenced = Regex("""\b(obj|arr|enum)\d+\b""").findAll(gbnf).map { it.value }.toSet()
            assertTrue(defined.containsAll(referenced), "${t.name}: undefined rules ${referenced - defined}")
        }
    }

    private suspend fun <I, O> runGood(g: Golden<I, O>) {
        val result = AiGateway({ FakeModel(g.good) }).run(g.task, g.input)
        if (result !is AiResult.Ok) fail("${g.task.name}: expected Ok, got $result")
        g.check(result.value)
    }

    private suspend fun <I, O> runBad(g: Golden<I, O>) {
        val result = AiGateway({ FakeModel(g.bad, g.bad) }).run(g.task, g.input)
        assertIs<AiResult.Unavailable>(result, g.task.name)
        assertTrue(result.reason.contains(g.badReason), "${g.task.name}: ${result.reason}")
    }

    private fun <I, O> checkPrompt(g: Golden<I, O>) {
        val messages = g.task.messages(g.input)
        assertEquals(Role.SYSTEM, messages.first().role, g.task.name)
        assertEquals(Role.USER, messages.last().role, g.task.name)
        val all = messages.joinToString("\n") { it.content }
        for (m in g.promptMentions) assertTrue(m in all, "${g.task.name} prompt should mention \"$m\":\n$all")
    }
}
