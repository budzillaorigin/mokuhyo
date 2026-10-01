package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.ValidationContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIEF §11.1 gate_speaking: the rating JSON validates 100 % on golden fixtures (good ratings in several languages
 * and scripts), and the validators reject the failure modes small models show (invented quotes, an estimate above the
 * sustained level, wrong number of next steps, non-English rationale).
 */
class OpiRateGoldenTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val task = OpiRate()

    private class Golden(val lang: String, val transcript: List<Pair<String, String>>, val rating: String)

    private fun history(t: List<Pair<String, String>>) = t.flatMap { (q, a) -> listOf(Turn(Speaker.PARTNER, q), Turn(Speaker.LEARNER, a)) }

    private fun rating(level: String, sustained: String, quote: String, rationale: String) = """{
        "functions":{"level":"$level","evidence":"Narrates and describes past events in connected sentences.","quotes":["$quote"]},
        "context_content":{"level":"$level","evidence":"Handles concrete personal and work topics.","quotes":[]},
        "accuracy":{"level":"$level","evidence":"Generally accurate with some agreement errors.","quotes":[]},
        "text_type":{"level":"$level","evidence":"Paragraph-length answers.","quotes":[]},
        "sustained_level":"$sustained","breakdown_level":"2+","estimate":"$level",
        "rationale":"$rationale","next_steps":["Practise giving reasons for an opinion.","Narrate a past event with time markers.","Review verb agreement in long sentences."]}"""

    private val goldens = listOf(
        Golden("es", listOf("¿Qué hizo el fin de semana pasado?" to "El fin de semana pasado fui a la playa con mi familia y comimos pescado en un restaurante muy bonito."),
            rating("2", "2", "fui a la playa con mi familia", "The candidate narrates past events in connected sentences on concrete topics, consistent with ILR 2.")),
        Golden("ja", listOf("週末は何をしましたか。" to "週末は友達と京都に行きました。お寺を見て、そのあと抹茶を飲みました。とても楽しかったです。"),
            rating("1+", "1+", "友達と京都に行きました", "Connected sentences about a recent trip with good control of past tense; limited elaboration, so ILR 1+.")),
        Golden("ar", listOf("ماذا فعلت في عطلة نهاية الأسبوع؟" to "ذهبت إلى السوق مع أخي واشترينا بعض الفواكه ثم رجعنا إلى البيت."),
            rating("1+", "1+", "ذهبت إلى السوق مع أخي", "Simple connected sentences narrating a routine past event; breaks down when asked for more detail.")),
        Golden("ko", listOf("주말에 뭐 했어요?" to "주말에 친구하고 영화를 봤어요. 그리고 같이 저녁을 먹었어요."),
            rating("1+", "1+", "친구하고 영화를 봤어요", "Short connected sentences in the polite register about a past weekend.")),
        Golden("ru", listOf("Что вы делали в выходные?" to "В выходные я ездил на дачу к родителям, мы работали в саду и вечером жарили шашлыки."),
            rating("2", "2", "я ездил на дачу к родителям", "Narrates a past event in a paragraph with mostly accurate morphology, consistent with ILR 2.")),
        Golden("zh-Hans", listOf("你周末做了什么？" to "周末我和家人去公园散步，然后在饭馆吃了晚饭。"),
            rating("1+", "1+", "和家人去公园散步", "Connected sentences about a routine past event; little elaboration.")),
    )

    @Test
    fun goldenRatingsValidate100Percent() {
        goldens.forEach { g ->
            val input = OpiRate.Input(g.lang, history(g.transcript))
            val out = json.decodeFromString(task.serializer, AiGateway.extractJsonObject(g.rating)!!)
            val problems = task.validate(input, out, ValidationContext())
            assertEquals(emptyList(), problems, g.lang)
        }
    }

    @Test
    fun badRatingsAreRejected() {
        val g = goldens.first()
        val input = OpiRate.Input(g.lang, history(g.transcript))
        fun problems(s: String) = task.validate(input, json.decodeFromString(task.serializer, AiGateway.extractJsonObject(s)!!), ValidationContext())
        assertTrue(problems(rating("2", "2", "hablé con el presidente", "The candidate narrates past events in connected sentences.")).any { "not in the candidate" in it })
        assertTrue(problems(rating("2+", "2", "fui a la playa", "The candidate narrates past events in connected sentences.")).isEmpty(), "one step above is capped, not rejected")
        assertTrue(problems(rating("3", "1+", "fui a la playa", "The candidate narrates past events in connected sentences.")).isEmpty(), "capped by normalize")
        assertTrue(problems(rating("2", "2", "fui a la playa", "El candidato narra eventos pasados con oraciones conectadas.")).any { "not English" in it })
        val twoSteps = rating("2", "2", "fui a la playa", "The candidate narrates past events in connected sentences.").replace(",\"Review verb agreement in long sentences.\"", "")
        assertTrue(problems(twoSteps).isEmpty(), "two steps are accepted")
        val oneStep = twoSteps.replace(",\"Narrate a past event with time markers.\"", "")
        assertTrue(problems(oneStep).any { "three items" in it })
        // normalize: caps the estimate and drops non-verbatim quotes.
        val raw = json.decodeFromString(task.serializer, AiGateway.extractJsonObject(rating("2+", "2", "fui a la playa", "The candidate narrates past events in connected sentences."))!!)
        val n = OpiRate.normalize(input, raw.copy(accuracy = raw.accuracy.copy(quotes = listOf("algo inventado"))))
        assertEquals("2", n.estimate)
        assertEquals(emptyList(), n.accuracy.quotes)
        assertEquals(listOf("fui a la playa"), n.functions.quotes)
    }
}
