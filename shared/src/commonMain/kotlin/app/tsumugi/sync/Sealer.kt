package app.tsumugi.sync

import app.tsumugi.sync.crypto.Argon2id
import app.tsumugi.sync.crypto.HmacSha256
import app.tsumugi.sync.crypto.XChaCha20Poly1305
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/** Seals and opens row payloads for end-to-end encrypted sync. */
interface Sealer {
    /** Base64 of nonce ‖ ciphertext. */
    fun seal(plaintext: String): String

    /** The plaintext, or null when the key is wrong or the data was tampered with. */
    fun open(sealed: String): String?

    /**
     * The id the server sees for a row key. Deterministic, so the server can still order and dedupe a row's
     * changes, but opaque: without the key it can't be reversed or linked across tables (BRIEF_V2 F-37).
     */
    fun keyId(table: String, key: String): String = key

    /** Seals binary data (recording and picture blobs, D-111). Default: the text sealer over base64. */
    @OptIn(ExperimentalEncodingApi::class)
    fun sealBytes(plain: ByteArray): ByteArray = seal(Base64.encode(plain)).encodeToByteArray()

    /** Opens [sealBytes] output; null when the key is wrong or the data was tampered with. */
    @OptIn(ExperimentalEncodingApi::class)
    fun openBytes(sealed: ByteArray): ByteArray? =
        open(sealed.decodeToString())?.let { runCatching { Base64.decode(it) }.getOrNull() }
}

/**
 * XChaCha20-Poly1305 with a key derived from the user's sync passphrase ([E2eKeys.derive]). The key lives only in
 * memory; the server stores ciphertext it can't read (docs/SYNC_PROTOCOL.md).
 */
@OptIn(ExperimentalEncodingApi::class)
class E2eSealer(private val key: ByteArray) : Sealer {
    init {
        require(key.size == XChaCha20Poly1305.KEY_BYTES)
    }

    /** Separate subkey for key ids, so the encryption key itself is never used as a MAC key. */
    private val keyIdKey: ByteArray = HmacSha256.mac(key, KEY_ID_LABEL.encodeToByteArray())

    /** HMAC-SHA256(subkey, table ‖ U+001F ‖ key), base64url without padding. */
    override fun keyId(table: String, key: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(HmacSha256.mac(keyIdKey, (table + TableSpec.KEY_SEPARATOR + key).encodeToByteArray()))

    private companion object {
        const val KEY_ID_LABEL = "tsumugi-sync-key-id-v1"
    }

    override fun seal(plaintext: String): String {
        val nonce = E2eKeys.randomBytes(XChaCha20Poly1305.NONCE_BYTES)
        return Base64.encode(nonce + XChaCha20Poly1305.seal(key, nonce, plaintext.encodeToByteArray()))
    }

    /** Raw nonce ‖ ciphertext, without the base64 layers of the text form. */
    override fun sealBytes(plain: ByteArray): ByteArray {
        val nonce = E2eKeys.randomBytes(XChaCha20Poly1305.NONCE_BYTES)
        return nonce + XChaCha20Poly1305.seal(key, nonce, plain)
    }

    override fun openBytes(sealed: ByteArray): ByteArray? {
        if (sealed.size < XChaCha20Poly1305.NONCE_BYTES + XChaCha20Poly1305.TAG_BYTES) return null
        val nonce = sealed.copyOf(XChaCha20Poly1305.NONCE_BYTES)
        return XChaCha20Poly1305.open(key, nonce, sealed.copyOfRange(XChaCha20Poly1305.NONCE_BYTES, sealed.size))
    }

    override fun open(sealed: String): String? {
        val bytes = runCatching { Base64.decode(sealed) }.getOrNull() ?: return null
        if (bytes.size < XChaCha20Poly1305.NONCE_BYTES + XChaCha20Poly1305.TAG_BYTES) return null
        val nonce = bytes.copyOf(XChaCha20Poly1305.NONCE_BYTES)
        val body = bytes.copyOfRange(XChaCha20Poly1305.NONCE_BYTES, bytes.size)
        return XChaCha20Poly1305.open(key, nonce, body)?.decodeToString()
    }
}

/** Passphrase → key derivation (Argon2id) and a verifier to detect a wrong passphrase before syncing. */
@OptIn(ExperimentalEncodingApi::class)
object E2eKeys {
    data class Params(val iterations: Int = 3, val memoryKiB: Int = 32 * 1024, val parallelism: Int = 1)

    private const val VERIFIER_TEXT = "tsumugi-e2e-v1"

    fun derive(passphrase: String, saltBase64: String, params: Params = Params()): ByteArray =
        Argon2id.hash(
            password = passphrase.encodeToByteArray(),
            salt = Base64.decode(saltBase64),
            iterations = params.iterations,
            memoryKiB = params.memoryKiB,
            parallelism = params.parallelism,
            tagLength = XChaCha20Poly1305.KEY_BYTES,
        )

    fun newSalt(): String = Base64.encode(randomBytes(16))

    fun verifier(key: ByteArray): String = E2eSealer(key).seal(VERIFIER_TEXT)

    fun matches(key: ByteArray, verifier: String): Boolean = E2eSealer(key).open(verifier) == VERIFIER_TEXT

    /** Cryptographically secure bytes from the platform CSPRNG (via kotlin.uuid, ~122 random bits per UUID). */
    internal fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val chunk = Uuid.random().toByteArray()
            // Skip the fixed version/variant nibbles by only using bytes 0-5 and 9-15.
            for (i in intArrayOf(0, 1, 2, 3, 4, 5, 9, 10, 11, 12, 13, 14, 15)) {
                if (filled == n) break
                out[filled++] = chunk[i]
            }
        }
        return out
    }
}
