package app.tsumugi.integrations.anki

/**
 * Zstandard decompressor (RFC 8878) in common Kotlin, for Anki's `collection.anki21b` and media files.
 *
 * Written from the RFC's decoding procedure; table layouts and the FSE/Huffman table construction follow the
 * reference implementation's approach as also used by airlift/aircompressor (Apache-2.0).
 * Supports: multiple and skippable frames, raw/RLE/compressed blocks, raw/RLE/Huffman/treeless literals
 * (1 and 4 streams), predefined/RLE/FSE/repeat sequence tables and repeat offsets.
 * Not supported: dictionaries (Anki doesn't use them). Content checksums are skipped, not verified.
 */
object Zstd {
    private const val MAGIC = 0xFD2FB528L
    private const val MAX_BLOCK_SIZE = 128 * 1024

    fun isZstd(data: ByteArray): Boolean = data.size >= 4 && data.u32le(0) == MAGIC

    fun decompress(input: ByteArray): ByteArray {
        val out = Bytes(maxOf(64, input.size * 4))
        var p = 0
        while (p < input.size) {
            val magic = input.u32le(p)
            if (magic and 0xFFFFFFF0L == 0x184D2A50L) {
                p += 8 + input.u32le(p + 4).toInt()
                continue
            }
            if (magic != MAGIC) throw AnkiFormatException("not a zstd frame")
            p = Frame(input, p + 4, out).decode()
        }
        return out.toByteArray()
    }

    // --- Frame and blocks -----------------------------------------------------------------------------------

    private class Frame(val src: ByteArray, var p: Int, val out: Bytes) {
        val frameStart = out.size
        var huffman: HuffmanTable? = null
        var llTable: FseTable? = null
        var ofTable: FseTable? = null
        var mlTable: FseTable? = null
        val reps = intArrayOf(1, 4, 8)

        fun decode(): Int {
            val fhd = src[p++].toInt() and 0xFF
            val fcsFlag = fhd ushr 6
            val singleSegment = fhd and 0x20 != 0
            val checksum = fhd and 0x04 != 0
            val dictIdFlag = fhd and 0x03
            if (!singleSegment) p++ // window descriptor
            val dictBytes = intArrayOf(0, 1, 2, 4)[dictIdFlag]
            if (dictBytes > 0 && src.uLE(p, dictBytes) != 0L) throw AnkiFormatException("zstd dictionaries are not supported")
            p += dictBytes
            p += when (fcsFlag) {
                0 -> if (singleSegment) 1 else 0
                1 -> 2
                2 -> 4
                else -> 8
            }
            while (true) {
                val header = src.uLE(p, 3).toInt()
                p += 3
                val last = header and 1 != 0
                val type = (header ushr 1) and 3
                val size = header ushr 3
                when (type) {
                    0 -> { out.bytes(src, p, p + size); p += size }
                    1 -> { val b = src[p++].toInt(); repeat(size) { out.byte(b) } }
                    2 -> { compressedBlock(p, p + size); p += size }
                    else -> throw AnkiFormatException("reserved zstd block type")
                }
                if (last) break
            }
            if (checksum) p += 4
            return p
        }

        private fun compressedBlock(start: Int, end: Int) {
            val (literals, afterLiterals) = literals(start)
            sequences(afterLiterals, end, literals)
        }

        // --- Literals -------------------------------------------------------------------------------------

