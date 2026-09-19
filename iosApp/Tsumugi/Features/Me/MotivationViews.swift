import Shared
import SwiftUI

/// The streak with freeze days (G-11, D-106): a freeze neither breaks nor extends the streak. Spending one needs no
/// network; the result is shown as it comes back from the shared stats service.
struct StreakCard: View {
    @Environment(AppModel.self) private var app
    let streak: Streak
    let onChange: () async -> Void

    @State private var message: String?
    @State private var busy = false
    @State private var confirm = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("🔥 \(Int(streak.current))-day streak").font(.headline)
                Spacer()
                if streak.frozenToday {
                    Label("Frozen today", systemImage: "snowflake").font(.caption).foregroundStyle(.cyan)
                }
            }
            Text("Longest \(Int(streak.longest)) days · \(Int(streak.freezesLeft)) freezes left this month")
                .font(.caption).foregroundStyle(.secondary)
            if !streak.studiedToday && !streak.frozenToday && !streak.onVacation {
                Button {
                    confirm = true
                } label: {
                    Label("Freeze today", systemImage: "snowflake")
                }
                .buttonStyle(.bordered)
                .disabled(busy || streak.freezesLeft <= 0)
            }
            if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
        }
        .padding()
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
        .confirmationDialog("Spend a streak freeze on today?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Freeze today") { freeze() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("A frozen day neither breaks nor extends your streak. A spent freeze can't be taken back.")
        }
    }

    private func freeze() {
        busy = true
        let graph = app.graph
        Task {
            do {
                let result = try await graph.stats.freeze(day: nil)
                switch result {
                case .frozen: message = String(localized: "Today is frozen. Your streak is safe.")
                case .alreadyFrozen: message = String(localized: "Today is already frozen.")
                case .alreadyStudied: message = String(localized: "You already studied today, so no freeze is needed.")
                case .noFreezesLeft: message = String(localized: "No freezes left this month.")
                default: message = String(localized: "That day can't be frozen.")
                }
                await onChange()
            } catch {
                message = String(localized: "Couldn't freeze: \(error.localizedDescription)")
            }
            busy = false
        }
    }
}

/// This week's challenge (G-11, D-108), tied to real finished Today blocks.
struct WeeklyChallengeCard: View {
    let challenge: WeeklyChallenge

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("This week: \(challenge.title)").font(.subheadline.weight(.semibold))
            ProgressView(value: min(1, Double(challenge.progress) / Double(max(1, challenge.goal))))
            Text(challenge.complete ? String(localized: "Done — nice work!") : "\(challenge.progress) / \(challenge.goal)").font(.caption)
        }
        .padding()
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

/// The opt-in leaderboard on the learner's own sync server (G-11, D-107). Off by default: nothing is sent until the
/// learner turns it on, and every other state explains itself.
struct LeaderboardView: View {
    @Environment(AppModel.self) private var app
    @State private var state: LeaderboardState?
    @State private var period: LeaderboardPeriod = .week
    @State private var optedIn = false
    @State private var displayName = ""
    @State private var busy = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Text("The leaderboard lives on your own sync server. Joining shares only a display name, your review count and your streak with the people on that server. End-to-end encrypted accounts can't join, because the server can't count their reviews. It's off unless you turn it on.")
                    .font(.subheadline)
                TextField("Display name", text: $displayName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Toggle("Show me on the leaderboard", isOn: Binding(get: { optedIn }, set: { setOptIn($0) }))
                    .disabled(busy)
                if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            } header: {
                Text("Leaderboard")
            }
            Section {
                Picker("Period", selection: $period) {
                    Text("Today").tag(LeaderboardPeriod.day)
                    Text("This week").tag(LeaderboardPeriod.week)
                    Text("This month").tag(LeaderboardPeriod.month)
                }
                .pickerStyle(.segmented)
                content
            }
        }
        .navigationTitle("Leaderboard")
        .task(id: period) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if let state {
            switch onEnum(of: state) {
            case .notSignedIn:
                Text("Sign in to a sync server first (Me → Sync).").foregroundStyle(.secondary)
            case .optedOut:
                Text("You're not on the leaderboard.").foregroundStyle(.secondary)
            case .encrypted:
                Text("End-to-end encrypted accounts never appear on leaderboards.").foregroundStyle(.secondary)
            case .failed(let f):
                Text("Couldn't load the leaderboard: \(f.message)").foregroundStyle(.red)
                Button("Retry") { Task { await load() } }
            case .rows(let r):
                if r.rows.isEmpty {
                    Text("No one on this server has opted in yet.").foregroundStyle(.secondary)
                }
                ForEach(Array(r.rows.enumerated()), id: \.offset) { i, row in
                    HStack {
                        Text(verbatim: "\(i + 1).").monospacedDigit().frame(width: 30, alignment: .leading)
                        Text(row.displayName).fontWeight(row.displayName == r.displayName ? .bold : .regular)
                        Spacer()
                        Text("\(Int(row.reviews)) reviews · 🔥\(Int(row.streak))").font(.caption.monospacedDigit())
                    }
                }
            }
        } else {
            ProgressView()
        }
    }

    private func load() async {
        optedIn = (try? await app.graph.leaderboard.isOptedIn())?.boolValue ?? false
        do {
            state = try await app.graph.leaderboard.load(period: period)
        } catch {
            state = LeaderboardStateFailed(message: error.localizedDescription)
        }
    }

    private func setOptIn(_ on: Bool) {
        busy = true
        message = nil
        let name = displayName
        let graph = app.graph
        Task {
            do {
                _ = try await graph.leaderboard.setOptIn(on: on, displayName: name.isEmpty ? nil : name)
                optedIn = on
                message = on ? String(localized: "You're on the leaderboard.") : String(localized: "You left the leaderboard.")
            } catch {
                message = String(localized: "Couldn't change it: \(error.localizedDescription)")
            }
            busy = false
            await load()
        }
    }
}

