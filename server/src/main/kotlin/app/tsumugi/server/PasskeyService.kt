package app.tsumugi.server

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import java.util.UUID

@Serializable data class RpInfo(val id: String, val name: String)
@Serializable data class UserInfo(val id: String, val name: String, val displayName: String)
@Serializable data class CredParam(val type: String = "public-key", val alg: Int)
@Serializable data class CredentialDescriptor(val type: String = "public-key", val id: String)
@Serializable data class AuthenticatorSelection(val residentKey: String = "preferred", val userVerification: String = "preferred")

/** `PublicKeyCredentialCreationOptions` (base64url binary fields) plus the id of the stored challenge. */
@Serializable
data class PasskeyRegisterOptions(
    val challengeId: String,
    val challenge: String,
    val rp: RpInfo,
    val user: UserInfo,
    val pubKeyCredParams: List<CredParam>,
    val timeout: Long,
    val attestation: String = "none",
    val excludeCredentials: List<CredentialDescriptor>,
    val authenticatorSelection: AuthenticatorSelection = AuthenticatorSelection(),
)

/** `credential` is the standard `PublicKeyCredential.toJSON()` shape (base64url fields). */
@Serializable data class PasskeyRegisterVerify(val challengeId: String, val credential: JsonObject)

@Serializable data class PasskeyLoginOptionsRequest(val email: String? = null)

@Serializable
data class PasskeyLoginOptions(
    val challengeId: String,
    val challenge: String,
    val rpId: String,
    val timeout: Long,
    val userVerification: String = "preferred",
    val allowCredentials: List<CredentialDescriptor>,
)

@Serializable
data class PasskeyLoginVerify(val challengeId: String, val credential: JsonObject, val deviceName: String, val platform: String)

/** WebAuthn passkeys (BRIEF §8.1) with webauthn4j: attestation "none", ES256 / RS256 / EdDSA credentials. */
class PasskeyService(private val db: Db, private val config: Config, private val auth: AuthService) {
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val converter = AttestedCredentialDataConverter(ObjectConverter())
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val origins get() = config.rpOrigins.map { Origin.create(it) }.toSet()

    suspend fun registerOptions(userId: String): PasskeyRegisterOptions {
        val challenge = DefaultChallenge()
        val challengeId = storeChallenge(userId, challenge, KIND_REGISTER)
        val (email, name) = db.tx {
            queryOne("SELECT email, display_name FROM users WHERE id = ?", userId) { it.getString(1) to it.getString(2) }
        } ?: unauthorized()
        val existing = db.tx { query("SELECT credential_id FROM passkeys WHERE user_id = ?", userId) { it.getString(1) } }
        return PasskeyRegisterOptions(
            challengeId = challengeId,
            challenge = b64.encodeToString(challenge.value),
            rp = RpInfo(config.rpId, config.rpName),
            user = UserInfo(b64.encodeToString(userId.toByteArray()), email, name ?: email),
            pubKeyCredParams = ALGORITHMS.map { CredParam(alg = it.value.toInt()) },
            timeout = TIMEOUT_MS,
            excludeCredentials = existing.map { CredentialDescriptor(id = it) },
        )
    }

