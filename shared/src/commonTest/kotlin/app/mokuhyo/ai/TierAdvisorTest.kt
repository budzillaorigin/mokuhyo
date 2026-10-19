package app.mokuhyo.ai

import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.opi.Calibration
import app.mokuhyo.opi.OpiInterviewerTurn
import app.mokuhyo.opi.OpiPhase
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

class TimingAwareTierTest {
    private val gb = 1024L * 1024 * 1024

    private fun model(tier: String, bytes: Long) = ModelInfo(
        id = "m-$tier", name = "M $tier", kind = ModelKind.LLM, tier = tier, minRamGb = 8, contextSize = 8192,
        license = "MIT", files = listOf(ModelFile("f.gguf", "https://x", "00", bytes)),
    )

    private val models = listOf(model("A", 2_500_000_000), model("B", 5_600_000_000), model("C", 7_500_000_000), model("D", 14_300_000_000))
    private val cpu32 = HardwareInfo("test", "x86_64", 32 * gb, 8, 500 * gb, emptyList())

    private fun timing(id: String, sentenceMs: Long) = ModelTiming(id, sentenceMs / 3, 12.0, sentenceMs)

    @Test
    fun slowMeasuredTierIsNotRecommendedEvenThoughRamPermits() {
        val timings = mapOf("m-A" to timing("m-A", 1_400), "m-B" to timing("m-B", 3_100), "m-C" to timing("m-C", 6_800))
        val options = TierAdvisor.options(cpu32, models, timings)
        val c = options.single { it.tier == Tier.C }
        assertTrue(c.runnable, "32 GB of RAM permits Tier C")
        assertEquals(Tier.A, options.single { it.recommended }.tier)
        assertTrue(c.slower)
        assertTrue(c.explanation!!.startsWith("Slower — better for end-of-session grading"), c.explanation)
        assertTrue(options.single { it.tier == Tier.B }.slower)
    }

    @Test
    fun beforeAnyTimingACpuOnlyMachineIsCappedAtTierB() {
        val o = TierAdvisor.options(cpu32, models).single { it.recommended }
        assertEquals(Tier.B, o.tier)
        assertTrue(o.explanation!!.contains("not timed yet"), o.explanation)
        // With a capable GPU the memory fit still decides until a timing exists.
        assertEquals(Tier.D, TierAdvisor.recommended(HardwareInfo("t", "x86_64", 32 * gb, 8, 500 * gb, listOf(GpuInfo("RTX", "Vulkan", 24 * gb))), models))
    }

    @Test
    fun qualityFloorWinsOverSpeed() {
        val table = Calibration.Table(entries = listOf(
            Calibration.Entry("fa", "A", "m-A", n = 12, exact = 3, within1 = 6),
            Calibration.Entry("fa", "B", "m-B", n = 12, exact = 8, within1 = 11),
        ))
        val floor = QualityFloor.of("fa", table)
        assertEquals(Tier.B, floor.tier)
        val timings = mapOf("m-A" to timing("m-A", 900), "m-B" to timing("m-B", 3_000))
        val options = TierAdvisor.options(cpu32, models, timings, floor)
        assertEquals(Tier.B, options.single { it.recommended }.tier, "A is fast but below the floor")
        assertTrue(options.single { it.tier == Tier.A }.explanation!!.contains("quality floor"))
    }

    @Test
    fun fixtureCalibrationIsNotEvidence() {
        val table = Calibration.Table(entries = listOf(Calibration.Entry("es", "A", "m-A", n = 30, exact = 30, within1 = 30, fixture = true)))
        val floor = QualityFloor.of("es", table)
        assertFalse(floor.verified)
        assertTrue(floor.evidence.startsWith("unverified"))
    }

    @Test
    fun theRecommendationExplainsItself() {
        val timings = mapOf("m-A" to timing("m-A", 1_200), "m-B" to timing("m-B", 1_900))
        val rec = TierAdvisor.options(cpu32, models, timings, QualityFloor("es", null, "unverified: no data")).single { it.recommended }
        assertEquals(Tier.B, rec.tier)
        assertTrue(rec.explanation!!.contains("1.9 s measured"), rec.explanation)
        assertTrue(rec.explanation!!.contains("quality floor for es: unverified"), rec.explanation)
        assertEquals(1_900, rec.timing!!.firstSentenceMs)
    }

    @Test
    fun memoryIsReservedForSpeechAndTheKvCache() {
        // 6.4 GB of weights pass the 8 GB tier threshold on paper but leave no room for Whisper, the voice and the cache.
        val heavyA = listOf(model("A", 5_500_000_000))
        val reason = TierAdvisor.reasonBlocked(Tier.A, HardwareInfo("t", "x86_64", 8 * gb, 4, 500 * gb, emptyList()), heavyA)
        assertTrue(reason!!.contains("speech recognition and the voice running"), reason)
    }

    @Test
    fun timingsRoundTripThroughSettingsJson() {
        val all = mapOf("m-A" to timing("m-A", 1_234))
        assertEquals(all, ModelTiming.parseAll(ModelTiming.encodeAll(all)))
        assertEquals(emptyMap(), ModelTiming.parseAll("not json"))
    }

    @Test
    fun timingCheckMeasuresFirstTokenAndSpeedOnTheLiveSchema() = kotlinx.coroutines.test.runTest {
        val model = object : LanguageModel {
            override val id = "stream"
            override val isLocal = true
            var schemaSeen = false
            override suspend fun complete(request: CompletionRequest): CompletionResult {
                schemaSeen = schemaSeen || request.jsonSchema != null
                listOf("{\"utt", "erance", "\":\"¿", "Dónde", " trabaja", "?\"}").forEach { request.onToken?.invoke(it) }
                return CompletionResult("""{"utterance":"¿Dónde trabaja?"}""", "fake", null)
            }
        }
        val t = ModelTimingCheck.measure(model, runs = 3)
        assertEquals("stream", t.modelId)
        assertTrue(model.schemaSeen, "the live interviewer schema is part of the prompt")
        assertTrue(t.firstSentenceMs >= t.firstTokenMs)
    }
}

class PinnedGatewayTest {
    @Test
    fun aSessionKeepsItsModelWhenSettingsChange() = kotlinx.coroutines.test.runTest {
        var current: LanguageModel = FakeModel("""{"utterance":"¿Y usted?","english":"And you?"}""").let { m -> object : LanguageModel by m { override val id = "first" } }
        val app = AiGateway({ current })
        val session = app.pinned()
        current = object : LanguageModel {
            override val id = "second"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult = error("swapped mid-session")
        }
        assertEquals("first", session.modelId())
        assertEquals("second", app.modelId())
        val r = session.run(OpiInterviewerTurn(), OpiInterviewerTurn.Input("es", "usted", OpiPhase.WARMUP, IlrLevel.L1))
        assertTrue(r is AiResult.Ok, "the pinned model answered: $r")
    }
}
