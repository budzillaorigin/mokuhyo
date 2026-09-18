package app.tsumugi.server

import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest {

    @Test
    fun healthIsPublic() = serverTest { client ->
        val health: Health = client.get("/v1/health").body()
        assertEquals("ok", health.status)
    }

    @Test
    fun registerLoginAndAccount() = serverTest { client ->
        val tokens = client.signUp("Aki@Example.com")
        val account: Account = client.getAuth("/v1/account", tokens.accessToken).body()
        assertEquals("aki@example.com", account.email, "emails are normalized")
        assertEquals(false, account.e2eEnabled)
        assertTrue(RecordingMailer.sent.any { it.first == "aki@example.com" && "/v1/auth/verify?token=" in it.second })

        assertEquals(409, client.postJson("/v1/auth/register", RegisterRequest("aki@example.com", "another password!")).status.value)
        assertEquals(400, client.postJson("/v1/auth/register", RegisterRequest("bob@example.com", "short")).status.value)
        assertEquals(401, client.postJson("/v1/auth/login", LoginRequest("aki@example.com", "wrong password!!", "x", "ios")).status.value)
        assertEquals(401, client.postJson("/v1/auth/login", LoginRequest("nobody@example.com", "whatever12345", "x", "ios")).status.value)
    }

    @Test
    fun emailVerificationLink() = serverTest { client ->
        client.signUp("verify@example.com")
        val link = RecordingMailer.sent.last { it.first == "verify@example.com" }.second
        assertEquals(200, client.get(link.substringAfter("8080")).status.value)
        assertEquals(404, client.get(link.substringAfter("8080")).status.value, "links are single-use")
    }

    @Test
    fun authIsRequired() = serverTest { client ->
        assertEquals(401, client.get("/v1/account").status.value)
        assertEquals(401, client.get("/v1/sync/pull?since=0").status.value)
        assertEquals(401, client.getAuth("/v1/account", "not-a-jwt").status.value)
    }

    @Test
    fun refreshRotatesAndReuseRevokesTheFamily() = serverTest { client ->
        val first = client.signUp("rotate@example.com")
        val second: TokenResponse = client.postJson("/v1/auth/refresh", RefreshRequest(first.refreshToken)).body()
        assertNotEquals(first.refreshToken, second.refreshToken)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(200, client.getAuth("/v1/account", second.accessToken).status.value)

        // Replaying the old (already rotated) token looks like theft: it fails and kills the whole family.
        assertEquals(401, client.postJson("/v1/auth/refresh", RefreshRequest(first.refreshToken)).status.value)
        assertEquals(401, client.postJson("/v1/auth/refresh", RefreshRequest(second.refreshToken)).status.value)
    }

    @Test
    fun logoutRevokes() = serverTest { client ->
        val tokens = client.signUp("logout@example.com")
        assertEquals(204, client.postJson("/v1/auth/logout", RefreshRequest(tokens.refreshToken)).status.value)
        assertEquals(401, client.postJson("/v1/auth/refresh", RefreshRequest(tokens.refreshToken)).status.value)
    }

    @Test
    fun devicesAndPacks() = serverTest { client ->
        val phone = client.signUp("devices@example.com", device = "phone")
        val tablet = client.login("devices@example.com", "tablet")
        val packs = PacksRequest(mapOf("dictionary.sqlite" to "1-abc"))
        assertEquals(204, client.putJson("/v1/devices/${phone.deviceId}/packs", packs, phone.accessToken).status.value)
        val devices: List<Device> = client.getAuth("/v1/devices", phone.accessToken).body()
        assertEquals(listOf("phone", "tablet"), devices.map { it.name })
        assertEquals(mapOf("dictionary.sqlite" to "1-abc"), devices.first().packs)

        assertEquals(204, client.deleteAuth("/v1/devices/${tablet.deviceId}", phone.accessToken).status.value)
        assertEquals(401, client.postJson("/v1/auth/refresh", RefreshRequest(tablet.refreshToken)).status.value, "removed device is signed out")
    }
}
