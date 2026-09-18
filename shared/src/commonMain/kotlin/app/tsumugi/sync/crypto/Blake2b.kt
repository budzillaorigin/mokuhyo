package app.tsumugi.sync.crypto

/**
 * BLAKE2b (RFC 7693), used by Argon2id. Pure Kotlin so it runs identically on every target.
 * Supports keyless hashing with an output length of 1..64 bytes.
 */
internal class Blake2b(private val outLen: Int) {
    private val h = LongArray(8)
    private val buffer = ByteArray(BLOCK)
    private var bufferLen = 0
    private var t0 = 0L
    private var t1 = 0L
    private val m = LongArray(16)
    private val v = LongArray(16)

    init {
        require(outLen in 1..64)
        IV.copyInto(h)
        h[0] = h[0] xor (0x01010000L or outLen.toLong())
    }

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size): Blake2b {
        var off = offset
        var len = length
        while (len > 0) {
            if (bufferLen == BLOCK) {
                increment(BLOCK)
                compress(buffer, 0, last = false)
                bufferLen = 0
            }
            val n = minOf(BLOCK - bufferLen, len)
            data.copyInto(buffer, bufferLen, off, off + n)
            bufferLen += n
            off += n
            len -= n
        }
        return this
    }

    fun digest(): ByteArray {
        increment(bufferLen)
        buffer.fill(0, bufferLen, BLOCK)
        compress(buffer, 0, last = true)
        val out = ByteArray(64)
        for (i in 0 until 8) storeLong(out, i * 8, h[i])
        return out.copyOf(outLen)
    }

    private fun increment(n: Int) {
        val before = t0
        t0 += n
        if (t0.toULong() < before.toULong()) t1++
    }

    private fun compress(block: ByteArray, off: Int, last: Boolean) {
        for (i in 0 until 16) m[i] = loadLong(block, off + i * 8)
        for (i in 0 until 8) {
            v[i] = h[i]
            v[i + 8] = IV[i]
        }
        v[12] = v[12] xor t0
        v[13] = v[13] xor t1
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    companion object {
        const val BLOCK = 128

        private val IV = longArrayOf(
            0x6a09e667f3bcc908L, -0x4498517a7b3558c5L, 0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
            0x510e527fade682d1L, -0x64fa9773d4c193e1L, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

        fun hash(outLen: Int, vararg parts: ByteArray): ByteArray {
            val b = Blake2b(outLen)
            parts.forEach { b.update(it) }
            return b.digest()
        }
    }
}

internal fun loadLong(b: ByteArray, off: Int): Long {
    var r = 0L
    for (i in 7 downTo 0) r = (r shl 8) or (b[off + i].toLong() and 0xff)
    return r
}

internal fun storeLong(b: ByteArray, off: Int, value: Long) {
    var x = value
    for (i in 0 until 8) {
        b[off + i] = x.toByte()
        x = x ushr 8
    }
}

internal fun loadInt(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
        ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

internal fun storeInt(b: ByteArray, off: Int, value: Int) {
    b[off] = value.toByte()
    b[off + 1] = (value ushr 8).toByte()
    b[off + 2] = (value ushr 16).toByte()
    b[off + 3] = (value ushr 24).toByte()
}

internal fun intLe(value: Int): ByteArray = ByteArray(4).also { storeInt(it, 0, value) }
