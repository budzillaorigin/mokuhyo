import Shared
import SwiftUI

// Track drills (BRIEF_V2 §6.5, D-215…D-217; iOS UI D-263): keigo, email slots, fill-in, synonym/antonym, usage,
// meaning, and memorize-and-perform. Checking happens in shared code (`check`, `PerformanceSession`); these views only
// collect the answer and show the result.

enum TrackDrillText {
    static func typeName(_ code: String) -> String {
        switch code {
        case "keigo": String(localized: "Keigo transformations")
        case "email": String(localized: "Business emails")
        case "fill_in": String(localized: "Fill in the blank")
        case "synonym": String(localized: "Synonyms and antonyms")
        case "usage": String(localized: "Right or wrong usage")
        case "meaning": String(localized: "Meanings")
        case "perform": String(localized: "Performances")
        default: code
        }
    }

    /// The form a keigo answer must take (KeigoForm codes).
    static func formName(_ code: String) -> String {
        switch code {
        case "dictionary": String(localized: "dictionary form")
        case "masu": String(localized: "ます form")
        case "past": String(localized: "past form")
        case "masu-past": String(localized: "ました form")
        case "te": String(localized: "て form")
        default: code
        }
    }
}

/// What the learner got for one drill: the shared `DrillResult`, copied.
private struct DrillFeedback {
    let correct: Bool
    let expected: [String]
    let explanation: String

    init(_ r: DrillResult) {
        correct = r.correct
        expected = r.expected
        explanation = r.explanation
    }
}

private struct FeedbackView: View {
    let feedback: DrillFeedback

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(feedback.correct ? String(localized: "Correct") : String(localized: "Not quite"),
                  systemImage: feedback.correct ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(feedback.correct ? Color.green : Color.orange)
                .font(.headline)
            if !feedback.correct && !feedback.expected.isEmpty {
                Text("Answer: \(feedback.expected.joined(separator: " / "))").font(.japanese(size: 16)).japaneseSpeech()
            }
            if !feedback.explanation.isEmpty { Text(feedback.explanation).font(.subheadline) }
        }
    }
}

/// A track's drills of one type, one at a time with a running score (perform opens its own screen).
struct TrackDrillsView: View {
    let trackId: String
    let type: String
    @Environment(AppModel.self) private var app
    @State private var drills: [any TrackDrill]?
    @State private var error: String?
    @State private var index = 0
    @State private var right = 0
    @State private var answered = 0

    var body: some View {
        Group {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding()
            } else if let drills {
                if drills.isEmpty {
                    ContentUnavailableView("No drills of this kind", systemImage: "list.bullet.rectangle")
                } else if type == "perform" {
                    performList(drills.compactMap { $0 as? PerformDrill })
                } else if index >= drills.count {
                    VStack(spacing: 12) {
                        Text("Done: \(String(right)) of \(String(answered)) right").font(.title3.weight(.semibold))
                        Button("Again") { restart() }.buttonStyle(.borderedProminent)
                    }
                    .padding()
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack {
                                Text("\(String(index + 1)) of \(String(drills.count))").font(.caption).foregroundStyle(.secondary)
                                Spacer()
                                Text("\(String(right)) right").font(.caption.monospacedDigit())
                            }
                            TrackDrillCard(drill: drills[index]) { correct in
                                answered += 1
                                if correct { right += 1 }
                            } next: {
                                index += 1
                            }
                            .id(index)
                        }
                        .padding()
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(TrackDrillText.typeName(type))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func performList(_ list: [PerformDrill]) -> some View {
        List(list, id: \.id) { d in
            NavigationLink(value: Route.trackDrill(d.id)) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.title)
                    HStack {
                        Text(d.titleJa).font(.japanese(size: 13)).foregroundStyle(.secondary)
                        if d.isAiGenerated { AiBadge() }
                    }
                }
            }
        }
    }

    private func restart() {
        index = 0
        right = 0
        answered = 0
        drills = drills?.shuffled()
    }

    private func load() async {
        do {
            guard let repo = try await app.graph.trackRepository() else {
                drills = []
                return
            }
            drills = try await SwiftSupport.shared.trackDrills(repo: repo, trackId: trackId, typeCode: type)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the drills: \(error.localizedDescription)")
        }
    }
}

