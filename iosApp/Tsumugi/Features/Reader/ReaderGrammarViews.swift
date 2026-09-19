import Shared
import SwiftUI

// Grammar in the reader (BRIEF_V2 §6.16, D-289, D-304): the constructions of one sentence with their matched spans,
// a one-line explanation in the language monolingual mode asks for, and "practice this point". Detection, the F-39
// filter, the explanation and answer checking are all shared (`SwiftSupport.readerConstructions` and friends).

/// A construction copied out of the Kotlin row.
private struct Construction: Identifiable {
    let id: String
    let title: String
    let structure: String
    let jlpt: Int
    let explanation: String
    let japanese: Bool
    let aiGenerated: Bool
    let japaneseMissing: Bool
    let spans: [(start: Int, end: Int)]
    let stage: String
    let practice: String

    init(_ row: ConstructionRow) {
        id = row.pointId
        title = row.title
        structure = row.structure
        jlpt = Int(row.jlpt)
        explanation = row.explanation
        japanese = row.japanese
        aiGenerated = row.aiGenerated
        japaneseMissing = row.japaneseMissing
        let starts = row.spanStarts.map { Int($0.intValue) }
        let ends = row.spanEnds.map { Int($0.intValue) }
        spans = zip(starts, ends).map { (start: $0, end: $1) }
        stage = row.stageCode
        practice = row.practiceCode
    }
}

/// One exercise opened by "practice this point".
private struct PracticeSheet: Identifiable {
    let id = UUID()
    let exercise: GrammarExercise
    let kind: String
    let added: Bool
}

struct ReaderGrammarPanel: View {
    @Environment(AppModel.self) private var app
    let sentence: ReaderSentence

    @State private var items: [Construction]?
    @State private var error: String?
    @State private var working: String?
    @State private var notes: [String: String] = [:]
    @State private var sheet: PracticeSheet?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let items {
                if items.isEmpty {
                    Text("No grammar points detected in this sentence.").font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(items) { c in row(c) }
                }
            } else {
                ProgressView()
            }
        }
        .task(id: "\(sentence.start)|\(sentence.text)") { await load() }
        .sheet(item: $sheet) { s in
            NavigationStack {
                GrammarExerciseSheet(exercise: s.exercise, kind: s.kind, added: s.added)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close") { sheet = nil }
                        }
                    }
            }
            .environment(app)
        }
    }

    private func row(_ c: Construction) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(verbatim: c.title).font(.japanese(size: 16).weight(.semibold)).japaneseSpeech()
                if c.jlpt > 0 { TagView("N\(c.jlpt)") }
                if !c.stage.isEmpty { TagView(Self.stageLabel(c.stage)) }
                if c.aiGenerated { AiBadge() }
            }
            if !c.structure.isEmpty {
                Text(verbatim: c.structure).font(.japanese(size: 13)).foregroundStyle(.secondary)
            }
            if !c.spans.isEmpty {
                underlined(sentence.text, spans: c.spans).font(.japanese(size: 15))
            }
            Text(verbatim: c.explanation)
                .font(c.japanese ? .japanese(size: 14) : .subheadline)
            if c.japaneseMissing {
                Text("No Japanese explanation for this point yet, so it is in English.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            if let note = notes[c.id] {
                Text(note).font(.caption).foregroundStyle(.tint)
            }
            HStack {
                if c.practice != "NONE" {
                    let title: LocalizedStringKey = working == c.id ? "Preparing…" : (c.practice == "ADD_TO_REVIEWS" ? "Add and practice" : "Practice this point")
                    Button {
                        practice(c)
                    } label: {
                        Text(title)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(working != nil)
                }
                NavigationLink("Grammar point", value: Route.grammarPoint(c.id))
                    .buttonStyle(.bordered)
            }
            .font(.caption)
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
    }

    /// The sentence with the construction's matches underlined (offsets are UTF-16, as in Kotlin strings).
    private func underlined(_ text: String, spans: [(start: Int, end: Int)]) -> Text {
        let ns = text as NSString
        var out = Text(verbatim: "")
        var cursor = 0
        for span in spans.sorted(by: { $0.start < $1.start }) {
            let start = max(cursor, min(span.start, ns.length))
            let end = max(start, min(span.end, ns.length))
            if start > cursor { out = out + Text(verbatim: ns.substring(with: NSRange(location: cursor, length: start - cursor))) }
            if end > start {
                out = out + Text(verbatim: ns.substring(with: NSRange(location: start, length: end - start)))
                    .underline(true, color: .accentColor)
                    .foregroundColor(.accentColor)
            }
            cursor = end
        }
        if cursor < ns.length { out = out + Text(verbatim: ns.substring(from: cursor)) }
        return out
    }

    static func stageLabel(_ code: String) -> String {
        switch code {
        case "APPRENTICE": String(localized: "Apprentice")
        case "GURU": String(localized: "Guru")
        case "MASTER": String(localized: "Master")
        case "ENLIGHTENED": String(localized: "Enlightened")
        case "BURNED": String(localized: "Burned")
        default: code
        }
    }

    private func load() async {
        error = nil
        do {
            items = try await SwiftSupport.shared.readerConstructions(graph: app.graph, sentence: sentence).map(Construction.init)
        } catch {
            self.error = String(localized: "Couldn't find the grammar: \(error.localizedDescription)")
        }
    }

    private func practice(_ c: Construction) {
        working = c.id
        let graph = app.graph
        let pointId = c.id
        Task {
            do {
                let r = try await SwiftSupport.shared.practiceGrammarPoint(graph: graph, pointId: pointId)
                switch r.outcomeCode {
                case "ADDED":
                    notes[pointId] = String(localized: "Added to reviews.")
                    if let ex = r.exercise { sheet = PracticeSheet(exercise: ex, kind: r.exerciseKind, added: true) }
                    else if !r.reason.isEmpty { notes[pointId] = String(localized: "Added to reviews.") + " " + r.reason }
                case "EXERCISE":
                    if let ex = r.exercise { sheet = PracticeSheet(exercise: ex, kind: r.exerciseKind, added: false) }
                default:
                    notes[pointId] = r.reason
                }
                await load()
            } catch {
                notes[pointId] = String(localized: "Couldn't start practice: \(error.localizedDescription)")
            }
            working = nil
        }
    }
}

