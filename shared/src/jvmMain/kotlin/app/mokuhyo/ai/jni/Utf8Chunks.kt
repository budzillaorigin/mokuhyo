package app.mokuhyo.ai.jni

import java.io.ByteArrayOutputStream

/**
 * Decodes a byte stream whose chunks may end inside a UTF-8 character (a token boundary can fall inside ご, 中, 한,
 * ك, گ or Ж). The native side already only hands over complete characters; this keeps the Kotlin side right if a
 * chunk ever ends mid-character, so a streamed sentence never contains U+FFFD from a split (N3).
 */
internal class Utf8Chunks {
    private val pending = ByteArrayOutputStream()

    /** Adds [bytes]; returns the text of every character now complete (the rest waits for the next chunk). */
    fun append(bytes: ByteArray): String {
        pending.write(bytes)
        val all = pending.toByteArray()
        val ready = completePrefix(all)
        pending.reset()
        pending.write(all, ready, all.size - ready)
        return String(all, 0, ready, Charsets.UTF_8)
    }

    /** Whatever is left at the end (malformed tails decode with the replacement character). */
    fun finish(): String = String(pending.toByteArray(), Charsets.UTF_8).also { pending.reset() }

    companion object {
        /** Length of the longest prefix of [b] that ends on a UTF-8 character boundary. */
        fun completePrefix(b: ByteArray): Int {
            var i = b.size
            // Walk back over at most three continuation bytes to the lead byte of the last character.
            var k = i - 1
            while (k >= 0 && k >= i - 4 && (b[k].toInt() and 0xC0) == 0x80) k--
            if (k < 0) return 0
            val lead = b[k].toInt() and 0xFF
            val need = when {
                lead < 0x80 -> 1
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            if (i - k < need) i = k
            return i
        }
    }
}