        private fun literals(start: Int): Pair<ByteArray, Int> {
            val b0 = src[start].toInt() and 0xFF
            val type = b0 and 3
            val sizeFormat = (b0 ushr 2) and 3
            if (type == 0 || type == 1) {
                val (regen, headerSize) = when (sizeFormat) {
                    0, 2 -> (b0 ushr 3) to 1
                    1 -> ((b0 ushr 4) + ((src[start + 1].toInt() and 0xFF) shl 4)) to 2
                    else -> ((b0 ushr 4) + ((src[start + 1].toInt() and 0xFF) shl 4) + ((src[start + 2].toInt() and 0xFF) shl 12)) to 3
                }
                val from = start + headerSize
                return if (type == 0) {
                    src.copyOfRange(from, from + regen) to from + regen
                } else {
                    ByteArray(regen) { src[from] } to from + 1
                }
            }
            val headerSize: Int
            val regen: Int
            val compressed: Int
            val streams: Int
            when (sizeFormat) {
                0, 1 -> {
                    val h = src.uLE(start, 3).toInt()
                    regen = (h ushr 4) and 0x3FF; compressed = (h ushr 14) and 0x3FF; headerSize = 3
                    streams = if (sizeFormat == 0) 1 else 4
                }
                2 -> {
                    val h = src.uLE(start, 4)
                    regen = ((h ushr 4) and 0x3FFF).toInt(); compressed = ((h ushr 18) and 0x3FFF).toInt(); headerSize = 4; streams = 4
                }
                else -> {
                    val h = src.uLE(start, 5)
                    regen = ((h ushr 4) and 0x3FFFF).toInt(); compressed = ((h ushr 22) and 0x3FFFF).toInt(); headerSize = 5; streams = 4
                }
            }
            var p = start + headerSize
            val end = p + compressed
            if (type == 2) {
                val (table, used) = HuffmanTable.read(src, p)
                huffman = table
                p += used
            }
            val table = huffman ?: throw AnkiFormatException("treeless literals without a previous Huffman table")
            val out = ByteArray(regen)
            if (streams == 1) {
                table.decodeStream(src, p, end, out, 0, regen)
            } else {
                val s1 = src.uLE(p, 2).toInt()
                val s2 = src.uLE(p + 2, 2).toInt()
                val s3 = src.uLE(p + 4, 2).toInt()
                p += 6
                val seg = (regen + 3) / 4
                var q = p
                val bounds = listOf(q.also { q += s1 } to q, q.also { q += s2 } to q, q.also { q += s3 } to q, q to end)
                for (i in 0 until 4) {
                    val from = seg * i
                    val count = if (i == 3) regen - seg * 3 else seg
                    table.decodeStream(src, bounds[i].first, bounds[i].second, out, from, count)
                }
            }
            return out to end
        }

        // --- Sequences ------------------------------------------------------------------------------------

