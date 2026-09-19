import Shared
import SwiftUI

/// Labels for the shared immersion and roadmap enums (the shared texts are English; BRIEF_V2 §6.11, D-167/D-168).
enum ImmersionText {
    static func source(_ s: ImmersionOrigin) -> String {
        switch s {
        case .reader: String(localized: "Reading")
        case .media: String(localized: "Video and audio")
        case .podcast: String(localized: "Podcasts")
        case .dialogue: String(localized: "Dialogues")
        default: String(localized: "Other (logged by hand)")
        }
    }

    static func stageTitle(_ s: RoadmapStage) -> String {
        switch s {
        case .foundations: String(localized: "Foundations")
        case .comprehension: String(localized: "Comprehension")
        case .output: String(localized: "Output")
        default: String(localized: "Refinement")
        }
    }

    static func stageSummary(_ s: RoadmapStage) -> String {
        switch s {
        case .foundations: String(localized: "Kana, the first few hundred words and the core grammar, with short daily listening so the sound of Japanese becomes familiar.")
        case .comprehension: String(localized: "Lots of input you mostly understand: shows, podcasts and graded readers. Words come from your own media; speaking can wait.")
        case .output: String(localized: "Start speaking and writing regularly: shadowing, conversations with the practice partner, short texts you get corrected.")
        default: String(localized: "Polish accuracy, register and pitch; read and listen to native material of every kind and close the remaining gaps.")
        }
    }

    static func milestone(_ m: Milestone) -> String {
        let target = Int(m.target.rounded())
        switch m.measure {
        case .knownWords: return String(localized: "Know \(target.formatted()) words")
        case .immersionHours: return String(localized: "Log \(target.formatted()) hours of immersion")
        case .readerComprehension: return String(localized: "Score \(target.formatted())% on graded-reader questions")
        default:
            if m.id == "s3-opi" { return String(localized: "Reach ILR 1+ in an OPI practice interview") }
            if m.id == "s4-opi" { return String(localized: "Reach ILR 2+ in an OPI practice interview") }
            return m.title
        }
    }

    static func current(_ m: Milestone) -> String? {
        guard let value = m.current?.doubleValue else {
            return m.measure == .readerComprehension ? String(localized: "Not measured yet") : (m.measure == .opiLevel ? String(localized: "Take an OPI practice interview") : nil)
        }
        switch m.measure {
        case .knownWords: return Int(value).formatted()
        case .immersionHours: return String(localized: "\(value.formatted(.number.precision(.fractionLength(1)))) h")
        case .readerComprehension: return "\(Int(value.rounded()).formatted())%"
        default: return nil
        }
    }
}

/// Immersion minutes as a heat-map: 7 rows × weeks, darker = more minutes; a ring marks days that met the target.
struct ImmersionHeatmap: View {
    let days: [ImmersionDayRow]

    var body: some View {
        Canvas { ctx, size in
            let weeks = max(1, (days.count + 6) / 7)
            let cell = min(size.width / CGFloat(weeks), size.height / 7)
            let maxMinutes = max(1, days.map { Int($0.totalMinutes) }.max() ?? 1)
            for (i, day) in days.enumerated() {
                let rect = CGRect(x: CGFloat(i / 7) * cell, y: CGFloat(i % 7) * cell, width: cell * 0.85, height: cell * 0.85)
                let minutes = Int(day.totalMinutes)
                let opacity = minutes == 0 ? 0.12 : 0.25 + 0.75 * Double(minutes) / Double(maxMinutes)
                ctx.fill(Path(roundedRect: rect, cornerRadius: cell * 0.2), with: .color(.teal.opacity(opacity)))
                if day.targetMet {
                    ctx.stroke(Path(roundedRect: rect, cornerRadius: cell * 0.2), with: .color(.teal), lineWidth: 1)
                }
            }
        }
        .accessibilityLabel(Text("Immersion over the last \(days.count.formatted()) days"))
    }
}

/// Me: today's immersion against the target, a small heat-map, and a link to the full log.
struct ImmersionSummaryCard: View {
    @Environment(AppModel.self) private var app
    @State private var days: [ImmersionDayRow] = []
    @State private var failed = false