    suspend fun registerVerify(userId: String, req: PasskeyRegisterVerify) {
        val challenge = takeChallenge(req.challengeId, KIND_REGISTER, userId)
        val params = RegistrationParameters(
            ServerProperty(origins, config.rpId, challenge),
            ALGORITHMS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) },
            false,
            true,
        )
        val data = try {
            manager.verifyRegistrationResponseJSON(req.credential.toString(), params)
        } catch (e: VerificationException) {
            badRequest("passkey registration failed: ${e.message}")
        } catch (e: RuntimeException) {
            badRequest("malformed passkey credential: ${e.message}")
        }
        val authData = data.attestationObject?.authenticatorData ?: badRequest("missing authenticator data")
        val attested = authData.attestedCredentialData ?: badRequest("missing attested credential data")
        db.tx {
            update(
                "INSERT INTO passkeys(credential_id, user_id, credential, sign_count, created_at) VALUES (?,?,?,?,?)",
                b64.encodeToString(attested.credentialId), userId,
                Base64.getEncoder().encodeToString(converter.convert(attested)), authData.signCount, now(),
            )
        }
    }

    suspend fun loginOptions(req: PasskeyLoginOptionsRequest): PasskeyLoginOptions {
        val challenge = DefaultChallenge()
        val challengeId = storeChallenge(null, challenge, KIND_LOGIN)
        // With an email we list that account's credentials; without one the client uses a discoverable credential.
        val allowed = req.email?.let { email ->
            db.tx {
                query(
                    "SELECT p.credential_id FROM passkeys p JOIN users u ON u.id = p.user_id WHERE u.email = ?",
                    email.trim().lowercase(),
                ) { it.getString(1) }
            }
        }.orEmpty()
        return PasskeyLoginOptions(challengeId, b64.encodeToString(challenge.value), config.rpId, TIMEOUT_MS, allowCredentials = allowed.map { CredentialDescriptor(id = it) })
    }

    suspend fun loginVerify(req: PasskeyLoginVerify): TokenResponse {
        val challenge = takeChallenge(req.challengeId, KIND_LOGIN, null)
        val credentialId = (req.credential["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: badRequest("credential id missing")
        val stored = db.tx {
            queryOne("SELECT user_id, credential, sign_count FROM passkeys WHERE credential_id = ?", credentialId) {
                Triple(it.getString(1), it.getString(2), it.getLong(3))
            }
        } ?: unauthorized("unknown passkey")
        val attested = converter.convert(Base64.getDecoder().decode(stored.second))
        val record = CredentialRecordImpl(NoneAttestationStatement(), null, null, null, stored.third, attested, null, null, null, null)
        val params = AuthenticationParameters(ServerProperty(origins, config.rpId, challenge), record, null, false, true)
        val data = try {
            manager.verifyAuthenticationResponseJSON(req.credential.toString(), params)
        } catch (e: VerificationException) {
            unauthorized("passkey verification failed: ${e.message}")
        } catch (e: RuntimeException) {
            badRequest("malformed passkey assertion: ${e.message}")
        }
        val signCount = data.authenticatorData?.signCount ?: stored.third
        db.tx { update("UPDATE passkeys SET sign_count = ? WHERE credential_id = ?", signCount, credentialId) }
        val deviceId = auth.registerDevice(stored.first, req.deviceName, req.platform)
        return auth.issue(stored.first, deviceId, UUID.randomUUID().toString())
    }

    private suspend fun storeChallenge(userId: String?, challenge: DefaultChallenge, kind: String): String {
        val id = UUID.randomUUID().toString()
        db.tx {
            update("DELETE FROM webauthn_challenges WHERE expires_at < ?", now())
            update(
                "INSERT INTO webauthn_challenges(id, user_id, challenge, kind, expires_at) VALUES (?,?,?,?,?)",
                id, userId, b64.encodeToString(challenge.value), kind, now() + TIMEOUT_MS,
            )
        }
        return id
    }

    /** Challenges are single-use: taking one deletes it. */
    private suspend fun takeChallenge(id: String, kind: String, userId: String?): DefaultChallenge {
        val row = db.tx {
            val r = queryOne("SELECT user_id, challenge, kind, expires_at FROM webauthn_challenges WHERE id = ?", id) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getLong(4).toString())
            }
            update("DELETE FROM webauthn_challenges WHERE id = ?", id)
            r
        } ?: badRequest("unknown or used challenge")
        if (row[2] != kind || row[3].toLong() < now() || (userId != null && row[0] != userId)) badRequest("challenge expired or not yours")
        return DefaultChallenge(Base64.getUrlDecoder().decode(row[1]))
    }

    companion object {
        const val TIMEOUT_MS = 300_000L
        private const val KIND_REGISTER = "register"
        private const val KIND_LOGIN = "login"
        private val ALGORITHMS = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256)
    }
}
