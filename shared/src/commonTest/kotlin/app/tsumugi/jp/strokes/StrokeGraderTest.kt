package app.tsumugi.jp.strokes

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrokeGraderTest {

    private val kanji = KanjiVgSamples.paths.keys.toList()

    @Test
    fun exactTemplateStrokesAreAccepted() {
        for (k in kanji) {
            val t = KanjiVgSamples.strokes(k)
            val s = WritingSession(t)
            t.forEach { stroke -> assertTrue(s.submit(stroke).accepted, "$k stroke ${s.expectedIndex + 1}") }
            assertTrue(s.done)
            assertEquals(4, s.suggestedRating)
        }
    }

    @Test
    fun jitteredScaledTranslatedStrokesAreAccepted() {
        val random = Random(7)
        var accepted = 0
        var total = 0
        for (k in kanji) {
            val t = KanjiVgSamples.strokes(k)
            val drawn = Distort.character(t, random, scale = 0.94f, dx = 3f, dy = -2f, noise = 2.5f)
            val s = WritingSession(t)
            drawn.forEach { stroke ->
                total++
                if (s.submit(stroke).accepted) accepted++ else s.skip()
            }
        }
        assertTrue(accepted >= total * 0.95, "accepted $accepted of $total")
    }

    @Test
    fun reversedStrokeIsWrongDirection() {
        val t = KanjiVgSamples.strokes("木")
        val s = WritingSession(t)
        val r = s.submit(t[0].reversed())
        assertFalse(r.accepted)
        assertEquals(StrokeProblem.WRONG_DIRECTION, r.problem)
        assertEquals(1, s.failuresOnCurrent)
    }

    @Test
    fun swappedStrokesAreWrongOrder() {
        val t = KanjiVgSamples.strokes("三")
        val s = WritingSession(t)
        val r = s.submit(t[2])
        assertFalse(r.accepted)
        assertEquals(StrokeProblem.WRONG_ORDER, r.problem)

        val m = WritingSession(KanjiVgSamples.strokes("十"))
        assertEquals(StrokeProblem.WRONG_ORDER, m.submit(KanjiVgSamples.strokes("十")[1]).problem)
    }

    @Test
    fun wrongShapeAndPositionAndShortStrokes() {
        val t = KanjiVgSamples.strokes("口")
        val s = WritingSession(t)
        // A diagonal slash is nothing like 口's first (vertical) stroke.
        assertEquals(StrokeProblem.WRONG_SHAPE, s.submit(listOf(Point(20f, 90f), Point(90f, 20f))).problem)
        // The right shape shifted far away.
        val moved = t[0].map { Point(it.x + 45f, it.y) }
        assertEquals(StrokeProblem.WRONG_POSITION, s.submit(moved).problem)
        assertEquals(StrokeProblem.TOO_SHORT, s.submit(listOf(t[0].first(), t[0].first())).problem)
        assertEquals(3, s.failuresOnCurrent)
        assertEquals(t[0], s.hint())
    }

    @Test
    fun rawCheckerOnFreeWriting() {
        val random = Random(3)
        val t = KanjiVgSamples.strokes("本")
        // Written small and off-centre, as people do without a template.
        val free = Distort.character(t, random, scale = 0.6f, dx = -15f, dy = 10f, noise = 1.5f)
        val good = RawWritingChecker.check(t, free)
        assertTrue(good.countOk && good.orderOk, good.toString())
        assertTrue(good.suggestedRating >= 3, good.toString())

        val swapped = free.toMutableList().also { val a = it[0]; it[0] = it[1]; it[1] = a }
        assertFalse(RawWritingChecker.check(t, swapped).orderOk)

        val missing = RawWritingChecker.check(t, free.dropLast(1))
        assertFalse(missing.countOk)
        assertTrue(missing.suggestedRating <= 2)
    }

    @Test
    fun geometryBasics() {
        val line = listOf(Point(0f, 0f), Point(10f, 0f))
        val r = StrokeGeometry.resample(line, 11)
        assertEquals(11, r.size)
        assertEquals(Point(5f, 0f), r[5])
        assertEquals(0f, StrokeGeometry.dtw(r, r))
        val h = StrokeGeometry.directionHistogram(r)
        assertTrue(h[0] > 0.99f, "rightward stroke fills the 0° bin")
    }
}
