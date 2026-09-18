import Shared
import SwiftUI

/// The Reviews tab (BRIEF §6, BRIEF_V2 F-07): what's due by item kind, Start (the shared review session, which ends
/// with its own summary), the next week's forecast, and the leeches that keep slipping.
struct ReviewsHomeView: View {
    @Environment(AppModel.self) private var app

    @State private var queue: [KindCount]?
    @State private var forecast: [Int] = []
    @State private var leeches: [LeechEntry] = []
    @State private var error: String?
    @State private var loading = false

    private var dueTotal: Int { (queue ?? []).reduce(0) { $0 + Int($1.count) } }

    var body: some View {
        List {
            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { Task { await refresh() } }
                }
            }
            queueSection
            if !forecast.isEmpty { forecastSection }
            leechSection
        }
        .navigationTitle("Reviews")
        .overlay {
            if queue == nil && error == nil { ProgressView() }
        }
        .onAppear { Task { await refresh() } } // also after returning from a session
        .refreshable { await refresh() }
    }

    private var queueSection: some View {
        Section {
            if let queue {
                if queue.isEmpty {
                    Text("Nothing is due right now.").foregroundStyle(.secondary)
                }
                ForEach(queue, id: \.kind) { entry in
                    HStack {
                        Circle().fill(entry.kind.color).frame(width: 10, height: 10).accessibilityHidden(true)
                        Text(entry.kind.label)
                        Spacer()
                        Text("\(entry.count)").monospacedDigit().foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
                NavigationLink(value: Route.reviews) {
                    Label(dueTotal == 0 ? "Start" : "Start \(dueTotal) reviews", systemImage: "play.fill")
                        .font(.headline)
                }
                .disabled(dueTotal == 0)
            }
        } header: {
            Text("Due now")
        } footer: {
            Text("Kinds are mixed in one session. Missed cards come back at the end as practice; you get a summary when you finish.")
        }
    }

    private var forecastSection: some View {
        Section {
            ForecastStrip(counts: forecast)
                .frame(height: 110)
                .padding(.vertical, 4)
        } header: {
            Text("Coming up")
        }
    }

    private var leechSection: some View {
        Section {
            if leeches.isEmpty {
                Text("No leeches. Cards that lapse \(SrsRepository.companion.LEECH_LAPSES) or more times show up here.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(leeches, id: \.item.id) { leech in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(leech.item.primaryText).font(.japanese(size: 20)).japaneseSpeech()
                        Text(([leech.item.reading].compactMap { $0 } + [leech.item.meanings.prefix(3).joined(separator: ", ")])
                            .filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    Spacer()
                    Text("\(leech.lapses) lapses").font(.caption.monospacedDigit()).foregroundStyle(.red)
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text("Leeches")
        } footer: {
            if !leeches.isEmpty {
                Text("These keep slipping. Rewrite their stories, or look them up again in the dictionary.")
            }
        }
    }

    private func refresh() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let graph = app.graph
            queue = try await SwiftSupport.shared.reviewQueue(graph: graph)
            leeches = try await SwiftSupport.shared.leeches(graph: graph, limit: 30)
            let stats = try await graph.stats.snapshot(heatmapDays: 1)
            forecast = stats.forecast.map { Int($0.count) }
            error = nil
        } catch {
            self.error = "Couldn't load your reviews: \(error.localizedDescription)"
        }
    }
}

/// Bars for the reviews coming due on each of the next days (today first).
private struct ForecastStrip: View {
    let counts: [Int]

    var body: some View {
        let top = max(1, counts.max() ?? 1)
        HStack(alignment: .bottom, spacing: 6) {
            ForEach(Array(counts.enumerated()), id: \.offset) { i, count in
                VStack(spacing: 4) {
                    Text("\(count)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(i == 0 ? Color.accentColor : Color.accentColor.opacity(0.5))
                        .frame(height: max(2, 60 * CGFloat(count) / CGFloat(top)))
                    Text(Self.dayLabel(i)).font(.caption2)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("\(Self.dayLabel(i)): \(count) reviews"))
            }
        }
    }

    private static func dayLabel(_ offset: Int) -> String {
        if offset == 0 { return String(localized: "Today") }
        let date = Calendar.current.date(byAdding: .day, value: offset, to: Date()) ?? Date()
        return date.formatted(.dateTime.weekday(.abbreviated))
    }
}
