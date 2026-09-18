package app.tsumugi.reader

/**
 * Decodes [bytes] in [charset] ("UTF-8", "Shift_JIS", "EUC-JP", "ISO-2022-JP", …). Japanese legacy encodings
 * need the platform (Android: java.nio.charset; iOS: NSString encodings). Unknown charsets fall back to UTF-8.
 */
expect fun decodeText(bytes: ByteArray, charset: String): String

/** Canonical charset name for a label from a header or <meta>, or null when unrecognized. */
internal fun canonicalCharset(label: String?): String? {
    val l = label?.trim()?.trim('"', '\'')?.lowercase() ?: return null
    return when (l) {
        "utf-8", "utf8" -> "UTF-8"
        "shift_jis", "shift-jis", "sjis", "x-sjis", "ms_kanji", "windows-31j", "cp932", "ms932" -> "Shift_JIS"
        "euc-jp", "eucjp", "x-euc-jp" -> "EUC-JP"
        "iso-2022-jp", "csiso2022jp" -> "ISO-2022-JP"
        else -> null
    }
}

/**
 * Picks the charset for a fetched page: HTTP header first, then a <meta charset> / http-equiv declaration in
 * the first bytes, then UTF-8.
 */
internal fun detectCharset(bytes: ByteArray, contentType: String?): String {
    canonicalCharset(contentType?.substringAfter("charset=", "")?.substringBefore(';')?.takeIf { it.isNotBlank() })?.let { return it }
    val head = bytes.copyOf(minOf(bytes.size, 4096)).map { b -> (b.toInt() and 0x7F).toChar() }.toCharArray().concatToString().lowercase()
    Regex("""charset\s*=\s*["']?([a-z0-9_\-]+)""").find(head)?.let { m -> canonicalCharset(m.groupValues[1])?.let { return it } }
    Regex("""<\?xml[^>]*encoding\s*=\s*["']([a-z0-9_\-]+)""").find(head)?.let { m -> canonicalCharset(m.groupValues[1])?.let { return it } }
    return "UTF-8"
}
