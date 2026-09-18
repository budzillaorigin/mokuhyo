package app.tsumugi.sync.crypto

/**
 * Argon2id v1.3 (RFC 9106), pure Kotlin. Lanes are computed sequentially (same result as parallel).
 * Used to derive the end-to-end sync key from the user's passphrase.
 */
object Argon2id {
    private const val VERSION = 0x13
    private const val TYPE_ID = 2
    private const val SYNC_POINTS = 4
    private const val WORDS = 128
    private const val ADDRESSES_IN_BLOCK = 128

    /**
     * @param memoryKiB memory cost in KiB (1 block = 1 KiB), at least 8 × [parallelism]
     * @param iterations time cost (passes)
     */
    fun hash(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
        tagLength: Int,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        require(parallelism >= 1 && iterations >= 1 && tagLength >= 4 && salt.size >= 8)
        require(memoryKiB >= 8 * parallelism)

        val h0 = Blake2b(64)
            .update(intLe(parallelism)).update(intLe(tagLength)).update(intLe(memoryKiB)).update(intLe(iterations))
            .update(intLe(VERSION)).update(intLe(TYPE_ID))
            .update(intLe(password.size)).update(password)
            .update(intLe(salt.size)).update(salt)
            .update(intLe(secret.size)).update(secret)
            .update(intLe(associatedData.size)).update(associatedData)
            .digest()

        val blocks = 4 * parallelism * (memoryKiB / (4 * parallelism))
        val laneLength = blocks / parallelism
        val segmentLength = laneLength / SYNC_POINTS
        val memory = Array(blocks) { LongArray(WORDS) }

        for (lane in 0 until parallelism) {
            for (j in 0..1) {
                val bytes = hPrime(1024, h0 + intLe(j) + intLe(lane))
                val block = memory[lane * laneLength + j]
                for (w in 0 until WORDS) block[w] = loadLong(bytes, w * 8)
            }
        }

        val zero = LongArray(WORDS)
        val input = LongArray(WORDS)
        val address = LongArray(WORDS)
        val tmp = LongArray(WORDS)

        for (pass in 0 until iterations) {
            for (slice in 0 until SYNC_POINTS) {
                for (lane in 0 until parallelism) {
                    val independent = pass == 0 && slice < SYNC_POINTS / 2
                    if (independent) {
                        input.fill(0)
                        input[0] = pass.toLong(); input[1] = lane.toLong(); input[2] = slice.toLong()
                        input[3] = blocks.toLong(); input[4] = iterations.toLong(); input[5] = TYPE_ID.toLong()
                    }
                    var start = 0
                    if (pass == 0 && slice == 0) {
                        start = 2
                        if (independent) nextAddresses(zero, input, address, tmp)
                    }
                    var curr = lane * laneLength + slice * segmentLength + start
                    var prev = if (curr % laneLength == 0) curr + laneLength - 1 else curr - 1
                    for (i in start until segmentLength) {
                        if (curr % laneLength == 1) prev = curr - 1
                        val pseudoRandom = if (independent) {
                            if (i % ADDRESSES_IN_BLOCK == 0) nextAddresses(zero, input, address, tmp)
                            address[i % ADDRESSES_IN_BLOCK]
                        } else {
                            memory[prev][0]
                        }
                        var refLane = ((pseudoRandom ushr 32) % parallelism).toInt()
                        if (pass == 0 && slice == 0) refLane = lane
                        val sameLane = refLane == lane
                        val areaSize: Long = if (pass == 0) {
                            when {
                                slice == 0 -> (i - 1).toLong()
                                sameLane -> (slice * segmentLength + i - 1).toLong()
                                else -> (slice * segmentLength + if (i == 0) -1 else 0).toLong()
                            }
                        } else {
                            if (sameLane) (laneLength - segmentLength + i - 1).toLong()
                            else (laneLength - segmentLength + if (i == 0) -1 else 0).toLong()
                        }
                        var rel = pseudoRandom and 0xffffffffL
                        rel = (rel * rel) ushr 32
                        rel = areaSize - 1 - ((areaSize * rel) ushr 32)
                        val startPos = if (pass != 0 && slice != SYNC_POINTS - 1) (slice + 1) * segmentLength else 0
                        val refIndex = ((startPos + rel) % laneLength).toInt()
                        fill(memory[prev], memory[refLane * laneLength + refIndex], memory[curr], withXor = pass != 0, tmp)
                        prev = curr
                        curr++
                    }
                }
            }
        }

        val final = memory[laneLength - 1].copyOf()
        for (lane in 1 until parallelism) {
            val last = memory[lane * laneLength + laneLength - 1]
            for (w in 0 until WORDS) final[w] = final[w] xor last[w]
        }
        val bytes = ByteArray(1024)
        for (w in 0 until WORDS) storeLong(bytes, w * 8, final[w])
        return hPrime(tagLength, bytes)
    }

    /** Variable-length hash H' (RFC 9106 §3.3). */
    private fun hPrime(length: Int, input: ByteArray): ByteArray {
        if (length <= 64) return Blake2b.hash(length, intLe(length), input)
        val out = ByteArray(length)
        val r = (length + 31) / 32 - 2
        var v = Blake2b.hash(64, intLe(length), input)
        v.copyInto(out, 0, 0, 32)
        for (i in 1 until r) {
            v = Blake2b.hash(64, v)
            v.copyInto(out, i * 32, 0, 32)
        }
        val last = Blake2b.hash(length - 32 * r, v)
        last.copyInto(out, 32 * r)
        return out
    }

    private fun nextAddresses(zero: LongArray, input: LongArray, address: LongArray, tmp: LongArray) {
        input[6]++
        fill(zero, input, address, withXor = false, tmp)
        fill(zero, address.copyOf(), address, withXor = false, tmp)
    }

    /** next = G(prev, ref) [xor next]; G(X, Y) = P(X ⊕ Y) ⊕ X ⊕ Y. */
    private fun fill(prev: LongArray, ref: LongArray, next: LongArray, withXor: Boolean, r: LongArray) {
        for (w in 0 until WORDS) r[w] = prev[w] xor ref[w]
        val z = r.copyOf()
        if (withXor) for (w in 0 until WORDS) z[w] = z[w] xor next[w]
        for (i in 0 until 8) {
            val b = 16 * i
            round(r, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
        }
        for (i in 0 until 8) {
            val b = 2 * i
            round(r, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
        }
        for (w in 0 until WORDS) next[w] = z[w] xor r[w]
    }

    private fun round(
        v: LongArray, v0: Int, v1: Int, v2: Int, v3: Int, v4: Int, v5: Int, v6: Int, v7: Int,
        v8: Int, v9: Int, v10: Int, v11: Int, v12: Int, v13: Int, v14: Int, v15: Int,
    ) {
        gb(v, v0, v4, v8, v12); gb(v, v1, v5, v9, v13); gb(v, v2, v6, v10, v14); gb(v, v3, v7, v11, v15)
        gb(v, v0, v5, v10, v15); gb(v, v1, v6, v11, v12); gb(v, v2, v7, v8, v13); gb(v, v3, v4, v9, v14)
    }

    private fun gb(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
        v[a] = blaMka(v[a], v[b]); v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = blaMka(v[c], v[d]); v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = blaMka(v[a], v[b]); v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = blaMka(v[c], v[d]); v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    private fun blaMka(x: Long, y: Long): Long = x + y + 2 * (x and 0xffffffffL) * (y and 0xffffffffL)
}