    var body: some View {
        Section("Immersion") {
            if let today = days.last {
                VStack(alignment: .leading, spacing: 4) {
                    if today.targetMinutes > 0 {
                        Text("Today: \(Int(today.totalMinutes).formatted()) of \(Int(today.targetMinutes).formatted()) min").font(.subheadline)
                        ProgressView(value: min(1, Double(today.totalMinutes) / Double(max(1, today.targetMinutes)))).tint(.teal)
                    } else {
                        Text("Today: \(Int(today.totalMinutes).formatted()) min").font(.subheadline)
                    }
                }
                ImmersionHeatmap(days: days).frame(height: 70)
            } else if failed {
                ErrorRetryView(message: String(localized: "Couldn't load the immersion log.")) { Task { await load() } }
            }
            NavigationLink("Immersion log", value: Route.immersionLog)
        }
        .task { await load() }
    }

    private func load() async {
        do {
            days = try await SwiftSupport.shared.immersionDays(graph: app.graph, days: 70)
            failed = false
        } catch {
            failed = true
        }
    }
}

/// Me: the four-stage roadmap (§6.11) with the current stage's milestones. The reached stage never drops (rule 11).
struct RoadmapCard: View {
    @Environment(AppModel.self) private var app
    @State private var status: RoadmapStatus?
    @State private var failed = false

    var body: some View {
        Section("Roadmap") {
            if let status {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Stage \(Int(status.current.number).formatted()) of 4").font(.caption.weight(.semibold)).foregroundStyle(.tint)
                        Spacer()
                        HStack(spacing: 4) {
                            ForEach(status.stages, id: \.stage.number) { s in
                                Capsule().fill(s.reached ? Color.accentColor : Color.secondary.opacity(0.25)).frame(width: 22, height: 5)
                            }
                        }
                        .accessibilityHidden(true)
                    }
                    Text(ImmersionText.stageTitle(status.current)).font(.headline)
                    Text(ImmersionText.stageSummary(status.current)).font(.caption).foregroundStyle(.secondary)
                    ForEach(status.stages.first { $0.stage.number == status.current.number }?.milestones ?? [], id: \.id) { m in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Image(systemName: m.met ? "checkmark.circle.fill" : "circle").foregroundStyle(m.met ? Color.green : Color.secondary)
                                    .accessibilityHidden(true)
                                Text(ImmersionText.milestone(m)).font(.subheadline)
                                Spacer()
                                if let current = ImmersionText.current(m) {
                                    Text(current).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                }
                            }
                            ProgressView(value: min(1, max(0, m.progress)))
                        }
                        .accessibilityElement(children: .combine)
                    }
                    if status.current.number >= 3 {
                        NavigationLink("Take an OPI practice interview", value: Route.opi).font(.caption)
                    }
                }
            } else if failed {
                ErrorRetryView(message: String(localized: "Couldn't load the roadmap.")) { Task { await load() } }
            } else {
                ProgressView()
            }
        }
        .task { await load() }
    }

    private func load() async {
        do {
            status = try await app.graph.roadmap.status()
            failed = false
        } catch {
            failed = true
        }
    }
}

