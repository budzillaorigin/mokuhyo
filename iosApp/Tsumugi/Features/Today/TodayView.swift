import Shared
import SwiftUI

/// The structured daily path (BRIEF §5.6): the day's blocks in order, sized to the chosen budget.
struct TodayView: View {
    @Environment(AppModel.self) private var app
    @State private var plan: TodayPlan?
    @State private var status: PathStatus?
    @State private var stats: StatsSnapshot?
    @State private var recompute: RecomputeProgress?

    private let budgets = [10, 20, 40, 60]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: "今日").font(.japanese(size: 40, weight: .semibold, relativeTo: .largeTitle))
                    .accessibilityAddTraits(.isHeader)
                    .japaneseSpeech()
                if let recompute, recompute.running {
                    RecomputeBanner(progress: recompute)
                        .padding()
                        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                }
                if let stats {
                    Text("🔥 \(stats.streak.current)-day streak · \(stats.reviewsToday) answers today").font(.headline)
                }
                if let plan {
                    Picker("Daily budget", selection: Binding(
                        get: { Int(plan.budgetMinutes) },
                        set: { minutes in
                            Task {
                                try? await app.graph.settings.put(key: "today.budgetMinutes", value: "\(minutes)")
                                await refresh()
                            }
                        }
                    )) {
                        ForEach(budgets, id: \.self) { Text("\($0) min").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Text("\(plan.phase.label) phase · about \(plan.plannedMinutes) min planned").font(.subheadline)
                    ForEach(Array(plan.blocks.enumerated()), id: \.offset) { i, block in
                        blockRow(i, block)
                    }
                    let c = plan.challenge
                    VStack(alignment: .leading, spacing: 6) {
                        Text("This week: \(c.title)").font(.subheadline.weight(.semibold))
                        ProgressView(value: min(1, Double(c.progress) / Double(max(1, c.goal))))
                        Text(c.complete ? "Done — nice work!" : "\(c.progress) / \(c.goal)").font(.caption)
                    }
                    .padding()
                    .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                }
                if let status {
                    Text("Level \(status.currentLevel) · \(Int(status.levelProgress * 100))% of kanji at Guru")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding()
        }
        .navigationTitle("Today")
        .task { await refresh() }
        .task { for await p in app.graph.recomputeProgress { recompute = p } }
        .refreshable { await refresh() }
    }

    @ViewBuilder
    private func blockRow(_ index: Int, _ block: TodayBlock) -> some View {
        let row = HStack {
            Text(block.done ? "✓" : "\(index + 1)").font(.title3.weight(.semibold)).frame(width: 30)
                .accessibilityLabel(block.done ? Text("Done") : Text("Step \(index + 1)"))
            VStack(alignment: .leading) {
                Text(block.title).font(.headline)
                Text(block.detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if block.minutes > 0 && !block.done { Text("\(block.minutes) min").font(.caption) }
        }
        .padding()
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)

        if let route = route(for: block), block.count > 0 {
            NavigationLink(value: route) { row }.buttonStyle(.plain)
        } else {
            row
        }
    }

    private func route(for block: TodayBlock) -> Route? {
        switch block.kind {
        case .reviews: .reviews
        case .lessons: .lessons
        case .grammar: .grammarLessons
        default: nil
        }
    }

    private func refresh() async {
        plan = try? await app.graph.today()
        status = try? await app.graph.path()?.status()
        stats = try? await app.graph.stats.snapshot(heatmapDays: 140)
    }
}
