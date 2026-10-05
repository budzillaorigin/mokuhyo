package app.mokuhyo.net

import app.mokuhyo.update.UpdateChecker
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** BRIEF_PHASE8 N-11 gate: with --no-network every client made by mokuhyoHttpClient fails closed; the engine is never reached. */
class NoNetworkTest {
    @AfterTest
    fun reset() {
        NetworkPolicy.disabled = false
    }

    @Test
    fun requestsFailClosed() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("ok") }
        val client = mokuhyoHttpClient(engine, NetTimeouts.API)
        client.get("https://example.org/a")
        assertEquals(1, calls)
        NetworkPolicy.disabled = true
        assertFailsWith<NetworkDisabledException> { client.get("https://example.org/b") }
        assertEquals(1, calls, "the engine was never reached")
    }

    @Test
    fun updateCheckReportsFailureNotACrash() = runTest {
        var calls = 0
        NetworkPolicy.disabled = true
        val r = UpdateChecker(MockEngine { calls++; respond("[]") }).check("0.1.0")
        assertIs<app.mokuhyo.update.UpdateResult.Failed>(r)
        assertEquals(0, calls)
    }
}
