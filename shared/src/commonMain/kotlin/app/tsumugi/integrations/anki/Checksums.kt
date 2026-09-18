package app.tsumugi.integrations.anki

/** CRC-32 (IEEE 802.3), as used by zip. */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = -1
        for (i in from until to) c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }
}

/** SHA-1, needed for Anki's note checksum (`csum`) and media entries. */
internal object Sha1 {
    fun of(data: ByteArray): ByteArray {
        val ml = data.size.toLong() * 8
        val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (ml ushr (8 * i)).toByte()
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                w[i] = (padded[o].toInt() and 0xFF shl 24) or (padded[o + 1].toInt() and 0xFF shl 16) or
                    (padded[o + 2].toInt() and 0xFF shl 8) or (padded[o + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val (f, k) = when {
                    i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = t
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        val out = ByteArray(20)
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { i, h -> for (j in 0 until 4) out[i * 4 + j] = (h ushr (24 - 8 * j)).toByte() }
        return out
    }

    fun hex(data: ByteArray): String = of(data).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