/// One drill (perform opens from a link instead).
struct TrackDrillCard: View {
    let drill: any TrackDrill
    let onAnswer: (Bool) -> Void
    let next: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if drill.isAiGenerated { AiBadge() }
            if let d = drill as? KeigoDrill {
                KeigoCard(drill: d, onAnswer: onAnswer, next: next)
            } else if let d = drill as? EmailDrill {
                EmailCard(drill: d, onAnswer: onAnswer, next: next)
            } else if let d = drill as? FillInDrill {
                FillInCard(drill: d, onAnswer: onAnswer, next: next)
            } else if let d = drill as? SynonymDrill {
                ChoiceCard(
                    prompt: d.antonym ? String(localized: "Pick the antonym of") : String(localized: "Pick the synonym of"),
                    word: d.word, choices: d.choices, check: { DrillFeedback(d.check(choice: Int32($0))) },
                    onAnswer: onAnswer, next: next
                )
            } else if let d = drill as? MeaningDrill {
                ChoiceCard(
                    prompt: String(localized: "What does this mean?"), word: d.word, choices: d.choices,
                    check: { DrillFeedback(d.check(choice: Int32($0))) }, onAnswer: onAnswer, next: next
                )
            } else if let d = drill as? UsageDrill {
                UsageCard(drill: d, onAnswer: onAnswer, next: next)
            } else if let d = drill as? PerformDrill {
                NavigationLink("Perform “\(d.title)”", value: Route.trackDrill(d.id))
            } else {
                Text("This drill type needs a newer app version.").foregroundStyle(.secondary)
            }
        }
    }
}

private struct NextButton: View {
    let next: () -> Void
    var body: some View {
        Button("Next") { next() }.buttonStyle(.borderedProminent)
    }
}

private struct KeigoCard: View {
    let drill: KeigoDrill
    let onAnswer: (Bool) -> Void
    let next: () -> Void
    @State private var answer = ""
    @State private var feedback: DrillFeedback?

