import Shared
import SwiftUI

/// Stats, settings and integrations (BRIEF §6 Me tab).
struct MeView: View {
    @Environment(AppModel.self) private var app
    @State private var stats: StatsSnapshot?
    @State private var developer = false

    var body: some View {
        List {
            if let stats {
                Section("Progress") {
                    StreakCard(streak: stats.streak) { await refresh() }
                        .listRowInsets(EdgeInsets(top: 6, leading: 8, bottom: 6, trailing: 8))
                    Toggle("Vacation mode", isOn: Binding(
                        get: { stats.streak.onVacation },
                        set: { on in Task { try? await app.graph.stats.setVacation(on: on); await refresh() } }
                    ))
                    Heatmap(days: stats.heatmap.map { Int($0.count) }).frame(height: 90)
                    ForEach(stats.stageList, id: \.stage) { s in
                        LabeledContent(SharedText.stage(s.stage), value: "\(s.count)")
                    }
                    ForEach(stats.accuracyList, id: \.kind) { a in
                        LabeledContent("\(SharedText.kind(a.kind)) accuracy (30 d)", value: "\(Int(a.accuracy.ratio * 100))% of \(a.accuracy.total)")
                    }
                }
            }
            // BRIEF_V2 §6.11: minutes against the daily target, and the four-stage roadmap.
            ImmersionSummaryCard()
            RoadmapCard()
            ConversationPatternsCard()
            Section {
                NavigationLink("Leaderboard", value: Route.leaderboard)
                NavigationLink("Export (CSV, PDF report, backup)", value: Route.export)
                NavigationLink("Import & export", value: Route.importExport)
                NavigationLink("Sync", value: Route.sync)
                NavigationLink("Settings", value: Route.settings)
                NavigationLink("Integrations (Notion, Anki)", value: Route.integrations)
                if developer {
                    NavigationLink("Content review", value: Route.contentReview)
                }
                NavigationLink("AI & speech (models, server, VOICEVOX)", value: Route.aiSettings)
                NavigationLink("Licenses", value: Route.licenses)
            }
        }
        .navigationTitle("Me")
        .task { await refresh() }
    }

    private func refresh() async {
        stats = try? await app.graph.stats.snapshot(heatmapDays: 140)
        // `get` rather than `bool(key:default:)`: a Kotlin parameter named `default` is a C keyword in the Objective-C header.
        developer = (try? await app.graph.deviceSettings.get(key: SettingsView.developerKey)) == "true"
    }
}

/// Review heat-map: 7 rows × N weeks, darker = more answers.
private struct Heatmap: View {
    let days: [Int]

    var body: some View {
        Canvas { ctx, size in
            let weeks = max(1, (days.count + 6) / 7)
            let cell = min(size.width / CGFloat(weeks), size.height / 7)
            let maxCount = max(1, days.max() ?? 1)
            for (i, count) in days.enumerated() {
                let rect = CGRect(x: CGFloat(i / 7) * cell, y: CGFloat(i % 7) * cell, width: cell * 0.85, height: cell * 0.85)
                let opacity = count == 0 ? 0.12 : 0.25 + 0.75 * Double(count) / Double(maxCount)
                ctx.fill(Path(roundedRect: rect, cornerRadius: cell * 0.2), with: .color(.accentColor.opacity(opacity)))
            }
        }
        .accessibilityLabel("Review activity over the last \(days.count) days")
    }
}

struct SettingsView: View {
    /// Device-local switch for the in-app content review (G-16).
    static let developerKey = "dev.contentReview"

    @Environment(AppModel.self) private var app
    @State private var developer = false
    @State private var batch = 5.0
    @State private var retention = 0.9
    @State private var optimizing = false
    @State private var optimizeNote: String?
    @State private var recompute: RecomputeProgress?

