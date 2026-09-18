package app.tsumugi.sync.crypto

import app.tsumugi.ai.Sha256

/** HMAC-SHA256 (RFC 2104) over the common-code [Sha256]. */
object HmacSha256 {
    private const val BLOCK = 64

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val k = (if (key.size > BLOCK) Sha256().update(key).digest() else key).copyOf(BLOCK)
        val inner = ByteArray(BLOCK) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK) { (k[it].toInt() xor 0x5c).toByte() }
        val innerHash = Sha256().update(inner).update(message).digest()
        return Sha256().update(outer).update(innerHash).digest()
    }
}
