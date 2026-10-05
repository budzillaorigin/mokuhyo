package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.FakeModel
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.exam.IlrLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BRIEF_PHASE8 C-06 gate: golden tests for the new prompt fields, and three fixture turns per language that produce
 * the expected pragmatic flag (register, face, directness, ritual, taboo) through the gateway and the topic session.
 * Also pins that flags never change a level: the OPI rating never reads the cultural review.
 */
class PragmaticsGoldenTest {
    private class Fixture(val lang: String, val learner: String, val kind: String, val better: String, val reply: String)

    private val fixtures = listOf(
        Fixture("ja", "おい、ドローンの報告書を早く出せ。", "register", "恐れ入りますが、ドローンの報告書をご提出いただけますでしょうか。", "承知しました。午後までに提出します。"),
        Fixture("ja", "あなたの計画は間違っています。", "face", "一点だけ確認させていただきたいのですが、この計画で北側は大丈夫でしょうか。", "なるほど、北側について説明します。"),
        Fixture("ja", "じゃあ、帰ります。", "ritual", "本日はありがとうございました。それでは失礼いたします。", "お疲れさまでした。"),
        Fixture("ko", "야, 드론 보고서 빨리 줘.", "register", "드론 보고서를 주실 수 있겠습니까?", "네, 곧 드리겠습니다."),
        Fixture("ko", "당신 계획은 틀렸어요.", "face", "한 가지 여쭤봐도 될까요? 북쪽 경계는 어떻게 하실 계획입니까?", "좋은 질문입니다."),
        Fixture("ko", "그럼 갈게요.", "ritual", "오늘 시간 내 주셔서 감사합니다. 먼저 가 보겠습니다.", "수고하셨습니다."),
        Fixture("de", "Hey du, gib mir mal den Bericht.", "register", "Herr Oberst, könnten Sie mir bitte den Bericht geben?", "Selbstverständlich, hier ist er."),
        Fixture("de", "Was verdienen Sie eigentlich im Monat?", "taboo", "Wie lange sind Sie schon bei der Luftwaffe?", "Seit zwölf Jahren."),
        Fixture("de", "Tschüss.", "ritual", "Vielen Dank für Ihre Zeit, Herr Oberst. Auf Wiedersehen.", "Auf Wiedersehen."),
        Fixture("fr", "Salut, tu me donnes le rapport ?", "register", "Mon colonel, pourriez-vous me transmettre le rapport, s'il vous plaît ?", "Bien sûr, je vous l'envoie."),
        Fixture("fr", "Combien vous gagnez par mois ?", "taboo", "Depuis combien de temps êtes-vous dans l'armée de l'air ?", "Depuis quinze ans."),
        Fixture("fr", "Donnez-moi les horaires.", "ritual", "Bonjour mon commandant, pourriez-vous m'indiquer les horaires, s'il vous plaît ?", "Bonjour. Les voici."),
        Fixture("es", "Oye, pásame el informe.", "register", "Mi coronel, ¿podría pasarme el informe, por favor?", "Claro, aquí lo tiene."),
        Fixture("es", "Su plan está mal.", "face", "Disculpe, mi coronel, ¿podríamos revisar juntos el sector norte del plan?", "Sí, revisémoslo."),
        Fixture("es", "Me voy.", "ritual", "Muchas gracias por su tiempo, mi coronel. Con su permiso, me retiro.", "Que le vaya bien."),
        Fixture("pt-BR", "E aí, me passa o relatório.", "register", "Coronel, o senhor poderia me passar o relatório, por favor?", "Claro, aqui está."),
        Fixture("pt-BR", "Seu plano está errado.", "face", "Com licença, coronel, podemos rever juntos o setor norte do plano?", "Podemos, sim."),
        Fixture("pt-BR", "Tchau.", "ritual", "Muito obrigado pelo seu tempo, coronel. Até logo.", "Até logo."),
        Fixture("ru", "Слушай, дай отчёт.", "register", "Товарищ полковник, разрешите получить отчёт.", "Хорошо, вот он."),
        Fixture("ru", "Ваш план неправильный.", "face", "Разрешите уточнить, как будет прикрыт северный сектор?", "Хороший вопрос."),
        Fixture("ru", "Ну всё, я пошёл.", "ritual", "Спасибо за уделённое время, товарищ полковник. Разрешите идти?", "Идите."),
        Fixture("ar", "أعطني التقرير بسرعة.", "directness", "لو سمحت يا سيادة العقيد، هل يمكنك أن تعطيني التقرير؟", "تفضل، هذا هو التقرير."),
        Fixture("ar", "هل تشرب الخمر؟", "taboo", "هل تحب القهوة العربية؟", "نعم، أحبها كثيرا."),
        Fixture("ar", "خطتك خطأ.", "face", "اسمح لي بسؤال: كيف سنحمي القطاع الشمالي؟", "سؤال جيد."),
        Fixture("fa", "گزارش رو بده.", "register", "جناب سرهنگ، ممکن است لطفاً گزارش را به من بدهید؟", "بله، بفرمایید."),
        Fixture("fa", "نقشه‌ی شما غلط است.", "face", "اجازه بدهید یک سؤال بپرسم: بخش شمالی چگونه محافظت می‌شود؟", "سؤال خوبی است."),
        Fixture("fa", "خداحافظ.", "ritual", "از وقتی که گذاشتید خیلی ممنونم. با اجازه‌تان مرخص می‌شوم.", "خواهش می‌کنم."),
        Fixture("id", "Kamu, kasih laporannya.", "register", "Mohon maaf, Bapak, bisakah Bapak memberikan laporannya?", "Tentu, ini laporannya."),
        Fixture("id", "Rencana Bapak salah.", "face", "Mohon izin bertanya, Pak, bagaimana pengamanan sektor utara?", "Pertanyaan yang bagus."),
        Fixture("id", "Saya pergi.", "ritual", "Terima kasih atas waktunya, Pak. Saya mohon pamit.", "Silakan."),
        Fixture("zh-Hans", "喂，把报告给我。", "register", "首长，请问您能把报告给我吗？", "好的，给你。"),
        Fixture("zh-Hans", "你的计划是错的。", "face", "请允许我问一个问题：北边的防区怎么安排？", "这个问题很好。"),
        Fixture("zh-Hans", "我走了。", "ritual", "谢谢您抽出时间，我先告辞了。", "慢走。"),
    )