    var body: some View {
        Form {
            Section("Lessons") {
                Stepper("Lessons per batch: \(Int(batch))", value: $batch, in: 3...15, step: 1)
                    .onChange(of: batch) { _, v in Task { try? await app.graph.settings.setLessonBatchSize(size: Int32(v)) } }
            }
            Section {
                Slider(value: $retention, in: 0.8...0.97, onEditingChanged: { editing in
                    guard !editing else { return }
                    Task {
                        try? await app.graph.settings.setDesiredRetention(value: retention)
                        app.graph.reloadScheduler()
                    }
                })
                .accessibilityLabel(Text("Desired retention"))
                Text("Desired retention: \(Int(retention * 100))%")
            } header: {
                Text("Scheduling")
            } footer: {
                Text("The share of reviews you aim to get right. Higher means shorter intervals and more reviews.")
            }
            Section {
                if let recompute, recompute.running {
                    RecomputeBanner(progress: recompute)
                }
                Button(optimizing ? "Fitting to your reviews…" : "Optimize from my reviews") { optimize() }
                    .disabled(optimizing || recompute?.running == true)
                if let optimizeNote { Text(optimizeNote).font(.caption) }
            } header: {
                Text("Scheduler weights")
            } footer: {
                Text("Fits the FSRS scheduler to your own answers (needs \(FsrsOptimizer.shared.MIN_REVIEWS) reviews). Your cards are then rescheduled in the background. Syncs to your other devices.")
            }
            Section("AI") {
                NavigationLink("AI & speech", value: Route.aiSettings)
            }
            Section {
                NavigationLink("Audio packs", value: Route.audioPacks)
            } header: {
                Text("Audio")
            } footer: {
                Text("Pre-rendered voices for exam listening, dialogues, minimal pairs and grammar examples.")
            }
            ImmersionKitSettingsSection()
            RecordingsSyncSection()
            Section("Integrations") {
                NavigationLink("Notion and AnkiConnect", value: Route.integrations)
            }
            Section {
                Toggle("Content review tools", isOn: Binding(
                    get: { developer },
                    set: { on in
                        developer = on
                        Task { try? await app.graph.deviceSettings.put(key: Self.developerKey, value: on ? "true" : "false") }
                    }
                ))
            } header: {
                Text("Developer")
            } footer: {
                Text("Shows Me → Content review, where AI-drafted pack content is accepted, edited or rejected for the build scripts. This device only.")
            }
        }
        .navigationTitle("Settings")
        .task {
            batch = Double((try? await app.graph.settings.lessonBatchSize())?.intValue ?? 5)
            retention = (try? await app.graph.settings.desiredRetention())?.doubleValue ?? 0.9
            developer = (try? await app.graph.deviceSettings.get(key: Self.developerKey)) == "true"
        }
        .task { for await p in app.graph.recomputeProgress { recompute = p } }
    }

    /// Runs the shared FSRS optimizer off the main actor and stores the result with `setFsrsWeights`, which reloads
    /// the scheduler and rebuilds every card in the background (F-32).
    private func optimize() {
        let graph = app.graph
        optimizing = true
        optimizeNote = nil
        Task {
            defer { optimizing = false }
            do {
                let log = try await graph.srs.reviewLogForOptimizer()
                let result = await Task.detached(priority: .userInitiated) {
                    FsrsOptimizer.shared.optimize(log: log, initial: FsrsParameters.companion.DEFAULT_WEIGHTS, iterations: 5, seed: 42)
                }.value
                let needed = Int(FsrsOptimizer.shared.MIN_REVIEWS)
                guard Int(result.reviewCount) >= needed else {
                    optimizeNote = String(localized: "Not enough history yet: \(Int(result.reviewCount)) of \(needed) usable reviews. The default weights work well until then.")
                    return
                }
                try await graph.setFsrsWeights(weights: result.weights)
                optimizeNote = String(localized: "Fitted to \(Int(result.reviewCount)) reviews. Prediction error \(String(format: "%.3f", result.lossBefore)) → \(String(format: "%.3f", result.lossAfter)).")
            } catch {
                optimizeNote = String(localized: "Couldn't optimize: \(error.localizedDescription)")
            }
        }
    }
}

