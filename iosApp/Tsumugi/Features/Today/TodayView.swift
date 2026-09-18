import Shared
import SwiftUI

/// What a Today block opens (a Swift mirror of the shared `TodayLaunch`, so navigation values stay Hashable).
enum TodayOpen: Hashable, Identifiable {
    case reviews(limit: Int)
    case lessons
    case kana(count: Int)
    case grammar
    case reader(docId: String)
    case dialogue(id: String)
    case shadowing([ShadowLine])
    case speaking(scenarioId: String)
    case writing([String])

    var id: Self { self }

    /// The block this screen finishes (weekly challenges count finished blocks, G-11).
    var block: TodayBlockKind {
        switch self {
        case .reviews: .reviews
        case .lessons, .kana: .lessons
        case .grammar: .grammar
        case .reader, .dialogue: .immersion
        case .shadowing: .shadowing
        case .speaking: .speaking
        case .writing: .writing
        }
    }

    /// Routes the plan's launch payload; nil when the block has nothing to start (an honest empty state).
    init?(_ launch: TodayLaunch?) {
        guard let launch else { return nil }
        switch onEnum(of: launch) {
        case .reviews(let r): self = .reviews(limit: Int(r.limit))
        case .lessons: self = .lessons
        case .kana(let k): self = .kana(count: Int(k.count))
        case .grammar: self = .grammar
        case .immersion(let i):
            if i.target.source == .reader {
                self = .reader(docId: i.target.id)
            } else {
                self = .dialogue(id: i.target.id)
            }
        case .shadowing(let s): self = .shadowing(s.sentences.map { ShadowLine($0) })
        case .speaking(let s): self = .speaking(scenarioId: s.scenarioId)
        case .writing(let w): self = .writing(w.kanji)
        }
    }
}

/// The structured daily path (BRIEF §5.6, BRIEF_V2 G-01): the day's blocks in order, sized to the chosen budget.
/// Every block opens its own screen from the plan's launch payload, can run a focus timer, and is recorded as
/// finished (`markTodayBlockDone`) when its screen completes.
struct TodayView: View {
    @Environment(AppModel.self) private var app
    @State private var plan: TodayPlan?
    @State private var status: PathStatus?
    @State private var stats: StatsSnapshot?
    @State private var recompute: RecomputeProgress?
    @State private var loadError: String?
    @State private var open: TodayOpen?

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
                    StreakCard(streak: stats.streak) { await refresh() }
                    Text("\(Int(stats.reviewsToday)) answers today").font(.subheadline).foregroundStyle(.secondary)
                }
                if let loadError, plan == nil {
                    ContentUnavailableView {
                        Label("Couldn't plan today", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(loadError)
                    } actions: {
                        Button("Retry") { Task { await refresh() } }.buttonStyle(.borderedProminent)
                    }
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
                    Text("\(SharedText.phase(plan.phase)) phase · about \(Int(plan.plannedMinutes)) min planned").font(.subheadline)
                    ForEach(Array(plan.blocks.enumerated()), id: \.offset) { i, block in
                        blockRow(i, block)
                    }
                    WeeklyChallengeCard(challenge: plan.challenge)
                } else if loadError == nil {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let status {
                    Text("Level \(status.currentLevel) · \(Int(status.levelProgress * 100))% of kanji at Guru")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding()
        }
        .navigationTitle("Today")
        .navigationDestination(item: $open) { dest in
            TodayBlockHost(kind: dest.block) { finish in destination(dest, finish: finish) }
        }
        .task { await refresh() }
        .task { for await p in app.graph.recomputeProgress { recompute = p } }
        .refreshable { await refresh() }
        .onChange(of: open) { _, now in if now == nil { Task { await refresh() } } }
    }

    @ViewBuilder
    private func destination(_ dest: TodayOpen, finish: @escaping () -> Void) -> some View {
        switch dest {
        case .reviews(let limit): ReviewView(limit: limit, onFinished: finish)
        case .lessons: LessonView()
        case .kana(let count): KanaTodayView(count: count, onFinished: finish)
        case .grammar: GrammarLessonView()
        case .reader(let id): ReaderView(docId: id)
        case .dialogue(let id): DialoguePlayerView(dialogueId: id)
        case .shadowing(let lines): ShadowingView(lines: lines, onFinished: finish)
        case .speaking(let id): RoleplayView(scenarioId: id)
        case .writing(let kanji): WritingPracticeView(kanji: kanji, onFinished: finish)
        }
    }

    @ViewBuilder
    private func blockRow(_ index: Int, _ block: TodayBlock) -> some View {
        let row = HStack {
            Text(block.done ? "✓" : "\(index + 1)").font(.title3.weight(.semibold)).frame(width: 30)
                .accessibilityLabel(block.done ? Text("Done") : Text("Step \(index + 1)"))
            VStack(alignment: .leading) {
                HStack(spacing: 6) {
                    Text(block.title).font(.headline)
                    if block.optional { TagView(String(localized: "Optional")) }
                    if isAiGenerated(block.launch) { AiBadge() }
                }
                Text(block.detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if block.minutes > 0 && !block.done { Text("\(Int(block.minutes)) min").font(.caption) }
            if TodayOpen(block.launch) != nil { Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary) }
        }
        .padding()
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)

        if let dest = TodayOpen(block.launch) {
            Button { open = dest } label: { row }.buttonStyle(.plain)
        } else {
            row
        }
    }

    /// Scenarios, dialogues and example sentences drafted by a model keep their badge on Today too (rule 10).
    private func isAiGenerated(_ launch: TodayLaunch?) -> Bool {
        guard let launch else { return false }
        switch onEnum(of: launch) {
        case .immersion(let i): return i.target.aiGenerated
        case .speaking(let s): return s.aiGenerated
        case .shadowing(let s): return s.sentences.contains { $0.aiGenerated }
        default: return false
        }
    }

    private func refresh() async {
        do {
            plan = try await app.graph.today()
            loadError = nil
        } catch {
            loadError = error.localizedDescription
        }
        status = try? await app.graph.path()?.status()
        stats = try? await app.graph.stats.snapshot(heatmapDays: 140)
    }
}

