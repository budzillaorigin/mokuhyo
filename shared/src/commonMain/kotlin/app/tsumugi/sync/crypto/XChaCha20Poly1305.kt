package app.tsumugi.sync.crypto

/**
 * XChaCha20-Poly1305 AEAD (draft-irtf-cfrg-xchacha, built on RFC 8439 ChaCha20 and Poly1305), pure Kotlin.
 * Seals sync payloads end to end: the server only ever sees ciphertext.
 */
object XChaCha20Poly1305 {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val (subKey, chachaNonce) = derive(key, nonce)
        val ciphertext = ChaCha20.xor(subKey, chachaNonce, 1, plaintext)
        val tag = tag(subKey, chachaNonce, aad, ciphertext)
        return ciphertext + tag
    }

    /** Returns null when authentication fails (wrong key or tampered data). */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < TAG_BYTES) return null
        val (subKey, chachaNonce) = derive(key, nonce)
        val ciphertext = sealed.copyOf(sealed.size - TAG_BYTES)
        val expected = tag(subKey, chachaNonce, aad, ciphertext)
        var diff = 0
        for (i in 0 until TAG_BYTES) diff = diff or (expected[i].toInt() xor sealed[ciphertext.size + i].toInt())
        if (diff != 0) return null
        return ChaCha20.xor(subKey, chachaNonce, 1, ciphertext)
    }

    private fun derive(key: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        val subKey = ChaCha20.hChaCha20(key, nonce.copyOf(16))
        val chachaNonce = ByteArray(12)
        nonce.copyInto(chachaNonce, 4, 16, 24)
        return subKey to chachaNonce
    }

    /** AEAD construction of RFC 8439 §2.8 (ChaCha20-Poly1305). */
    internal fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val polyKey = ChaCha20.block(key, nonce, 0).copyOf(32)
        val mac = Poly1305(polyKey)
        mac.update(aad); mac.update(ByteArray((16 - aad.size % 16) % 16))
        mac.update(ciphertext); mac.update(ByteArray((16 - ciphertext.size % 16) % 16))
        val lengths = ByteArray(16)
        storeLong(lengths, 0, aad.size.toLong())
        storeLong(lengths, 8, ciphertext.size.toLong())
        mac.update(lengths)
        return mac.finish()
    }
}

internal object ChaCha20 {
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun rounds(s: IntArray) {
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
    }

    /** One 64-byte keystream block (RFC 8439 §2.3), 96-bit nonce. */
    fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val init = IntArray(16)
        SIGMA.copyInto(init)
        for (i in 0 until 8) init[4 + i] = loadInt(key, i * 4)
        init[12] = counter
        for (i in 0 until 3) init[13 + i] = loadInt(nonce, i * 4)
        val s = init.copyOf()
        rounds(s)
        val out = ByteArray(64)
        for (i in 0 until 16) storeInt(out, i * 4, s[i] + init[i])
        return out
    }

    fun xor(key: ByteArray, nonce: ByteArray, initialCounter: Int, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var counter = initialCounter
        var off = 0
        while (off < data.size) {
            val ks = block(key, nonce, counter++)
            val n = minOf(64, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return out
    }

    /** HChaCha20: derives a subkey from a key and a 128-bit nonce. */
    fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        SIGMA.copyInto(s)
        for (i in 0 until 8) s[4 + i] = loadInt(key, i * 4)
        for (i in 0 until 4) s[12 + i] = loadInt(nonce16, i * 4)
        rounds(s)
        val out = ByteArray(32)
        for (i in 0 until 4) storeInt(out, i * 4, s[i])
        for (i in 0 until 4) storeInt(out, 16 + i * 4, s[12 + i])
        return out
    }
}

/** Poly1305 one-time authenticator (RFC 8439 §2.5), 26-bit limb arithmetic. */
internal class Poly1305(key: ByteArray) {
    private val r0: Long; private val r1: Long; private val r2: Long; private val r3: Long; private val r4: Long
    private val s1: Long; private val s2: Long; private val s3: Long; private val s4: Long
    private var h0 = 0L; private var h1 = 0L; private var h2 = 0L; private var h3 = 0L; private var h4 = 0L
    private val pad = key.copyOfRange(16, 32)
    private val buffer = ByteArray(16)
    private var bufferLen = 0

    init {
        val t0 = loadInt(key, 0).toLong() and 0xffffffffL
        val t1 = loadInt(key, 4).toLong() and 0xffffffffL
        val t2 = loadInt(key, 8).toLong() and 0xffffffffL
        val t3 = loadInt(key, 12).toLong() and 0xffffffffL
        r0 = t0 and 0x3ffffff
        r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03
        r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ff
        r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fff
        r4 = (t3 ushr 8) and 0x00fffff
        s1 = r1 * 5; s2 = r2 * 5; s3 = r3 * 5; s4 = r4 * 5
    }

    fun update(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(16 - bufferLen, data.size - off)
            data.copyInto(buffer, bufferLen, off, off + n)
            bufferLen += n
            off += n
            if (bufferLen == 16) {
                processBlock(buffer, 1L shl 24)
                bufferLen = 0
            }
        }
    }

    private fun processBlock(b: ByteArray, hibit: Long) {
        val t0 = loadInt(b, 0).toLong() and 0xffffffffL
        val t1 = loadInt(b, 4).toLong() and 0xffffffffL
        val t2 = loadInt(b, 8).toLong() and 0xffffffffL
        val t3 = loadInt(b, 12).toLong() and 0xffffffffL
        h0 += t0 and 0x3ffffff
        h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3ffffff
        h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3ffffff
        h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3ffffff
        h4 += (t3 ushr 8) or hibit

        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

        var c = d0 ushr 26; h0 = d0 and 0x3ffffff
        d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
        d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
        d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
        d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
    }

    fun finish(): ByteArray {
        if (bufferLen > 0) {
            val last = ByteArray(16)
            buffer.copyInto(last, 0, 0, bufferLen)
            last[bufferLen] = 1
            processBlock(last, 0)
        }
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c

        // Compute h - p and select it if h >= p.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4
        }

        val f0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        val p0 = loadInt(pad, 0).toLong() and 0xffffffffL
        val p1 = loadInt(pad, 4).toLong() and 0xffffffffL
        val p2 = loadInt(pad, 8).toLong() and 0xffffffffL
        val p3 = loadInt(pad, 12).toLong() and 0xffffffffL
        var acc = f0 + p0
        val out = ByteArray(16)
        storeInt(out, 0, acc.toInt()); acc = (acc ushr 32) + f1 + p1
        storeInt(out, 4, acc.toInt()); acc = (acc ushr 32) + f2 + p2
        storeInt(out, 8, acc.toInt()); acc = (acc ushr 32) + f3 + p3
        storeInt(out, 12, acc.toInt())
        return out
    }
}
