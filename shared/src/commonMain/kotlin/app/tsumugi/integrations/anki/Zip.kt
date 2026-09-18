package app.tsumugi.integrations.anki

/**
 * Minimal zip support in common Kotlin (no platform zlib), enough for .apkg files:
 * reading STORED and DEFLATED entries via the central directory, writing STORED entries.
 * ZIP64 archives are not supported (Anki packages stay far below 4 GB).
 */
internal object Zip {

    /** All entries of a zip archive, by name, decompressed. */
    fun read(zip: ByteArray): Map<String, ByteArray> {
        val eocd = (zip.size - 22 downTo maxOf(0, zip.size - 22 - 65_535)).firstOrNull { zip.u32(it) == 0x06054b50L }
            ?: throw AnkiFormatException("not a zip archive (no end-of-central-directory record)")
        val count = zip.u16(eocd + 10)
        var p = zip.u32(eocd + 16).toInt()
        val out = LinkedHashMap<String, ByteArray>()
        repeat(count) {
            if (zip.u32(p) != 0x02014b50L) throw AnkiFormatException("corrupt zip central directory")
            val method = zip.u16(p + 10)
            val compressedSize = zip.u32(p + 20).toInt()
            val size = zip.u32(p + 24).toInt()
            val nameLen = zip.u16(p + 28)
            val extraLen = zip.u16(p + 30)
            val commentLen = zip.u16(p + 32)
            val localOffset = zip.u32(p + 42).toInt()
            val name = zip.decodeToString(p + 46, p + 46 + nameLen)
            p += 46 + nameLen + extraLen + commentLen

            if (zip.u32(localOffset) != 0x04034b50L) throw AnkiFormatException("corrupt zip entry $name")
            val dataStart = localOffset + 30 + zip.u16(localOffset + 26) + zip.u16(localOffset + 28)
            val raw = zip.copyOfRange(dataStart, dataStart + compressedSize)
            out[name] = when (method) {
                0 -> raw
                8 -> Inflater.inflate(raw, size)
                else -> throw AnkiFormatException("unsupported zip compression method $method in $name")
            }
        }
        return out
    }

    /** A zip archive with every entry STORED (uncompressed), UTF-8 names. */
    fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = Bytes()
        val central = Bytes()
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = Crc32.of(data)
            val offset = out.size
            out.u32(0x04034b50); out.u16(20); out.u16(0x0800); out.u16(0); out.u16(0); out.u16(0x21)
            out.u32(crc.toLong()); out.u32(data.size.toLong()); out.u32(data.size.toLong())
            out.u16(nameBytes.size); out.u16(0); out.bytes(nameBytes); out.bytes(data)

            central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0x0800); central.u16(0)
            central.u16(0); central.u16(0x21); central.u32(crc.toLong()); central.u32(data.size.toLong())
            central.u32(data.size.toLong()); central.u16(nameBytes.size); central.u16(0); central.u16(0)
            central.u16(0); central.u16(0); central.u32(0); central.u32(offset.toLong()); central.bytes(nameBytes)
        }
        val centralOffset = out.size
        out.bytes(central.toByteArray())
        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
        out.u32(central.size.toLong()); out.u32(centralOffset.toLong()); out.u16(0)
        return out.toByteArray()
    }

    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or (this[i + 1].toInt() and 0xFF shl 8)
    private fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
}