/// One grammar exercise: typed cloze / fill-in / production, sentence building, or a meaning choice.
private struct GrammarExerciseSheet: View {
    @Environment(AppModel.self) private var app
    let exercise: GrammarExercise
    let kind: String
    let added: Bool

    @State private var answer = ""
    @State private var result: ExerciseCheckRow?
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if added {
                    Label("Added to reviews", systemImage: "checkmark.circle").foregroundStyle(.green).font(.subheadline)
                }
                Text(verbatim: "\(exercise.point.title) · \(exercise.point.structure)").font(.japanese(size: 15)).foregroundStyle(.secondary)
                if exercise.example.isAiGenerated { AiBadge() }
                prompt
                if let result {
                    let verdict: LocalizedStringKey = result.accepted ? "Correct" : "Not quite"
                    Label(verdict, systemImage: result.accepted ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundStyle(result.accepted ? .green : .red)
                    if !result.accepted || kind != "MEANING_CHOICE" {
                        Text("Answer: \(result.expected)").font(.japanese(size: 16)).japaneseSpeech()
                    }
                    Text(verbatim: exercise.example.japanese).font(.japanese(size: 18)).japaneseSpeech()
                    Text(verbatim: exercise.example.english).font(.subheadline).foregroundStyle(.secondary)
                }
                if let error { Text(error).font(.caption).foregroundStyle(.red) }
            }
            .padding()
        }
        .navigationTitle("Practice")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var prompt: some View {
        switch kind {
        case "BUILD":
            Text(verbatim: exercise.example.english).font(.subheadline)
            if result == nil {
                BuildAnswer(exercise: exercise) { built in check(built) }
            }
        case "MEANING_CHOICE":
            Text(verbatim: exercise.marked).font(.japanese(size: 20)).japaneseSpeech()
            Text("What does the part in 【】 mean?").font(.subheadline)
            ForEach(Array(exercise.choices.enumerated()), id: \.offset) { i, choice in
                Button {
                    if result == nil { check(String(i)) }
                } label: {
                    Text(verbatim: choice).frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
                .disabled(result != nil || checking)
            }
        case "PRODUCTION":
            Text("Say it in Japanese:").font(.subheadline)
            Text(verbatim: exercise.example.english).font(.title3)
            answerField
        default:
            Text(verbatim: exercise.prompt).font(.japanese(size: 22)).japaneseSpeech()
            Text(verbatim: kind == "FILL_HINT" ? exercise.pointHint : exercise.example.english)
                .font(.subheadline).foregroundStyle(.secondary)
            answerField
        }
    }

    private var answerField: some View {
        HStack {
            TextField("Your answer", text: $answer)
                .textFieldStyle(.roundedBorder)
                .font(.japanese(size: 18))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { check(answer) }
                .disabled(result != nil)
            Button("Check") { check(answer) }
                .buttonStyle(.borderedProminent)
                .disabled(result != nil || checking || answer.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func check(_ given: String) {
        checking = true
        error = nil
        let graph = app.graph
        let ex = exercise
        Task {
            do {
                result = try await SwiftSupport.shared.checkGrammarExercise(graph: graph, exercise: ex, answer: given)
            } catch {
                self.error = String(localized: "Couldn't check the answer: \(error.localizedDescription)")
            }
            checking = false
        }
    }
}
