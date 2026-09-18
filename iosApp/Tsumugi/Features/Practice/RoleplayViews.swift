import Shared
import SwiftUI

/// Scenario role-plays (BRIEF §5.10), grouped by category, filterable by JLPT level.
struct ScenarioListView: View {
    var body: some View {
        PracticeGate { repo in ScenarioList(repo: repo) }
            .navigationTitle("Role-plays")
    }
}

private struct ScenarioList: View {
    let repo: PracticeRepository
    @State private var scenarios: [ScenarioSummary] = []
    @State private var loaded = false
    @State private var level = 0

    private var categories: [String] {
        var seen: [String] = []
        for s in scenarios where !seen.contains(s.category) { seen.append(s.category) }
        return seen
    }

    var body: some View {
        List {
            Section {
                Picker("Level", selection: $level) {
                    Text("All").tag(0)
                    ForEach(jlptLevels, id: \.self) { Text("N\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
            }
            if loaded && scenarios.isEmpty {
                Text("No scenarios at this level in the installed pack.").foregroundStyle(.secondary)
            }
            ForEach(categories, id: \.self) { category in
                Section(category) {
                    ForEach(scenarios.filter { $0.category == category }) { s in
                        NavigationLink(value: Route.roleplay(s.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.titleJa).font(.japanese(size: 17))
                                Text(s.titleEn).font(.subheadline)
                                HStack {
                                    Text("N\(s.jlpt) · ILR \(s.ilr)").font(.caption).foregroundStyle(.secondary)
                                    if s.aiGenerated { AIBadge() }
                                }
                            }
                        }
                    }
                }
            }
        }
        .task(id: level) {
            let filter: KotlinInt? = level == 0 ? nil : KotlinInt(int: Int32(level))
            let list = (try? await repo.scenarios(level: filter)) ?? []
            scenarios = list.map {
                ScenarioSummary(id: $0.id, titleEn: $0.titleEn, titleJa: $0.titleJa, jlpt: Int($0.jlpt), ilr: $0.ilr, category: $0.category, aiGenerated: $0.isAiGenerated)
            }
            loaded = true
        }
    }
}

/// One line of the role-play transcript.
struct ChatLine: Identifiable {
    let id: Int
    let learner: Bool
    let japanese: String
    let english: String
    let hint: String
    let engine: String?
}

@MainActor
@Observable
final class RoleplayModel {
    private(set) var loaded = false
    private(set) var missing = false
    private(set) var title = ""
    private(set) var titleJa = ""
    private(set) var setting = ""
    private(set) var roles = ""
    private(set) var goals: [String] = []
    private(set) var phrases: [String] = []
    private(set) var vocabulary: [(text: String, reading: String)] = []
    private(set) var scenarioIsAi = false
    private(set) var lines: [ChatLine] = []
    private(set) var busy = false
    private(set) var goalReached = false
    private(set) var feedback: [Int: TurnFeedback] = [:]
    private(set) var feedbackBusy: Int?

    @ObservationIgnored private var session: RoleplaySession?

    func load(graph: AppGraph, scenarioId: String, voice: VoicePlayer) async {
        guard session == nil else { return }
        guard let s = try? await graph.roleplay(scenarioId: scenarioId) else {
            missing = true
            loaded = true
            return
        }
        session = s
        let sc = s.scenario
        title = sc.titleEn
        titleJa = sc.titleJa
        setting = sc.setting
        roles = "You: \(sc.learnerRole) · Partner: \(sc.partnerRole)"
        goals = sc.goals
        phrases = sc.phrases
        vocabulary = sc.vocabulary.map { (text: $0.text, reading: $0.reading) }
        scenarioIsAi = sc.isAiGenerated
        loaded = true
        busy = true
        if let first = try? await s.start() {
            append(first)
            busy = false
            await voice.sayPartner(first.japanese, graph: graph)
        }
        busy = false
    }

    func send(_ text: String, graph: AppGraph, voice: VoicePlayer) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let session, !trimmed.isEmpty, !busy else { return }
        busy = true
        lines.append(ChatLine(id: lines.count, learner: true, japanese: trimmed, english: "", hint: "", engine: nil))
        let reply = try? await session.reply(text: trimmed)
        goalReached = session.goalReached
        busy = false
        if let reply {
            append(reply)
            await voice.sayPartner(reply.japanese, graph: graph)
        }
    }

    func requestFeedback(for line: ChatLine) async {
        guard let session, feedback[line.id] == nil else { return }
        feedbackBusy = line.id
        if let fb = try? await session.feedback(text: line.japanese) {
            feedback[line.id] = fb
        }
        feedbackBusy = nil
    }

    var lastHint: String? {
        guard let last = lines.last, !last.learner, !last.hint.isEmpty else { return nil }
        return last.hint
    }

    private func append(_ line: ConversationLine) {
        lines.append(ChatLine(
            id: lines.count, learner: line.speaker == .learner, japanese: line.japanese,
            english: line.english, hint: line.hint, engine: line.engine
        ))
    }
}

/// The conversation loop: partner speaks (TTS), learner answers by voice or typing, feedback on request.
struct RoleplayView: View {
    @Environment(AppModel.self) private var app
    let scenarioId: String

