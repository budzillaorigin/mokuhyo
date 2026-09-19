package app.tsumugi.decks

import app.tsumugi.coverage.CoverageService
import app.tsumugi.coverage.KnownWords
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.TextProfileStore
import app.tsumugi.coverage.TextProfiler
import app.tsumugi.coverage.WordState
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.Freq_word
import app.tsumugi.reader.ImportedText
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.reader.ReaderRepository
import app.tsumugi.reader.SourceKind
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathStatus
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.LessonState
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.1 media decks, Core frequency decks and deck lessons (D-150, D-152, D-154). */
class MediaDeckTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val settings = SettingsRepository(db, clock)
    private val dictDb = DictionaryFixture.create()
    private val dictionary = DictionaryRepository(dictDb)
    private val profiler = TextProfiler({ ReaderAnalyzer(dictionary, { emptyMap() }) }, { dictionary.wordStats(it) })
    private val knowledge = LearnerKnowledge(db, srs)
    private val repo = ReaderRepository(db, clock)
    private val collection = CollectionService(db, srs, { null }, clock)
    private val knownWords = KnownWords(db, knowledge, { dictionary }, clock)
    private val coverage = CoverageService(knowledge, profiler, TextProfileStore(db, clock), { repo.documents() }, { repo.document(it) })
    private val decks = MediaDeckService(db, knowledge, profiler, coverage, { dictionary }, { repo.document(it) }, { error("no files") }, clock)
    private val lessons = DeckLessons(db, settings, knowledge, { dictionary }) { entry, context -> collection.addToReviews(entry, context) }

    private val body = "私は学校に行きました。猫が食べた！\n寿司を食べる。猫は寿司を食べる。"

    @Test
    fun documentBecomesAVocabularyListInStudyOrder() = runTest {
        val id = repo.save(ImportedText("Story", body, SourceKind.PASTE))
        val vocab = assertNotNull(decks.vocabularyForDocument(id))
        // Frequency in the media × global frequency; particles (は, に) are left out.
        assertEquals(DictionaryFixture.TABERU, vocab.words.first().entryId)
        assertEquals(setOf(DictionaryFixture.TABERU, DictionaryFixture.NEKO, DictionaryFixture.SUSHI, DictionaryFixture.IKU, DictionaryFixture.WATASHI, DictionaryFixture.GAKKOU), vocab.words.map { it.entryId }.toSet())
        assertTrue(vocab.words.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals("食", vocab.kanji.first().kanji)
        assertEquals(3, vocab.kanji.first().count)
        assertEquals(6, vocab.stats.uniqueWords)
        assertEquals(10, vocab.stats.wordTokens)
        assertEquals(listOf(4, 5, 6, 6), listOf(vocab.stats.words80, vocab.stats.words90, vocab.stats.words95, vocab.stats.words98))
        assertEquals(5, vocab.stats.jlpt)
        assertEquals(DeckSourceKind.DOCUMENT, vocab.kind)

        val saved = decks.save(vocab)
        assertEquals(6, saved.wordCount)
        assertEquals(0, saved.coverage.knownTokens)
        assertEquals(1.0, saved.libraryCoverage, "the deck covers its own document, the only profiled media")

        knownWords.markKnown(listOf(DictionaryFixture.TABERU))
        val detail = assertNotNull(decks.deck(saved.id))
        assertEquals(3, detail.summary.coverage.knownTokens, "coverage is recomputed from the stored words")
        assertEquals(WordState.KNOWN, detail.words.first().state)
        assertEquals("私は学校に行きました。", detail.words.single { it.entryId == DictionaryFixture.IKU }.context)

        // Re-creating the deck of the same document replaces it.
        val again = decks.save(assertNotNull(decks.vocabularyForDocument(id)))
        assertEquals(listOf(again.id), decks.decks().map { it.id })
        decks.delete(again.id)
        assertTrue(decks.decks().isEmpty())
        assertNull(decks.deck(again.id))
    }

    @Test
    fun subtitlesAndTextBuildDecksToo() = runTest {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n猫は寿司を食べる。\n\n2\n00:00:03,000 --> 00:00:04,000\n寿司を食べる。\n"
        val vocab = assertNotNull(decks.vocabularyForSubtitles("Episode 1", srt, "hash-1"))
        assertEquals(DeckSourceKind.SUBTITLES, vocab.kind)
        // 食べる ×2, 寿司 ×2, 猫 ×1 (食べる is the more common of the two with two occurrences).
        assertEquals(listOf(DictionaryFixture.TABERU, DictionaryFixture.SUSHI, DictionaryFixture.NEKO), vocab.words.map { it.entryId })
        assertEquals("hash-1", vocab.sourceRef)
        assertNotNull(coverage.profiles.get(CoverageService.mediaProfileKey("hash-1")), "the media's profile feeds library coverage")
        val text = assertNotNull(decks.vocabularyForText("Note", "猫が好き。"))
        assertEquals(listOf(DictionaryFixture.NEKO), text.words.map { it.entryId })
    }

    @Test
    fun coreDecksComeFromTheFrequencyList() = runTest {
        assertTrue(decks.frequencyDecks().isEmpty(), "a pack without the list shows an honest empty state")
        val order = listOf(DictionaryFixture.WATASHI, DictionaryFixture.IKU, DictionaryFixture.TABERU, DictionaryFixture.NEKO)
        order.forEachIndexed { i, id -> dictDb.dictionaryQueries.insertFreqWord(Freq_word(i + 1L, id, 50L - i)) }
        knownWords.markKnown(listOf(DictionaryFixture.WATASHI))
        repo.save(ImportedText("Story", body, SourceKind.PASTE)).also { coverage.documentCoverage(it) }

        val core = decks.frequencyDecks()
        assertEquals(listOf(CoreDeck.CORE_2K, CoreDeck.CORE_6K, CoreDeck.CORE_10K), core.map { it.deck })
        assertEquals(4, core.first().size)
        assertEquals(1, core.first().known)
        // 私 1 + 行く 1 + 食べる 3 + 猫 2 of 10 word occurrences in the learner's library.
        assertEquals(0.7, core.first().libraryCoverage, 1e-9)
        val words = decks.frequencyDeckWords("core2k", offset = 1, limit = 2)
        assertEquals(listOf("行く", "食べる"), words.map { it.text })
    }

    @Test
    fun deckLessonsInterleaveWithThePathAndSkipKnownWords() = runTest {
        val id = repo.save(ImportedText("Story", body, SourceKind.PASTE))
        val deck = decks.save(assertNotNull(decks.vocabularyForDocument(id)))
        assertNull(lessons.startSession(null, 4), "no active deck: plain path lessons")

        knownWords.markKnown(listOf(DictionaryFixture.TABERU))
        lessons.activate(deck.id)
        assertEquals(DeckLessonSettings(deck.id, DeckLessonMode.INTERLEAVE), lessons.settings())
        assertEquals(5, lessons.available(), "食べる is known")
        val status = PathStatus(3, 60, 0.5, availableLessons = 10, dueReviews = 0, stageCounts = emptyMap())
        assertEquals(15, lessons.adjust(status)!!.availableLessons)

        val session = assertNotNull(lessons.startSession(null, 2, Random(1)))
        val presenting = assertIs<LessonState.Presenting>(session.state.value)
        assertEquals(listOf("jmdict:${DictionaryFixture.NEKO}", "jmdict:${DictionaryFixture.SUSHI}").toSet(), presenting.items.map { it.id }.toSet())
        // Answer every quiz question correctly.
        session.startQuiz()
        while (true) {
            val q = session.state.value as? LessonState.Quizzing ?: break
            session.submit(q.question.expected.first())
            session.next()
        }
        assertIs<LessonState.Complete>(session.state.value)
        assertEquals(WordState.LEARNING, knowledge.snapshot().word(DictionaryFixture.NEKO))
        val sushi = srs.item("jmdict:${DictionaryFixture.SUSHI}")!!
        assertTrue(sushi.context!!.contains("寿司"), "the media sentence travels with the word")
        assertEquals(3, lessons.available())

        lessons.setMode(DeckLessonMode.DECK_ONLY)
        assertEquals(3, lessons.adjust(status)!!.availableLessons)
        lessons.deactivate()
        assertEquals(10, lessons.adjust(status)!!.availableLessons)
    }

    @Test
    fun interleaveAlternatesThenFills() {
        assertEquals(listOf("p1", "d1", "p2", "d2", "d3"), DeckLessons.interleave(listOf("p1", "p2"), listOf("d1", "d2", "d3", "d4"), 5))
        assertEquals(listOf("p1", "p2", "p3"), DeckLessons.interleave(listOf("p1", "p2", "p3"), emptyList(), 5))
    }
}