/// Wraps a Today block's screen: a focus timer from the toolbar (5–20 min, `FocusTimer`), and "Finish block", which
/// records the block (`markTodayBlockDone`) and returns to Today. Screens that know when they're done call `finish`.
struct TodayBlockHost<Content: View>: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let kind: TodayBlockKind
    @ViewBuilder let content: (_ finish: @escaping () -> Void) -> Content

    @State private var timer: FocusTimer?
    @State private var finishing = false
    @State private var error: String?

    var body: some View {
        content(finish)
            .safeAreaInset(edge: .top) {
                VStack(spacing: 4) {
                    if let timer {
                        FocusTimerBanner(timer: timer) { self.timer = nil }
                    }
                    if let error {
                        Text(error).font(.caption).foregroundStyle(.red)
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(timerOptions, id: \.self) { minutes in
                            Button("\(minutes) min") { timer = SwiftSupport.shared.focusTimer(block: kind, minutes: Int32(minutes)) }
                        }
                        if timer != nil {
                            Button("Stop timer", role: .destructive) { timer = nil }
                        }
                    } label: {
                        Image(systemName: "timer")
                    }
                    .accessibilityLabel(Text("Focus timer"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Finish block") { finish() }.disabled(finishing)
                }
            }
    }

    private var timerOptions: [Int] { FocusTimer.companion.OPTIONS_MINUTES.map { Int($0.intValue) } }

    private func finish() {
        guard !finishing else { return }
        finishing = true
        error = nil
        let graph = app.graph
        let block = kind
        Task {
            do {
                try await graph.markTodayBlockDone(kind: block)
                dismiss()
            } catch {
                self.error = String(localized: "Couldn't record the block: \(error.localizedDescription)")
            }
            finishing = false
        }
    }
}

/// A running focus timer: work countdown, then the break, then done.
struct FocusTimerBanner: View {
    let timer: FocusTimer
    let onClose: () -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let remaining = SwiftSupport.shared.focusRemainingSeconds(timer: timer)
            let rest = SwiftSupport.shared.focusBreakRemainingSeconds(timer: timer)
            HStack {
                if remaining > 0 {
                    Label("Focus \(clockText(seconds: remaining))", systemImage: "timer")
                    ProgressView(value: timer.fraction).frame(maxWidth: 120)
                } else if rest > 0 {
                    Label("Break \(clockText(seconds: rest))", systemImage: "cup.and.saucer")
                } else {
                    Label("Timer done", systemImage: "checkmark.circle")
                }
                Spacer()
                Button {
                    onClose()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                }
                .accessibilityLabel(Text("Stop timer"))
            }
            .font(.subheadline.monospacedDigit())
            .padding(.horizontal)
            .padding(.vertical, 6)
            .background(.regularMaterial)
        }
    }
}
