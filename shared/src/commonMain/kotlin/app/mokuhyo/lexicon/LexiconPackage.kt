package app.mokuhyo.lexicon

import app.mokuhyo.ai.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A lexicon update package (BRIEF_PHASE8 C-04, docs/LEXICON_FORMAT.md): `lexicon-<lang>-<domain>-<version>.json`
 * with the full term set of one track for one language, a manifest, the attribution and an Ed25519 signature over
 * the canonical JSON of everything but the signature. Built and signed by tools/release/lexicon.py.
 */
@Serializable
data class LexiconPackage(
    val format: String,
    val manifest: Manifest,
    val attribution: String = "",
    val terms: List<TrackTerm>,
    val signature: Signature? = null,
) {
    @Serializable
    data class Manifest(
        val id: String,
        val lang: String,
        val domain: String,
        val version: String,
        val created: String,
        val publisher: String,
        val sha256: String,
        val terms: Int = 0,
        val previousVersion: String? = null,
    )

    @Serializable
    data class Signature(val alg: String, val keyId: String, val value: String)

    companion object {
        const val FORMAT = "mokuhyo-lexicon/1"
        val json = Json { ignoreUnknownKeys = true }

        /** Parses a package file; throws IllegalArgumentException with a learner-readable message. */
        fun parse(text: String): Parsed {
            val element = runCatching { json.parseToJsonElement(text) }.getOrElse { throw IllegalArgumentException("not a JSON file") }
            val obj = element as? JsonObject ?: throw IllegalArgumentException("not a lexicon package")
            val format = (obj["format"] as? JsonPrimitive)?.content
            require(format == FORMAT) { if (format == null) "not a lexicon package" else "package format $format is not supported by this version" }
            val pkg = runCatching { json.decodeFromJsonElement(serializer(), obj) }.getOrElse { throw IllegalArgumentException("damaged lexicon package: ${it.message}") }
            val termsHash = Sha256.hex(CanonicalJson.encode(obj["terms"] ?: JsonArray(emptyList())).encodeToByteArray())
            val signed = CanonicalJson.encode(JsonObject(obj.filterKeys { it != "signature" })).encodeToByteArray()
            return Parsed(pkg, termsHash == pkg.manifest.sha256, signed)
        }
    }

    /** [termsIntact]: the terms hash to the manifest's sha256. [signedBytes]: what the signature covers. */
    class Parsed(val pkg: LexiconPackage, val termsIntact: Boolean, val signedBytes: ByteArray)
}

/** What the importer reports about a package's publisher. */
sealed interface Publisher {
    data class Verified(val name: String, val keyId: String) : Publisher
    data object Unsigned : Publisher
    data class Invalid(val reason: String) : Publisher
}

/** A public key the app trusts (tools/release/keys/lexicon-ed25519.pub.json). */
@Serializable
data class TrustedKey(val keyId: String, val alg: String = "ed25519", val publisher: String, val publicKey: String)

@Serializable
data class TrustedKeys(val keys: List<TrustedKey> = emptyList())

/** Added, changed and removed terms between two term sets, matched by term id. */
data class LexiconDelta(val added: List<TrackTerm>, val changed: List<TrackTerm>, val removed: List<TrackTerm>) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty()

    companion object {
        fun between(before: List<TrackTerm>, after: List<TrackTerm>): LexiconDelta {
            val old = before.associateBy { it.id }
            val new = after.associateBy { it.id }
            return LexiconDelta(
                added = after.filter { it.id !in old },
                changed = after.filter { t -> old[t.id]?.let { hash(it) != hash(t) } == true },
                removed = before.filter { it.id !in new },
            )
        }

        fun hash(t: TrackTerm): String = Sha256.hex(CanonicalJson.encode(LexiconPackage.json.encodeToJsonElement(TrackTerm.serializer(), t)).encodeToByteArray())
    }
}

/** Dotted numeric versions ("1.10.0" > "1.9.2"); non-numeric parts compare as 0. */
object SemVer {
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/**
 * Canonical JSON (must match tools/release/lexicon.py `canonical`): object keys sorted by code point, no
 * whitespace, strings escaped like Python's json.dumps(ensure_ascii=False) — `\"`, `\\`, `\n`, `\r`, `\t`, `\b`,
 * `\f`, other control characters as `\u00xx` — numbers as written (packages carry integers only).
 */
object CanonicalJson {
    fun encode(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sortedWith(::codePointCompare).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(k, out)
                    out.append(':')
                    write(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, x -> if (i > 0) out.append(','); write(x, out) }
                out.append(']')
            }
            is JsonPrimitive -> if (e.isString) string(e.content, out) else out.append(e.content)
        }
    }

    /** Python sorts str keys by code point; Kotlin's String.compareTo compares UTF-16 units, which differs above U+FFFF. */
    private fun codePointCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i)
            val cb = codePointAt(b, j)
            if (ca != cb) return ca.compareTo(cb)
            i += if (ca > 0xFFFF) 2 else 1
            j += if (cb > 0xFFFF) 2 else 1
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
        }
        return c.code
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch.code < 0x20) out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        out.append('"')
    }
}

/** Parses the trusted key file shipped with the app. */
fun parseTrustedKeys(text: String): List<TrustedKey> =
    LexiconPackage.json.decodeFromJsonElement(TrustedKeys.serializer(), LexiconPackage.json.parseToJsonElement(text).jsonObject).keys