    var body: some View {
        let prompt = SwiftSupport.shared.keigoPrompt(drill: drill)
        VStack(alignment: .leading, spacing: 10) {
            Text("Make it \(prompt.first ?? "") (\(TrackDrillText.formName(prompt.last ?? "")))").font(.subheadline.weight(.semibold))
            Text(drill.plain).font(.japanese(size: 28, weight: .semibold, relativeTo: .title)).japaneseSpeech()
            if let sentence = drill.sentence { Text(sentence).font(.japanese(size: 18)).japaneseSpeech() }
            if let english = drill.english { Text(english).font(.caption).foregroundStyle(.secondary) }
            TextField("Your answer", text: $answer)
                .textFieldStyle(.roundedBorder)
                .font(.japanese(size: 18))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(feedback != nil)
                .onSubmit { check() }
            if let feedback {
                FeedbackView(feedback: feedback)
                NextButton(next: next)
            } else {
                Button("Check") { check() }.buttonStyle(.borderedProminent).disabled(answer.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func check() {
        guard feedback == nil, !answer.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let f = DrillFeedback(drill.check(answer: answer))
        feedback = f
        onAnswer(f.correct)
    }
}

/// A business email with numbered slots: the body shows ［1］…［n］, and each slot has a field (or choices) below.
private struct EmailCard: View {
    let drill: EmailDrill
    let onAnswer: (Bool) -> Void
    let next: () -> Void
    @State private var answers: [Int: String] = [:]
    @State private var results: [Int: DrillFeedback] = [:]

    var body: some View {
        let segments = SwiftSupport.shared.emailSegments(drill: drill)
        let text = segments.map { $0.blank < 0 ? $0.text : "［\($0.blank + 1)］" }.joined()
        VStack(alignment: .leading, spacing: 10) {
            Text(drill.title).font(.headline)
            Text(drill.situation).font(.subheadline)
            if let english = drill.english { Text(english).font(.caption).foregroundStyle(.secondary) }
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: "件名：\(drill.subject)").font(.japanese(size: 15, weight: .semibold))
                Text(text).font(.japanese(size: 16)).japaneseSpeech()
            }
            .padding(8)
            .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 8))
            ForEach(Array(drill.blanks.enumerated()), id: \.offset) { i, blank in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(verbatim: "［\(i + 1)］").font(.japanese(size: 15, weight: .semibold))
                        if !blank.hint.isEmpty { Text(blank.hint).font(.caption).foregroundStyle(.secondary) }
                    }
                    if let choices = blank.choices, !choices.isEmpty {
                        FlowLayout(spacing: 6) {
                            ForEach(Array(choices.enumerated()), id: \.offset) { _, choice in
                                Button(choice) { answers[i] = choice }
                                    .font(.japanese(size: 15))
                                    .buttonStyle(.bordered)
                                    .tint(answers[i] == choice ? .accentColor : .secondary)
                                    .disabled(results[i] != nil)
                            }
                        }
                    } else {
                        TextField("Slot \(String(i + 1))", text: Binding(get: { answers[i] ?? "" }, set: { answers[i] = $0 }))
                            .textFieldStyle(.roundedBorder)
                            .font(.japanese(size: 16))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .disabled(results[i] != nil)
                    }
                    if let r = results[i] { FeedbackView(feedback: r) }
                }
            }
            if results.count == drill.blanks.count && !drill.blanks.isEmpty {
                Text("Model email").font(.caption.weight(.semibold))
                Text(drill.filled).font(.japanese(size: 15)).textSelection(.enabled).japaneseSpeech()
                NextButton(next: next)
            } else {
                Button("Check") { check() }
                    .buttonStyle(.borderedProminent)
                    .disabled(answers.values.allSatisfy { $0.trimmingCharacters(in: .whitespaces).isEmpty })
            }
        }
    }

    private func check() {
        var all = true
        for i in 0..<drill.blanks.count {
            let f = DrillFeedback(drill.check(blank: Int32(i), answer: answers[i] ?? ""))
            results[i] = f
            if !f.correct { all = false }
        }
        onAnswer(all)
    }
}

private struct FillInCard: View {
    let drill: FillInDrill
    let onAnswer: (Bool) -> Void
    let next: () -> Void
    @State private var answer = ""
    @State private var feedback: DrillFeedback?

    var body: some View {
        let parts = SwiftSupport.shared.fillInParts(drill: drill)
        VStack(alignment: .leading, spacing: 10) {
            Text("Fill in the blank").font(.subheadline.weight(.semibold))
            (Text(parts.first ?? "") + Text(verbatim: feedback == nil ? "（　　）" : "（\(answer)）").foregroundColor(.accentColor) + Text(parts.last ?? ""))
                .font(.japanese(size: 20))
                .japaneseSpeech()
            Text(drill.english).font(.caption).foregroundStyle(.secondary)
            if let choices = drill.choices, !choices.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(Array(choices.enumerated()), id: \.offset) { _, choice in
                        Button(choice) {
                            answer = choice
                            check()
                        }
                        .font(.japanese(size: 16))
                        .buttonStyle(.bordered)
                        .disabled(feedback != nil)
                    }
                }
            } else {
                TextField("Your answer", text: $answer)
                    .textFieldStyle(.roundedBorder)
                    .font(.japanese(size: 18))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(feedback != nil)
                    .onSubmit { check() }
                if feedback == nil {
                    Button("Check") { check() }.buttonStyle(.borderedProminent).disabled(answer.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            if let feedback {
                FeedbackView(feedback: feedback)
                Text(drill.completed).font(.japanese(size: 15)).foregroundStyle(.secondary)
                NextButton(next: next)
            }
        }
    }

    private func check() {
        guard feedback == nil, !answer.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let f = DrillFeedback(drill.check(answer: answer))
        feedback = f
        onAnswer(f.correct)
    }
}

/// Synonym/antonym and meaning drills: pick one of the choices.
private struct ChoiceCard: View {
    let prompt: String
    let word: String
    let choices: [String]
    let check: (Int) -> DrillFeedback
    let onAnswer: (Bool) -> Void
    let next: () -> Void
    @State private var picked: Int?
    @State private var feedback: DrillFeedback?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: prompt).font(.subheadline.weight(.semibold))
            Text(word).font(.japanese(size: 28, weight: .semibold, relativeTo: .title)).japaneseSpeech()
            ForEach(Array(choices.enumerated()), id: \.offset) { i, choice in
                Button {
                    guard feedback == nil else { return }
                    picked = i
                    let f = check(i)
                    feedback = f
                    onAnswer(f.correct)
                } label: {
                    HStack {
                        Text(choice).multilineTextAlignment(.leading)
                        Spacer()
                        if feedback != nil && picked == i {
                            Image(systemName: feedback?.correct == true ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundStyle(feedback?.correct == true ? Color.green : Color.red)
                        }
                    }
                }
                .buttonStyle(.bordered)
            }
            if let feedback {
                FeedbackView(feedback: feedback)
                NextButton(next: next)
            }
        }
    }
}