        private fun sequences(start: Int, end: Int, literals: ByteArray) {
            var p = start
            val b0 = src[p++].toInt() and 0xFF
            val count = when {
                b0 == 0 -> 0
                b0 < 128 -> b0
                b0 < 255 -> ((b0 - 128) shl 8) + (src[p++].toInt() and 0xFF)
                else -> (src.uLE(p, 2).toInt() + 0x7F00).also { p += 2 }
            }
            if (count == 0) {
                out.bytes(literals)
                return
            }
            val modes = src[p++].toInt() and 0xFF
            fun table(mode: Int, previous: FseTable?, predefined: FseTable, maxSymbol: Int, maxLog: Int): FseTable = when (mode) {
                0 -> predefined
                1 -> FseTable.rle(src[p++].toInt() and 0xFF)
                2 -> {
                    val (norm, log, used) = FseTable.readNormalized(src, p, maxSymbol, maxLog)
                    p += used
                    FseTable.build(norm, log)
                }
                else -> previous ?: throw AnkiFormatException("repeat sequence table without a previous table")
            }
            val ll = table(modes ushr 6, llTable, LL_PREDEFINED, 35, 9).also { llTable = it }
            val of = table((modes ushr 4) and 3, ofTable, OF_PREDEFINED, 31, 8).also { ofTable = it }
            val ml = table((modes ushr 2) and 3, mlTable, ML_PREDEFINED, 52, 9).also { mlTable = it }

            val bits = BackwardBits(src, p, end)
            var llState = bits.read(ll.log).toInt()
            var ofState = bits.read(of.log).toInt()
            var mlState = bits.read(ml.log).toInt()
            var litPos = 0
            for (i in 0 until count) {
                val ofCode = of.symbol[ofState]
                val llCode = ll.symbol[llState]
                val mlCode = ml.symbol[mlState]
                val offsetValue = (1L shl ofCode) + bits.read(ofCode)
                val matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode]).toInt()
                val literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode]).toInt()

                val offset: Int
                if (offsetValue > 3) {
                    offset = (offsetValue - 3).toInt()
                    reps[2] = reps[1]; reps[1] = reps[0]; reps[0] = offset
                } else {
                    val idx = (offsetValue - 1).toInt() + if (literalLength == 0) 1 else 0
                    when (idx) {
                        0 -> offset = reps[0]
                        3 -> { offset = reps[0] - 1; reps[2] = reps[1]; reps[1] = reps[0]; reps[0] = offset }
                        else -> {
                            offset = reps[idx]
                            if (idx == 2) reps[2] = reps[1]
                            reps[1] = reps[0]
                            reps[0] = offset
                        }
                    }
                }

                if (litPos + literalLength > literals.size) throw AnkiFormatException("zstd literal overrun")
                out.bytes(literals, litPos, litPos + literalLength)
                litPos += literalLength
                if (offset > out.size - frameStart) throw AnkiFormatException("zstd offset beyond frame start")
                out.copyBack(offset, matchLength)

                if (i != count - 1) {
                    llState = ll.newState[llState] + bits.read(ll.nbBits[llState]).toInt()
                    mlState = ml.newState[mlState] + bits.read(ml.nbBits[mlState]).toInt()
                    ofState = of.newState[ofState] + bits.read(of.nbBits[ofState]).toInt()
                }
            }
            out.bytes(literals, litPos, literals.size)
        }
    }

    // --- Bit readers ----------------------------------------------------------------------------------------

    /**
     * Reads a zstd backward bitstream: starts at the end (after the padding marker bit of the last byte) and
     * moves toward [start]; reading past the beginning yields zeros and sets [overflowed].
     */
    internal class BackwardBits(private val data: ByteArray, private val start: Int, end: Int) {
        private var pos: Long // bit position relative to start; bits [pos, …) are unread

        init {
            val last = data[end - 1].toInt() and 0xFF
            if (last == 0) throw AnkiFormatException("zstd bitstream missing end marker")
            pos = (end - start - 1).toLong() * 8 + (31 - last.countLeadingZeroBits())
        }

        val overflowed: Boolean get() = pos < 0

        fun peek(n: Int): Long = if (n == 0) 0 else bitsAt(pos - n, n)

        fun skip(n: Int) {
            pos -= n
        }

        fun read(n: Int): Long = peek(n).also { pos -= n }

        private fun bitsAt(from: Long, n: Int): Long {
            if (from < 0) {
                val available = n + from.toInt()
                return if (available <= 0) 0 else bitsAt(0, available) shl (-from).toInt()
            }
            val byte = (from ushr 3).toInt()
            val shift = (from and 7).toInt()
            var v = 0L
            for (i in 0 until 8) {
                val idx = start + byte + i
                if (idx >= data.size) break
                v = v or ((data[idx].toLong() and 0xFF) shl (8 * i))
            }
            return (v ushr shift) and ((1L shl n) - 1)
        }
    }

    /** Little-endian forward bit reader (FSE table descriptions). */
    private class ForwardBits(val data: ByteArray, val start: Int) {
        var pos = 0L
        fun peek(n: Int): Int {
            var v = 0L
            val byte = (pos ushr 3).toInt()
            for (i in 0 until 5) {
                val idx = start + byte + i
                if (idx < data.size) v = v or ((data[idx].toLong() and 0xFF) shl (8 * i))
            }
            return ((v ushr (pos and 7).toInt()) and ((1L shl n) - 1)).toInt()
        }
        fun skip(n: Int) { pos += n }
    }

    // --- FSE ------------------------------------------------------------------------------------------------

    internal class FseTable(val log: Int, val symbol: IntArray, val nbBits: IntArray, val newState: IntArray) {
        companion object {
            fun rle(symbol: Int) = FseTable(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

            /** Parses an FSE table description; returns normalized counts, accuracy log and bytes consumed. */
            fun readNormalized(src: ByteArray, start: Int, maxSymbol: Int, maxLog: Int): Triple<IntArray, Int, Int> {
                val bits = ForwardBits(src, start)
                val log = bits.peek(4) + 5
                bits.skip(4)
                if (log > maxLog) throw AnkiFormatException("zstd FSE accuracy log $log too large")
                val norm = IntArray(maxSymbol + 1)
                var remaining = (1 shl log) + 1
                var threshold = 1 shl log
                var nbBits = log + 1
                var symbol = 0
                var previousZero = false
                while (remaining > 1 && symbol <= maxSymbol) {
                    if (previousZero) {
                        var n0 = symbol
                        while (bits.peek(2) == 3) {
                            n0 += 3
                            bits.skip(2)
                        }
                        n0 += bits.peek(2)
                        bits.skip(2)
                        if (n0 > maxSymbol + 1) throw AnkiFormatException("zstd FSE zero run too long")
                        symbol = n0
                        if (symbol > maxSymbol) break
                    }
                    val max = (2 * threshold - 1) - remaining
                    var count: Int
                    val low = bits.peek(nbBits - 1)
                    if (low < max) {
                        count = low
                        bits.skip(nbBits - 1)
                    } else {
                        count = bits.peek(nbBits)
                        if (count >= threshold) count -= max
                        bits.skip(nbBits)
                    }
                    count--
                    remaining -= if (count < 0) -count else count
                    norm[symbol++] = count
                    previousZero = count == 0
                    while (remaining < threshold) {
                        nbBits--
                        threshold = threshold shr 1
                    }
                }
                if (remaining != 1) throw AnkiFormatException("corrupt zstd FSE table description")
                return Triple(norm.copyOf(symbol), log, ((bits.pos + 7) / 8).toInt())
            }

            fun build(norm: IntArray, log: Int): FseTable {
                val size = 1 shl log
                val symbol = IntArray(size)
                val next = IntArray(norm.size)
                var high = size - 1
                for (s in norm.indices) {
                    if (norm[s] == -1) {
                        symbol[high--] = s
                        next[s] = 1
                    } else {
                        next[s] = norm[s]
                    }
                }
                val step = (size ushr 1) + (size ushr 3) + 3
                val mask = size - 1
                var pos = 0
                for (s in norm.indices) {
                    repeat(maxOf(0, norm[s])) {
                        symbol[pos] = s
                        do { pos = (pos + step) and mask } while (pos > high)
                    }
                }
                if (pos != 0) throw AnkiFormatException("corrupt zstd FSE distribution")
                val nb = IntArray(size)
                val ns = IntArray(size)
                for (u in 0 until size) {
                    val s = symbol[u]
                    val nextState = next[s]++
                    nb[u] = log - (31 - nextState.countLeadingZeroBits())
                    ns[u] = (nextState shl nb[u]) - size
                }
                return FseTable(log, symbol, nb, ns)
            }
        }
    }

    // --- Huffman --------------------------------------------------------------------------------------------

    internal class HuffmanTable(val log: Int, val symbol: IntArray, val nbBits: IntArray) {

        /** Decodes exactly [count] symbols from the backward stream [start, end) into [out] at [at]. */
        fun decodeStream(src: ByteArray, start: Int, end: Int, out: ByteArray, at: Int, count: Int) {
            val bits = BackwardBits(src, start, end)
            for (i in 0 until count) {
                val idx = bits.peek(log).toInt()
                out[at + i] = symbol[idx].toByte()
                bits.skip(nbBits[idx])
            }
        }

        companion object {
            /** Reads a Huffman tree description; returns the table and the bytes consumed. */
            fun read(src: ByteArray, start: Int): Pair<HuffmanTable, Int> {
                val header = src[start].toInt() and 0xFF
                val weights: IntArray
                val used: Int
                if (header < 128) {
                    weights = fseWeights(src, start + 1, header)
                    used = 1 + header
                } else {
                    val n = header - 127
                    weights = IntArray(n) { i ->
                        val b = src[start + 1 + i / 2].toInt() and 0xFF
                        if (i % 2 == 0) b ushr 4 else b and 0xF
                    }
                    used = 1 + (n + 1) / 2
                }
                return build(weights) to used
            }

            private fun fseWeights(src: ByteArray, start: Int, size: Int): IntArray {
                val (norm, log, used) = FseTable.readNormalized(src, start, 255, 6)
                val table = FseTable.build(norm, log)
                val bits = BackwardBits(src, start + used, start + size)
                var s1 = bits.read(log).toInt()
                var s2 = bits.read(log).toInt()
                val out = ArrayList<Int>()
                while (true) {
                    out += table.symbol[s1]
                    s1 = table.newState[s1] + bits.read(table.nbBits[s1]).toInt()
                    if (bits.overflowed) { out += table.symbol[s2]; break }
                    out += table.symbol[s2]
                    s2 = table.newState[s2] + bits.read(table.nbBits[s2]).toInt()
                    if (bits.overflowed) { out += table.symbol[s1]; break }
                    if (out.size > 255) throw AnkiFormatException("too many Huffman weights")
                }
                return out.toIntArray()
            }

            private fun build(partial: IntArray): HuffmanTable {
                var total = 0
                for (w in partial) if (w > 0) total += 1 shl (w - 1)
                if (total == 0) throw AnkiFormatException("empty Huffman weights")
                val maxBits = 32 - total.countLeadingZeroBits() // highest bit + 1
                val lastTotal = (1 shl maxBits) - total
                if (lastTotal and (lastTotal - 1) != 0) throw AnkiFormatException("invalid Huffman weights")
                val lastWeight = (31 - lastTotal.countLeadingZeroBits()) + 1
                val weights = partial + lastWeight
                val log = maxBits
                val rankCount = IntArray(log + 2)
                for (w in weights) rankCount[w]++
                val rankStart = IntArray(log + 2)
                var next = 0
                for (w in 1..log) {
                    rankStart[w] = next
                    next += rankCount[w] shl (w - 1)
                }
                val size = 1 shl log
                val symbol = IntArray(size)
                val nb = IntArray(size)
                for (s in weights.indices) {
                    val w = weights[s]
                    if (w == 0) continue
                    val length = (1 shl w) ushr 1
                    val from = rankStart[w]
                    for (i in from until from + length) {
                        symbol[i] = s
                        nb[i] = log + 1 - w
                    }
                    rankStart[w] += length
                }
                return HuffmanTable(log, symbol, nb)
            }
        }
    }

    // --- Tables ---------------------------------------------------------------------------------------------

    private val LL_BASE = IntArray(36) { if (it < 16) it else intArrayOf(16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)[it - 16] }
    private val LL_BITS = IntArray(36) { if (it < 16) 0 else intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)[it - 16] }
    private val ML_BASE = IntArray(53) { if (it < 32) it + 3 else intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)[it - 32] }
    private val ML_BITS = IntArray(53) { if (it < 32) 0 else intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)[it - 32] }

    private val LL_PREDEFINED = FseTable.build(
        intArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1), 6,
    )
    private val ML_PREDEFINED = FseTable.build(
        intArrayOf(
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1,
        ),
        6,
    )
    private val OF_PREDEFINED = FseTable.build(
        intArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1), 5,
    )

    private fun ByteArray.u32le(i: Int): Long = uLE(i, 4)

    private fun ByteArray.uLE(i: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((this[i + k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    @Suppress("unused")
    private val maxBlock = MAX_BLOCK_SIZE
}
