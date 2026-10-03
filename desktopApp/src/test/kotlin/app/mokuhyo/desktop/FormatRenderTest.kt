package app.mokuhyo.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.exam.FormattedPassage
import app.mokuhyo.exam.Formats
import app.mokuhyo.lang.LanguageRegistry
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-08 gate: each format's renderer draws (snapshot render) in a left-to-right and a right-to-left language. */
class FormatRenderTest {
    private val fixtures = mapOf(
        Formats.SIGNAGE to """{"lines":["ZONA RESTRINGIDA","Prohibido volar drones"],"kind":"prohibition"}""",
        Formats.BADGE_FORM to """{"title":"Pase","fields":[{"label":"Nombre","value":"Luis"}],"footer":"Devolver"}""",
        Formats.CHAT to """{"app":"line","messages":[{"from":"Ana","text":"¿Dron?"},{"from":"Yo","text":"Sí.","me":true}]}""",
        Formats.SHIFT_LOG to """{"unit":"Puesto 3","date":"03/10","entries":[{"time":"0215","entry":"Dron avistado","initials":"JR"}]}""",
        Formats.MUNICIPAL_NOTICE to """{"issuer":"Ayuntamiento","title":"Aviso","paragraphs":["No se permiten drones."]}""",
        Formats.SCHEDULE_BOARD to """{"title":"Relevos","columns":["Hora","Puesto"],"rows":[["06:00","Puerta 1"]]}""",
    )

    @Test
    fun rendersEveryFormatLtrAndRtl() {
        System.setProperty("java.awt.headless", "true")
        val reg = LanguageRegistry(null)
        listOf("es", "ar").forEach { lang ->
            val module = reg.module(lang)
            fixtures.forEach { (f, j) ->
                var drawn = false
                val scene = ImageComposeScene(800, 600, Density(1f)) { MokuhyoTheme { Column { drawn = FormattedPassage(module, f, Json.parseToJsonElement(j)) } } }
                val img = scene.render(0)
                scene.close()
                assertTrue(drawn && img.width == 800, "$lang $f")
            }
        }
    }
}