private struct UsageCard: View {
    let drill: UsageDrill
    let onAnswer: (Bool) -> Void
    let next: () -> Void
    @State private var feedback: DrillFeedback?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Is 「\(drill.word)」 used correctly?").font(.subheadline.weight(.semibold))
            Text(drill.sentence).font(.japanese(size: 20)).japaneseSpeech()
            HStack {
                Button { answer(true) } label: { Label("Correct usage", systemImage: "circle") }
                    .buttonStyle(.bordered)
                Button { answer(false) } label: { Label("Wrong usage", systemImage: "xmark") }
                    .buttonStyle(.bordered)
            }
            .disabled(feedback != nil)
            if let feedback {
                FeedbackView(feedback: feedback)
                NextButton(next: next)
            }
        }
    }

    private func answer(_ saysCorrect: Bool) {
        guard feedback == nil else { return }
        let f = DrillFeedback(drill.check(saysCorrect: saysCorrect))
        feedback = f
        onAnswer(f.correct)
    }
}

// MARK: - Memorize and perform

/// Opens one drill by id (perform drills have their own screen).
struct TrackDrillView: View {
    let drillId: String
    @Environment(AppModel.self) private var app
    @State private var drill: (any TrackDrill)?
    @State private var loaded = false
    @State private var error: String?

    var body: some View {
        Group {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding()
            } else if let perform = drill as? PerformDrill {
                PerformView(drill: perform)
            } else if let drill {
                ScrollView {
                    TrackDrillCard(drill: drill, onAnswer: { _ in }, next: {}).padding()
                }
            } else if loaded {
                ContentUnavailableView("Drill not found", systemImage: "questionmark.square")
            } else {
                ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        defer { loaded = true }
        do {
            drill = try await app.graph.trackRepository()?.drill(id: drillId)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the drill: \(error.localizedDescription)")
        }
    }
}

/// One prompt of the current round, copied from `PerformPrompt`.
private struct PerformLineRow: Identifiable {
    let id: Int
    let speaker: String
    let speakerName: String
    let voice: VoicePlayer.Voice
    let isLearner: Bool
    let japanese: String?
    let english: String
    let stage: String?
}

private struct LineResult {
    let passed: Bool
    let similarity: Double
    let expected: String
    let given: String
    let selfRated: Bool
}

/// Memorize-and-perform (D-217): the learner plays one role; their lines fade FULL → HALF → INITIAL → CUE_ONLY as
/// rounds are passed. Each line is spoken (recognizer, 80% similarity) or typed, or self-rated "I said it".
struct PerformView: View {
    let drill: PerformDrill
    @Environment(AppModel.self) private var app
    @State private var session: PerformanceSession?
    @State private var rows: [PerformLineRow] = []
    @State private var results: [Int: LineResult] = [:]
    @State private var step = 0
    @State private var round = 1
    @State private var finished = false
    @State private var recorder = Recorder()
    @State private var voice = VoicePlayer()
    @State private var recordingLine: Int?
    @State private var busyLine: Int?
    @State private var typed: [Int: String] = [:]
    @State private var typing = false
    @State private var note: String?

