package app.tsumugi.reader

import app.tsumugi.platform.normalizeNfc
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess

/** Pasted text (or text shared into the app) → a reader document. */
object TextImporter {
    fun import(text: String, title: String? = null): ImportedText {
        val body = Html.normalizeText(normalizeNfc(text))
        return ImportedText(title?.trim()?.ifEmpty { null } ?: defaultTitle(body), body, SourceKind.PASTE)
    }

    /** First line, cut to 20 characters. */
    fun defaultTitle(body: String): String {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.length <= TITLE_LENGTH) first.ifEmpty { "Untitled" } else first.take(TITLE_LENGTH) + "…"
    }

    private const val TITLE_LENGTH = 20
}

class ImportException(message: String) : Exception(message)

/**
 * Fetches a web page on the user's device and extracts its article (BRIEF §5.8: articles are fetched and cached
 * locally at the user's request, never redistributed). Handles Shift_JIS/EUC-JP pages.
 */
class UrlImporter(engine: HttpClientEngine) {
    private val http = HttpClient(engine) { expectSuccess = false }

    @Throws(Exception::class)
    suspend fun fetch(url: String): Pair<ByteArray, String?> {
        val response = http.get(url) { header("User-Agent", USER_AGENT) }
        if (!response.status.isSuccess()) throw ImportException("HTTP ${response.status.value} for $url")
        return response.body<ByteArray>() to response.headers["Content-Type"]
    }

    @Throws(Exception::class)
    suspend fun fetchText(url: String): String {
        val (bytes, type) = fetch(url)
        return normalizeNfc(decodeText(bytes, detectCharset(bytes, type)))
    }

    @Throws(Exception::class)
    suspend fun import(url: String, kind: SourceKind = SourceKind.URL): ImportedText {
        val html = fetchText(url)
        val article = WebArticleExtractor.extract(html)
        if (article.body.isBlank()) throw ImportException("No readable text found at $url")
        return ImportedText(article.title.ifEmpty { TextImporter.defaultTitle(article.body) }, article.body, kind, url)
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (compatible; Tsumugi reader)"
    }
}
