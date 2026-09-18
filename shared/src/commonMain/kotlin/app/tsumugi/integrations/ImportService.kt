package app.tsumugi.integrations

import app.tsumugi.api.AppGraph
import app.tsumugi.integrations.anki.AnkiExporter
import app.tsumugi.integrations.bunpro.BunproImportResult
import app.tsumugi.integrations.bunpro.BunproImporter
import app.tsumugi.integrations.anki.AnkiImportResult
import app.tsumugi.integrations.anki.AnkiImporter
import app.tsumugi.integrations.imiwa.ImiwaImporter
import app.tsumugi.integrations.imiwa.ListImportResult
import app.tsumugi.integrations.wanikani.WaniKaniClient
import app.tsumugi.integrations.wanikani.WaniKaniImportResult
import app.tsumugi.integrations.wanikani.WaniKaniSync
import app.tsumugi.integrations.wanikani.WkUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath

/**
 * File- and token-based imports/exports for both apps (BRIEF §5.4, §9). Paths are plain file-system paths so
 * the apps can hand over whatever their document pickers produced (copied into a readable location first).
 */
class ImportService(private val graph: AppGraph) {
    private val fs get() = graph.platform.fileSystem
    private val workDir: Path get() = graph.platform.dataDir / "tmp"
    private val mediaDir: Path get() = graph.platform.dataDir / "media"

    val wanikani: WaniKaniSync by lazy {
        WaniKaniSync(graph.userDatabase, graph.srs, graph.platform.secrets, { token -> WaniKaniClient(token, graph.platform.httpEngine()) })
    }

    /** Imports an Anki .apkg (legacy or modern). Cards with review history keep it; new cards await lessons. */
    @Throws(Exception::class)
    suspend fun importAnki(apkgPath: String): AnkiImportResult {
        val bytes = withContext(Dispatchers.IO) { fs.read(apkgPath.toPath()) { readByteArray() } }
        prepareDirs()
        return AnkiImporter(graph.configuredSrs(), fs, workDir, graph.platform::openSqlite, mediaDir).import(bytes)
    }

    /** Exports items (all started items when [itemIds] is empty) to an .apkg at [outPath]; returns the path. */
    @Throws(Exception::class)
    suspend fun exportAnki(outPath: String, itemIds: List<String> = emptyList(), deckName: String = "Tsumugi"): String {
        prepareDirs()
        val ids = itemIds.ifEmpty { graph.srs.allItemIds() }
        val bytes = AnkiExporter(graph.userDatabase, fs, workDir, graph.platform::openSqlite, mediaDir).export(ids, deckName)
        withContext(Dispatchers.IO) { fs.write(outPath.toPath()) { write(bytes) } }
        return outPath
    }

    /** Imports an imiwa export (or any word/reading/meaning list) into a new word list, matched to JMdict. */
    @Throws(Exception::class)
    suspend fun importWordList(filePath: String, listName: String): ListImportResult {
        val text = withContext(Dispatchers.IO) { fs.read(filePath.toPath()) { readUtf8() } }
        val words = ImiwaImporter.parse(text)
        val dictionary = graph.dictionary()
        return ImiwaImporter.importToList(graph.userDatabase, listName, words) { w ->
            val hits = dictionary?.search(w.text)?.hits.orEmpty()
            val match = hits.firstOrNull { it.entry.headword == w.text && (w.reading.isBlank() || it.entry.reading == w.reading) }
                ?: hits.firstOrNull { it.entry.headword == w.text }
            match?.let { "jmdict:${it.entry.id}" }
        }
    }

    @Throws(Exception::class)
    suspend fun connectWaniKani(token: String): WkUser = wanikani.connect(token)

    /** Imports a Bunpro CSV/TSV export; returns null when the grammar pack isn't installed. */
    @Throws(Exception::class)
    suspend fun importBunpro(filePath: String): BunproImportResult? {
        val grammar = graph.grammar() ?: return null
        val text = withContext(Dispatchers.IO) { fs.read(filePath.toPath()) { readUtf8() } }
        return BunproImporter(grammar, graph.configuredSrs()).import(text)
    }

    /** Imports WaniKani progress onto the kanji path; returns null when the path pack isn't installed. */
    @Throws(Exception::class)
    suspend fun importWaniKani(progress: (String) -> Unit = {}): WaniKaniImportResult? {
        val path = graph.path() ?: return null
        return wanikani.import(path.items(), progress)
    }

    private suspend fun prepareDirs() = withContext(Dispatchers.IO) {
        fs.createDirectories(workDir)
        fs.createDirectories(mediaDir)
    }
}
