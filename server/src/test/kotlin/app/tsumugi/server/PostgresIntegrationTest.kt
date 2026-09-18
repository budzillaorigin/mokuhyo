package app.tsumugi.server

import io.ktor.client.call.body
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same flows against real Postgres 16. Runs where Docker is available (CI); skipped elsewhere. */
class PostgresIntegrationTest {

    @Test
    fun syncOnPostgres() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false), "Docker not available")
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val url = "postgres://${pg.username}:${pg.password}@${pg.host}:${pg.firstMappedPort}/${pg.databaseName}"
            serverTest(testConfig(url)) { client ->
                val phone = client.signUp("pg@example.com", device = "phone")
                val tablet = client.login("pg@example.com", "tablet")
                client.patchJson("/v1/account", AccountPatch(leaderboardOptIn = true), phone.accessToken)
                client.postJson("/v1/sync/push", PushRequest(listOf(reviewChange("a", System.currentTimeMillis(), phone.deviceId))), phone.accessToken)
                client.postJson("/v1/sync/push", PushRequest(listOf(reviewChange("b", System.currentTimeMillis(), tablet.deviceId))), tablet.accessToken)
                client.postJson("/v1/sync/push", PushRequest(listOf(reviewChange("a", System.currentTimeMillis(), phone.deviceId))), phone.accessToken)
                val pulled: PullResponse = client.getAuth("/v1/sync/pull?since=0", tablet.accessToken).body()
                assertEquals(listOf("a", "b", "a"), pulled.changes.map { it.key }, "different updatedAt → a new change")
                assertEquals(listOf(1L, 2L, 3L), pulled.changes.map { it.seq })
                val board: List<LeaderboardEntry> = client.getAuth("/v1/leaderboard", phone.accessToken).body()
                assertEquals(2, board.single().reviews, "review facts dedupe by review id")
                val refreshed: TokenResponse = client.postJson("/v1/auth/refresh", RefreshRequest(phone.refreshToken)).body()
                assertEquals(phone.deviceId, refreshed.deviceId)
            }
        }
    }
}
