package app.mokuhyo.desktop

import app.mokuhyo.ai.HardwareInfo
import app.mokuhyo.platform.Os
import app.mokuhyo.platform.ProcessMemory
import app.mokuhyo.speech.TimelineSetup
import app.mokuhyo.speech.TimingReport
import app.mokuhyo.speech.TurnTimeline
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
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(report(listOf(timeline.snapshot()), real = true).toJson())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
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
