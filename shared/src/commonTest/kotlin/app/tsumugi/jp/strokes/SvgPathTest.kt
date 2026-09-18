package app.tsumugi.jp.strokes

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgPathTest {

    private fun assertNear(expected: Point, actual: Point, tolerance: Float = 0.01f) {
        assertTrue(abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance, "$expected vs $actual")
    }

    @Test
    fun absoluteLines() {
        val pts = SvgPath.flatten("M10,10 L20,10 L20,30")
        assertEquals(listOf(Point(10f, 10f), Point(20f, 10f), Point(20f, 30f)), pts)
    }

    @Test
    fun relativeLinesAndImplicitLineTo() {
        val pts = SvgPath.flatten("m10 10 10 0 0 20")
        assertEquals(listOf(Point(10f, 10f), Point(20f, 10f), Point(20f, 30f)), pts)
    }

    @Test
    fun horizontalVerticalAndClose() {
        val pts = SvgPath.flatten("M0,0H10V10h-10z")
        assertEquals(listOf(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f), Point(0f, 0f)), pts)
    }

    @Test
    fun cubicEndsAtEndpointAndStaysInHull() {
        val pts = SvgPath.flatten("M0,0C0,10,10,10,10,0")
        assertNear(Point(10f, 0f), pts.last())
        assertTrue(pts.all { it.x in -0.01f..10.01f && it.y in -0.01f..7.6f })
        assertTrue(pts.size > 5, "curve should be subdivided")
    }

    @Test
    fun relativeCubicAndSmoothReflection() {
        // KanjiVG-style: relative c followed by s, numbers packed with minus signs as separators.
        val pts = SvgPath.flatten("M10,10c0,5-5,10-10,10s-10-5-10-10")
        assertNear(Point(0f, 20f), pts.first { abs(it.x) < 0.01f && abs(it.y - 20f) < 0.01f })
        assertNear(Point(-10f, 10f), pts.last())
    }

    @Test
    fun parsesRealKanjiVgStroke() {
        // 一 (U+4E00), KanjiVG stroke 1.
        val pts = SvgPath.flatten("M11,54.25c3.19,0.62,6.25,0.75,9.73,0.5c20.64-1.5,50.39-5.12,68.58-5.24c3.6-0.02,5.77,0.24,7.57,0.49")
        assertNear(Point(11f, 54.25f), pts.first())
        assertNear(Point(96.88f, 50f), pts.last(), 0.02f)
    }

    @Test
    fun exponentsAndDecimalsWithoutLeadingZero() {
        val pts = SvgPath.flatten("M.5,1e1L-.5,.25")
        assertEquals(listOf(Point(0.5f, 10f), Point(-0.5f, 0.25f)), pts)
    }
}
