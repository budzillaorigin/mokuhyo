import Shared
import SwiftUI

/// Stats, settings and integrations (BRIEF §6 Me tab).
struct MeView: View {
    @Environment(AppModel.self) private var app
    @State private var stats: StatsSnapshot?

    var body: some View {
        List {
            if let stats {
                Section("Progress") {
                    Text("Streak: \(stats.streak.current) days (longest \(stats.streak.longest))")
                    Toggle("Vacation mode", isOn: Binding(
                        get: { stats.streak.onVacation },
                        set: { on in Task { try? await app.graph.stats.setVacation(on: on); await refresh() } }
                    ))
                    Heatmap(days: stats.heatmap.map { Int($0.count) }).frame(height: 90)
                    ForEach(stats.stageList, id: \.stage) { s in
                        LabeledContent(s.stage.label, value: "\(s.count)")
                    }
                    ForEach(stats.accuracyList, id: \.kind) { a in
                        LabeledContent("\(a.kind.label) accuracy (30 d)", value: "\(Int(a.accuracy.ratio * 100))% of \(a.accuracy.total)")
                    }
                }
            }
            Section {
                NavigationLink("Import & export", value: Route.importExport)
                NavigationLink("Settings", value: Route.settings)
                NavigationLink("Licenses", value: Route.licenses)
            }
        }
        .navigationTitle("Me")
        .task { await refresh() }
    }

    private func refresh() async {
        stats = try? await app.graph.stats.snapshot(heatmapDays: 140)
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
    @Environment(AppModel.self) private var app
    @State private var batch = 5.0
    @State private var retention = 0.9

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
                Text("Desired retention: \(Int(retention * 100))%")
            } header: {
                Text("Scheduling")
            } footer: {
                Text("The share of reviews you aim to get right. Higher means shorter intervals and more reviews.")
            }
        }
        .navigationTitle("Settings")
        .task {
            batch = Double((try? await app.graph.settings.lessonBatchSize())?.intValue ?? 5)
            retention = (try? await app.graph.settings.desiredRetention())?.doubleValue ?? 0.9
        }
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
