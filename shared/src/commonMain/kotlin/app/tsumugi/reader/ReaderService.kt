package app.tsumugi.reader

import app.tsumugi.api.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/**
 * One entry point for the reader screens (BRIEF §5.8): documents, importers, analysis and sentence mining.
 * Network importers (URL, RSS, Aozora) run only when the learner asks; everything they fetch stays on the
 * device (never synced, BRIEF §4).
 */
/** Difficulty analysis of [documentId] in progress: [fraction] of the sampled text done. */
data class AnalysisProgress(val documentId: String, val fraction: Double)

class ReaderService(private val graph: AppGraph) {
    val repository: ReaderRepository by lazy { ReaderRepository(graph.userDatabase) }
    private val web: UrlImporter by lazy { UrlImporter(graph.platform.httpEngine()) }
    val feeds: FeedService by lazy { FeedService(repository, web) }
    val aozora: AozoraService by lazy { AozoraService(web, repository) }

    private var analyzerCache: ReaderAnalyzer? = null

    private val _analysisProgress = MutableStateFlow<AnalysisProgress?>(null)

    /** The document being analyzed after an import, and how far along (0..1); null when idle (rule 15). */
    val analysisProgress: StateFlow<AnalysisProgress?> = _analysisProgress.asStateFlow()

    /**
     * Tokenizing/analysis needs the dictionary pack; null without it. Tokens come from the morphological analyzer
     * (F-26); only a build without the tokenizer pack falls back to the dictionary's longest-match tokenizer.
     */
    @Throws(Exception::class)
    suspend fun analyzer(): ReaderAnalyzer? {
        analyzerCache?.let { return it }
        val dictionary = graph.dictionary() ?: return null
        val srs = graph.configuredSrs()
        val morphology = graph.analyzer()
        val analyzer = if (morphology != null) {
            ReaderAnalyzer(morphology, dictionary, stages = { srs.stages() }, grammarPatterns = { grammarPatterns() })
        } else {
            ReaderAnalyzer(dictionary, stages = { srs.stages() }, grammarPatterns = { grammarPatterns() })
        }
        return analyzer.also { analyzerCache = it }
    }

    @Throws(Exception::class)
    suspend fun documents(): List<ReaderDocumentSummary> = repository.documents()

    @Throws(Exception::class)
    suspend fun document(id: String): ReaderDocument? = repository.document(id)

    @Throws(Exception::class)
    suspend fun importText(text: String, title: String? = null): String = saveAndAnalyze(TextImporter.import(text, title))

    @Throws(Exception::class)
    suspend fun importUrl(url: String): String = saveAndAnalyze(web.import(url.trim()))

    @Throws(Exception::class)
    suspend fun importEpub(path: String): String {
        val bytes = withContext(Dispatchers.IO) { graph.platform.fileSystem.read(path.toPath()) { readByteArray() } }
        return saveAndAnalyze(EpubImporter.import(bytes, path.substringAfterLast('/')))
    }

    @Throws(Exception::class)
    suspend fun importFeedItem(item: FeedItem): String = feeds.importItem(item).also { analyzeLater(it) }

    @Throws(Exception::class)
    suspend fun importAozora(work: AozoraWork): String = aozora.import(work).also { analyzeLater(it) }

    @Throws(Exception::class)
    suspend fun delete(id: String) = repository.delete(id)

    @Throws(Exception::class)
    suspend fun setProgress(id: String, offset: Int) = repository.setProgress(id, offset)

    /** Adds a tapped word to reviews with its sentence as context (sentence mining, BRIEF §5.8). */
    @Throws(Exception::class)
    suspend fun mine(token: ReaderToken, sentence: ReaderSentence): String? {
        val analyzer = analyzer() ?: return null
        val dictionary = graph.dictionary() ?: return null
        return analyzer.mine(token, sentence, dictionary, graph.collection)
    }

    // --- Phase 10 (BRIEF_V2 G-07) --------------------------------------------------------------------------

    /** Comprehension questions (AI-generated, cached per document). */
    val questions: ReadingQuestionService by lazy { ReadingQuestionService(graph.userDatabase, { graph.ai.gateway() }) }

    /** Graded passage packs; empty until Phase 12 installs one (the app sets this when a pack is present). */
    var packs: ReaderPackRepository = EmptyReaderPacks

    /** Paragraphs [from, from + count) of [document], with its own ruby as authoritative readings. */
    @Throws(Exception::class)
    suspend fun page(document: ReaderDocument, from: Int, count: Int): List<ReaderParagraph> =
        analyzer()?.page(document.body, from, count, document.ruby).orEmpty()

    /**
     * The learner's level for "furigana only above my level": the synced [LEARNER_JLPT] setting when set, else an
     * estimate from the passed kanji-path level; known kanji from SRS (Guru+).
     */
    @Throws(Exception::class)
    suspend fun learnerLevel(): LearnerLevel {
        val stages = graph.configuredSrs().stages()
        val jlpt = graph.settings.get(LEARNER_JLPT)?.toIntOrNull()?.takeIf { it in 1..5 }
            ?: LearnerLevel.jlptFromPathLevel(graph.pathProgress.progress().passedLevel)
        return LearnerLevel(jlpt, LearnerLevel.knownKanji(stages))
    }

    /** Pitch accents for the words of [sentence] (dictionary pitch table; empty without the dictionary pack). */
    @Throws(Exception::class)
    suspend fun pitch(sentence: ReaderSentence): List<TokenPitch> {
        val dictionary = graph.dictionary() ?: return emptyList()
        return ReaderPitch.overlay(sentence.tokens) { w, r -> dictionary.pitchAccents(w, r) }
    }

    /** Opens a graded passage as a reader document (reused on reopen); returns the document id. */
    @Throws(Exception::class)
    suspend fun openPassage(passageId: String): String? {
        val passage = packs.passage(passageId) ?: return null
        return saveAndAnalyze(passage.toImportedText())
    }

    private suspend fun saveAndAnalyze(text: ImportedText): String = repository.save(text).also { analyzeLater(it) }

    private suspend fun analyzeLater(id: String) {
        val doc = repository.document(id) ?: return
        val analyzer = analyzer() ?: return
        _analysisProgress.value = AnalysisProgress(id, 0.0)
        try {
            val analysis = analyzer.analyzeWithProgress(doc.body, doc.ruby) { _analysisProgress.value = AnalysisProgress(id, it) }
            repository.saveAnalysis(doc.id, analysis)
        } finally {
            _analysisProgress.value = null
        }
    }

    private suspend fun grammarPatterns(): List<Pair<String, Regex>> = graph.grammar()?.detectionPatterns().orEmpty()

    companion object {
        /** Synced learner preference: the learner's JLPT level, 5 (N5) … 1 (N1). */
        const val LEARNER_JLPT = "learner.jlpt"
    }
}
