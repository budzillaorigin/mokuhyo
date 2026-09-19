import Shared
import SwiftUI

// Speaking drill sets, Swotter format (BRIEF_V2 §6.10, D-224; iOS UI D-267): English cue → pause to answer aloud →
// model answer → pause to repeat. Hands-free, keeps playing with the screen off, with lock-screen and headset
// controls.

private struct DrillSetRow: Identifiable {
    let id: String
    let title: String
    let jlpt: Int
    let description: String
    let aiGenerated: Bool
}

struct DrillSetsView: View {
    var body: some View {
        PracticeGate { repo in DrillSetList(repo: repo) }
            .navigationTitle("Speaking drills")
    }
}

private struct DrillSetList: View {
    let repo: PracticeRepository
    @State private var level = 0
    @State private var sets: [DrillSetRow]?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Picker("Level", selection: $level) {
                    Text("All").tag(0)
                    ForEach(jlptLevels, id: \.self) { Text(verbatim: "N\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
            } footer: {
                Text("Hear the English, say it in Japanese in the pause, hear the model answer, repeat it. Works hands-free with the screen off.")
            }
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let sets {
                if sets.isEmpty {
                    Text("No drill sets at this level in the installed pack.").foregroundStyle(.secondary)
                }
                ForEach(sets) { s in
                    NavigationLink(value: Route.drillSet(s.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.title)
                            HStack {
                                Text(verbatim: "N\(s.jlpt)").font(.caption).foregroundStyle(.secondary)
                                if !s.description.isEmpty { Text(s.description).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                                if s.aiGenerated { AiBadge() }
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .task(id: level) { await load() }
    }

    private func load() async {
        do {
            let filter: KotlinInt? = level == 0 ? nil : KotlinInt(int: Int32(level))
            let found = try await repo.drillSets(level: filter)
            sets = found.map {
                DrillSetRow(id: $0.id, title: $0.title, jlpt: Int($0.jlpt), description: SwiftSupport.shared.drillSetDescription(summary: $0), aiGenerated: $0.isAiGenerated)
            }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the drill sets: \(error.localizedDescription)")
        }
    }
}

struct DrillSetPlayerView: View {
    let setId: String
    @Environment(AppModel.self) private var app
    @AppStorage("drills.preset") private var preset = "default"
    @AppStorage("drills.repeat") private var repeatPause = true
    @AppStorage("drills.fixedPause") private var fixedPause = false
    @AppStorage("drills.showText") private var showText = true
    @State private var player = DrillPlayer()
    @State private var set: DrillSet?
    @State private var loaded = false
    @State private var error: String?
    @State private var preparing = false

    var body: some View {
        Group {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding()
            } else if set != nil {
                content
            } else if loaded {
                ContentUnavailableView("Drill set not found", systemImage: "questionmark.circle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(player.title.isEmpty ? String(localized: "Speaking drill") : player.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onDisappear { player.stop() }
    }

    private var content: some View {
        List {
            Section {
                if let item = player.currentItem {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Item \(String(player.itemIndex + 1)) of \(String(player.items.count))")
                                .font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            if item.aiGenerated { AiBadge() }
                        }
                        Text(item.prompt).font(.title3)
                        if showText && (player.stepCode == "ANSWER" || player.stepCode == "REPEAT_PAUSE" || player.finished) {
                            Text(item.answer).font(.japanese(size: 22)).japaneseSpeech()
                        } else {
                            Text(verbatim: "• • •").foregroundStyle(.secondary)
                                .accessibilityLabel(Text("Answer hidden until it plays"))
                        }
                        stepLabel
                    }
                    .padding(.vertical, 4)
                }
                controls
                if player.finished {
                    Label("Set finished. Play again or pick another set.", systemImage: "checkmark.circle").foregroundStyle(.green)
                }
            }
            Section {
                Picker("Pause length", selection: $preset) {
                    Text("Short").tag("short")
                    Text("Normal").tag("default")
                    Text("Long").tag("long")
                }
                .pickerStyle(.segmented)
                Toggle("Same pause for every sentence", isOn: $fixedPause)
                Toggle("Pause to repeat after the answer", isOn: $repeatPause)
                Toggle("Show the Japanese as it plays", isOn: $showText)
                if preparing { ProgressView() }
            } header: {
                Text("Timing")
            } footer: {
                Text("Pauses grow with the length of the answer unless you choose the same pause for every sentence. Changing the timing restarts the set.")
            }
            .onChange(of: preset) { _, _ in Task { await rebuild() } }
            .onChange(of: fixedPause) { _, _ in Task { await rebuild() } }
            .onChange(of: repeatPause) { _, _ in Task { await rebuild() } }
            Section {
                ForEach(Array(player.items.enumerated()), id: \.offset) { i, item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.prompt).font(.subheadline)
                        Text(item.answer).font(.japanese(size: 15)).foregroundStyle(.secondary).japaneseSpeech()
                    }
                    .listRowBackground(i == player.itemIndex ? Color.accentColor.opacity(0.1) : nil)
                }
            } header: {
                Text("Sentences")
            }
        }
    }

    @ViewBuilder
    private var stepLabel: some View {
        switch player.stepCode {
        case "PROMPT": Label("Listen to the cue", systemImage: "ear").font(.caption)
        case "ANSWER_PAUSE": countdown(String(localized: "Say it in Japanese"), icon: "mic")
        case "ANSWER": Label("Model answer", systemImage: "speaker.wave.2").font(.caption)
        case "REPEAT_PAUSE": countdown(String(localized: "Repeat it"), icon: "arrow.counterclockwise")
        case "GAP": Label("Next sentence…", systemImage: "forward").font(.caption)
        default: EmptyView()
        }
    }

    private func countdown(_ text: String, icon: String) -> some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { context in
            let left = player.stepEndsAt.map { max(0, $0.timeIntervalSince(context.date)) } ?? 0
            Label(String(format: "%@ · %.0f s", text, left.rounded(.up)), systemImage: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tint)
        }
    }

    private var controls: some View {
        HStack(spacing: 28) {
            Button { player.back() } label: { Image(systemName: "backward.end.fill").font(.title2) }
                .accessibilityLabel(Text("Previous sentence"))
            Button { player.toggle() } label: {
                Image(systemName: player.running ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 52))
            }
            .accessibilityLabel(player.running ? Text("Pause") : Text("Play"))
            Button { player.skip() } label: { Image(systemName: "forward.end.fill").font(.title2) }
                .accessibilityLabel(Text("Next sentence"))
        }
        .buttonStyle(.borderless)
        .frame(maxWidth: .infinity)
    }

    private func load() async {
        defer { loaded = true }
        do {
            guard let repo = try await app.graph.practice(), let found = try await repo.drillSet(id: setId) else { return }
            set = found
            error = nil
            await rebuild()
        } catch {
            self.error = String(localized: "Couldn't open the drill set: \(error.localizedDescription)")
        }
    }

    /// Builds the plan in shared code (answer lengths from the installed audio packs) and loads it into the player.
    private func rebuild() async {
        guard let set else { return }
        preparing = true
        defer { preparing = false }
        do {
            let plan = try await SwiftSupport.shared.drillPlan(graph: app.graph, set: set, preset: preset, repeatPause: repeatPause, fixedPause: fixedPause)
            player.load(plan: plan, title: set.summary.title, graph: app.graph)
        } catch {
            self.error = String(localized: "Couldn't prepare the drill: \(error.localizedDescription)")
        }
    }
}