    private static let steps = [
        String(localized: "Full script"), String(localized: "Half hidden"), String(localized: "First letters"), String(localized: "Cues only"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                if finished {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Performance learned: you played it from the cues alone.", systemImage: "star.fill")
                            .foregroundStyle(.green).font(.headline)
                        Button("Start over") { start() }.buttonStyle(.bordered)
                    }
                } else {
                    Toggle("Type instead of speaking", isOn: $typing).font(.caption)
                    if let note { Text(note).font(.caption).foregroundStyle(.orange) }
                    ForEach(rows) { row in line(row) }
                    roundFooter
                }
            }
            .padding()
        }
        .navigationTitle(drill.title)
        .onAppear { if session == nil { start() } }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(drill.titleJa).font(.japanese(size: 22, weight: .semibold, relativeTo: .title2)).japaneseSpeech()
                if drill.isAiGenerated { AiBadge() }
            }
            Text(drill.setting).font(.subheadline)
            let role = drill.speaker(id: drill.learner)?.name ?? drill.learner
            Text("You play \(role).").font(.subheadline.weight(.semibold))
            if !drill.staging.isEmpty {
                DisclosureGroup("Staging notes") {
                    ForEach(Array(drill.staging.enumerated()), id: \.offset) { _, s in
                        Text(verbatim: "• \(s)").font(.caption)
                    }
                }
                .font(.subheadline)
            }
            HStack(spacing: 4) {
                ForEach(0..<Self.steps.count, id: \.self) { i in
                    Capsule().fill(i <= step ? Color.accentColor : Color.secondary.opacity(0.3)).frame(height: 5)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Step \(String(step + 1)) of 4: \(Self.steps[min(step, 3)])"))
            Text("Round \(String(round)) · \(Self.steps[min(step, 3)])").font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func line(_ row: PerformLineRow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row.isLearner ? String(localized: "You (\(row.speakerName))") : row.speakerName)
                    .font(.caption2.weight(.semibold)).foregroundStyle(row.isLearner ? Color.accentColor : Color.secondary)
                Spacer()
                if !row.isLearner, let text = row.japanese {
                    Button { speak(text, voice: row.voice) } label: { Image(systemName: "play.circle") }
                        .accessibilityLabel(Text("Play line"))
                }
            }
            if let stage = row.stage, !stage.isEmpty {
                Label(stage, systemImage: "figure.stand").font(.caption).foregroundStyle(.purple)
            }
            if let text = row.japanese {
                Text(text).font(.japanese(size: 19)).japaneseSpeech(!row.isLearner)
            } else {
                Text("Say it from the cue.").font(.caption).foregroundStyle(.secondary)
            }
            if row.isLearner || row.japanese == nil { Text(row.english).font(.caption).foregroundStyle(.secondary) }
            if row.isLearner { learnerControls(row) }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(row.isLearner ? Color.accentColor.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func learnerControls(_ row: PerformLineRow) -> some View {
        if let r = results[row.id] {
            VStack(alignment: .leading, spacing: 2) {
                Label(r.passed ? String(localized: "Delivered") : String(localized: "Try again next round"),
                      systemImage: r.passed ? "checkmark.circle.fill" : "arrow.counterclockwise")
                    .foregroundStyle(r.passed ? Color.green : Color.orange)
                    .font(.subheadline)
                if !r.selfRated {
                    Text("Match \(String(Int((r.similarity * 100).rounded())))% · you said: \(r.given)").font(.caption)
                }
                Text(r.expected).font(.japanese(size: 15)).foregroundStyle(.secondary)
            }
        } else if busyLine == row.id {
            ProgressView("Checking…")
        } else {
            if typing {
                HStack {
                    TextField("Your line", text: Binding(get: { typed[row.id] ?? "" }, set: { typed[row.id] = $0 }))
                        .textFieldStyle(.roundedBorder)
                        .font(.japanese(size: 16))
                    Button("Check") { deliver(row.id, typed[row.id] ?? "") }
                        .buttonStyle(.bordered)
                        .disabled((typed[row.id] ?? "").trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } else {
                Button {
                    toggleRecording(row.id)
                } label: {
                    Label(recordingLine == row.id ? "Stop" : "Say it", systemImage: recordingLine == row.id ? "stop.circle.fill" : "mic.circle.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(recordingLine == row.id ? .red : .accentColor)
                .disabled(recordingLine != nil && recordingLine != row.id)
                if let message = recorder.message, recordingLine == nil { Text(message).font(.caption).foregroundStyle(.red) }
            }
            HStack {
                Text("Or rate yourself:").font(.caption).foregroundStyle(.secondary)
                Button("I said it") { selfRate(row.id, true) }.font(.caption)
                Button("I missed it") { selfRate(row.id, false) }.font(.caption)
            }
        }
    }

    private var roundFooter: some View {
        let mine = rows.filter(\.isLearner).map(\.id)
        let done = mine.allSatisfy { results[$0] != nil }
        let passed = session?.roundPassed ?? false
        return VStack(alignment: .leading, spacing: 6) {
            if done {
                Text(passed
                     ? String(localized: "Round passed. The next round hides more of your lines.")
                     : String(localized: "Some lines need another go. The next round keeps the same prompts."))
                    .font(.subheadline)
            }
            Button(done ? String(localized: "Next round") : String(localized: "Finish the round")) { nextRound() }
                .buttonStyle(.borderedProminent)
                .disabled(!done)
        }
    }

    private func start() {
        let s = SwiftSupport.shared.performanceSession(drill: drill)
        session = s
        results = [:]
        typed = [:]
        finished = false
        round = 1
        refresh()
    }

    private func refresh() {
        guard let session else { return }
        step = Int(SwiftSupport.shared.performanceStep(session: session))
        finished = session.finished
        rows = session.prompts().map { p in
            let speaker = drill.speaker(id: p.speaker)
            return PerformLineRow(
                id: Int(p.lineIndex), speaker: p.speaker, speakerName: speaker?.name ?? p.speaker,
                voice: VoicePlayer.Voice(hint: speaker?.voice), isLearner: p.isLearner, japanese: p.japanese,
                english: p.english, stage: p.stage
            )
        }
    }

    private func speak(_ text: String, voice hint: VoicePlayer.Voice) {
        Task { await voice.say(text, voice: hint) }
    }

    private func toggleRecording(_ line: Int) {
        if recordingLine == line {
            let samples = recorder.stop()
            recordingLine = nil
            transcribe(line, samples)
        } else {
            voice.stop()
            Task {
                if await recorder.start() { recordingLine = line }
            }
        }
    }

    private func transcribe(_ line: Int, _ samples: [Float]) {
        busyLine = line
        note = nil
        let graph = app.graph
        Task {
            let out = await SpeechToText.transcribe(samples, graph: graph)
            busyLine = nil
            if let error = out.error {
                note = error
                return
            }
            deliver(line, out.text)
        }
    }

    private func deliver(_ line: Int, _ given: String) {
        guard let session, !given.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let c = session.deliver(lineIndex: Int32(line), given: given)
        results[line] = LineResult(passed: c.passed, similarity: c.similarity, expected: c.expected, given: c.given, selfRated: false)
    }

    private func selfRate(_ line: Int, _ gotIt: Bool) {
        guard let session else { return }
        let c = session.selfRate(lineIndex: Int32(line), gotIt: gotIt)
        results[line] = LineResult(passed: c.passed, similarity: c.similarity, expected: c.expected, given: "", selfRated: true)
    }

    private func nextRound() {
        guard let session else { return }
        _ = session.nextRound()
        results = [:]
        typed = [:]
        round += 1
        refresh()
    }
}
