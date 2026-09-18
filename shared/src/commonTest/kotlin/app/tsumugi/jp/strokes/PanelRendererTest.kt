package app.tsumugi.jp.strokes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelRendererTest {

    private val paths = KanjiVgSamples.paths.getValue("三")

    @Test
    fun panelShowsDoneCurrentAndStart() {
        val svg = PanelRenderer.panel(paths, 1)
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"))
        assertTrue("viewBox=\"0 0 109 109\"" in svg)
        assertEquals(1, Regex("stroke=\"#b0b0b0\"").findAll(svg).count(), "one completed stroke")
        assertEquals(1, Regex("stroke=\"#000000\"").findAll(svg).count(), "one current stroke")
        assertEquals(1, Regex("<circle").findAll(svg).count(), "one start dot")
    }

    @Test
    fun stripHasOnePanelPerStroke() {
        val svg = PanelRenderer.strip(paths)
        assertTrue("width=\"${109 * paths.size}\"" in svg)
        assertEquals(paths.size, Regex("<circle").findAll(svg).count())
        assertEquals(paths.size, Regex("translate\\(").findAll(svg).count())
    }
}
