package app.mokuhyo.ai

/** A GPU the inference engine can use. [unifiedMemory] = Apple Silicon (GPU shares system RAM). */
data class GpuInfo(val name: String, val backend: String, val memoryBytes: Long, val unifiedMemory: Boolean = false)

/** What the hardware probe found (BRIEF §6.1): RAM, GPU/VRAM, CPU cores, free disk. */
data class HardwareInfo(
    val os: String,
    val arch: String,
    val ramBytes: Long,
    val cpuCores: Int,
    val freeDiskBytes: Long?,
    val gpus: List<GpuInfo>,
) {
    val ramGb: Double get() = ramBytes / GB
    val appleSilicon: Boolean get() = gpus.any { it.unifiedMemory }

    /** Largest dedicated (non-unified) GPU memory, in GB; 0 when there's none. */
    val vramGb: Double get() = gpus.filter { !it.unifiedMemory }.maxOfOrNull { it.memoryBytes / GB } ?: 0.0

    companion object {
        const val GB = 1024.0 * 1024 * 1024
    }
}

/** One row of the tier picker. */
data class TierOption(
    val tier: Tier,
    val model: ModelInfo?,
    val runnable: Boolean,
    /** Why the tier is disabled (when [runnable] is false), or a caution. */
    val reason: String?,
    val recommended: Boolean,
    /** The on-device timing check's result for this tier's model, when it has been run (N2). */
    val timing: ModelTiming? = null,
    /** Runs here but misses the live-turn budget: "Slower — better for end-of-session grading". */
    val slower: Boolean = false,
    /** Why this row is (or isn't) the recommendation, in one sentence for the picker. */
    val explanation: String? = null,
)

enum class Tier(val id: String, val title: String, val blurb: String) {
    A("A", "Basic", "Smallest model; runs on any 8 GB laptop on the CPU, but replies take 10–25 seconds."),
    B("B", "Standard", "Good conversations at 4–10 seconds a turn; needs 16 GB of RAM or a 6 GB graphics card."),
    C("C", "Advanced", "Noticeably better feedback and ratings; needs 32 GB of RAM, a 12 GB graphics card, or a 24 GB Apple Silicon Mac."),
    D("D", "Workstation", "The strongest model; needs a 64 GB Apple Silicon Mac or a 24 GB graphics card."),
    ;

