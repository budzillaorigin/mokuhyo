package app.tsumugi.reader

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSISO2022JPStringEncoding
import platform.Foundation.NSJapaneseEUCStringEncoding
import platform.Foundation.NSShiftJISStringEncoding
import platform.Foundation.NSString
import platform.Foundation.NSStringEncoding
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun decodeText(bytes: ByteArray, charset: String): String {
    val encoding: NSStringEncoding = when (charset) {
        "Shift_JIS" -> NSShiftJISStringEncoding
        "EUC-JP" -> NSJapaneseEUCStringEncoding
        "ISO-2022-JP" -> NSISO2022JPStringEncoding
        else -> return bytes.decodeToString()
    }
    if (bytes.isEmpty()) return ""
    val data = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
    val decoded = NSString.create(data = data, encoding = encoding)
    @Suppress("CAST_NEVER_SUCCEEDS")
    return (decoded as String?) ?: bytes.decodeToString()
}
