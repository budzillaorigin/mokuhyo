package app.tsumugi.reader

import java.nio.charset.Charset

actual fun decodeText(bytes: ByteArray, charset: String): String {
    val cs = runCatching { Charset.forName(if (charset == "Shift_JIS") "windows-31j" else charset) }.getOrNull() ?: Charsets.UTF_8
    return String(bytes, cs)
}
