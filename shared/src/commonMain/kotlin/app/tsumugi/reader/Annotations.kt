package app.tsumugi.reader

import app.tsumugi.ai.Sha256
import app.tsumugi.db.Reader_annotation
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.recordings.ImageStore
import app.tsumugi.recordings.PendingRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Japan Reader-style annotation tools (BRIEF_V2 §6.4). */
enum class AnnotationKind { BOX, HIGHLIGHT, NOTE, GRAMMAR }

/**
 * An annotation on a reader document. [start]/[end] are character offsets in the document body as shown now;
 * [detached] means the text it was made on is no longer in the body (the document was re-imported with other
 * text), and the UI lists it under the document instead of drawing it.
 */
data class ReaderAnnotation(
    val id: String,
    val docKey: String,
    val kind: AnnotationKind,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    val color: String?,
    val grammarPointId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val detached: Boolean = false,
)

/** A screenshot kept as the page image of a document: its text is body[start, end). */
@Serializable
data class PageImage(val imageId: String, val start: Int, val end: Int)

/** A page image with its file on this device (null when it isn't here, e.g. recordings sync off). */
data class PageImageFile(val imageId: String, val start: Int, val end: Int, val path: String?)

/** One screenshot to import: the picture the platform wrote into [image] and the text its OCR found. */
data class ScreenshotPage(val image: PendingRecording, val ocrText: String)

/**
 * Reader annotations (BRIEF_V2 §6.4, DECISIONS D-164): box a phrase, highlight, attach a note, mark a grammar span.
 * They persist per document by character offsets and sync as one row per annotation (last writer wins, deletes are
 * tombstones). Reader documents themselves don't sync, so annotations are keyed by a [docKey] that is the same on
 * every device holding the same text: the source URL when there is one, else a hash of the NFC body.
 */
