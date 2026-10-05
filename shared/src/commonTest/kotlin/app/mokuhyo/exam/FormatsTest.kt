package app.mokuhyo.exam

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** BRIEF_PHASE8 N-08: every format parses from its fixture and yields the plain text `body` must hold. */
class FormatsTest {
    companion object {
        val FIXTURES = mapOf(
            Formats.SIGNAGE to """{"lines":["ZONA RESTRINGIDA","Prohibido volar drones","Base Aérea Militar"],"kind":"prohibition"}""",
            Formats.BADGE_FORM to """{"title":"Pase de visitante","fields":[{"label":"Nombre","value":"Luis Pérez"},{"label":"Escolta","value":"Sgto. Ríos"}],"footer":"Devolver al salir"}""",
            Formats.CHAT to """{"app":"whatsapp","title":"Turno noche","messages":[{"from":"Ana","text":"¿Viste el dron?","time":"22:10"},{"from":"Yo","text":"Sí, al norte.","time":"22:11","me":true}]}""",
            Formats.SHIFT_LOG to """{"unit":"Puesto 3","date":"03/10/2026","entries":[{"time":"0215","entry":"Dron avistado sobre la valla norte","initials":"JR"}]}""",
            Formats.MUNICIPAL_NOTICE to """{"issuer":"Ayuntamiento","title":"Aviso: zona sin drones","paragraphs":["Durante el festival no se permiten drones."],"date":"1 de octubre","contact":"Tel. 555"}""",
            Formats.SCHEDULE_BOARD to """{"title":"Relevos","columns":["Hora","Puesto","Unidad"],"rows":[["06:00","Puerta 1","Escuadrón A"],["14:00","Puerta 2","Escuadrón B"]]}""",
        )
    }

    @Test
    fun everyFormatParsesAndHasText() {
        assertEquals(Formats.ALL.toSet(), FIXTURES.keys)
        FIXTURES.forEach { (f, j) ->
            val p = Formats.parse(f, Json.parseToJsonElement(j))
            requireNotNull(p) { f }
            assert(Formats.text(p).isNotBlank()) { f }
        }
        assertIs<Formats.ChatP>(Formats.parse(Formats.CHAT, Json.parseToJsonElement(FIXTURES.getValue(Formats.CHAT))))
        assertEquals("Ana: ¿Viste el dron?\nYo: Sí, al norte.", Formats.text(Formats.parse(Formats.CHAT, Json.parseToJsonElement(FIXTURES.getValue(Formats.CHAT)))!!))
        assertNull(Formats.parse(Formats.CHAT, Json.parseToJsonElement("""{"nope":1}""")))
        assertNull(Formats.parse("poster", Json.parseToJsonElement("{}")))
    }
}
