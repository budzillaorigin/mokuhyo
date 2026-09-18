package app.tsumugi.android.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.tsumugi.android.R
import app.tsumugi.api.AppGraph
import app.tsumugi.exam.BankImportResult
import app.tsumugi.exam.ExamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files opened with Tsumugi from a file manager, mail or chat app (F-42; ACTION_VIEW filters in AndroidManifest.xml).
 * EPUBs open in the reader, .apkg decks go through the Anki importer and JSON item banks through the exam-bank
 * importer. Subtitles need a video first, so they only get a message saying where to use them.
 */
sealed interface OpenedFile {
    data class Document(val docId: String) : OpenedFile
    data class Message(val text: String) : OpenedFile
}

/** The file's name as the sending app reports it (content URIs rarely end in the real file name). */
fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

private fun kindOf(name: String, mimeType: String?): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext in setOf("epub", "apkg", "srt", "vtt", "json")) return ext
    return when (mimeType) {
        "application/epub+zip" -> "epub"
        "application/x-subrip", "text/srt" -> "srt"
        "text/vtt" -> "vtt"
        "application/json" -> "json"
        "application/apkg", "application/x-apkg" -> "apkg"
        else -> ext
    }
}

suspend fun openFile(context: Context, graph: AppGraph, uri: Uri, name: String, mimeType: String?): OpenedFile {
    val kind = kindOf(name, mimeType)
    if (kind !in setOf("epub", "apkg", "srt", "vtt", "json")) {
        return OpenedFile.Message(context.getString(R.string.open_file_unsupported, name))
    }
    if (kind == "srt" || kind == "vtt") return OpenedFile.Message(context.getString(R.string.open_file_subtitles, name))
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "opened-file.$kind").also { out ->
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
    return when (kind) {
        "epub" -> OpenedFile.Document(graph.reader.importEpub(file.path))
        "apkg" -> {
            val r = graph.imports.importAnki(file.path)
            OpenedFile.Message(context.getString(R.string.import_anki_result, r.notes, r.cards, r.reviews))
        }
        else -> {
            val text = withContext(Dispatchers.IO) { file.readText() }
            when (val r = graph.exams().importBank(text)) {
                is BankImportResult.Imported -> OpenedFile.Message(
                    context.getString(R.string.import_bank_result, r.bank.removePrefix(ExamService.USER_PREFIX), r.items, r.passages),
                )
                is BankImportResult.Invalid -> OpenedFile.Message(
                    context.resources.getQuantityString(R.plurals.import_bank_invalid, r.errors.size, r.errors.size) +
                        "\n" + r.errors.take(10).joinToString("\n") { "• $it" },
                )
            }
        }
    }
}
