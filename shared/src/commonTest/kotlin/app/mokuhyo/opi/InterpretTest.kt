package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.FakeModel
import app.mokuhyo.ai.ValidationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-01 gate: chunking and scoring golden tests, a scripted session in two languages, grader JSON 100% valid on fixtures. */
class InterpretTest {
    private val lines = listOf(
        SourceLine("Buenos días.", "Good morning.", "male"),
        SourceLine("¿Listo?", "Ready?", "male"),
        SourceLine("Vimos un dron sobre la puerta norte a las seis.", "We saw a drone over the north gate at six.", "female"),
        SourceLine("Voló hacia el oeste. Luego desapareció. Avisamos al centro. Nadie respondió.", "It flew west. Then it disappeared. We told the center. Nobody answered.", "female"),
    )

    @Test
    fun chunkingIsPinned() {
        val c = Chunker.chunks(lines, "es", InterpretDirection.TO_ENGLISH, "es-int")
        assertEquals(listOf("Buenos días. ¿Listo?", "Vimos un dron sobre la puerta norte a las seis.", "Voló hacia el oeste. Luego desapareció. Avisamos al centro.", "Nadie respondió."),
            c.map { it.source })
        assertEquals("Good morning. Ready?", c[0].reference)
        assertEquals(listOf("es-int-1", "es-int-2", "es-int-3", "es-int-4"), c.map { it.id })
        val back = Chunker.chunks(lines, "es", InterpretDirection.FROM_ENGLISH, "x")
        assertEquals("en", back[1].sourceLang)
        assertEquals("We saw a drone over the north gate at six.", back[1].source)
        assertEquals(listOf("今日は晴れです。", "明日は雨です。"), Chunker.sentences("今日は晴れです。明日は雨です。"))
    }

    private val fixtures = listOf(
        Triple("es", InterpretDirection.TO_ENGLISH, """{"accuracy":4,"completeness":3,"register":5,"omissions":["the time (six)"],"distortions":[],
            "better_version":"We saw a drone over the north gate at six.","note":"Keep times and places exact."}"""),
        Triple("ja", InterpretDirection.FROM_ENGLISH, """{"accuracy":5,"completeness":5,"register":4,"omissions":[],"distortions":[],
            "better_version":"六時に北門の上空でドローンを確認しました。","note":"Use the polite form in a report."}"""),
    )

    @Test
    fun graderJsonValidatesOnFixtures() {
        val json = Json { ignoreUnknownKeys = true }
        fixtures.forEach { (lang, dir, out) ->
            val chunk = Chunk("c", if (dir == InterpretDirection.TO_ENGLISH) "Vimos un dron." else "We saw a drone.", if (dir == InterpretDirection.TO_ENGLISH) lang else "en")
            val target = if (dir == InterpretDirection.TO_ENGLISH) "en" else lang
            val o = json.decodeFromString(GradeInterpretation().serializer, out)
            assertEquals(emptyList(), GradeInterpretation().validate(GradeInterpretation.Input(chunk, target, "x"), o, ValidationContext()), lang)
        }
        val bad = json.decodeFromString(GradeInterpretation().serializer, fixtures[0].third.replace("\"accuracy\":4", "\"accuracy\":9"))
        assertTrue(GradeInterpretation().validate(GradeInterpretation.Input(Chunk("c", "x", "es"), "en", "y"), bad, ValidationContext()).isNotEmpty())
        assertEquals(80, json.decodeFromString(GradeInterpretation().serializer, fixtures[0].third).score) // (8+3+5)/20
    }

    @Test
    fun scriptedSessionsCompleteInTwoLanguages() = runTest {
        fixtures.forEach { (lang, dir, out) ->
            val chunks = if (lang == "es") Chunker.chunks(lines, "es", dir, "es") else listOf(Chunk("ja-1", "We saw a drone at six.", "en", "六時にドローンを見ました。"))
            val model = FakeModel(*Array(chunks.size) { out })
            val s = InterpretSession(lang, dir, InterpretVariant.CONSECUTIVE, chunks, AiGateway({ model }), gradeAtEnd = true)
            while (!s.finished) s.render(if (dir == InterpretDirection.TO_ENGLISH) "We saw a drone." else "ドローンを見ました。")
            assertTrue(model.requests.isEmpty(), "After action: nothing graded during the session")
            val results = s.finish()
            assertEquals(chunks.size, results.size)
            assertTrue(results.all { it.grade != null }, lang)
            assertEquals(results.first().grade!!.score, s.meanScore())
        }
    }

    @Test
    fun liveGradingWhenAsked() = runTest {
        val s = InterpretSession("es", InterpretDirection.TO_ENGLISH, InterpretVariant.RADIO_RELAY, listOf(Chunk("1", "Vimos un dron.", "es")),
            AiGateway({ FakeModel(fixtures[0].third) }), gradeAtEnd = false)
        val r = s.render("We saw a drone.")
        assertEquals(4, r!!.grade!!.accuracy)
    }
}
