package app.tsumugi.reader

import app.tsumugi.integrations.anki.Zip
import app.tsumugi.platform.normalizeNfc

/**
 * EPUB → one reader document (BRIEF §5.8; the owner's Japanese EPUBs, including vertical-writing ones, which
 * are just text here). Reads META-INF/container.xml → the OPF package → spine order → XHTML chapters.
 * Ruby readings are dropped from the text (the reader adds its own furigana). DRM-protected books can't be read.
 */
object EpubImporter {

    fun import(epub: ByteArray, sourceName: String? = null): ImportedText {
        val files = Zip.read(epub)
        // Font obfuscation is common and harmless; encrypted chapters mean DRM.
        val encryption = files["META-INF/encryption.xml"]?.decodeToString()
        if (encryption != null) {
            val encrypted = Html.parse(encryption).elements().filter { it.tag == "cipherreference" }.mapNotNull { it.attrs["uri"] }
            if (encrypted.any { it.endsWith("html") || it.endsWith("htm") }) throw ImportException("This EPUB is DRM-protected and can't be imported")
        }
        val container = files["META-INF/container.xml"]?.decodeToString() ?: throw ImportException("Not an EPUB (no container.xml)")
        val opfPath = Html.parse(container).elements().firstOrNull { it.tag == "rootfile" }?.attrs?.get("full-path")
            ?: throw ImportException("EPUB container has no rootfile")
        val opf = Html.parse(files[opfPath]?.decodeToString() ?: throw ImportException("Missing $opfPath"))
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val manifest = opf.elements().filter { it.tag == "item" }.mapNotNull { item ->
            val id = item.attrs["id"] ?: return@mapNotNull null
            val href = item.attrs["href"] ?: return@mapNotNull null
            id to resolve(base, href)
        }.toMap()
        val spine = opf.elements().filter { it.tag == "itemref" }.mapNotNull { manifest[it.attrs["idref"]] }.toList()
        val chapters = spine.ifEmpty { manifest.values.filter { it.endsWith("html") || it.endsWith("htm") } }

        val body = chapters.mapNotNull { path -> files[path]?.let { WebArticleExtractor.bodyText(it.decodeToString()) } }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (body.isBlank()) throw ImportException("No text found in the EPUB")
        val title = opf.first("title")?.let { Html.text(it, emptySet()) }?.trim().orEmpty()
        val author = opf.first("creator")?.let { Html.text(it, emptySet()) }?.trim()?.ifEmpty { null }
        return ImportedText(
            normalizeNfc(title.ifEmpty { sourceName ?: TextImporter.defaultTitle(body) }), normalizeNfc(body),
            SourceKind.EPUB, null, author?.let(::normalizeNfc),
        )
    }

    /** Resolves [href] (possibly %-encoded, with ../) against the OPF directory. */
    internal fun resolve(base: String, href: String): String {
        val parts = ArrayList<String>()
        for (segment in (base + percentDecode(href.substringBefore('#'))).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                bytes += s.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
