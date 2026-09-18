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

    private suspend fun saveAndAnalyze(text: ImportedText): String = repository.save(text).also { analyzeLater(it) }

    private suspend fun analyzeLater(id: String) {
        val doc = repository.document(id) ?: return
        val analyzer = analyzer() ?: return
        _analysisProgress.value = AnalysisProgress(id, 0.0)
        try {
            val analysis = analyzer.analyzeWithProgress(doc.body) { _analysisProgress.value = AnalysisProgress(id, it) }
            repository.saveAnalysis(doc.id, analysis)
        } finally {
            _analysisProgress.value = null
        }
    }

    private suspend fun grammarPatterns(): List<Pair<String, Regex>> = graph.grammar()?.detectionPatterns().orEmpty()
}