    private fun output(f: Fixture): String = buildJsonObject {
        put("reply", f.reply)
        put("reply_english", "")
        put("corrected", f.learner)
        put("changes", JsonArray(emptyList()))
        put("rewrite", f.better)
        put("vocabulary", JsonArray(emptyList()))
        put("turn_level", "1+")
        put("pragmatics", buildJsonArray {
            add(buildJsonObject {
                put("kind", f.kind); put("severity", "medium"); put("what", f.learner)
                put("why", "Expected courtesy toward a senior host-nation officer."); put("better", f.better)
            })
        })
    }.toString()

    private val persona = PersonaContext("Sato Kenji", "佐藤一等空佐", "Senior counterpart", "Japan Air Self-Defense Force",
        "Formal keigo with outsiders; expects rank and surname.", patience = 2, formality = 5)

    @Test
    fun everyLanguageHasThreeFixtureTurns() {
        assertEquals(11, fixtures.map { it.lang }.distinct().size)
        fixtures.groupBy { it.lang }.forEach { (lang, fs) -> assertEquals(3, fs.size, lang) }
    }

    @Test
    fun fixtureTurnsProduceTheExpectedFlag() = runTest {
        fixtures.forEach { f ->
            val model = FakeModel(output(f), output(f)) // reply, then the critique (N-00b split)
            val profile = OpiProfile(f.lang, "Use polite register with the learner.")
            val session = TopicSession(f.lang, profile, Topic("t", "military_operations", "Base security", "…"), AiGateway({ model }),
                persona = persona, culturalNotes = listOf("Address seniors by rank."))
            val ex = assertNotNull(session.say(f.learner), "${f.lang}: ${f.learner}")
            assertEquals(listOf(f.kind), ex.pragmatics.map { it.kind }, "${f.lang}: ${f.learner}")
            assertEquals("1+", ex.turnLevel, "a pragmatic flag does not change the turn level")
            // The persona reaches the partner's prompt; the cultural notes reach the critique's.
            assertEquals(2, model.requests.size)
            val partner = model.requests[0].messages.first().content
            val critique = model.requests[1].messages.first().content
            assertTrue("Sato Kenji" in partner && "Formality 5/5" in partner, f.lang)
            assertTrue("Address seniors by rank." in critique, f.lang)
        }
    }