/** Growable little-endian byte buffer. */
internal class Bytes(initial: Int = 1024) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun byte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun bytes(b: ByteArray, from: Int = 0, to: Int = b.size) {
        ensure(to - from)
        b.copyInto(buf, size, from, to)
        size += to - from
    }

    fun u16(v: Int) { byte(v); byte(v ushr 8) }
    fun u32(v: Long) { u16(v.toInt()); u16((v ushr 16).toInt()) }

    operator fun get(i: Int): Byte = buf[i]

    /** Copies [length] bytes starting [distance] back from the end (may overlap, as in LZ77 matches). */
    fun copyBack(distance: Int, length: Int) {
        if (distance <= 0 || distance > size) throw AnkiFormatException("invalid back-reference distance $distance")
        ensure(length)
        var from = size - distance
        repeat(length) { buf[size++] = buf[from++] }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

class AnkiFormatException(message: String) : Exception(message)

/** Raw DEFLATE (RFC 1951) decoder. Canonical-Huffman decoding in the style of zlib's `puff`. */
internal object Inflater {

    private class BitIn(val data: ByteArray) {
        var pos = 0
        var bitBuf = 0
        var bitCount = 0

        fun bits(n: Int): Int {
            var v = bitBuf
            while (bitCount < n) {
                if (pos >= data.size) throw AnkiFormatException("truncated deflate stream")
                v = v or ((data[pos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            bitBuf = v ushr n
            bitCount -= n
            return v and ((1 shl n) - 1)
        }

        fun alignToByte() {
            bitBuf = 0
            bitCount = 0
        }
    }

    private class Huffman(lengths: IntArray, n: Int) {
        val count = IntArray(16)
        val symbol = IntArray(n)

        init {
            for (i in 0 until n) count[lengths[i]]++
            count[0] = 0
            val offs = IntArray(16)
            for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
            for (s in 0 until n) if (lengths[s] != 0) symbol[offs[lengths[s]]++] = s
        }

        fun decode(bin: BitIn): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or bin.bits(1)
                val c = count[len]
                if (code - c < first) return symbol[index + (code - first)]
                index += c
                first += c
                first = first shl 1
                code = code shl 1
            }
            throw AnkiFormatException("invalid Huffman code in deflate stream")
        }
    }

    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val fixedLit: Huffman by lazy {
        val l = IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }
        Huffman(l, 288)
    }
    private val fixedDist: Huffman by lazy { Huffman(IntArray(30) { 5 }, 30) }

    fun inflate(data: ByteArray, expectedSize: Int = data.size * 4): ByteArray {
        val bin = BitIn(data)
        val out = Bytes(maxOf(expectedSize, 64))
        do {
            val last = bin.bits(1)
            when (bin.bits(2)) {
                0 -> {
                    bin.alignToByte()
                    val p = bin.pos
                    val len = (data[p].toInt() and 0xFF) or (data[p + 1].toInt() and 0xFF shl 8)
                    out.bytes(data, p + 4, p + 4 + len)
                    bin.pos = p + 4 + len
                }
                1 -> codes(bin, out, fixedLit, fixedDist)
                2 -> {
                    val nlen = bin.bits(5) + 257
                    val ndist = bin.bits(5) + 1
                    val ncode = bin.bits(4) + 4
                    val cl = IntArray(19)
                    for (i in 0 until ncode) cl[CL_ORDER[i]] = bin.bits(3)
                    val clHuff = Huffman(cl, 19)
                    val lengths = IntArray(nlen + ndist)
                    var i = 0
                    while (i < nlen + ndist) {
                        val sym = clHuff.decode(bin)
                        if (sym < 16) {
                            lengths[i++] = sym
                        } else {
                            val (value, repeat) = when (sym) {
                                16 -> {
                                    if (i == 0) throw AnkiFormatException("repeat with no previous length")
                                    lengths[i - 1] to 3 + bin.bits(2)
                                }
                                17 -> 0 to 3 + bin.bits(3)
                                else -> 0 to 11 + bin.bits(7)
                            }
                            repeat(repeat) { lengths[i++] = value }
                        }
                    }
                    codes(bin, out, Huffman(lengths.copyOfRange(0, nlen), nlen), Huffman(lengths.copyOfRange(nlen, nlen + ndist), ndist))
                }
                else -> throw AnkiFormatException("invalid deflate block type")
            }
        } while (last == 0)
        return out.toByteArray()
    }

    private fun codes(bin: BitIn, out: Bytes, lit: Huffman, dist: Huffman) {
        while (true) {
            val sym = lit.decode(bin)
            when {
                sym < 256 -> out.byte(sym)
                sym == 256 -> return
                else -> {
                    val s = sym - 257
                    if (s >= 29) throw AnkiFormatException("invalid length symbol")
                    val len = LEN_BASE[s] + bin.bits(LEN_EXTRA[s])
                    val d = dist.decode(bin)
                    if (d >= 30) throw AnkiFormatException("invalid distance symbol")
                    out.copyBack(DIST_BASE[d] + bin.bits(DIST_EXTRA[d]), len)
                }
            }
        }
    }
}
