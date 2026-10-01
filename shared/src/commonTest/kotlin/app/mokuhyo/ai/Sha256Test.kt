package app.mokuhyo.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    // FIPS 180-4 / NIST CAVS example vectors.
    @Test
    fun emptyString() = assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        Sha256.hex(ByteArray(0)),
    )

    @Test
    fun abc() = assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Sha256.hex("abc".encodeToByteArray()),
    )

    @Test
    fun twoBlockMessage() = assertEquals(
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
    )

    @Test
    fun oneMillionAs() {
        val sha = Sha256()
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { sha.update(chunk) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", sha.hexDigest())
    }

    @Test
    fun incrementalUpdatesMatchOneShot() {
        val data = ByteArray(1000) { (it * 31 + 7).toByte() }
        val oneShot = Sha256.hex(data)
        for (step in listOf(1, 3, 55, 56, 63, 64, 65, 999)) {
            val sha = Sha256()
            var i = 0
            while (i < data.size) {
                val n = minOf(step, data.size - i)
                sha.update(data, i, n)
                i += n
            }
            assertEquals(oneShot, sha.hexDigest(), "step $step")
        }
    }
}
