import Shared
import SwiftUI

/// Comprehension questions for a reader document (G-07, D-114). Always written by a model, so always badged; without
/// a model the sheet says why and links to AI settings instead of showing anything made up (rule 9).
struct ReadingQuestionsSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let document: ReaderDocument
    /// The learner's JLPT level (5 = N5) when known; questions are asked at N4 otherwise.
    let jlpt: Int?

    @State private var questions: ReadingQuestions?
    @State private var unavailable: String?
    @State private var error: String?
    @State private var loading = false
    @State private var picked: [Int: Int] = [:]
    @State private var work: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if loading {
                    HStack {
                        ProgressView()
                        Text("Writing questions…")
                        Spacer()
                        Button("Cancel") { work?.cancel(); work = nil; loading = false }
                    }
                } else if let unavailable {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Questions need an AI model").font(.headline)
                        Text(unavailable).font(.subheadline).foregroundStyle(.secondary)
                        Text("Set one up in Me → AI & speech (on device, or your own server).").font(.caption)
                        Button("Try again") { load(regenerate: false) }.buttonStyle(.bordered)
                    }
                } else if let error {
                    Label("Couldn't write questions: \(error)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { load(regenerate: false) }.buttonStyle(.bordered)
                } else if let questions {
                    AIBadge(engine: questions.engine)
                    Text("Level \(questions.level) · about the start of the text").font(.caption).foregroundStyle(.secondary)
                    ForEach(Array(questions.questions.enumerated()), id: \.offset) { i, q in
                        questionView(i, QuestionData(question: q.question, choices: q.choices, answer: Int(q.answer), explanation: q.explanation))
                    }
                    Button("New questions") { load(regenerate: true) }.buttonStyle(.bordered)
                }
            }
            .padding()
        }
        .navigationTitle("Questions")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        .onAppear { if questions == nil && !loading { load(regenerate: false) } }
        .onDisappear { work?.cancel() }
    }

    @ViewBuilder
    private func questionView(_ index: Int, _ q: QuestionData) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(index + 1). \(q.question)").font(.japanese(size: 17, weight: .semibold))
            ForEach(Array(q.choices.enumerated()), id: \.offset) { c, choice in
                Button {
                    picked[index] = c
                } label: {
                    HStack {
                        Text(choice).font(.japanese(size: 15)).multilineTextAlignment(.leading)
                        Spacer()
                        if let p = picked[index] {
                            if c == q.answer {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            } else if c == p {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                            }
                        }
                    }
                }
                .buttonStyle(.bordered)
                .disabled(picked[index] != nil)
            }
            if picked[index] != nil, !q.explanation.isEmpty {
                Text(q.explanation).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func load(regenerate: Bool) {
        work?.cancel()
        loading = true
        error = nil
        unavailable = nil
        picked = [:]
        let service = app.graph.reader.questions
        let doc = document
        let level = "N\(jlpt ?? 4)"
        work = Task {
            do {
                let result = try await service.questions(document: doc, level: level, count: 3, regenerate: regenerate)
                switch onEnum(of: result) {
                case .ready(let r): questions = r.value
                case .unavailable(let u): unavailable = u.reason
                }
            } catch {
                if !Task.isCancelled { self.error = error.localizedDescription }
            }
            loading = false
        }
    }
}

/// A plain Swift copy of one generated question, so the view doesn't depend on how Kotlin names nested types.
private struct QuestionData {
    let question: String
    let choices: [String]
    let answer: Int
    let explanation: String
}
