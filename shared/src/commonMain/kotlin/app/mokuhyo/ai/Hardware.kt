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
 * [HardwareInfo], the manifest supplies the models. The thresholds are the brief's "typical hardware" column.
 */
object TierAdvisor {
    fun options(hw: HardwareInfo, models: List<ModelInfo>): List<TierOption> {
        val runnable = Tier.entries.associateWith { reasonBlocked(it, hw, models) == null }
        val best = Tier.entries.lastOrNull { runnable.getValue(it) }
        return Tier.entries.map { tier ->
            val model = defaultModel(tier, models)
            TierOption(tier, model, runnable.getValue(tier), reasonBlocked(tier, hw, models), tier == best)
        }
    }

    fun recommended(hw: HardwareInfo, models: List<ModelInfo>): Tier? = options(hw, models).firstOrNull { it.recommended }?.tier

    fun defaultModel(tier: Tier, models: List<ModelInfo>): ModelInfo? =
        models.filter { it.kind == ModelKind.LLM && it.tier == tier.id }.let { list -> list.firstOrNull { it.role == "default" } ?: list.firstOrNull() }

    /** Null when [tier] can run on [hw]; otherwise a one-line reason for the disabled row. */
    fun reasonBlocked(tier: Tier, hw: HardwareInfo, models: List<ModelInfo>): String? {
        val model = defaultModel(tier, models) ?: return "No model is listed for this tier."
        val ram = hw.ramGb
        val vram = hw.vramGb
        val fits = when (tier) {
            Tier.A -> ram >= 7.5
            Tier.B -> ram >= 15.5 || vram >= 5.5
            Tier.C -> ram >= 31.5 || vram >= 11.5 || (hw.appleSilicon && ram >= 23.5)
            Tier.D -> (hw.appleSilicon && ram >= 63.5) || vram >= 23.5
        }
        val free = hw.freeDiskBytes
        return when {
            !fits -> "Needs ${requirement(tier)}; this computer has ${fmt(ram)} GB RAM" +
                (if (vram > 0) " and a ${fmt(vram)} GB graphics card." else if (hw.appleSilicon) " (shared with the GPU)." else " and no usable graphics card.")
            free != null && free < model.totalBytes * 3 / 2 ->
                "Needs ${fmt(model.totalBytes * 1.5 / HardwareInfo.GB)} GB of free disk space; ${fmt(free / HardwareInfo.GB)} GB is free."
            else -> null
        }
    }

    private fun requirement(tier: Tier) = when (tier) {
        Tier.A -> "8 GB of RAM"
        Tier.B -> "16 GB of RAM or a graphics card with 6 GB"
        Tier.C -> "32 GB of RAM, a graphics card with 12 GB, or a 24 GB Apple Silicon Mac"
        Tier.D -> "a 64 GB Apple Silicon Mac or a graphics card with 24 GB"
    }

    private fun fmt(gb: Double): String {
        val r = kotlin.math.round(gb * 10) / 10
        return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
    }
}