    companion object {
        fun of(id: String?): Tier? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Recommends a tier for a machine and explains why the others can't run (BRIEF §6.1). Pure: the probe supplies
 * [HardwareInfo], the manifest supplies the models. The thresholds are the brief's "typical hardware" column, and on
 * top of them the model must fit with memory left for Whisper, the voice, the UI and its KV cache.
 *
 * The recommendation is for conversation speed, not just RAM (BRIEF_INTERVIEW_SPEED N2): the most capable tier whose
 * measured first sentence meets [ModelTimingCheck.LIVE_BUDGET_MS] and that is at or above the language's
 * [QualityFloor]. Larger tiers that miss the budget stay available, marked slower. Before any timing check, a computer
 * without a usable GPU is not recommended above Tier B (D-045): bigger models are known to be too slow there.
 */
object TierAdvisor {
    fun options(
        hw: HardwareInfo,
        models: List<ModelInfo>,
        timings: Map<String, ModelTiming> = emptyMap(),
        floor: QualityFloor? = null,
        sttBytes: Long = DEFAULT_STT_BYTES,
    ): List<TierOption> {
        val blocked = Tier.entries.associateWith { reasonBlocked(it, hw, models, sttBytes) }
        val runnable = Tier.entries.filter { blocked.getValue(it) == null }
        val timingOf = { t: Tier -> defaultModel(t, models)?.let { timings[it.id] } }
        val floorTier = floor?.tier
        val eligible = runnable.filter { floorTier == null || it >= floorTier }
        val measured = eligible.filter { timingOf(it) != null }
        val (best, why) = when {
            eligible.isEmpty() -> null to null
            measured.any { timingOf(it)!!.meetsBudget } -> measured.last { timingOf(it)!!.meetsBudget }.let { t ->
                t to "Recommended: the most capable tier that answers within ${ModelTimingCheck.LIVE_BUDGET_MS / 1000} s here " +
                    "(first sentence ≈ ${secs(timingOf(t)!!.firstSentenceMs)} measured)"
            }
            measured.isNotEmpty() -> measured.minBy { timingOf(it)!!.firstSentenceMs }.let { t ->
                t to "Recommended: the fastest tier measured here (≈ ${secs(timingOf(t)!!.firstSentenceMs)}, over the " +
                    "${ModelTimingCheck.LIVE_BUDGET_MS / 1000} s budget); slow replies use guided questions"
            }
            !gpuCapable(hw) -> (eligible.lastOrNull { it <= Tier.B } ?: eligible.first()).let { t ->
                t to "Recommended: the largest tier this computer can keep conversational without a graphics card " +
                    "(not timed yet; run the speed check after the download)"
            }
            else -> eligible.last() to "Recommended: the most capable tier that fits this computer's memory (not timed yet)"
        }
        val floorNote = floor?.let { f -> " · quality floor for ${f.language}: " + (f.tier?.let { "Tier ${it.id} (${f.evidence})" } ?: f.evidence) }.orEmpty()
        return Tier.entries.map { tier ->
            val model = defaultModel(tier, models)
            val timing = timingOf(tier)
            val slower = timing != null && !timing.meetsBudget && tier != best
            val explanation = when {
                tier == best -> why + floorNote
                blocked.getValue(tier) != null -> null
                floorTier != null && tier < floorTier -> "Below this language's quality floor (Tier ${floorTier.id}, ${floor.evidence})"
                slower -> "Slower — better for end-of-session grading (first sentence ≈ ${secs(timing!!.firstSentenceMs)})"
                else -> null
            }
            TierOption(tier, model, blocked.getValue(tier) == null, blocked.getValue(tier), tier == best, timing, slower, explanation)
        }
    }

    /** A machine where the model can run on a graphics processor: a discrete GPU with memory, or Apple Silicon. */
    private fun gpuCapable(hw: HardwareInfo) = hw.appleSilicon || hw.vramGb >= 5.5

    fun recommended(hw: HardwareInfo, models: List<ModelInfo>, timings: Map<String, ModelTiming> = emptyMap(), floor: QualityFloor? = null): Tier? =
        options(hw, models, timings, floor).firstOrNull { it.recommended }?.tier

    fun defaultModel(tier: Tier, models: List<ModelInfo>): ModelInfo? =
        models.filter { it.kind == ModelKind.LLM && it.tier == tier.id }.let { list -> list.firstOrNull { it.role == "default" } ?: list.firstOrNull() }

    /**
     * Memory the model needs beyond its weights while a spoken session runs: its KV cache, plus (when it runs from
     * system RAM) Whisper, the voice process and the app's UI. The KV estimate scales with the weights and the context
     * (about 0.5 GB for a 2.5 GB model at 8k tokens).
     */
    fun kvBytes(model: ModelInfo): Long = (model.contextSize.takeIf { it > 0 } ?: LocalLlamaModel.DEFAULT_CONTEXT).toLong() * model.totalBytes / 40_000

    fun speechReserveBytes(sttBytes: Long): Long = sttBytes * 13 / 10 + TTS_BYTES + UI_BYTES

    /** Null when [tier] can run on [hw]; otherwise a one-line reason for the disabled row. */
    fun reasonBlocked(tier: Tier, hw: HardwareInfo, models: List<ModelInfo>, sttBytes: Long = DEFAULT_STT_BYTES): String? {
        val model = defaultModel(tier, models) ?: return "No model is listed for this tier."
        val ram = hw.ramGb
        val vram = hw.vramGb
        val fits = when (tier) {
            Tier.A -> ram >= 7.5
            Tier.B -> ram >= 15.5 || vram >= 5.5
            Tier.C -> ram >= 31.5 || vram >= 11.5 || (hw.appleSilicon && ram >= 23.5)
            Tier.D -> (hw.appleSilicon && ram >= 63.5) || vram >= 23.5
        }
        // With room for the KV cache, Whisper, the voice and the UI: in system RAM (CPU or unified memory, keeping a
        // fifth for the OS), or the weights and cache on a discrete GPU.
        val inRam = model.totalBytes + kvBytes(model) + speechReserveBytes(sttBytes) <= hw.ramBytes * 0.8
        val onGpu = !hw.appleSilicon && model.totalBytes + kvBytes(model) <= vram * HardwareInfo.GB
        val free = hw.freeDiskBytes
        return when {
            !fits -> "Needs ${requirement(tier)}; this computer has ${fmt(ram)} GB RAM" +
                (if (vram > 0) " and a ${fmt(vram)} GB graphics card." else if (hw.appleSilicon) " (shared with the GPU)." else " and no usable graphics card.")
            !inRam && !onGpu -> "Needs about ${fmt((model.totalBytes + kvBytes(model) + speechReserveBytes(sttBytes)) / HardwareInfo.GB)} GB with " +
                "speech recognition and the voice running; this computer has ${fmt(ram)} GB RAM" + (if (vram > 0) " and a ${fmt(vram)} GB graphics card." else ".")
            free != null && free < model.totalBytes * 3 / 2 ->
                "Needs ${fmt(model.totalBytes * 1.5 / HardwareInfo.GB)} GB of free disk space; ${fmt(free / HardwareInfo.GB)} GB is free."
            else -> null
        }
    }

    /** Whisper small, the bundled speech model. */
    const val DEFAULT_STT_BYTES = 488L * 1024 * 1024
    private const val TTS_BYTES = 300L * 1024 * 1024
    private const val UI_BYTES = 600L * 1024 * 1024

    private fun requirement(tier: Tier) = when (tier) {
        Tier.A -> "8 GB of RAM"
        Tier.B -> "16 GB of RAM or a graphics card with 6 GB"
        Tier.C -> "32 GB of RAM, a graphics card with 12 GB, or a 24 GB Apple Silicon Mac"
        Tier.D -> "a 64 GB Apple Silicon Mac or a graphics card with 24 GB"
    }

    private fun secs(ms: Long) = "${kotlin.math.round(ms / 100.0) / 10} s"

    private fun fmt(gb: Double): String {
        val r = kotlin.math.round(gb * 10) / 10
        return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
    }
}
