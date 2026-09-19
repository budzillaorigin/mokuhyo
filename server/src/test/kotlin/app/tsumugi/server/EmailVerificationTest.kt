package app.tsumugi.server

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Phase 14 (BRIEF_V2 §8, D-310…D-312): a confirmed email is required before an account can sync. */
class EmailVerificationTest {

    private suspend fun io.ktor.client.HttpClient.push(t: TokenResponse) =
        postJson("/v1/sync/push", PushRequest(listOf(reviewChange("r1", 1_000, t.deviceId))), t.accessToken)

    private suspend fun io.ktor.client.HttpClient.resend(t: TokenResponse) =
        post("/v1/auth/verify/resend") { bearerAuth(t.accessToken) }

    @Test
    fun unverifiedAccountCannotSyncAndGetsAClearErrorCode() = serverTest { client ->
        val t = client.signUp("unverified@example.com", verify = false)

        val push = client.push(t)
        assertEquals(403, push.status.value)
        assertEquals(ErrorCodes.EMAIL_UNVERIFIED, push.body<ErrorBody>().code)
        val pull = client.getAuth("/v1/sync/pull?since=0", t.accessToken)
        assertEquals(403, pull.status.value)
        assertEquals(ErrorCodes.EMAIL_UNVERIFIED, pull.body<ErrorBody>().code)
        assertEquals(403, client.put("/v1/blobs/rec-1") { bearerAuth(t.accessToken); setBody(ByteArray(3)) }.status.value, "blobs too")
        assertEquals(403, client.putJson("/v1/devices/${t.deviceId}/packs", PacksRequest(), t.accessToken).status.value, "packs too")

        // Signing in and reading the account still work, so the app can explain what's missing.
        val account: Account = client.getAuth("/v1/account", t.accessToken).body()
        assertFalse(account.emailVerified)
        assertTrue(account.emailVerificationRequired)
    }

    @Test
    fun verifyingTheEmailUnlocksSync() = serverTest { client ->
        val t = client.signUp("later@example.com", verify = false)
        assertEquals(403, client.push(t).status.value)

        val confirm = client.get(RecordingMailer.lastPath("later@example.com"))
        assertEquals(200, confirm.status.value)
        assertTrue("confirmed" in confirm.bodyAsText())
        assertEquals(404, client.get(RecordingMailer.lastPath("later@example.com")).status.value, "links are single-use")

        assertEquals(200, client.push(t).status.value, "the same access token works once the email is confirmed")
        assertTrue(client.getAuth("/v1/account", t.accessToken).body<Account>().emailVerified)
    }

    @Test
    fun resendIsRateLimited() = serverTest { client ->
        val t = client.signUp("resend@example.com", verify = false)
        // Registration just sent one, so an immediate resend is refused.
        val tooSoon = client.resend(t)
        assertEquals(429, tooSoon.status.value)
        assertEquals(ErrorCodes.RESEND_TOO_SOON, tooSoon.body<ErrorBody>().code)
        assertEquals(1, RecordingMailer.sent.count { it.first == "resend@example.com" }, "no second email went out")
        assertEquals(401, client.post("/v1/auth/verify/resend").status.value, "only a signed-in account can ask")
    }

    @Test
    fun resendReplacesTheLink() = serverTest(testConfig().copy(verifyResendInterval = Duration.ZERO)) { client ->
        val t = client.signUp("again@example.com", verify = false)
        val first = RecordingMailer.lastPath("again@example.com")
        assertEquals(204, client.resend(t).status.value)
        val second = RecordingMailer.lastPath("again@example.com")
        assertNotEquals(first, second)
        assertEquals(404, client.get(first).status.value, "the old link stops working")
        assertEquals(200, client.get(second).status.value)
        assertEquals(200, client.push(t).status.value)

        val done = client.resend(t)
        assertEquals(409, done.status.value)
        assertEquals(ErrorCodes.ALREADY_VERIFIED, done.body<ErrorBody>().code)
    }

    @Test
    fun expiredLinksAreRefused() = serverTest(testConfig().copy(verifyTokenTtl = (-1).minutes)) { client ->
        // A negative lifetime makes every link already expired, without sleeping in the test.
        val t = client.signUp("expired@example.com", verify = false)
        val expired = client.get(RecordingMailer.lastPath("expired@example.com"))
        assertEquals(410, expired.status.value)
        assertTrue("expired" in expired.bodyAsText())
        assertEquals(403, client.push(t).status.value, "still unverified")
    }

    @Test
    fun devModeDoesNotRequireVerification() = serverTest(testConfig().copy(requireEmailVerification = false)) { client ->
        val t = client.signUp("dev@example.com", verify = false)
        assertEquals(200, client.push(t).status.value)
        val account: Account = client.getAuth("/v1/account", t.accessToken).body()
        assertFalse(account.emailVerified)
        assertFalse(account.emailVerificationRequired)
        assertTrue(RecordingMailer.sent.any { it.first == "dev@example.com" }, "the link is still sent (or logged)")
    }

    @Test
    fun verificationIsRequiredUnlessConfiguredOff() {
        val secret = mapOf("TSUMUGI_JWT_SECRET" to "s".repeat(40))
        assertTrue(Config.fromEnv(secret).requireEmailVerification, "production default")
        assertFalse(Config.fromEnv(secret + ("TSUMUGI_REQUIRE_EMAIL_VERIFICATION" to "false")).requireEmailVerification)
        assertEquals(90, Config.fromEnv(secret + ("TSUMUGI_VERIFY_RESEND_SECONDS" to "90")).verifyResendInterval.inWholeSeconds)
        assertEquals(24, Config.fromEnv(secret + ("TSUMUGI_VERIFY_TOKEN_TTL_HOURS" to "24")).verifyTokenTtl.inWholeHours)
    }
}
