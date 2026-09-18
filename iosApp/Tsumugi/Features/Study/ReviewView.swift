import Shared
import SwiftUI

/// Observes a shared ReviewSession; all review logic lives in Kotlin.
@MainActor
@Observable
final class ReviewModel {
    private(set) var state: ReviewState?
    private var session: ReviewSession?

    func run(graph: AppGraph) async {
        if session == nil { session = try? await graph.startReviews(limit: 500) }
        guard let session else { return }
        for await value in session.state { state = value }
    }

    func submit(_ answer: String) { act { try await $0.submit(answer: answer) } }
    func next() { act { try await $0.next() } }
    func undo() { act { try await $0.undo() } }
    func reveal() { session?.reveal() }
    func grade(_ rating: Rating) { act { try await $0.grade(rating: rating) } }
    func wrapUp() { session?.wrapUp(keep: 10) }
    func finish() { act { try await $0.finish() } }

    private func act(_ block: @escaping (ReviewSession) async throws -> Void) {
        guard let session else { return }
        Task { try? await block(session) }
    }
}

struct ReviewView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model = ReviewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let state = model.state {
                    content(state)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
        }
        .navigationTitle("Reviews")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.run(graph: app.graph) }
    }

    @ViewBuilder
    private func content(_ state: ReviewState) -> some View {
        switch onEnum(of: state) {
        case .asking(let s):
            ProgressLine(done: Int(s.done), left: Int(s.remaining) + 1)
            Text(s.prompt.label + (s.prompt.practice ? " · practice" : "")).font(.caption.weight(.semibold))
            if s.prompt.exercise != nil {
                Text(s.prompt.question).font(.japanese(size: 24))
                if let hint = s.prompt.hint { Text(hint).font(.subheadline).foregroundStyle(.secondary) }
            } else {
                ItemGlyph(text: s.prompt.question, kind: s.prompt.item.kind)
            }
            if s.prompt.mode == .selfGraded {
                Button("Show answer") { model.reveal() }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
            } else if s.prompt.mode == .build, let exercise = s.prompt.exercise {
                BuildAnswer(exercise: exercise) { model.submit($0) }
                    .id("\(s.prompt.card.id)-\(s.done)-\(s.prompt.practice)")
            } else {
                AnswerField(mode: s.prompt.mode == .cloze ? .reading : s.prompt.mode) { model.submit($0) }
                    .id("\(s.prompt.card.id)-\(s.done)-\(s.prompt.practice)")
                if let hint = s.hint { Text(hint).foregroundStyle(.orange) }
            }
            HStack {
                if !s.wrappingUp { Button("Wrap up") { model.wrapUp() } }
                Button("End session") { model.finish() }
            }
            .font(.subheadline)
        case .revealed(let s):
            ProgressLine(done: Int(s.done), left: Int(s.remaining) + 1)
            ItemGlyph(text: s.prompt.question, kind: s.prompt.item.kind)
            Text(s.prompt.expected.joined(separator: "; ")).font(.japanese(size: 22)).frame(maxWidth: .infinity)
            if let reading = s.prompt.item.reading { Text(reading).font(.japanese(size: 20)).frame(maxWidth: .infinity) }
            HStack {
                ForEach([Rating.again, .hard, .good, .easy], id: \.self) { r in
                    Button(String(describing: r).capitalized) { model.grade(r) }
                        .buttonStyle(.bordered).frame(maxWidth: .infinity)
                }
            }
        case .answered(let s):
            ProgressLine(done: Int(s.done), left: Int(s.remaining))
            Text(s.prompt.label).font(.caption.weight(.semibold))
            if let exercise = s.prompt.exercise {
                Text(exercise.example.japanese).font(.japanese(size: 24))
                Text(exercise.example.english).font(.subheadline)
                Text("\(exercise.point.title) — \(exercise.point.meaning)").font(.japanese(size: 15)).foregroundStyle(.tint)
            } else {
                ItemGlyph(text: s.prompt.question, kind: s.prompt.item.kind)
            }
            Text(verdictText(s)).font(.title3.weight(.semibold)).foregroundStyle(s.correct ? .green : .red)
            Text("You answered: \(s.given)").font(.japanese(size: 17))
            Text("Accepted: " + s.prompt.expected.joined(separator: ", ")).font(.japanese(size: 17))
            if !s.prompt.item.myStory.isEmpty { Text("My story: " + s.prompt.item.myStory).font(.subheadline) }
            HStack {
                Button("Next") { model.next() }.buttonStyle(.borderedProminent)
                if s.canUndo { Button("Undo") { model.undo() }.buttonStyle(.bordered) }
            }
        case .finished(let s):
            SummaryView(summary: s.summary) { dismiss() }
        }
    }

    private func verdictText(_ s: ReviewStateAnswered) -> String {
        switch s.verdict {
        case .correct: "Correct"
        case .close: "Close enough — “\(s.matched ?? "")”"
        default: "Not quite"
        }
    }
}

struct ProgressLine: View {
    let done: Int
    let left: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ProgressView(value: Double(done), total: Double(max(1, done + left)))
            Text("\(done) done · \(left) left").font(.caption2).foregroundStyle(.secondary)
        }
    }
}

private struct SummaryView: View {
    let summary: ReviewSummary
    let onDone: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if summary.reviewed == 0 {
                Text("No reviews due right now.").font(.headline)
            } else {
                Text("Session complete").font(.title2.weight(.semibold))
                Text("\(summary.correct) / \(summary.reviewed) correct (\(Int(summary.accuracy * 100))%)").font(.headline)
                ForEach(summary.kinds, id: \.kind) { k in
                    Text("\(k.kind.label): \(k.correct)/\(k.total)")
                }
                if !summary.missed.isEmpty {
                    SectionHeader("Missed")
                    Text(summary.missed.map(\.primaryText).joined(separator: "、")).font(.japanese(size: 22))
                }
                if !summary.leeches.isEmpty {
                    Text("Leeches").font(.headline).foregroundStyle(.red)
                    Text("These keep slipping: \(summary.leeches.map(\.primaryText).joined(separator: "、")). Try rewriting their stories.")
                        .font(.japanese(size: 15))
                }
            }
            Button("Done", action: onDone).buttonStyle(.borderedProminent).padding(.top, 8)
        }
    }
}
