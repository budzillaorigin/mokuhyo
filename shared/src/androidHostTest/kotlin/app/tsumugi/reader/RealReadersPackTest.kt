package app.tsumugi.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.coverage.DifficultyScorer
import app.tsumugi.coverage.TextProfiler
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.jp.tokenizer.LatticeTokenizer
import app.tsumugi.readers.db.ReadersDatabase
import app.tsumugi.tokenizer.db.TokenizerDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built readers pack (tools/packs/readers/build_readers.py) against the app's own code, with the real dictionary
 * and tokenizer packs. Skipped when the packs haven't been built.
 * - Read-along lines are exactly the reader's sentences (ReaderAnalyzer.sentences, trimmed), so the highlight and
 *   the audio clips line up with what the reader shows.
 * - The build's text score (the Python reimplementation of the §6.4 formula) agrees with [DifficultyScorer].
 */
class RealReadersPackTest {
    private fun find(path: String) = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.exists() }

    private fun readOnly(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("open_mode", "1") })

    @Test
    fun packMatchesTheReaderAndTheDifficultyScore() = runTest {
        val readersPack = find("content/packs/readers.sqlite")
        val dictPack = find("content/packs/dictionary.sqlite")
        val tokPack = find("content/packs/tokenizer.sqlite")
        if (readersPack == null || dictPack == null || tokPack == null) return@runTest println("RealReadersPackTest skipped: packs not built")
        val repo = PackReaderRepository { ReadersDatabase(readOnly(readersPack)) }
        val dictionary = DictionaryRepository(DictionaryDatabase(readOnly(dictPack)))
        val analyzer = ReaderAnalyzer(LatticeTokenizer(TokenizerDatabase(readOnly(tokPack))), dictionary, { emptyMap() })
        val profiler = TextProfiler({ analyzer }, { dictionary.wordStats(it) })

        val levels = repo.levels()
        println("levels: " + levels.joinToString { "${it.level} ${it.storyCount}" })
        val stories = repo.passages()
        assertTrue(stories.isNotEmpty())
        var close = 0
        val diffs = ArrayList<Int>()
        val byLevel = HashMap<String, MutableList<Int>>()
        for (summary in stories) {
            val story = repo.story(summary.id)!!
            val expected = analyzer.paragraphs(story.body).flatMap { analyzer.sentences(story.body, it) }.map { r ->
                val raw = story.body.substring(r.first, r.last + 1)
                val start = r.first + (raw.length - raw.trimStart().length)
                start to start + raw.trim().length
            }
            assertEquals(expected, story.lines.map { it.start to it.end }, "${story.id}: read-along lines = reader sentences")
            assertTrue(story.questions.isNotEmpty() && story.questions.all { it.answer in it.choices.indices }, story.id)
            assertTrue(story.tasks.prediction != null && story.tasks.skim != null && story.tasks.output != null, "${story.id} tasks")

            val score = DifficultyScorer.score(profiler.profile(story.body)!!)
            val diff = score.textScore - story.textScore
            diffs += diff
            byLevel.getOrPut(story.level) { ArrayList() } += score.textScore
            if (abs(diff) <= 3) close++ else println("${story.id}: app ${score.textScore} vs build ${story.textScore}")
        }
        for (l in levels) byLevel[l.level]?.let { println("${l.level}: app text score ${it.sorted()} mean ${"%.1f".format(it.average())}") }
        println("text score within 3 of the build: $close / ${stories.size}; mean diff ${"%.2f".format(diffs.average())}")
        assertTrue(close >= stories.size * 0.9, "the build's score reimplementation drifted from DifficultyScorer: $close / ${stories.size}")
        val means = levels.mapNotNull { l -> byLevel[l.level]?.average() }
        assertTrue(means.zipWithNext().count { (a, b) -> b >= a } >= means.size - 2, "level means mostly rise: $means")
    }
}
