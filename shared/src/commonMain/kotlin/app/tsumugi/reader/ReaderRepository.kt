package app.tsumugi.reader

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class SourceKind { PASTE, URL, RSS, AOZORA, EPUB, PACK }

/** A text ready to be saved as a reader document (the output of every importer). */
data class ImportedText(
    val title: String,
    val body: String,
    val kind: SourceKind,
    val sourceUrl: String? = null,
    val author: String? = null,
    /** Readings the source itself supplied (Aozora ruby), by offset in [body]. Used for furigana. */
    val ruby: List<RubyHint> = emptyList(),
)

/** A source-provided reading: [base] at [start] in the body reads [reading]. */
data class RubyHint(val start: Int, val base: String, val reading: String)

data class ReaderDocument(
    val id: String,
    val title: String,
    val kind: SourceKind,
    val sourceUrl: String?,
    val author: String?,
    val body: String,
    val importedAt: Long,
    /** 5..1, 0 = harder than N1, null = not analyzed yet. */
    val jlptEstimate: Int?,
    val ilrEstimate: String?,
    val knownRatio: Double?,
    val progress: Int,
)

data class ReaderDocumentSummary(
    val id: String,
    val title: String,
    val kind: SourceKind,
    val sourceUrl: String?,
    val author: String?,
    val importedAt: Long,
    val jlptEstimate: Int?,
    val ilrEstimate: String?,
    val knownRatio: Double?,
    val progress: Int,
    val length: Int,
) {
    /** "≈ N3 / ILR 1+" (BRIEF §5.8), or null before analysis. */
    val levelLabel: String? get() = levelLabel(jlptEstimate, ilrEstimate)
}

data class ReaderFeed(val id: String, val url: String, val title: String, val addedAt: Long, val lastFetchedAt: Long?)

internal fun levelLabel(jlpt: Int?, ilr: String?): String? {
    if (jlpt == null && ilr == null) return null
    val j = jlpt?.let { if (it == 0) "above N1" else "N$it" }
    return listOfNotNull(j?.let { "≈ $it" }, ilr?.let { "ILR $it" }).joinToString(" / ")
}

/** Reader documents and feeds in the user DB (device-local; never synced — BRIEF §5.8). */
class ReaderRepository(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.readerQueries

    /** Saves an imported text; re-importing the same URL replaces the earlier copy. Returns the document id. */
    suspend fun save(text: ImportedText): String = io {
        val existing = text.sourceUrl?.let { q.docBySourceUrl(it).executeAsOneOrNull() }
        val id = existing?.id ?: Uuid.random().toString()
        q.insertDoc(
            id, text.title, text.kind.name, text.sourceUrl, text.author, text.body,
            clock.now().toEpochMilliseconds(), null, null, null, existing?.progress ?: 0,
            if (text.kind == SourceKind.PACK) 0 else 1,
        )
        id
    }

    suspend fun documents(): List<ReaderDocumentSummary> = io {
        q.docList().executeAsList().map {
            ReaderDocumentSummary(
                it.id, it.title, SourceKind.valueOf(it.source_kind), it.source_url, it.author, it.imported_at,
                it.jlpt_estimate?.toInt(), it.ilr_estimate, it.known_ratio, it.progress.toInt(), it.length.toInt(),
            )
        }
    }

    suspend fun document(id: String): ReaderDocument? = io {
        q.docById(id).executeAsOneOrNull()?.let {
            ReaderDocument(
                it.id, it.title, SourceKind.valueOf(it.source_kind), it.source_url, it.author, it.body, it.imported_at,
                it.jlpt_estimate?.toInt(), it.ilr_estimate, it.known_ratio, it.progress.toInt(),
            )
        }
    }

    suspend fun delete(id: String) = io { q.deleteDoc(id) }

    suspend fun setProgress(id: String, offset: Int) = io { q.updateProgress(offset.toLong(), id) }

    suspend fun saveAnalysis(id: String, analysis: ReaderAnalysis) = io {
        q.updateAnalysis(analysis.jlptEstimate?.toLong(), analysis.ilrEstimate, analysis.knownRatio, id)
    }

    // --- Feeds --------------------------------------------------------------------------------------------

    suspend fun addFeed(url: String, title: String): ReaderFeed = io {
        q.insertFeed(Uuid.random().toString(), url, title, clock.now().toEpochMilliseconds())
        q.feedByUrl(url).executeAsOne().let { ReaderFeed(it.id, it.url, it.title, it.added_at, it.last_fetched_at) }
    }

    suspend fun feeds(): List<ReaderFeed> = io {
        q.feeds().executeAsList().map { ReaderFeed(it.id, it.url, it.title, it.added_at, it.last_fetched_at) }
    }

    suspend fun renameFeed(id: String, title: String) = io { q.renameFeed(title, id) }

    suspend fun markFetched(id: String) = io { q.markFeedFetched(clock.now().toEpochMilliseconds(), id) }

    suspend fun deleteFeed(id: String) = io { q.deleteFeed(id) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
