package app.tsumugi.server

import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorAssertionResponse
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.AuthenticatorAttestationResponse
import com.webauthn4j.data.PublicKeyCredential
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.EmulatorUtil
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasskeyTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun decode(s: String) = Base64.getUrlDecoder().decode(s)

    /** A software authenticator acting as the browser/OS at the configured origin. */
    private val platform = ClientPlatform(Origin.create("http://localhost:8080"), WebAuthnAuthenticatorAdaptor(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR))

    private fun registrationJson(cred: PublicKeyCredential<AuthenticatorAttestationResponse, *>) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(b64.encodeToString(cred.rawId)),
            "rawId" to JsonPrimitive(b64.encodeToString(cred.rawId)),
            "type" to JsonPrimitive("public-key"),
            "response" to JsonObject(
                mapOf(
                    "clientDataJSON" to JsonPrimitive(b64.encodeToString(cred.response!!.clientDataJSON)),
                    "attestationObject" to JsonPrimitive(b64.encodeToString(cred.response!!.attestationObject)),
                ),
            ),
            "clientExtensionResults" to JsonObject(emptyMap()),
        ),
    )

    private fun assertionJson(cred: PublicKeyCredential<AuthenticatorAssertionResponse, *>) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(b64.encodeToString(cred.rawId)),
            "rawId" to JsonPrimitive(b64.encodeToString(cred.rawId)),
            "type" to JsonPrimitive("public-key"),
            "response" to JsonObject(
                buildMap {
                    put("clientDataJSON", JsonPrimitive(b64.encodeToString(cred.response!!.clientDataJSON)))
                    put("authenticatorData", JsonPrimitive(b64.encodeToString(cred.response!!.authenticatorData)))
                    put("signature", JsonPrimitive(b64.encodeToString(cred.response!!.signature)))
                    cred.response!!.userHandle?.let { put("userHandle", JsonPrimitive(b64.encodeToString(it))) }
                },
            ),
            "clientExtensionResults" to JsonObject(emptyMap()),
        ),
    )

    @Test
    fun registerThenSignInWithPasskey() = serverTest { client ->
        val t = client.signUp("passkey@example.com")

        val options: PasskeyRegisterOptions = client.post("/v1/auth/passkey/register/options") { bearerAuth(t.accessToken) }.body()
        assertEquals("localhost", options.rp.id)
        assertEquals("passkey@example.com", options.user.name)
        assertTrue(options.pubKeyCredParams.any { it.alg == -7 }, "ES256 offered")

        val created = platform.create(
            PublicKeyCredentialCreationOptions(
                PublicKeyCredentialRpEntity(options.rp.id, options.rp.name),
                PublicKeyCredentialUserEntity(decode(options.user.id), options.user.name, options.user.displayName),
                DefaultChallenge(decode(options.challenge)),
                listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                options.timeout,
                emptyList(),
                AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.PREFERRED, UserVerificationRequirement.PREFERRED),
                AttestationConveyancePreference.NONE,
                null,
            ),
        )
        val verify = client.postJson("/v1/auth/passkey/register/verify", PasskeyRegisterVerify(options.challengeId, registrationJson(created)), t.accessToken)
        assertEquals(204, verify.status.value)
        // Challenges are single-use.
        assertEquals(400, client.postJson("/v1/auth/passkey/register/verify", PasskeyRegisterVerify(options.challengeId, registrationJson(created)), t.accessToken).status.value)

        val login: PasskeyLoginOptions = client.postJson("/v1/auth/passkey/login/options", PasskeyLoginOptionsRequest("passkey@example.com")).body()
        assertEquals(listOf(b64.encodeToString(created.rawId)), login.allowCredentials.map { it.id })
        val assertion = platform.get(
            PublicKeyCredentialRequestOptions(
                DefaultChallenge(decode(login.challenge)), login.timeout, login.rpId,
                listOf(PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, created.rawId, null)),
                UserVerificationRequirement.PREFERRED, null,
            ),
        )
        val tokens: TokenResponse = client.postJson(
            "/v1/auth/passkey/login/verify", PasskeyLoginVerify(login.challengeId, assertionJson(assertion), "laptop", "web"),
        ).body()
        val account: Account = client.getAuth("/v1/account", tokens.accessToken).body()
        assertEquals("passkey@example.com", account.email)
    }

    @Test
    fun rejectsBadCredentials() = serverTest { client ->
        val t = client.signUp("nopasskey@example.com")
        val options: PasskeyRegisterOptions = client.post("/v1/auth/passkey/register/options") { bearerAuth(t.accessToken) }.body()
        val garbage = JsonObject(mapOf("id" to JsonPrimitive("abc"), "type" to JsonPrimitive("public-key")))
        assertEquals(400, client.postJson("/v1/auth/passkey/register/verify", PasskeyRegisterVerify(options.challengeId, garbage), t.accessToken).status.value)

        val login: PasskeyLoginOptions = client.postJson("/v1/auth/passkey/login/options", PasskeyLoginOptionsRequest()).body()
        assertTrue(login.allowCredentials.isEmpty(), "no email → discoverable credentials")
        assertEquals(401, client.postJson("/v1/auth/passkey/login/verify", PasskeyLoginVerify(login.challengeId, garbage, "x", "web")).status.value)
        assertEquals(400, client.postJson("/v1/auth/passkey/login/verify", PasskeyLoginVerify("no-such-challenge", garbage, "x", "web")).status.value)
        assertEquals(401, client.post("/v1/auth/passkey/register/options").status.value, "registering needs a signed-in account")
    }
}
