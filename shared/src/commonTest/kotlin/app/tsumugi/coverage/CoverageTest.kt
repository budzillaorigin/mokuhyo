package app.tsumugi.coverage

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.Freq_word
import app.tsumugi.domain.Stage
import app.tsumugi.reader.ImportedText
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.reader.ReaderRepository
import app.tsumugi.reader.SourceKind
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.ImmersionCandidate
import app.tsumugi.study.ImmersionSource
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.1 coverage overlay, §6.4 difficulty, §6.11 1T sentences and known words (D-150…D-155). */
class CoverageTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val dictDb = DictionaryFixture.create()
    private val dictionary = DictionaryRepository(dictDb)
    private val readerAnalyzer = ReaderAnalyzer(dictionary, { emptyMap() }, { listOf("n5-masu" to Regex("ま(す|した|せん)")) })
    private val profiler = TextProfiler({ readerAnalyzer }, { dictionary.wordStats(it) })
    private val knowledge = LearnerKnowledge(db, srs)
    private val knownWords = KnownWords(db, knowledge, { dictionary }, clock)
    private val repo = ReaderRepository(db, clock)
    private val collection = CollectionService(db, srs, { null }, clock)
    private val coverage = CoverageService(
        knowledge, profiler, TextProfileStore(db, clock), { repo.documents() }, { repo.document(it) },
        mine = { id, sentence -> dictionary.entry(id)?.entry?.let { collection.addToReviews(it, sentence) } },
    )

    // 私 は 学校 に 行きました 。 猫 が 食べた ！ / 寿司 を 食べる 。 猫 は 寿司 を 食べる 。
    private val body = "私は学校に行きました。猫が食べた！\n寿司を食べる。猫は寿司を食べる。"

    @Test
    fun profileCountsContentWordsAndLeavesFunctionWordsOut() = runTest {
        val profile = assertNotNull(profiler.profile(body))
        val counts = profile.words.associate { it.entryId to it.count }
        assertEquals(3, counts[DictionaryFixture.TABERU])
        assertEquals(2, counts[DictionaryFixture.NEKO])
        assertEquals(2, counts[DictionaryFixture.SUSHI])
        assertEquals(1, counts[DictionaryFixture.IKU])
        // は and に are particles: recorded, but never counted as words to know.
        assertTrue(profile.words.single { it.entryId == DictionaryFixture.WA }.function)
        assertTrue(profile.words.single { it.entryId == DictionaryFixture.NI }.function)
        assertEquals(10, profile.wordTokens)
        assertEquals(6, profile.uniqueWords)
        assertEquals(3, profile.kanji["食"])
        assertEquals(mapOf("n5-masu" to 1), profile.grammar)
        assertEquals(4, profile.sentences)
        assertEquals("行く", profile.words.single { it.entryId == DictionaryFixture.IKU }.lemma)
        assertEquals("私は学校に行きました。", profile.words.single { it.entryId == DictionaryFixture.IKU }.context)
        // Round-trips through the device-local store.
        assertEquals(profile, TextProfile.decode(profile.encode()))
    }

    @Test
    fun snapshotMatchesByEntryIdAndTextAndDerivesKanji() {
        val stages = mapOf(
            "jmdict:${DictionaryFixture.TABERU}" to Stage.GURU,
            "v:${DictionaryFixture.NEKO}" to Stage.APPRENTICE,
            "wk:9" to Stage.MASTER, // WaniKani-only word, matched by text
            "anki:1" to Stage.GURU, // an Anki note whose primary text is a word
            "k:寿" to Stage.GURU,
        )
        val items = listOf(
            Triple("jmdict:${DictionaryFixture.TABERU}", "VOCAB", "食べる"),
            Triple("v:${DictionaryFixture.NEKO}", "VOCAB", "猫"),
            Triple("wk:9", "VOCAB", "学校"),
            Triple("anki:1", "CUSTOM", "行く"),
            Triple("k:寿", "KANJI", "寿"),
        )
        val snap = KnowledgeSnapshot.build(stages, items, listOf(DictionaryFixture.WATASHI to "私"), "v1")
        assertEquals(WordState.KNOWN, snap.word(DictionaryFixture.TABERU))
        assertEquals(WordState.LEARNING, snap.word(DictionaryFixture.NEKO))
        assertEquals(WordState.KNOWN, snap.word(DictionaryFixture.GAKKOU, "学校"))
        assertEquals(WordState.KNOWN, snap.word(DictionaryFixture.IKU, "行く"))
        assertEquals(WordState.KNOWN, snap.word(DictionaryFixture.WATASHI))
        assertEquals(WordState.UNKNOWN, snap.word(DictionaryFixture.SUSHI, "寿司"))
        assertEquals(WordState.KNOWN, snap.kanji("寿"), "kanji item at Guru")
        assertEquals(WordState.KNOWN, snap.kanji("食"), "kanji of a known word")
        assertEquals(WordState.LEARNING, snap.kanji("猫"))
        assertEquals(WordState.UNKNOWN, snap.kanji("司"))
    }

    @Test
    fun coverageOverlayAndWordsToNinetyFive() = runTest {
        val profile = assertNotNull(profiler.profile(body))
        val snap = KnowledgeSnapshot.build(
            mapOf("jmdict:${DictionaryFixture.TABERU}" to Stage.GURU, "v:${DictionaryFixture.NEKO}" to Stage.APPRENTICE),
            listOf(Triple("jmdict:${DictionaryFixture.TABERU}", "VOCAB", "食べる"), Triple("v:${DictionaryFixture.NEKO}", "VOCAB", "猫")),
            listOf(DictionaryFixture.WATASHI to "私"), "v",
        )
        val c = CoverageMath.coverage(profile, snap)
        assertEquals(10, c.wordTokens)
        assertEquals(4, c.knownTokens) // 食べる ×3 + 私
        assertEquals(2, c.learningTokens) // 猫 ×2 (Apprentice = learning, not known)
        assertEquals(40, c.knownPercent)
        // 95% of 10 = 10 tokens: 猫(2) + 寿司(2) + 学校(1) + 行く(1) → 4 new words.
        assertEquals(4, c.newWordsTo95)
        // Kanji: 食×3 and 私 known (in known words) of 13 kanji occurrences.
        assertEquals(13, c.kanjiTokens)
        assertEquals(4, c.knownKanjiTokens)
        assertTrue(c.summary.startsWith("You know 40% of the words · 30% of the kanji · 4 new words"))

        assertEquals(CoverageThresholds(4, 5, 6, 6), CoverageMath.thresholds(profile))
        assertEquals(0, CoverageMath.wordsToReach(0.95, 0, 0, emptyList()))
        assertEquals(0, CoverageMath.wordsToReach(0.95, 20, 19, listOf(1)))
    }

    @Test
    fun reviewsAndMarksInvalidateTheCachedSnapshot() = runTest {
        val first = knowledge.snapshot()
        assertEquals(WordState.UNKNOWN, first.word(DictionaryFixture.TABERU))
        assertTrue(first === knowledge.snapshot(), "unchanged data reuses the snapshot")

        collection.addToReviews(dictionary.entry(DictionaryFixture.TABERU)!!.entry) // a lesson: Apprentice
        assertEquals(WordState.LEARNING, knowledge.snapshot().word(DictionaryFixture.TABERU))

        knownWords.markKnown(listOf(DictionaryFixture.SUSHI, DictionaryFixture.NEKO))
        assertEquals(WordState.KNOWN, knowledge.snapshot().word(DictionaryFixture.SUSHI))
        assertEquals(2, knownWords.count())
        clock.advance(kotlin.time.Duration.parse("1s"))
        knownWords.markUnknown(listOf(DictionaryFixture.NEKO))
        assertEquals(WordState.UNKNOWN, knowledge.snapshot().word(DictionaryFixture.NEKO))
        assertEquals("寿司", knownWords.rows().single { it.entryId == DictionaryFixture.SUSHI }.text)
        assertEquals(1, knownWords.count())
    }

    @Test
    fun documentCoverageIsCachedPerDocumentAndFollowsReviews() = runTest {
        val id = repo.save(ImportedText("Story", body, SourceKind.PASTE))
        val before = assertNotNull(coverage.documentCoverage(id))
        assertEquals(0, before.coverage.knownTokens)
        assertTrue(before.difficulty.score in 0..100)
        assertNotNull(coverage.profiles.get(CoverageService.docKey(id)), "profile stored")

        knownWords.markKnown(listOf(DictionaryFixture.TABERU, DictionaryFixture.NEKO, DictionaryFixture.SUSHI, DictionaryFixture.WATASHI))
        val after = assertNotNull(coverage.documentCoverage(id))
        assertEquals(8, after.coverage.knownTokens)
        assertTrue(after.difficulty.score < before.difficulty.score, "knowing words makes the text easier for the learner")
        assertEquals(before.difficulty.textScore, after.difficulty.textScore, "the text itself didn't change")

        val other = repo.save(ImportedText("Other", "猫は寿司を食べる。", SourceKind.PASTE))
        val unprofiled = repo.save(ImportedText("New", "私は学校に行きました。", SourceKind.PASTE))
        coverage.documentCoverage(other)
        val library = coverage.librarySortedByCoverage()
        assertEquals(listOf(other, id, unprofiled), library.map { it.document.id }, "best covered first, unprofiled last")
        assertNull(library.last().coverage)
        assertEquals(1, coverage.profileLibrary())
        assertTrue(coverage.librarySortedByCoverage().all { it.coverage != null })
    }

    @Test
    fun oneTargetSentencesHaveExactlyOneUnknownWord() = runTest {
        val id = repo.save(ImportedText("Story", body, SourceKind.PASTE))
        // Known: 私, 学校, 猫, 食べる. Unknown: 行く, 寿司.
        knownWords.markKnown(listOf(DictionaryFixture.WATASHI, DictionaryFixture.GAKKOU, DictionaryFixture.NEKO, DictionaryFixture.TABERU))
        val found = coverage.oneTargetSentences(id)
        assertEquals(setOf(DictionaryFixture.IKU, DictionaryFixture.SUSHI), found.map { it.entryId }.toSet())
        val sushi = found.single { it.entryId == DictionaryFixture.SUSHI }
        assertEquals(2, sushi.occurrences)
        assertEquals("寿司", sushi.text.substring(sushi.targetStart, sushi.targetEnd))
        assertTrue(found.first().entryId == DictionaryFixture.SUSHI, "the more frequent target ranks first")
        val iku = found.single { it.entryId == DictionaryFixture.IKU }
        assertEquals("行く", iku.targetLemma)
        assertEquals("行きました", iku.target)

        // Mining adds the target with its sentence; the sentence is then no longer 1T.
        val itemId = assertNotNull(coverage.mineOneTarget(sushi))
        assertEquals(sushi.text, srs.item(itemId)!!.context)
        assertEquals(listOf(DictionaryFixture.IKU), coverage.oneTargetSentences(id).map { it.entryId })
    }

    @Test
    fun oneTargetCuesCarryTheirTimes() = runTest {
        val srt = "1\n00:00:01,000 --> 00:00:03,000\n猫は寿司を\n食べる。\n\n2\n00:00:04,000 --> 00:00:06,000\n私は学校に行きました。\n"
        knownWords.markKnown(listOf(DictionaryFixture.NEKO, DictionaryFixture.TABERU, DictionaryFixture.WATASHI, DictionaryFixture.GAKKOU))
        val found = coverage.oneTargetCues(srt)
        val sushi = found.single { it.entryId == DictionaryFixture.SUSHI }
        assertEquals(0, sushi.cueIndex)
        assertEquals(1_000L, sushi.startMs)
        assertEquals("猫は寿司を食べる。", sushi.text)
        val iku = found.single { it.entryId == DictionaryFixture.IKU }
        assertEquals(1, iku.cueIndex)
        assertEquals(6_000L, iku.endMs)
        assertNotNull(coverage.subtitleCoverage("media-hash", srt)).also { assertEquals(66, it.coverage.knownPercent) }
    }

    @Test
    fun difficultyRisesWithVocabularySentenceLengthAndAbstractWords() {
        fun profile(words: List<ProfileWord>, avgLen: Int, kanjiDensity: Double, abstractHits: Int) = TextProfile(
            words = words, kanji = emptyMap(), grammar = emptyMap(), sentences = 10, chars = 1000, japaneseChars = 1000,
            kanjiChars = (kanjiDensity * 1000).toInt(), sentenceJapaneseChars = avgLen * 10, abstractHits = abstractHits, approxTokens = 400,
        )
        val easy = profile(listOf(ProfileWord(1, "a", count = 50, jlpt = 5), ProfileWord(2, "b", count = 10, jlpt = 4)), 14, 0.15, 0)
        val hard = profile(listOf(ProfileWord(1, "a", count = 20, jlpt = 2), ProfileWord(2, "b", count = 30, jlpt = null)), 60, 0.45, 30)
        val e = DifficultyScorer.score(easy)
        val h = DifficultyScorer.score(hard)
        assertTrue(e.textScore < 15, "easy text scores N5 (${e.textScore})")
        assertEquals(5, e.jlpt)
        assertEquals("0+", e.ilr)
        assertTrue(h.textScore >= 75, "hard text scores above N1 (${h.textScore})")
        assertEquals("above N1 · ILR 3", h.label)
        assertEquals(h.textScore, h.score, "no learner data: score is the text score")
        assertTrue(DifficultyScorer.score(hard, knownWordRatio = 1.0).score < h.score)
        assertTrue(DifficultyScorer.score(easy, knownWordRatio = 0.5).score > e.score)
    }

    @Test
    fun abstractVocabularyMatchesTheDlptGate() {
        // Longest first and non-overlapping, like tools/items/gen_dlpt.py: 価値観 is one hit, not 価値 + 価値観.
        val (hits, tokens) = AbstractVocabulary.measure("価値観の多様性について議論する。")
        assertEquals(3, hits)
        assertEquals(6, tokens) // runs of one script: 価値観|の|多様性|について|議論|する (。 is not a token)
    }

    @Test
    fun scoredImmersionDifficultyPrefersTextsAtTheLearnersLevel() {
        fun score(text: Int, known: Double? = null) = DifficultyScore(text, text, 5, "1", known, 0.0, 0.0, 0.0, 0.0)
        val matcher = ScoredImmersionDifficulty(mapOf("n5" to score(5), "n3" to score(25), "n1" to score(70)))
        fun c(id: String) = ImmersionCandidate(ImmersionSource.READER, id, id, null)
        val learnerN4 = 4
        val ranked = listOf("n5", "n3", "n1").sortedBy { matcher.mismatch(c(it), learnerN4) }
        assertEquals(listOf("n5", "n3", "n1"), ranked)
        assertTrue(matcher.mismatch(c("n3"), 3) < 0.3, "an N3 text for an N3 learner")
        // Unscored candidates use the simple JLPT distance.
        assertEquals(2.0, matcher.mismatch(c("unknown"), learnerN4))
    }

    @Test
    fun frequencyBatchesSkipKnownWords() = runTest {
        val order = listOf(DictionaryFixture.WATASHI, DictionaryFixture.IKU, DictionaryFixture.TABERU, DictionaryFixture.NEKO, DictionaryFixture.GAKKOU)
        order.forEachIndexed { i, id -> dictDb.dictionaryQueries.insertFreqWord(Freq_word(i + 1L, id, 100L - i)) }
        knownWords.markKnown(listOf(DictionaryFixture.IKU))
        val batch = knownWords.frequencyBatch(0, 2)
        assertEquals(listOf(DictionaryFixture.WATASHI, DictionaryFixture.TABERU), batch.words.map { it.entryId })
        assertEquals("私", batch.words.first().headword)
        assertEquals(3, batch.nextAfter)
        // The learner knows both: mark them, and the next page continues after the cursor.
        knownWords.markKnown(batch.words.map { it.entryId }, KnownWords.SOURCE_ONBOARDING)
        val next = knownWords.frequencyBatch(batch.nextAfter, 5)
        assertEquals(listOf(DictionaryFixture.NEKO, DictionaryFixture.GAKKOU), next.words.map { it.entryId })
        assertTrue(next.exhausted)
        val bands = knownWords.bands(bandSize = 2)
        assertEquals(listOf(2, 1, 0), bands.map { it.known })
        assertEquals(listOf(2, 2, 1), bands.map { it.total })
    }
}