    @Test
    fun promptFieldsArePinned() {
        val task = TopicTurn()
        val props = task.schema.toString()
        assertTrue("pragmatics" in props && "register" in props && "taboo" in props && "directness" in props, props)
        val msgs = task.messages(TopicTurn.Input("ja", "polite", "topic", "military_operations", IlrLevel.L1, listOf(Turn(Speaker.LEARNER, "はい")),
            persona = persona, culturalNotes = listOf("Bow slightly when greeting.")))
        val sys = msgs.first().content
        assertTrue("pragmatics = cultural and pragmatic problems in the learner's LAST turn only" in sys)
        assertTrue("Pragmatic problems never lower turn_level." in sys)
        assertTrue("• Bow slightly when greeting." in sys)
        assertEquals(
            "You are Sato Kenji, 佐藤一等空佐 (Senior counterpart), Japan Air Self-Defense Force. Stay in character.\n" +
                "How you speak and expect to be addressed: Formal keigo with outsiders; expects rank and surname.\n" +
                "Formality 5/5: formal; you notice and mind casual address or skipped courtesies.\n" +
                "Patience 2/5: busy and brisk; you keep answers short and move on if the learner is slow or unclear.",
            PersonaPrompt.lines(persona),
        )
    }

    @Test
    fun interviewerFollowsThePragmaticsPack() {
        val msgs = OpiInterviewerTurn().messages(OpiInterviewerTurn.Input("ko", "합쇼체", OpiPhase.WARMUP, IlrLevel.L1,
            culturalNotes = listOf("Use rank and title, never a bare name, with a senior officer.")))
        assertTrue("Cultural norms you follow as a native speaker (and in role-plays): • Use rank and title, never a bare name, with a senior officer." in msgs.first().content)
    }

    @Test
    fun invalidFlagsAreRejected() {
        val json = Json { ignoreUnknownKeys = true }
        val f = fixtures.first()
        val bad = output(f).replace("\"register\"", "\"rudeness\"")
        val out = json.decodeFromString(TopicTurn().serializer, bad)
        val problems = TopicTurn().validate(TopicTurn.Input("ja", "", "t", "d", IlrLevel.L1, listOf(Turn(Speaker.LEARNER, f.learner))), out, ValidationContext())
        assertTrue(problems.any { "pragmatics kind" in it }, problems.toString())
    }

    @Test
    fun culturalReviewNeverChangesTheRating() = runTest {
        val rating = """{"functions":{"level":"1+","evidence":"Asks and answers simple questions.","quotes":[]},
            "context_content":{"level":"1+","evidence":"Routine topics.","quotes":[]},"accuracy":{"level":"1+","evidence":"Some errors.","quotes":[]},
            "text_type":{"level":"1+","evidence":"Short connected sentences.","quotes":[]},"sustained_level":"1+","breakdown_level":"2","estimate":"1+",
            "rationale":"Connected sentences on routine topics; breaks down at narration, so ILR 1+.",
            "next_steps":["Narrate a past event.","Describe your unit.","Practise polite requests."]}"""
        val review = """{"flags":[{"turn":1,"kind":"register","severity":"high","what":"おい","why":"Too casual for an interviewer.","better":"すみません"}],
            "summary":"Mostly polite; one very casual opening."}"""
        val model = FakeModel(
            """{"utterance":"お名前は何ですか。","english":"What is your name?","next_phase":"warmup","topic":"name","domain":"personal"}""",
            rating, review,
        )
        val s = OpiSession("ja", OpiProfile("ja", "です/ます"), emptyList(), emptyList(), AiGateway({ model }), { it.length / 2 })
        s.next()
        s.answer("おい、スミスです。")
        val before = s.rate()
        val cultural = assertNotNull(s.culturalReview(listOf("Use です/ます with the interviewer.")))
        assertEquals("register", cultural.flags.single().kind)
        assertEquals("1+", before.estimate)
        assertEquals(3, model.requests.size, "the rating is one call; the cultural review is a separate call")
        assertTrue(model.requests[1].messages.none { "Cultural" in it.content || "pragmatic" in it.content }, "opi_rate never sees cultural notes")
    }
}
