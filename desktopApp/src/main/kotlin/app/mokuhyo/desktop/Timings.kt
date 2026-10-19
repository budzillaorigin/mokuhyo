package app.mokuhyo.desktop

import app.mokuhyo.ai.HardwareInfo
import app.mokuhyo.platform.Os
import app.mokuhyo.platform.ProcessMemory
import app.mokuhyo.speech.MeasuredLatency
import app.mokuhyo.speech.TimelineSetup
import app.mokuhyo.speech.TimingReport
import app.mokuhyo.speech.TurnTimeline
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The last spoken session's timeline (BRIEF_INTERVIEW_SPEED N1), for Settings → Speech & audio → Diagnostics and its
 * "Export timing report". Kept in memory while a session runs and written to `diagnostics/last-session-timing.json`
 * (timings only) after every turn, so it survives a crash or restart. Never part of the backup bundle.
 */
class Timings(private val dataDir: File, private val hardware: () -> HardwareInfo?) {
    private val file get() = File(dataDir, "diagnostics/last-session-timing.json")

    @Volatile
    var current: TurnTimeline? = null
        private set

    /** A new timeline for a spoken session; it becomes "the last session" straight away. */
    fun start(sessionId: String, kind: String, language: String, setup: TimelineSetup): TurnTimeline =
        TurnTimeline(sessionId, kind, language).also {
            it.setup = setup
            current = it
        }

    /** Records the process's memory on [turn] and saves the timeline. Call off the UI thread. */
    fun turnDone(timeline: TurnTimeline, turn: Int = timeline.current) {
        val rss = ProcessMemory.peakRssBytes()
        timeline.note(turn) { peakRssBytes = rss }
        save(timeline)
    }

    fun save(timeline: TurnTimeline) {
        runCatching {
            val report = report(listOf(timeline.snapshot()), real = true)
            write(file, report.toJson())
            // The per-language readiness panel shows the last measured headline (N4).
            report.summary.warm[TurnTimeline.FIRST_AUDIO]?.takeIf { it.n > 0 }?.let { s ->
                val all = measured() + (timeline.language to MeasuredLatency(s.p50, s.p95, s.n, System.currentTimeMillis()))
                write(byLanguage, json.encodeToString(byLanguageSerializer, all))
            }
        }
    }

    /** The last measured first-audio p50/p95 per language on this computer. */
    fun measured(): Map<String, MeasuredLatency> =
        runCatching { json.decodeFromString(byLanguageSerializer, byLanguage.readText()) }.getOrDefault(emptyMap())

    private val byLanguage get() = File(dataDir, "diagnostics/by-language.json")

    private fun write(target: File, text: String) {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    /** The last session's report: the live one if a session is running, else the saved one. */
    fun last(): TimingReport? = current?.let { report(listOf(it.snapshot()), real = true) }
        ?: runCatching { TimingReport.parse(file.readText()) }.getOrNull()

    fun report(
        sessions: List<TimingReport.Session>,
        real: Boolean,
        generatedBy: String = "Mokuhyo live session",
        cancellation: List<TimingReport.CancelSample> = emptyList(),
        notRun: Map<String, String> = emptyMap(),
    ): TimingReport = TimingReport(
        generatedBy = generatedBy, appVersion = BuildInfo.version, machine = machine(hardware()), real = real,
        sessions = sessions, summary = TimingReport.summarize(sessions, cancellation), notRun = notRun, cancellation = cancellation,
    )

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val byLanguageSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), MeasuredLatency.serializer())

        fun machine(hw: HardwareInfo?): TimingReport.Machine = TimingReport.Machine(
            os = Os.current.id, arch = Os.arch, cpuCores = hw?.cpuCores ?: Runtime.getRuntime().availableProcessors(),
            ramGb = hw?.ramGb?.let { Math.round(it * 10) / 10.0 } ?: 0.0,
            gpu = hw?.gpus?.firstOrNull()?.let { "${it.name} (${it.backend})" },
        )

        /** Writes [report] as `<base>.json` and `<base>.md`; returns the two files. */
        fun export(report: TimingReport, base: File): List<File> {
            base.parentFile?.mkdirs()
            val json = File(base.path + ".json").apply { writeText(report.toJson()) }
            val md = File(base.path + ".md").apply { writeText(report.toMarkdown()) }
            return listOf(json, md)
        }
    }
}
