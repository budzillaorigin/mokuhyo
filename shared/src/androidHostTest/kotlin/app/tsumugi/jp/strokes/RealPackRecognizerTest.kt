package app.tsumugi.jp.strokes

import kotlinx.coroutines.test.runTest
import java.io.File
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Handwriting recognition over every KanjiVG kanji in the real dictionary pack, when it has been built
 * (tools/packs/build_all.py); skipped otherwise. Inputs are template strokes with synthetic wobble, size and
 * position changes, so this measures robustness of the matcher, not real handwriting.
 */
class RealPackRecognizerTest {

    private val pack = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/packs/dictionary.sqlite") }
        .firstOrNull { it.exists() }

    private fun loadAll(file: File): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().executeQuery("SELECT kanji, path FROM stroke ORDER BY kanji, ord").use { rs ->
                while (rs.next()) out.getOrPut(rs.getString(1)) { ArrayList() } += rs.getString(2)
            }
        }
        return out
    }

    @Test
    fun accuracyAndSpeed() = runTest {
        val file = pack ?: return@runTest println("RealPackRecognizerTest skipped: no built pack")
        val all = loadAll(file)
        val recognizer = HandwritingRecognizer(SvgTemplateSource { all })
        val warm = measureTime { recognizer.warmUp() }

        val random = Random(42)
        val sample = all.keys.shuffled(random).take(300)
        var top1 = 0
        var top10 = 0
        val elapsed = measureTime {
            for (k in sample) {
                val strokes = all.getValue(k).map { SvgPath.flatten(it) }
                val drawn = Distort.character(strokes, random, scale = 0.8f + random.nextFloat() * 0.4f, dx = random.nextFloat() * 10 - 5, dy = random.nextFloat() * 10 - 5, noise = 2.5f)
                val result = recognizer.recognize(drawn, 10)
                if (result.firstOrNull()?.kanji == k) top1++
                if (result.any { it.kanji == k }) top10++
            }
        }
        val perQuery = elapsed / sample.size
        println("RealPackRecognizerTest: ${all.size} templates (load ${warm.inWholeMilliseconds} ms); top-1 $top1/300, top-10 $top10/300; ${perQuery.inWholeMilliseconds} ms per query")
        assertTrue(top10 >= 270, "top-10 accuracy $top10/300")
        assertTrue(perQuery.inWholeMilliseconds < 150, "query took $perQuery")
    }
}
