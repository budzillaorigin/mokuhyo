import Shared
import SwiftUI

/// Observes a shared LessonSession.
@MainActor
@Observable
final class LessonModel {
    private(set) var state: LessonState?
    private(set) var empty = false
    private var session: LessonSession?

    func run(graph: AppGraph) async {
        if session == nil {
            session = try? await graph.startLessons()
            empty = session == nil
        }
        guard let session else { return }
        for await value in session.state { state = value }
    }

    func nextItem() { session?.nextItem() }
    func previousItem() { session?.previousItem() }
    func submit(_ answer: String) { session?.submit(answer: answer) }
    func reviewItem() { session?.reviewItem() }
    func next() {
        guard let session else { return }
        Task { try? await session.next() }
    }
}

struct LessonView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model = LessonModel()
    @State private var detail: PathItemDetail?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let state = model.state {
                    content(state)
                } else if model.empty {
                    Text("No lessons available. Keep reviewing — new items unlock as earlier ones reach Guru.")
                    Button("OK") { dismiss() }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
        }
        .navigationTitle("Lessons")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.run(graph: app.graph) }
    }

    @ViewBuilder
    private func content(_ state: LessonState) -> some View {
        switch onEnum(of: state) {
        case .presenting(let s):
            Text("Lesson \(Int(s.index) + 1) of \(s.items.count)").font(.caption.weight(.semibold))
            Group {
                if let detail, detail.item.id == s.item.id {
                    PathItemContent(detail: detail) { story in
                        Task { try? await app.graph.path()?.saveMyStory(itemId: s.item.id, story: story) }
                    }
                } else {
                    ProgressView()
                }
            }
            .task(id: s.item.id) { detail = try? await app.graph.path()?.detail(id: s.item.id) }
            HStack {
                if s.index > 0 { Button("Back") { model.previousItem() }.buttonStyle(.bordered) }
                Button(s.isLast ? "Start quiz" : "Next") { model.nextItem() }.buttonStyle(.borderedProminent)
            }
        case .quizzing(let s):
            Text("Quiz · \(Int(s.remaining) + 1) left").font(.caption.weight(.semibold))
            // "quiz.reading" is the kana reading (読み), unlike the "Reading" key used for reading comprehension (読解).
            let modeLabel = s.question.mode == .reading
                ? String(localized: "quiz.reading", defaultValue: "Reading")
                : String(localized: "Meaning")
            Text(verbatim: "\(s.question.item.kind.label) · \(modeLabel)")
            ItemGlyph(text: s.question.item.display, kind: s.question.item.kind)
            AnswerField(mode: s.question.mode) { model.submit($0) }
                .id("\(s.question.item.id)-\(s.question.direction)-\(s.remaining)")
            if let hint = s.hint { Text(hint).foregroundStyle(.orange) }
            Button("Look at the lesson again") { model.reviewItem() }.font(.subheadline)
        case .quizFeedback(let s):
            ItemGlyph(text: s.question.item.display, kind: s.question.item.kind)
            Text(s.correct ? "Correct" : "Not quite").font(.title3.weight(.semibold)).foregroundStyle(s.correct ? .green : .red)
            Text("Accepted: " + s.question.expected.joined(separator: ", ")).font(.japanese(size: 17))
            Button("Next") { model.next() }.buttonStyle(.borderedProminent)
        case .complete(let s):
            Text("Lessons done").font(.title2.weight(.semibold))
            Text("\(s.items.count) items added. Their first reviews are due in 10 minutes.")
            Button("Done") { dismiss() }.buttonStyle(.borderedProminent)
        }
    }
}
