package app.mokuhyo.lexicon

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 verification of lexicon packages against the public keys shipped in the app (BRIEF_PHASE8 C-04). Uses the
 * JDK's own Ed25519 (no extra dependency).
 */
object LexiconVerifier {
    /** DER prefix that turns a raw 32-byte Ed25519 public key into an X.509 SubjectPublicKeyInfo. */
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    fun verify(parsed: LexiconPackage.Parsed, trusted: List<TrustedKey>): Publisher {
        if (!parsed.termsIntact) return Publisher.Invalid("the terms don't match the manifest (damaged or altered)")
        val sig = parsed.pkg.signature ?: return Publisher.Unsigned
        if (sig.alg != "ed25519") return Publisher.Invalid("unsupported signature algorithm ${sig.alg}")
        val key = trusted.firstOrNull { it.keyId == sig.keyId }
            ?: return Publisher.Invalid("signed with an unknown key (${sig.keyId}); not a publisher this app trusts")
        return runCatching {
            val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + Base64.getDecoder().decode(key.publicKey)))
            val verifier = Signature.getInstance("Ed25519").apply { initVerify(pub); update(parsed.signedBytes) }
            if (verifier.verify(Base64.getDecoder().decode(sig.value))) Publisher.Verified(key.publisher, key.keyId)
            else Publisher.Invalid("the signature does not match the package (altered after signing)")
        }.getOrElse { Publisher.Invalid("couldn't check the signature: ${it.message}") }
    }
}