    @State private var model = RoleplayModel()
    @State private var voice = VoicePlayer()
    @State private var recorder = Recorder()
    @State private var input = ""
    @State private var showHint = false
    @State private var showEnglish = false
    @State private var transcribing = false
    @State private var sttNote: String?
    @State private var feedbackLine: ChatLine?

    var body: some View {
        Group {
            if !model.loaded {
                ProgressView()
            } else if model.missing {
                ContentUnavailableView("Scenario unavailable", systemImage: "questionmark.bubble", description: Text("This scenario isn't in the installed practice pack."))
            } else {
                conversation
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load(graph: app.graph, scenarioId: scenarioId, voice: voice) }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
        .sheet(item: $feedbackLine) { line in
            NavigationStack {
                TurnFeedbackSheet(line: line, model: model)
            }
        }
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        header
                        ForEach(model.lines) { line in
                            bubble(line).id(line.id)
                        }
                        if model.busy { ProgressView().frame(maxWidth: .infinity) }
                        if model.goalReached {
                            Label("Goal reached! Keep going or try another scenario.", systemImage: "flag.checkered")
                                .font(.subheadline.weight(.semibold)).foregroundStyle(.green)
                        }
                    }
                    .padding()
                }
                .onChange(of: model.lines.count) { _, _ in
                    if let last = model.lines.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            Divider()
            composer.padding()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            AiStatusBanner()
            HStack {
                Text(model.titleJa).font(.japanese(size: 20, weight: .semibold))
                if model.scenarioIsAi { AIBadge() }
            }
            Text(model.setting).font(.subheadline)
            Text(model.roles).font(.caption).foregroundStyle(.secondary)
            if !model.goals.isEmpty {
                Text("Goals: " + model.goals.joined(separator: "; ")).font(.caption)
            }
            if !model.vocabulary.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(Array(model.vocabulary.enumerated()), id: \.offset) { _, v in
                            NavigationLink(value: Route.lookup(v.text)) {
                                VStack(spacing: 0) {
                                    Text(v.reading).font(.caption2)
                                    Text(v.text).font(.japanese(size: 15))
                                }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
            Toggle("Show English", isOn: $showEnglish).font(.caption)
        }
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private func bubble(_ line: ChatLine) -> some View {
        VStack(alignment: line.learner ? .trailing : .leading, spacing: 4) {
            Text(line.japanese)
                .font(.japanese(size: 18))
                .padding(10)
                .background(line.learner ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .textSelection(.enabled)
            if showEnglish && !line.english.isEmpty {
                Text(line.english).font(.caption).foregroundStyle(.secondary)
            }
            HStack(spacing: 10) {
                if line.engine != nil { AIBadge(engine: line.engine) }
                if line.learner {
                    Button(model.feedbackBusy == line.id ? "Checking…" : "Feedback") {
                        feedbackLine = line
                    }
                    .font(.caption)
                } else {
                    Button {
                        Task { await voice.sayPartner(line.japanese, graph: app.graph) }
                    } label: {
                        Image(systemName: "speaker.wave.2")
                    }
                    .font(.caption)
                    .accessibilityLabel("Play again")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: line.learner ? .trailing : .leading)
    }

    private var composer: some View {
        VStack(spacing: 8) {
            if showHint, let hint = model.lastHint {
                Text("Hint: \(hint)").font(.caption).frame(maxWidth: .infinity, alignment: .leading)
            }
            if let sttNote { Text(sttNote).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading) }
            HStack {
                TextField("Type or speak your reply", text: $input, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...3)
                Button {
                    let text = input
                    input = ""
                    showHint = false
                    Task { await model.send(text, graph: app.graph, voice: voice) }
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty || model.busy)
                .accessibilityLabel("Send")
            }
            HStack {
                RecordButton(recorder: recorder, label: transcribing ? "Listening…" : "Speak", disabled: transcribing || model.busy) { samples in
                    transcribe(samples)
                }
                Button(showHint ? "Hide hint" : "Hint") { showHint.toggle() }
                    .buttonStyle(.bordered)
                    .disabled(model.lastHint == nil)
            }
        }
    }

    private func transcribe(_ samples: [Float]) {
        voice.stop()
        transcribing = true
        sttNote = nil
        let graph = app.graph
        Task {
            let out = await SpeechToText.transcribe(samples, graph: graph)
            transcribing = false
            if let error = out.error {
                sttNote = error
            } else {
                input = out.text
                sttNote = "Heard by \(out.engine). Edit if needed, then send."
            }
        }
    }
}

/// Corrections (diff), the natural version, and "say it again" with pronunciation feedback (Kigaru's loop).
private struct TurnFeedbackSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let line: ChatLine
    let model: RoleplayModel

    @State private var recorder = Recorder()
    @State private var voice = VoicePlayer()
    @State private var attempt: String?
    @State private var report: PronunciationReport?
    @State private var working = false
    @State private var note: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("You said").font(.caption.weight(.semibold))
                Text(line.japanese).font(.japanese(size: 18))
                if let fb = model.feedback[line.id] {
                    content(fb)
                } else if model.feedbackBusy == line.id {
                    ProgressView("Checking your sentence…")
                } else {
                    ProgressView()
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Feedback")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        .task { await model.requestFeedback(for: line) }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
    }

    @ViewBuilder
    private func content(_ fb: TurnFeedback) -> some View {
        if let reason = fb.unavailable {
            VStack(alignment: .leading, spacing: 6) {
                Text("No feedback: \(reason)").font(.subheadline)
                Text("Download a model or add your own server in Practice → AI & speech settings.").font(.caption).foregroundStyle(.secondary)
            }
        }
        if fb.engine != nil { AIBadge(engine: fb.engine) }
        if let c = fb.correction {
            VStack(alignment: .leading, spacing: 6) {
                Text("Corrections").font(.headline)
                if c.isUnsure {
                    Text("Unsure: the model isn't confident about this one, so no correction is shown as fact.").font(.subheadline).foregroundStyle(.orange)
                } else if c.isCorrect {
                    Label("Looks correct.", systemImage: "checkmark.circle").foregroundStyle(.green)
                } else {
                    Text(c.corrected).font(.japanese(size: 18))
                    ForEach(Array(c.edits.enumerated()), id: \.offset) { _, e in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(e.original).strikethrough().foregroundStyle(.red)
                                Image(systemName: "arrow.right").font(.caption)
                                Text(e.replacement).foregroundStyle(.green)
                            }
                            .font(.japanese(size: 16))
                            if !e.reason.isEmpty { Text(e.reason).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
                if !c.explanation.isEmpty && !c.isUnsure { Text(c.explanation).font(.subheadline) }
            }
        }
        if let n = fb.natural {
            VStack(alignment: .leading, spacing: 6) {
                Text("Natural version").font(.headline)
                Text(n.rewrite).font(.japanese(size: 20)).textSelection(.enabled)
                if !n.notes.isEmpty { Text(n.notes).font(.caption).foregroundStyle(.secondary) }
                HStack {
                    Button {
                        Task { await voice.say(n.rewrite) }
                    } label: {
                        Label("Listen", systemImage: "speaker.wave.2")
                    }
                    .buttonStyle(.bordered)
                }
                Text("Say it again").font(.subheadline.weight(.semibold)).padding(.top, 6)
                RecordButton(recorder: recorder, label: working ? "Scoring…" : "Say it again", disabled: working) { samples in
                    score(samples, target: n.rewrite)
                }
                if let attempt { Text("Heard: \(attempt)").font(.japanese(size: 15)) }
                if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
                if let report { PronunciationPanel(report: report) }
            }
        }
    }

    private func score(_ samples: [Float], target: String) {
        voice.stop()
        working = true
        note = nil
        let graph = app.graph
        Task {
            let out = await SpeechToText.transcribe(samples, graph: graph)
            attempt = out.error == nil ? out.text : nil
            if let error = out.error { note = error + " Scoring pitch and fluency only." }
            report = try? await SwiftSupport.shared.analyzePronunciation(graph: graph, sentence: target, transcript: out.error == nil ? out.text : nil, samples: kotlinFloats(samples))
            working = false
        }
    }
}