/// Shown while every card is rebuilt after new FSRS weights (local or synced; F-32).
struct RecomputeBanner: View {
    let progress: RecomputeProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Updating your review schedule…", systemImage: "arrow.triangle.2.circlepath")
                .font(.subheadline.weight(.semibold))
            ProgressView(value: progress.fraction)
            Text("\(progress.done) of \(progress.total) cards").font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Renders docs/LICENSES.md, copied into the app bundle by the "Bundle Content Packs" build phase.
struct LicensesView: View {
    @State private var lines: [String] = []

    var body: some View {
        List(Array(lines.enumerated()), id: \.offset) { _, line in
            if line.hasPrefix("## ") {
                Text(line.dropFirst(3)).font(.headline)
            } else if line.hasPrefix("# ") {
                Text(line.dropFirst(2)).font(.title3.weight(.semibold))
            } else if line.hasPrefix("|") {
                Text(line.trimmingCharacters(in: CharacterSet(charactersIn: "|"))
                    .split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }.joined(separator: " · "))
                    .font(.caption)
            } else {
                Text(line).font(.subheadline)
            }
        }
        .navigationTitle("Licenses")
        .task {
            guard let url = Bundle.main.url(forResource: "LICENSES", withExtension: "md"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else {
                lines = ["Licenses file missing from this build."]
                return
            }
            lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !($0.hasPrefix("|") && $0.contains("---")) }
        }
    }
}

/// Opt-in recordings and pictures sync (G-03, D-111): off by default and per device. Uses the sync server's blob
/// store; nothing is uploaded until the learner turns it on here.
struct RecordingsSyncSection: View {
    @Environment(AppModel.self) private var app
    @State private var enabled = false
    @State private var bytes: Int64?
    @State private var syncing = false
    @State private var progress: Double?
    @State private var message: String?

    var body: some View {
        Section {
            Toggle("Sync my recordings and pictures", isOn: Binding(get: { enabled }, set: { set($0) }))
            if let bytes {
                LabeledContent("On this device", value: ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))
            }
            if enabled {
                Button(syncing ? "Syncing…" : "Sync recordings now") { syncNow() }.disabled(syncing)
                if let progress { ProgressView(value: progress) }
            }
            if let message { Text(message).font(.caption) }
        } header: {
            Text("Recordings")
        } footer: {
            Text("Off by default. When on, this device uploads your recordings and card pictures to your own sync server (encrypted when end-to-end encryption is on) and downloads the ones made on your other devices. Each device decides for itself.")
        }
        .task { await load() }
    }

    private func load() async {
        enabled = (try? await app.graph.recordingSync.isEnabled())?.boolValue ?? false
        bytes = (try? await app.graph.recordings.totalBytes())?.int64Value
    }

    private func set(_ on: Bool) {
        enabled = on
        let sync = app.graph.recordingSync
        Task { try? await sync.setEnabled(on: on) }
    }

    private func syncNow() {
        syncing = true
        message = nil
        progress = nil
        let sync = app.graph.recordingSync
        Task {
            do {
                let r = try await sync.sync { p in
                    let fraction = p.total > 0 ? Double(p.done) / Double(p.total) : 0
                    Task { @MainActor in progress = fraction }
                }
                var text = String(localized: "\(Int(r.uploaded)) uploaded, \(Int(r.downloaded)) downloaded, \(Int(r.deleted)) deleted.")
                if !r.failures.isEmpty { text += " " + r.failures.prefix(3).joined(separator: "; ") }
                message = text
            } catch {
                message = String(localized: "Couldn't sync recordings: \(error.localizedDescription)")
            }
            syncing = false
            progress = nil
            await load()
        }
    }
}