/// The immersion log (§6.11, D-167): daily target, heat-map, minutes per source, manual entry and the sessions list.
struct ImmersionLogView: View {
    @Environment(AppModel.self) private var app
    @State private var days: [ImmersionDayRow] = []
    @State private var sessions: [ImmersionSession] = []
    @State private var target = 20
    @State private var savedTarget: Int?
    @State private var loadError: String?
    @State private var adding = false

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            }
            Section {
                Stepper(value: $target, in: 0...240, step: 5) {
                    Text(target == 0 ? String(localized: "Daily target: none") : String(localized: "Daily target: \(target.formatted()) min"))
                }
                .onChange(of: target) { _, minutes in
                    guard savedTarget != nil, minutes != savedTarget else { return }
                    savedTarget = minutes
                    let log = app.graph.immersion
                    Task { try? await log.setTargetMinutes(minutes: Int32(minutes)) }
                }
            } footer: {
                Text("Today's immersion block counts as done once you reach it. Syncs to your other devices.")
            }
            Section("Last 20 weeks") {
                ImmersionHeatmap(days: days).frame(height: 110)
                let total = days.reduce(0) { $0 + Int($1.totalMinutes) }
                let active = days.reduce(0) { $0 + Int($1.activeMinutes) }
                LabeledContent("Total", value: String(localized: "\((Double(total) / 60).formatted(.number.precision(.fractionLength(1)))) h"))
                LabeledContent("Active", value: String(localized: "\((Double(active) / 60).formatted(.number.precision(.fractionLength(1)))) h"))
                LabeledContent("Passive", value: String(localized: "\((Double(total - active) / 60).formatted(.number.precision(.fractionLength(1)))) h"))
            }
            Section("By source") {
                ForEach(bySource()) { row in
                    LabeledContent(row.label, value: String(localized: "\(row.minutes.formatted()) min"))
                }
                if bySource().isEmpty {
                    Text("Reading and the media player log time automatically.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section {
                Button {
                    adding = true
                } label: {
                    Label("Log time by hand", systemImage: "plus.circle")
                }
            } footer: {
                Text("For immersion away from the app: a show, a podcast, a conversation.")
            }
            Section("Recent") {
                if sessions.isEmpty {
                    Text("Nothing logged in the last two weeks.").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(sessions, id: \.id) { s in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(ImmersionText.source(s.source)).font(.subheadline)
                            Spacer()
                            Text("\(Int((Double(s.durationSeconds) / 60).rounded()).formatted()) min").font(.caption.monospacedDigit())
                        }
                        Text([s.day, s.mode == .active ? String(localized: "active") : String(localized: "passive"), s.title].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .swipeActions {
                        Button("Delete", role: .destructive) { delete(s.id) }
                    }
                }
            }
        }
        .navigationTitle("Immersion log")
        .task { await load() }
        .sheet(isPresented: $adding) {
            NavigationStack { ManualImmersionForm { Task { await load() } } }.environment(app)
        }
    }

    private struct SourceRow: Identifiable {
        let label: String
        let minutes: Int
        var id: String { label }
    }

    /// Minutes per source over the last 30 days (the shared per-day rows, summed for display).
    private func bySource() -> [SourceRow] {
        var minutes: [ImmersionOrigin: Int] = [:]
        for d in days.suffix(30) {
            for s in d.bySource { minutes[s.source, default: 0] += Int(s.minutes) }
        }
        return minutes.filter { $0.value > 0 }.sorted { $0.value > $1.value }.map { SourceRow(label: ImmersionText.source($0.key), minutes: $0.value) }
    }

    private func load() async {
        loadError = nil
        do {
            days = try await SwiftSupport.shared.immersionDays(graph: app.graph, days: 140)
            sessions = try await SwiftSupport.shared.recentImmersionSessions(graph: app.graph, days: 14)
            let stored = try await app.graph.immersion.targetMinutes()
            target = Int(truncating: stored)
            savedTarget = target
        } catch {
            loadError = String(localized: "Couldn't load the immersion log: \(error.localizedDescription)")
        }
    }

    private func delete(_ id: String) {
        Task {
            try? await app.graph.immersion.delete(id: id)
            await load()
        }
    }
}

/// A manual immersion entry: today or an earlier day.
struct ManualImmersionForm: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let onSaved: () -> Void

    @State private var date = Date()
    @State private var minutes = 30
    @State private var active = true
    @State private var source = ImmersionOrigin.manual
    @State private var title = ""
    @State private var failure: String?
    @State private var saving = false

    private let sources: [ImmersionOrigin] = [.manual, .media, .podcast, .reader, .dialogue]

    var body: some View {
        Form {
            DatePicker("Day", selection: $date, in: ...Date(), displayedComponents: .date)
            Stepper(value: $minutes, in: 1...360, step: 5) {
                Text("\(minutes.formatted()) min")
            }
            Picker("Kind", selection: $active) {
                Text("Active (paying attention)").tag(true)
                Text("Passive (in the background)").tag(false)
            }
            Picker("Source", selection: $source) {
                ForEach(sources, id: \.self) { Text(ImmersionText.source($0)).tag($0) }
            }
            TextField("What was it? (optional)", text: $title)
            if let failure { Text(failure).foregroundStyle(.red).font(.caption) }
        }
        .navigationTitle("Log immersion")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(saving ? "Saving…" : "Save") { save() }.disabled(saving)
            }
        }
    }

    private func save() {
        saving = true
        failure = nil
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        let iso = formatter.string(from: date)
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                _ = try await SwiftSupport.shared.addManualImmersion(
                    graph: app.graph, isoDate: iso, minutes: Int32(minutes), active: active, source: source, title: trimmed.isEmpty ? nil : trimmed
                )
                onSaved()
                dismiss()
            } catch {
                failure = String(localized: "Couldn't save: \(error.localizedDescription)")
            }
            saving = false
        }
    }
}
