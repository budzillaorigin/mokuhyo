import Shared
import SwiftUI

/// Dialogue drills (BRIEF §5.9): scripted two-voice dialogues from the practice pack.
struct DialogueListView: View {
    var body: some View {
        PracticeGate { repo in DialogueList(repo: repo) }
            .navigationTitle("Dialogues")
    }
}

private struct DialogueList: View {
    let repo: PracticeRepository
    @State private var dialogues: [DialogueSummary] = []
    @State private var loaded = false
    @State private var level = 0

    var body: some View {
        List {
            Section {
                Picker("Level", selection: $level) {
                    Text("All").tag(0)
                    ForEach(jlptLevels, id: \.self) { Text("N\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
            }
            if loaded && dialogues.isEmpty {
                Text("No dialogues at this level in the installed pack.").foregroundStyle(.secondary)
            }
            ForEach(dialogues, id: \.id) { d in
                NavigationLink(value: Route.dialogue(d.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(d.title).font(.japanese(size: 17))
                        HStack {
                            Text("N\(d.jlpt) · \(d.topic)").font(.caption).foregroundStyle(.secondary)
                            if d.isAiGenerated { AIBadge() }
                        }
                    }
                }
            }
        }
        .task(id: level) {
            let filter: KotlinInt? = level == 0 ? nil : KotlinInt(int: Int32(level))
            dialogues = (try? await repo.dialogues(level: filter)) ?? []
            loaded = true
        }
    }
}

/// Swift copy of a pack dialogue (the Kotlin `Dialogue`/`Speaker` names clash with other packages).
struct DialogueData {
    struct GapData {
        let text: String
        let start: Int
        let end: Int
    }

    struct Line: Identifiable {
        let id: Int
        let speakerName: String
        let voice: VoicePlayer.Voice
        let japanese: String
        let english: String
        let gaps: [GapData]
        let chunks: [String]

        /// The line with its first gap blanked out (gap offsets are UTF-16, like NSString).
        var gapped: String? {
            guard let gap = gaps.first else { return nil }
            let ns = japanese as NSString
            guard gap.start >= 0, gap.end > gap.start, gap.end <= ns.length else { return nil }
            return ns.replacingCharacters(in: NSRange(location: gap.start, length: gap.end - gap.start), with: "＿＿＿")
        }
    }

    struct Question: Identifiable {
        let id: Int
        let question: String
        let choices: [String]
        let answer: Int
    }

    let title: String
    let jlpt: Int
    let topic: String
    let aiGenerated: Bool
    let lines: [Line]
    let questions: [Question]
}

struct DialoguePlayerView: View {
    let dialogueId: String

    var body: some View {
        PracticeGate { repo in DialoguePlayer(repo: repo, dialogueId: dialogueId) }
            .navigationBarTitleDisplayMode(.inline)
    }
}

private enum ListenMode: String, CaseIterable, Identifiable {
    case listen = "Listen"
    case gapFill = "Gap-fill"
    case order = "Order"
    case questions = "Questions"

    var id: String { rawValue }
}

private struct DialoguePlayer: View {
    @Environment(AppModel.self) private var app
    let repo: PracticeRepository
    let dialogueId: String

    @State private var data: DialogueData?
    @State private var loaded = false
    @State private var mode = ListenMode.listen
    @State private var voice = VoicePlayer()
    @State private var rate = 1.0
    @State private var playing: Int?
    @State private var playTask: Task<Void, Never>?
    @State private var showJapanese = true
    @State private var showEnglish = false
    @State private var loopLine = false

    var body: some View {
        Group {
            if let data {
                content(data)
            } else if loaded {
                ContentUnavailableView("Dialogue not found", systemImage: "questionmark.bubble")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(data?.title ?? "Dialogue")
        .task { await load() }
        .onDisappear { stop() }
    }

    private func content(_ d: DialogueData) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("N\(d.jlpt) · \(d.topic)").font(.caption).foregroundStyle(.secondary)
                    if d.aiGenerated { AIBadge() }
                }
                Picker("Mode", selection: $mode) {
                    ForEach(ListenMode.allCases) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
                }
                .pickerStyle(.segmented)
                .onChange(of: mode) { _, _ in stop() }
                HStack {
                    Text("Speed \(String(format: "%.1f", rate))×").font(.caption.monospacedDigit())
                    Slider(value: $rate, in: 0.7...1.2, step: 0.1)
                }
                switch mode {
                case .listen: listen(d)
                case .gapFill: GapFillDrill(lines: d.lines, play: { play([$0]) })
                case .order: OrderDrill(lines: d.lines.filter { $0.chunks.count >= 3 }, play: { play([$0]) })
                case .questions: QuestionsDrill(questions: d.questions, playAll: { play(d.lines) })
                }
            }
            .padding()
        }
    }

    @ViewBuilder
    private func listen(_ d: DialogueData) -> some View {
        HStack {
            Button {
                if playTask != nil { stop() } else { play(d.lines) }
            } label: {
                Label(playTask != nil ? "Stop" : "Play dialogue", systemImage: playTask != nil ? "stop.fill" : "play.fill")
            }
            .buttonStyle(.borderedProminent)
            Toggle("Loop line", isOn: $loopLine).toggleStyle(.button)
        }
        HStack {
            Toggle("Japanese", isOn: $showJapanese).toggleStyle(.button)
            Toggle("English", isOn: $showEnglish).toggleStyle(.button)
        }
        .font(.caption)
        ForEach(d.lines) { line in
            VStack(alignment: .leading, spacing: 3) {
                Text(line.speakerName).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                if showJapanese {
                    Text(line.japanese).font(.japanese(size: 19)).japaneseSpeech()
                } else {
                    Text("• • •").foregroundStyle(.secondary)
                        .accessibilityLabel(Text("Japanese hidden"))
                }
                if showEnglish { Text(line.english).font(.caption) }
                HStack(spacing: 14) {
                    Button { play([line]) } label: { Image(systemName: "play.circle") }
                        .accessibilityLabel("Play line")
                    NavigationLink(value: Route.pronunciation(line.japanese)) {
                        Label("Shadow", systemImage: "mic")
                    }
                }
                .font(.subheadline)
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(playing == line.id ? Color.accentColor.opacity(0.15) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
        }
    }

    private func play(_ lines: [DialogueData.Line]) {
        stop()
        let speed = Float(rate)
        let graph = app.graph
        let id = dialogueId
        let title = data?.title
        playTask = Task {
            let started = Date()
            repeat {
                for line in lines {
                    if Task.isCancelled { break }
                    playing = line.id
                    // Pre-rendered dialogue audio when installed (rule 20); line ids are the pack's line ords.
                    await voice.say(line.japanese, key: PackAudio.dialogueKey(dialogueId: id, line: line.id), graph: graph, voice: line.voice, rate: speed)
                }
            } while loopLine && lines.count == 1 && !Task.isCancelled
            // Listening time goes into the immersion log (D-194); the shared log drops stretches under 15 s.
            let seconds = Int64(Date().timeIntervalSince(started))
            _ = try? await graph.immersion.report(source: .dialogue, mode: .active, elapsedSeconds: seconds, ref: id, title: title)
            // A cancelled run was replaced by a newer one (or stopped); leave the state to that.
            if !Task.isCancelled {
                playing = nil
                playTask = nil
            }
        }
    }

    private func stop() {
        playTask?.cancel()
        playTask = nil
        voice.stop()
        playing = nil
    }

    private func load() async {
        guard data == nil else { return }
        defer { loaded = true }
        guard let d = try? await repo.dialogue(id: dialogueId) else { return }
        var lines: [DialogueData.Line] = []
        for (i, l) in d.lines.enumerated() {
            let speaker = d.speaker(id: l.speaker)
            lines.append(DialogueData.Line(
                id: i,
                speakerName: speaker?.name ?? l.speaker,
                voice: VoicePlayer.Voice(hint: speaker?.voice),
                japanese: l.japanese,
                english: l.english,
                gaps: l.gaps.map { DialogueData.GapData(text: $0.text, start: Int($0.start), end: Int($0.end)) },
                chunks: l.chunks
            ))
        }
        let questions = d.questions.enumerated().map { i, q in
            DialogueData.Question(id: i, question: q.question, choices: q.choices, answer: Int(q.answer))
        }
        data = DialogueData(title: d.title, jlpt: Int(d.jlpt), topic: d.topic, aiGenerated: d.isAiGenerated, lines: lines, questions: questions)
    }
}

/// Listen, then type the missing word.
private struct GapFillDrill: View {
    let lines: [DialogueData.Line]
    let play: (DialogueData.Line) -> Void
    @State private var answers: [Int: String] = [:]
    @State private var checked: Set<Int> = []

    var body: some View {
        let gapped = lines.filter { $0.gapped != nil }
        if gapped.isEmpty {
            Text("This dialogue has no gap-fill lines.").foregroundStyle(.secondary)
        }
        ForEach(gapped) { line in
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Button { play(line) } label: { Image(systemName: "play.circle") }
                        .accessibilityLabel("Play line")
                    Text(line.gapped ?? "").font(.japanese(size: 18))
                }
                HStack {
                    TextField("Missing word", text: Binding(get: { answers[line.id] ?? "" }, set: { answers[line.id] = $0 }))
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Check") { checked.insert(line.id) }.buttonStyle(.bordered)
                }
                if checked.contains(line.id), let gap = line.gaps.first {
                    let given = (answers[line.id] ?? "").trimmingCharacters(in: .whitespaces)
                    if given == gap.text {
                        Label("Correct", systemImage: "checkmark.circle").foregroundStyle(.green)
                    } else {
                        Text("Answer: \(gap.text)").font(.japanese(size: 16)).foregroundStyle(.orange)
                    }
                    Text(line.japanese).font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// Listen, then put the line's phrase chunks back in order.
private struct OrderDrill: View {
    let lines: [DialogueData.Line]
    let play: (DialogueData.Line) -> Void
    @State private var index = 0
    @State private var shuffled: [Int] = []
    @State private var picked: [Int] = []
    @State private var correct = 0

    var body: some View {
        if lines.isEmpty {
            Text("No lines in this dialogue are long enough to order.").foregroundStyle(.secondary)
        } else if index >= lines.count {
            VStack(alignment: .leading, spacing: 8) {
                Text("Done: \(correct) of \(lines.count) in the right order.").font(.headline)
                Button("Again") { index = 0; correct = 0; reset() }.buttonStyle(.bordered)
            }
        } else {
            let line = lines[index]
            VStack(alignment: .leading, spacing: 10) {
                Text("Line \(index + 1) of \(lines.count)").font(.caption).foregroundStyle(.secondary)
                Button { play(line) } label: { Label("Play line", systemImage: "play.circle") }
                    .buttonStyle(.bordered)
                Text(picked.map { line.chunks[$0] }.joined())
                    .font(.japanese(size: 19))
                    .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                    .padding(6)
                    .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 8))
                FlowChips(items: shuffled.filter { !picked.contains($0) }.map { FlowChips.Chip(id: $0, text: line.chunks[$0]) }) { i in
                    picked.append(i)
                }
                if picked.count == line.chunks.count {
                    let ok = picked.map { line.chunks[$0] } == line.chunks
                    Label(ok ? "Correct" : "Not quite: \(line.japanese)", systemImage: ok ? "checkmark.circle" : "xmark.circle")
                        .foregroundStyle(ok ? .green : .orange)
                    Button("Next") {
                        if ok { correct += 1 }
                        index += 1
                        reset()
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button("Reset") { picked = [] }.disabled(picked.isEmpty)
                }
            }
            .onAppear { if shuffled.isEmpty { reset() } }
        }
    }

    private func reset() {
        picked = []
        guard index < lines.count else { return }
        var order = Array(lines[index].chunks.indices)
        if order.count > 1 {
            repeat { order.shuffle() } while order == Array(lines[index].chunks.indices)
        }
        shuffled = order
    }
}

/// Wrapping row of tappable chips.
struct FlowChips: View {
    struct Chip: Identifiable {
        let id: Int
        let text: String
    }

    let items: [Chip]
    let onTap: (Int) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items) { item in
                Button(item.text) { onTap(item.id) }
                    .font(.japanese(size: 17))
                    .buttonStyle(.bordered)
            }
        }
    }
}

/// Comprehension questions after listening.
private struct QuestionsDrill: View {
    let questions: [DialogueData.Question]
    let playAll: () -> Void
    @State private var chosen: [Int: Int] = [:]

    var body: some View {
        if questions.isEmpty {
            Text("This dialogue has no comprehension questions.").foregroundStyle(.secondary)
        } else {
            Button { playAll() } label: { Label("Listen to the whole dialogue", systemImage: "play.fill") }
                .buttonStyle(.bordered)
            ForEach(questions) { q in
                VStack(alignment: .leading, spacing: 6) {
                    Text(q.question).font(.headline)
                    ForEach(Array(q.choices.enumerated()), id: \.offset) { i, choice in
                        Button {
                            if chosen[q.id] == nil { chosen[q.id] = i }
                        } label: {
                            HStack {
                                Text(choice).multilineTextAlignment(.leading)
                                Spacer()
                                if let c = chosen[q.id] {
                                    if i == q.answer { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                    else if i == c { Image(systemName: "xmark.circle.fill").foregroundStyle(.red) }
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.vertical, 4)
            }
            let answered = chosen.count
            if answered == questions.count {
                let right = questions.filter { chosen[$0.id] == $0.answer }.count
                Text("\(right) of \(questions.count) correct").font(.headline)
            }
        }
    }
}
