package app.mokuhyo.backup

import app.mokuhyo.backup.crypto.Argon2id
import app.mokuhyo.backup.crypto.XChaCha20Poly1305
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * The `.mokuhyo` backup bundle (BRIEF §8.2, docs/BUNDLE_FORMAT.md):
 *
 *     "MKHY" | u16 format version | u16 flags (bit 0 = encrypted) | u32 header length | header JSON (UTF-8) | payload
 *
 * The payload is a zip (manifest.json, db.sqlite, recordings/…, settings.json, packs.json). When encrypted, the zip is
 * split into 1 MiB chunks, each sealed with XChaCha20-Poly1305 under a key derived from the passphrase with
 * Argon2id; chunk i uses nonce = 16-byte random prefix ‖ u64 big-endian i and AAD = header bytes ‖ u64 i ‖ final flag,
 * so chunks can't be reordered, dropped or truncated. Each sealed chunk is preceded by its u32 length.
 */
object Bundle {
    const val MAGIC = "MKHY"
    const val FORMAT_VERSION = 1
    const val CHUNK = 1 shl 20

    @Serializable
    data class Header(
        val format: Int = FORMAT_VERSION,
        val created: String,
        val appVersion: String,
        val encrypted: Boolean,
        val kdf: Kdf? = null,
        val noncePrefix: String? = null,
    )

    /** Argon2id parameters; [salt] hex. Defaults: 64 MiB, 3 passes, 1 lane (OWASP's recommended minimum is lower). */
    @Serializable
    data class Kdf(val alg: String = "argon2id", val salt: String, val memoryKiB: Int = 65_536, val iterations: Int = 3, val parallelism: Int = 1)

    /** The zip's manifest.json. [checksums] are SHA-256 of every other zip entry. */
    @Serializable
    data class Manifest(
        val format: Int = FORMAT_VERSION,
        val appVersion: String,
        val created: String,
        val learnerId: String,
        val learnerName: String,
        val languages: List<String>,
        val schemaVersion: Long,
        val counts: Map<String, Long>,
        val checksums: Map<String, String>,
    )

    /** packs.json: which content, voice and model packs were installed (the importer offers to fetch missing ones). */
    @Serializable
    data class PacksList(val content: List<String>, val voices: List<String>, val models: List<String>)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encodeHeader(header: Header): ByteArray {
        val body = json.encodeToString(Header.serializer(), header).encodeToByteArray()
        val out = ByteArray(12 + body.size)
        MAGIC.encodeToByteArray().copyInto(out, 0)
        putU16(out, 4, FORMAT_VERSION)
        putU16(out, 6, if (header.encrypted) 1 else 0)
        putU32(out, 8, body.size)
        body.copyInto(out, 12)
        return out
    }

    /** Header and the offset where the payload starts; throws on a file that isn't a bundle. */
    fun decodeHeader(bytes: ByteArray): Pair<Header, Int> {
        require(bytes.size >= 12 && bytes.decodeToString(0, 4) == MAGIC) { "not a .mokuhyo bundle" }
        val version = getU16(bytes, 4)
        require(version <= FORMAT_VERSION) { "bundle format $version is newer than this app supports ($FORMAT_VERSION); update Mokuhyo" }
        val len = getU32(bytes, 8)
        require(12 + len <= bytes.size) { "truncated bundle header" }
        return json.decodeFromString(Header.serializer(), bytes.decodeToString(12, 12 + len)) to 12 + len
    }

    fun deriveKey(passphrase: String, kdf: Kdf): ByteArray =
        Argon2id.hash(passphrase.encodeToByteArray(), hex(kdf.salt), kdf.iterations, kdf.memoryKiB, kdf.parallelism, 32)

    fun newKdf(random: Random = Random.Default): Kdf = Kdf(salt = hex(random.nextBytes(16)))

    fun newNoncePrefix(random: Random = Random.Default): String = hex(random.nextBytes(16))

    /** Seals [chunk] number [index]; [final] marks the last chunk. */
    fun sealChunk(key: ByteArray, noncePrefix: ByteArray, headerBytes: ByteArray, index: Long, final: Boolean, chunk: ByteArray): ByteArray =
        XChaCha20Poly1305.seal(key, nonce(noncePrefix, index), chunk, aad(headerBytes, index, final))

    /** Opens chunk [index]; null when the passphrase is wrong or the data was tampered with. */
    fun openChunk(key: ByteArray, noncePrefix: ByteArray, headerBytes: ByteArray, index: Long, final: Boolean, sealed: ByteArray): ByteArray? =
        XChaCha20Poly1305.open(key, nonce(noncePrefix, index), sealed, aad(headerBytes, index, final))

    private fun nonce(prefix: ByteArray, index: Long): ByteArray = ByteArray(24).also { n ->
        prefix.copyInto(n, 0, 0, 16)
        for (i in 0 until 8) n[16 + i] = (index ushr (56 - 8 * i)).toByte()
    }

    private fun aad(header: ByteArray, index: Long, final: Boolean): ByteArray = ByteArray(header.size + 9).also { a ->
        header.copyInto(a)
        for (i in 0 until 8) a[header.size + i] = (index ushr (56 - 8 * i)).toByte()
        a[header.size + 8] = if (final) 1 else 0
    }

    fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun putU16(b: ByteArray, at: Int, v: Int) { b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte() }
    fun putU32(b: ByteArray, at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte() }
    fun getU16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    fun getU32(b: ByteArray, at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (b[at + i].toInt() and 0xFF) }
}
