import Shared
import SwiftUI

/// Phase 2 Today: lessons and reviews at a glance. The full daily planner arrives with Phase 3.
struct TodayView: View {
    @Environment(AppModel.self) private var app
    @State private var status: PathStatus?
    @State private var stats: StatsSnapshot?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("今日").font(.japanese(size: 40, weight: .semibold, relativeTo: .largeTitle))
                if let stats {
                    Text("🔥 \(stats.streak.current)-day streak · \(stats.reviewsToday) answers today").font(.headline)
                }
                if let status {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Level \(status.currentLevel) of \(status.maxLevel)").font(.title3.weight(.semibold))
                        ProgressView(value: status.levelProgress)
                        Text("\(Int(status.levelProgress * 100))% of this level's kanji at Guru (90% to level up)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .padding()
                    .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                    HStack(spacing: 12) {
                        NavigationLink(value: Route.lessons) {
                            Label("Lessons \(status.availableLessons)", systemImage: "book").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(status.availableLessons == 0)
                        NavigationLink(value: Route.reviews) {
                            Label("Reviews \(status.dueReviews)", systemImage: "arrow.triangle.2.circlepath").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(status.dueReviews == 0)
                    }
                }
                if let stats {
                    SectionHeader("Next 7 days")
                    ForEach(Array(stats.forecast.enumerated()), id: \.offset) { _, day in
                        Text("\(String(describing: day.date)): \(day.count) reviews").font(.subheadline)
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Today")
        .task { await refresh() }
        .refreshable { await refresh() }
    }

    private func refresh() async {
        status = try? await app.graph.path()?.status()
        stats = try? await app.graph.stats.snapshot(heatmapDays: 140)
    }
}
