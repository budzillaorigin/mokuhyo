package app.mokuhyo.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** BRIEF_PHASE8 N-00b: a remote Ollama URL is accepted only on the local network. */
class LanUrlTest {
    @Test
    fun localNetworkAddressesOnly() {
        assertEquals("http://192.168.1.46:11434", LanUrl.check("http://192.168.1.46:11434/v1/").baseUrl)
        assertEquals("http://gpu.local:11434", LanUrl.check("http://GPU.local").baseUrl)
        assertEquals("http://10.0.0.5:8080", LanUrl.check("http://10.0.0.5:8080").baseUrl)
        assertEquals("http://[fe80::1]:11434", LanUrl.check("http://[fe80::1]:11434").baseUrl)
        assertNotNull(LanUrl.check("http://172.32.0.1:11434").problem, "172.32 is public")
        assertNotNull(LanUrl.check("https://api.example.com").problem)
        assertNotNull(LanUrl.check("http://8.8.8.8:11434").problem)
        assertNotNull(LanUrl.check("192.168.1.46:11434").problem, "scheme required")
        assertNull(LanUrl.check("http://127.0.0.1:11434").problem)
    }
}
