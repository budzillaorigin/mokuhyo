package app.mokuhyo.ai

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Reads what the app needs from a GGUF file's header — the model's own name, its architecture and its trained context
 * length (`<arch>.context_length`) — without native code (BRIEF_PHASE8 N-00b: the context is capped at the trained
 * length and the engine label comes from the file, not the configured tier). Stops as soon as the three keys are
 * found; they precede the large tokenizer arrays in every GGUF the app ships.
 */
object GgufReader {
    fun read(path: String): ModelFileInfo? = runCatching { File(path).inputStream().use { read(it) } }.getOrNull()

    fun read(input: InputStream): ModelFileInfo? {
        val d = LeInput(BufferedInputStream(input, 1 shl 16))
        if (String(d.bytes(4), Charsets.US_ASCII) != "GGUF") return null
        val version = d.u32()
        if (version < 2) return null
        d.u64() // tensor count
        val kvCount = d.u64()
        var name: String? = null
        var arch: String? = null
        val lengths = HashMap<String, Long>()
        for (i in 0 until kvCount) {
            val key = d.string()
            val type = d.u32().toInt()
            when {
                key == "general.name" && type == STRING -> name = d.string()
                key == "general.architecture" && type == STRING -> arch = d.string()
                key.endsWith(".context_length") && type in INTS -> lengths[key.substringBeforeLast(".context_length")] = d.int(type)
                else -> d.skip(type)
            }
            val ctx = arch?.let { lengths[it] }
            if (name != null && ctx != null) return ModelFileInfo(name, arch, ctx.toInt())
        }
        return arch?.let { ModelFileInfo(name, it, lengths[it]?.toInt()) } ?: name?.let { ModelFileInfo(it, null, null) }
    }

    private const val STRING = 8
    private const val ARRAY = 9
    private val INTS = setOf(0, 1, 2, 3, 4, 5, 10, 11)

    private class LeInput(stream: InputStream) {
        private val d = DataInputStream(stream)
        fun bytes(n: Int): ByteArray = ByteArray(n).also { d.readFully(it) }
        fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(d.readInt()))
        fun u64(): Long = java.lang.Long.reverseBytes(d.readLong())
        fun string(): String {
            val n = u64()
            if (n < 0 || n > 1 shl 24) throw EOFException("bad string length $n")
            return String(bytes(n.toInt()), Charsets.UTF_8)
        }
        fun int(type: Int): Long = when (type) {
            0 -> (d.readByte().toLong() and 0xff)
            1 -> d.readByte().toLong()
            2 -> (java.lang.Short.reverseBytes(d.readShort()).toLong() and 0xffff)
            3 -> java.lang.Short.reverseBytes(d.readShort()).toLong()
            4 -> u32()
            5 -> Integer.reverseBytes(d.readInt()).toLong()
            10, 11 -> u64()
            else -> error("not an integer type $type")
        }
        fun skip(type: Int) {
            when (type) {
                0, 1, 7 -> d.skipNBytes(1)
                2, 3 -> d.skipNBytes(2)
                4, 5, 6 -> d.skipNBytes(4)
                10, 11, 12 -> d.skipNBytes(8)
                STRING -> d.skipNBytes(u64())
                ARRAY -> {
                    val inner = u32().toInt()
                    val n = u64()
                    for (k in 0 until n) skip(inner)
                }
                else -> error("unknown GGUF value type $type")
            }
        }
    }
}
