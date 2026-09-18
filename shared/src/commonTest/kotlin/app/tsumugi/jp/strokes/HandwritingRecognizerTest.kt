package app.tsumugi.jp.strokes

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandwritingRecognizerTest {

    private val source = SvgTemplateSource { KanjiVgSamples.paths }
    private val recognizer = HandwritingRecognizer(source)

    @Test
    fun cleanInputIsTopOne() = runTest {
        for (k in KanjiVgSamples.paths.keys) {
            val result = recognizer.recognize(KanjiVgSamples.strokes(k))
            assertEquals(k, result.first().kanji, "for $k got ${result.take(3)}")
        }
    }

    @Test
    fun jitteredInputIsInTopThree() = runTest {
        val random = Random(11)
        var hits = 0
        val keys = KanjiVgSamples.paths.keys
        for (k in keys) {
            // Different size and position on a differently sized canvas, with wobble.
            val drawn = Distort.character(KanjiVgSamples.strokes(k), random, scale = 1.8f, dx = 40f, dy = 25f, noise = 4f)
            if (recognizer.recognize(drawn, 3).any { it.kanji == k }) hits++
        }
        assertEquals(keys.size, hits)
    }

    @Test
    fun wrongStrokeOrderStillRecognized() = runTest {
        val t = KanjiVgSamples.strokes("田")
        val shuffled = listOf(t[3], t[0], t[4], t[1], t[2])
        assertTrue(recognizer.recognize(shuffled, 3).any { it.kanji == "田" })
    }

    @Test
    fun scoresAreOrderedAndBounded() = runTest {
        val result = recognizer.recognize(KanjiVgSamples.strokes("日"), 5)
        assertTrue(result.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(result.all { it.score in 0.0..1.0 })
        assertTrue(recognizer.recognize(emptyList()).isEmpty())
    }
}
