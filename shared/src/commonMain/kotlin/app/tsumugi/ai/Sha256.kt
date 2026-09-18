package app.tsumugi.ai

/** Incremental SHA-256 (FIPS 180-4) in common code, for verifying multi-gigabyte model downloads as a stream. */
class Sha256 {
    private val h = H0.copyOf()
    private val block = ByteArray(64)
    private var blockLen = 0
    private var totalBytes = 0L
    private val w = IntArray(64)

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Sha256 {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(64 - blockLen, end - i)
            data.copyInto(block, blockLen, i, i + n)
            blockLen += n
            i += n
            if (blockLen == 64) {
                compress(block, 0)
                blockLen = 0
            }
        }
        totalBytes += length
        return this
    }

    fun digest(): ByteArray {
        val bitLen = totalBytes * 8
        val pad = ByteArray(if (blockLen < 56) 64 - blockLen else 128 - blockLen)
        pad[0] = 0x80.toByte()
        for (j in 0 until 8) pad[pad.size - 1 - j] = (bitLen ushr (8 * j)).toByte()
        update(pad)
        val out = ByteArray(32)
        for (j in 0 until 8) for (k in 0 until 4) out[j * 4 + k] = (h[j] ushr (24 - 8 * k)).toByte()
        return out
    }

    fun hexDigest(): String = digest().toHex()

    private fun compress(b: ByteArray, o: Int) {
        for (t in 0 until 16) {
            val p = o + t * 4
            w[t] = (b[p].toInt() and 0xFF shl 24) or (b[p + 1].toInt() and 0xFF shl 16) or
                (b[p + 2].toInt() and 0xFF shl 8) or (b[p + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var bb = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and bb) xor (a and c) xor (bb and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2
        }
        h[0] += a; h[1] += bb; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    companion object {
        fun hex(data: ByteArray): String = Sha256().update(data).hexDigest()

        private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        private val H0 = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )

        private val K = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).let { longs -> IntArray(longs.size) { longs[it].toInt() } }
    }
}
