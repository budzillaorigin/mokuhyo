package app.mokuhyo.opi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExemplarsTest {
    private fun ex(q: String, prompt: String, lv: String) = Exemplar("$q-$lv", q, prompt, "", lv, "respuesta $lv", "Two sentences. Here.")

    private val pack = ExemplarPack(lang = "es", exemplars = listOf(
        ex("q1", "¿Qué hizo el fin de semana pasado?", "3"), ex("q1", "¿Qué hizo el fin de semana pasado?", "1+"), ex("q1", "¿Qué hizo el fin de semana pasado?", "2"),
        ex("q2", "¿Qué opina de los drones en las ciudades?", "1+"), ex("q2", "¿Qué opina de los drones en las ciudades?", "2"), ex("q2", "¿Qué opina de los drones en las ciudades?", "3"),
    ))

    @Test
    fun matchesExactThenSimilarThenNothing() {
        assertEquals(listOf("1+", "2", "3"), pack.forQuestion("¿Qué hizo el fin de semana pasado?").map { it.level })
        assertEquals("q2", pack.forQuestion("¿Qué opina usted de los drones en las ciudades grandes?").first().questionId)
        assertTrue(pack.forQuestion("お名前は？").isEmpty())
        assertEquals(2, pack.byQuestion().size)
    }
}
