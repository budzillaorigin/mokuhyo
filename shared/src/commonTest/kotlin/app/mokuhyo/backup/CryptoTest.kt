package app.mokuhyo.sync

import app.mokuhyo.backup.crypto.Argon2id
import app.mokuhyo.backup.crypto.Blake2b
import app.mokuhyo.backup.crypto.ChaCha20
import app.mokuhyo.backup.crypto.Poly1305
import app.mokuhyo.backup.crypto.XChaCha20Poly1305
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Published test vectors: RFC 7693 (BLAKE2b), RFC 8439 (ChaCha20/Poly1305), RFC 9106 (Argon2id), XChaCha draft. */
class CryptoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private val sunscreen = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."

    @Test
    fun blake2b() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            Blake2b.hash(64, "abc".encodeToByteArray()).hex(),
        )
        // Multi-block input exercises the counter and buffering.
        val big = ByteArray(300) { it.toByte() }
        assertContentEquals(Blake2b.hash(32, big), Blake2b(32).update(big, 0, 100).update(big, 100, 200).digest())
    }

    @Test
    fun poly1305() {
        val mac = Poly1305(hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b"))
        mac.update("Cryptographic Forum Research Group".encodeToByteArray())
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", mac.finish().hex())
    }

    @Test
    fun hChaCha20() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            ChaCha20.hChaCha20(key, hex("000000090000004a0000000031415927")).hex(),
        )
    }

    @Test
    fun chaCha20Poly1305Tag() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val ciphertext = ChaCha20.xor(key, nonce, 1, sunscreen.encodeToByteArray())
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", ciphertext.copyOf(16).hex())
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", XChaCha20Poly1305.tag(key, nonce, aad, ciphertext).hex())
    }

    @Test
    fun xChaCha20Poly1305() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = ByteArray(24) { (0x40 + it).toByte() }
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = XChaCha20Poly1305.seal(key, nonce, sunscreen.encodeToByteArray(), aad)
        assertEquals("bd6d179d3e83d43b9576579493c0e939", sealed.copyOf(16).hex())
        assertEquals("c0875924c1c7987947deafd8780acf49", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertEquals(sunscreen, XChaCha20Poly1305.open(key, nonce, sealed, aad)!!.decodeToString())
        sealed[3] = (sealed[3] + 1).toByte()
        assertNull(XChaCha20Poly1305.open(key, nonce, sealed, aad), "tampering is detected")
    }

    @Test
    fun argon2idRfc9106() {
        val tag = Argon2id.hash(
            password = ByteArray(32) { 1 }, salt = ByteArray(16) { 2 }, iterations = 3, memoryKiB = 32, parallelism = 4,
            tagLength = 32, secret = ByteArray(8) { 3 }, associatedData = ByteArray(12) { 4 },
        )
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", tag.hex())
    }
}
