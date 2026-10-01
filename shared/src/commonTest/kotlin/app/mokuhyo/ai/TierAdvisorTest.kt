package app.mokuhyo.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TierAdvisorTest {
    private val gb = 1024L * 1024 * 1024

    private fun model(tier: String, role: String = "default", bytes: Long = 5 * gb) = ModelInfo(
        id = "m-$tier-$role", name = "M $tier", kind = ModelKind.LLM, tier = tier, role = role, minRamGb = 8,
        license = "MIT", files = listOf(ModelFile("f.gguf", "https://x", "00", bytes)),
    )

    private val models = listOf(model("A", bytes = 2 * gb), model("B"), model("B", "alternate"), model("C", bytes = 7 * gb), model("D", bytes = 14 * gb))

    private fun hw(ramGb: Int, gpus: List<GpuInfo> = emptyList(), freeGb: Long = 500) =
        HardwareInfo("test", "x86_64", ramGb * gb, 8, freeGb * gb, gpus)

    @Test
    fun basicLaptopGetsTierA() {
        val options = TierAdvisor.options(hw(8), models)
        assertEquals(Tier.A, options.single { it.recommended }.tier)
        val b = options.single { it.tier == Tier.B }
        assertFalse(b.runnable)
        assertTrue(b.reason!!.contains("16 GB of RAM"), b.reason)
        assertTrue(b.reason!!.contains("no usable graphics card"), b.reason)
    }

    @Test
    fun discreteGpuUnlocksTiers() {
        val rtx = GpuInfo("RTX 4070", "Vulkan", 12 * gb)
        assertEquals(Tier.C, TierAdvisor.recommended(hw(16, listOf(rtx)), models))
        assertEquals(Tier.D, TierAdvisor.recommended(hw(32, listOf(GpuInfo("RTX 5090", "Vulkan", 32 * gb))), models))
        assertEquals(Tier.B, TierAdvisor.recommended(hw(8, listOf(GpuInfo("GTX 1660", "Vulkan", 6 * gb))), models))
    }

    @Test
    fun appleSiliconUsesUnifiedMemory() {
        fun mac(ram: Int) = hw(ram, listOf(GpuInfo("Apple M4", "Metal", ram * gb, unifiedMemory = true)))
        assertEquals(Tier.B, TierAdvisor.recommended(mac(16), models))
        assertEquals(Tier.C, TierAdvisor.recommended(mac(24), models))
        assertEquals(Tier.C, TierAdvisor.recommended(mac(48), models))
        assertEquals(Tier.D, TierAdvisor.recommended(mac(64), models))
    }

    @Test
    fun lowDiskBlocksATier() {
        val reason = TierAdvisor.reasonBlocked(Tier.A, hw(16, freeGb = 2), models)
        assertNotNull(reason)
        assertTrue(reason.contains("free disk space"), reason)
        assertNull(TierAdvisor.recommended(hw(16, freeGb = 2), models))
    }

    @Test
    fun defaultModelPrefersTheDefaultRole() {
        assertEquals("m-B-default", TierAdvisor.defaultModel(Tier.B, models)!!.id)
    }

    @Test
    fun ollamaModelsAreScreenedByPolicy() {
        val json = """{"models":[
            {"name":"mistral-nemo:12b","details":{"family":"llama","families":["llama"],"parameter_size":"12.2B"}},
            {"name":"qwen2.5:7b","details":{"family":"qwen2","parameter_size":"7.6B"}},
            {"name":"deepseek-r1:8b","details":{"family":"qwen2","families":["qwen2"]}},
            {"name":"my-distill:7b","details":{"family":"qwen2"}},
            {"name":"llama3.1:8b","details":{"family":"llama"}},
            {"name":"gemma3:27b","details":{"family":"gemma3"}},
            {"name":"phi4-mini:3.8b","details":{"family":"phi3"}}
        ]}"""
        val byName = OllamaDetector.parse(json).associateBy { it.name }
        assertNull(byName.getValue("mistral-nemo:12b").excludedReason)
        assertNull(byName.getValue("phi4-mini:3.8b").excludedReason)
        assertTrue(byName.getValue("qwen2.5:7b").excludedReason!!.contains("PRC"))
        assertTrue(byName.getValue("deepseek-r1:8b").excludedReason!!.contains("PRC"))
        assertTrue(byName.getValue("my-distill:7b").excludedReason!!.contains("PRC"))
        assertTrue(byName.getValue("llama3.1:8b").excludedReason!!.contains("not permissive"))
        assertTrue(byName.getValue("gemma3:27b").excludedReason!!.contains("not permissive"))
    }

    @Test
    fun shippedManifestObeysPolicy() {
        // Rule 13 at the data level: nothing in the catalog may be excluded by the policy the app applies to Ollama.
        val text = ManifestFixture.read()
        val manifest = ModelManager.parseManifest(text)
        assertTrue(manifest.models.isNotEmpty())
        manifest.models.forEach { m ->
            assertNull(ModelPolicy.exclusion(m.name, listOf(m.family)), m.id)
            assertNotNull(m.provenance, m.id)
        }
        Tier.entries.forEach { assertNotNull(TierAdvisor.defaultModel(it, manifest.models), it.name) }
    }
}