class ReaderAnnotations(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.readerNotesQueries

    /** The cross-device key of [document] (computed once and stored with the document's extras). */
    @Throws(Exception::class)
    suspend fun docKey(document: ReaderDocument): String = io { docKeyBlocking(db, document) }

    @Throws(Exception::class)
    suspend fun add(
        document: ReaderDocument,
        kind: AnnotationKind,
        start: Int,
        end: Int,
        note: String = "",
        color: String? = null,
        grammarPointId: String? = null,
    ): ReaderAnnotation {
        require(start in 0 until end && end <= document.body.length) { "annotation range $start..$end is outside the document" }
        val key = docKey(document)
        val id = Uuid.random().toString()
        val now = clock.now().toEpochMilliseconds()
        io {
            q.putAnnotation(
                id, key, kind.name, start.toLong(), end.toLong(), document.body.substring(start, end), normalizeNfc(note.trim()),
                color, grammarPointId, now, now,
            )
        }
        return annotation(id)!!
    }

    /** Changes an annotation's note, colour, kind or grammar point (null keeps the current value). */
    @Throws(Exception::class)
    suspend fun update(
        id: String,
        note: String? = null,
        color: String? = null,
        kind: AnnotationKind? = null,
        grammarPointId: String? = null,
    ): ReaderAnnotation? {
        val a = annotation(id) ?: return null
        val now = maxOf(clock.now().toEpochMilliseconds(), a.updatedAt + 1)
        io {
            q.putAnnotation(
                a.id, a.docKey, (kind ?: a.kind).name, a.start.toLong(), a.end.toLong(), a.quote, note?.trim()?.let(::normalizeNfc) ?: a.note,
                color ?: a.color, grammarPointId ?: a.grammarPointId, a.createdAt, now,
            )
        }
        return annotation(id)
    }

    /** Deletes an annotation everywhere (tombstone row). */
    @Throws(Exception::class)
    suspend fun delete(id: String) {
        io { q.tombstoneAnnotation(clock.now().toEpochMilliseconds(), id) }
    }

    @Throws(Exception::class)
    suspend fun annotation(id: String): ReaderAnnotation? = io { q.annotationById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toAnnotation() }

    /** The document's annotations, re-anchored on its current body (see [ReaderAnnotation.detached]). */
    @Throws(Exception::class)
    suspend fun forDocument(document: ReaderDocument): List<ReaderAnnotation> {
        val key = docKey(document)
        return io {
            q.annotationsForDoc(key).executeAsList().map { row ->
                val a = row.toAnnotation()
                val range = anchor(document.body, a.start, a.end, a.quote)
                if (range == null) a.copy(detached = true) else a.copy(start = range.first, end = range.last + 1)
            }.sortedWith(compareBy<ReaderAnnotation>({ it.detached }, { it.start }))
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun Reader_annotation.toAnnotation() = ReaderAnnotation(
        id, doc_key, runCatching { AnnotationKind.valueOf(kind) }.getOrDefault(AnnotationKind.HIGHLIGHT), start_offset.toInt(),
        end_offset.toInt(), quote, note, color, grammar_id, created_at, updated_at,
    )

    companion object {
        /** "url:<source url>" when the document has one, else "sha256:<hex of the NFC body>". */
        fun docKeyOf(sourceUrl: String?, body: String): String =
            sourceUrl?.takeIf { it.isNotBlank() }?.let { "url:${it.trim()}" }
                ?: "sha256:" + Sha256.hex(normalizeNfc(body).encodeToByteArray())

        /**
         * Where [quote] is in [body] now: at [start] if unchanged, else the occurrence nearest [start]; null when the
         * text is gone.
         */
        fun anchor(body: String, start: Int, end: Int, quote: String): IntRange? {
            if (quote.isEmpty()) return null
            if (start >= 0 && end <= body.length && start < end && body.substring(start, end) == quote) return start until end
            var best: Int? = null
            var from = body.indexOf(quote)
            while (from >= 0) {
                if (best == null || kotlin.math.abs(from - start) < kotlin.math.abs(best - start)) best = from
                from = body.indexOf(quote, from + 1)
            }
            return best?.let { it until it + quote.length }
        }

        internal fun docKeyBlocking(db: TsumugiDatabase, document: ReaderDocument): String {
            val q = db.readerNotesQueries
            q.docMeta(document.id).executeAsOneOrNull()?.let { return it.doc_key }
            val key = docKeyOf(document.sourceUrl, document.body)
            q.putDocMeta(document.id, key, null)
            return key
        }
    }
}

/**
 * Screenshot import (BRIEF_V2 §6.4): the platform OCRs each picture (Vision / ML Kit, the existing path), writes the
 * picture into a pending [ImageStore] file, and calls [importPages]; the pictures are kept as the document's page images.
 */
class ScreenshotImport(
    private val db: TsumugiDatabase,
    private val repository: ReaderRepository,
    private val images: ImageStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pages = ListSerializer(PageImage.serializer())

    /** A file for the platform to write one screenshot into. */
    @Throws(Exception::class)
    suspend fun screenshotFile(extension: String = "jpg"): PendingRecording = images.newImage(extension)

    /** Saves the pages as one reader document (pages separated by blank lines); returns the document id. */
    @Throws(Exception::class)
    suspend fun importPages(pages: List<ScreenshotPage>, title: String? = null): String {
        require(pages.isNotEmpty()) { "no screenshots" }
        val body = StringBuilder()
        val spans = ArrayList<Pair<PendingRecording, IntRange>>()
        for (p in pages) {
            val text = normalizeNfc(p.ocrText.trim())
            if (body.isNotEmpty()) body.append("\n\n")
            val start = body.length
            body.append(text)
            spans += p.image to (start until body.length)
        }
        require(body.isNotBlank()) { "OCR found no text in the screenshots" }
        val docTitle = title?.trim()?.ifEmpty { null } ?: body.lineSequence().first { it.isNotBlank() }.take(TITLE_CHARS)
        val id = repository.save(ImportedText(docTitle, body.toString(), SourceKind.SCREENSHOT))
        val refs = spans.map { (pending, range) -> PageImage(images.register(pending).id, range.first, range.last + 1) }
        val doc = repository.document(id)!!
        withContext(Dispatchers.IO) {
            db.readerNotesQueries.putDocMeta(id, ReaderAnnotations.docKeyOf(doc.sourceUrl, doc.body), json.encodeToString(this@ScreenshotImport.pages, refs))
        }
        return id
    }

    /** The document's page images in order (empty for documents that weren't screenshots). */
    @Throws(Exception::class)
    suspend fun pageImages(documentId: String): List<PageImageFile> {
        val raw = withContext(Dispatchers.IO) { db.readerNotesQueries.docMeta(documentId).executeAsOneOrNull()?.page_images } ?: return emptyList()
        val refs = runCatching { json.decodeFromString(pages, raw) }.getOrDefault(emptyList())
        return refs.map { PageImageFile(it.imageId, it.start, it.end, images.pathOf(it.imageId)) }
    }

    private companion object {
        const val TITLE_CHARS = 30
    }
}