/// Free-talk patterns for the Me tab (G-02, D-102): this week's conversations, the rolling level estimate (for
/// practice, never a rating) and the recurring error types with examples.
struct ConversationPatternsCard: View {
    @Environment(AppModel.self) private var app
    @State private var patterns: WeeklyPatterns?
    @State private var level: RollingLevel?
    @State private var recurring: [ErrorPattern] = []
    @State private var error: String?

    var body: some View {
        Section {
            if let error {
                Text("Couldn't load your conversation patterns: \(error)").font(.caption).foregroundStyle(.red)
                Button("Retry") { Task { await load() } }
            } else if let patterns {
                if patterns.isEmpty && recurring.isEmpty {
                    Text("No conversations yet. Free talk and role-plays you finish show up here with a level estimate and the mistakes that keep coming back.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    LabeledContent("This week", value: String(localized: "\(Int(patterns.conversations)) conversations · \(Int(patterns.minutes)) min"))
                    if let level {
                        VStack(alignment: .leading, spacing: 2) {
                            LabeledContent("Level estimate", value: "\(level.level.jlptLabel) · ILR \(level.level.ilr)")
                            if let change = patterns.levelChange?.intValue, change != 0 {
                                Text(change > 0 ? String(localized: "Up \(Int(change)) points this week") : String(localized: "Down \(Int(-change)) points this week"))
                                    .font(.caption).foregroundStyle(change > 0 ? .green : .orange)
                            }
                            Text("An estimate for practice from your last \(Int(level.conversations)) conversations, not a rating.")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    if !patterns.errors.isEmpty {
                        Text("This week's patterns").font(.subheadline.weight(.semibold))
                        ForEach(patterns.errors, id: \.type) { p in errorRow(p, showTrend: true) }
                    }
                    if !recurring.isEmpty {
                        Text("Recurring errors (30 days)").font(.subheadline.weight(.semibold))
                        ForEach(recurring, id: \.type) { p in errorRow(p, showTrend: false) }
                    }
                }
            } else {
                ProgressView()
            }
        } header: {
            Text("Conversation")
        }
        .task { await load() }
    }

    private func errorRow(_ p: ErrorPattern, showTrend: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(Self.label(p.type))
                Spacer()
                Text("\(Int(p.count))").monospacedDigit()
                if showTrend && p.trend != 0 {
                    Image(systemName: p.trend > 0 ? "arrow.up" : "arrow.down")
                        .foregroundStyle(p.trend > 0 ? .orange : .green)
                        .accessibilityLabel(p.trend > 0 ? Text("More than last week") : Text("Fewer than last week"))
                }
            }
            if let e = p.examples.first {
                Text(verbatim: "\(e.original) → \(e.replacement)").font(.japanese(size: 14)).foregroundStyle(.secondary)
            }
        }
    }

    /// Module-qualified so `ErrorType` can't be confused with Swift's old name for `Error`.
    static func label(_ type: Shared.ErrorType) -> String {
        switch type {
        case .particle: String(localized: "Particles")
        case .conjugation: String(localized: "Conjugation")
        case .tense: String(localized: "Tense")
        case .politeness: String(localized: "Politeness")
        case .spelling: String(localized: "Spelling")
        case .wordChoice: String(localized: "Word choice")
        default: String(localized: "Other")
        }
    }

    private func load() async {
        error = nil
        do {
            patterns = try await app.graph.conversations.weeklyPatterns()
            level = try await app.graph.conversations.levelEstimate()
            recurring = try await app.graph.conversations.recurringErrors(days: 30, minCount: 2)
        } catch {
            self.error = error.localizedDescription
        }
    }
}
