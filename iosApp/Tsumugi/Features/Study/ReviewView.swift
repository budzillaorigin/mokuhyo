import Shared
import SwiftUI

/// Observes a shared ReviewSession; all review logic lives in Kotlin.
///
/// F-09: one action at a time. [busy] is set before an action's task starts and cleared when it ends; while it's set
/// further taps are ignored and the answer controls are disabled, so a double tap can't enqueue a second submit (the
/// shared session also serializes, D-044). F-33: a failed start or action shows an error with Retry, never a spinner.
@MainActor
@Observable
final class ReviewModel {
    private(set) var state: ReviewState?
    private(set) var busy = false
    private(set) var loadError: String?
    private(set) var actionError: String?
    private var session: ReviewSession?

    func run(graph: AppGraph, limit: Int = 500) async {
        loadError = nil
        if session == nil {
            do {
                session = try await graph.startReviews(limit: Int32(limit))
            } catch {
                loadError = error.localizedDescription
                return
            }
        }
        guard let session else { return }
        for await value in session.state { state = value }
    }

    func submit(_ answer: String) { act { try await $0.submit(answer: answer) } }
    func next() { act { try await $0.next() } }
    func undo() { act { try await $0.undo() } }
    func reveal() { if !busy { session?.reveal() } }
    func grade(_ rating: Rating) { act { try await $0.grade(rating: rating) } }
    func wrapUp() { session?.wrapUp(keep: 10) }
    func finish() { act { try await $0.finish() } }

    private func act(_ block: @escaping (ReviewSession) async throws -> Void) {
        guard let session, !busy else { return }
        busy = true
        actionError = nil
        Task {
            do {
                try await block(session)
            } catch {
                actionError = error.localizedDescription
            }
            busy = false
        }
    }
}

struct ReviewView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    /// Today passes its review budget (G-01); the Reviews tab takes everything due.
    var limit: Int = 500
    /// Called when a session with at least one answer finishes (Today marks its block done).
    var onFinished: (() -> Void)?
    @State private var model = ReviewModel()
    @State private var writingResult: RawResult?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error = model.loadError {
                    ContentUnavailableView {
                        Label("Couldn't start reviews", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("Retry") { Task { await model.run(graph: app.graph, limit: limit) } }.buttonStyle(.borderedProminent)
                    }
                } else if let state = model.state {
                    content(state)
                        .disabled(model.busy)
                    if let error = model.actionError {
                        Label("That didn't save: \(error). Try again.", systemImage: "exclamationmark.triangle")
                            .font(.subheadline).foregroundStyle(.red)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
        }
        .locksScrollWhileWriting()
        .navigationTitle("Reviews")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.run(graph: app.graph, limit: limit) }
    }

    @ViewBuilder
    private func content(_ state: ReviewState) -> some View {
        switch onEnum(of: state) {
        case .asking(let s):
            ProgressLine(done: Int(s.done), left: Int(s.remaining) + 1)
            Text(s.prompt.label + (s.prompt.practice ? String(localized: " · practice") : "")).font(.caption.weight(.semibold))
            question(s.prompt)
            answerControls(s)
            HStack {
                if !s.wrappingUp { Button("Wrap up") { model.wrapUp() } }
                Button("End session") { model.finish() }
            }
            .font(.subheadline)
        case .revealed(let s):
            ProgressLine(done: Int(s.done), left: Int(s.remaining) + 1)
            if s.prompt.mode == .writing {
                WritingReveal(kanji: s.prompt.item.primaryText, result: writingResult)
            } else if let production = s.production {
                Text(s.prompt.question).font(.subheadline)
                ProductionFeedback(grade: production, given: s.given)
            } else if s.prompt.item.kind == .custom {
                PersonalCardFace(cardId: s.prompt.card.id, back: true)
            } else {
                ItemGlyph(text: s.prompt.card.direction == .listening ? s.prompt.item.primaryText : s.prompt.question, kind: s.prompt.item.kind)
            }
            if s.production == nil {
                Text(s.prompt.expected.joined(separator: "; ")).font(.japanese(size: 22)).frame(maxWidth: .infinity)
                if let reading = s.prompt.item.reading { Text(reading).font(.japanese(size: 20)).frame(maxWidth: .infinity).japaneseSpeech() }
            }
            // One row normally; stacked when large Dynamic Type sizes don't fit four buttons across.
            ViewThatFits(in: .horizontal) {
                HStack { gradeButtons(s) }
                VStack { gradeButtons(s) }
            }
        case .answered(let s):
            ProgressLine(done: Int(s.done), left: Int(s.remaining))
            Text(s.prompt.label).font(.caption.weight(.semibold))
            if let exercise = s.prompt.exercise {
                Text(exercise.example.japanese).font(.japanese(size: 24)).japaneseSpeech()
                Text(exercise.example.english).font(.subheadline)
                Text("\(exercise.point.title) — \(exercise.point.meaning)").font(.japanese(size: 15)).foregroundStyle(.tint)
            } else {
                ItemGlyph(text: s.prompt.question, kind: s.prompt.item.kind)
            }
            Text(verdictText(s)).font(.title3.weight(.semibold)).foregroundStyle(s.correct ? .green : .red)
            if let production = s.production {
                ProductionFeedback(grade: production, given: s.given)
            } else {
                Text("You answered: \(answerText(s))").font(.japanese(size: 17))
                Text("Accepted: \(s.prompt.expected.joined(separator: ", "))").font(.japanese(size: 17))
            }
            if !s.prompt.item.myStory.isEmpty { Text("My story: \(s.prompt.item.myStory)").font(.subheadline) }
            HStack {
                Button("Next") { model.next() }.buttonStyle(.borderedProminent)
                if s.canUndo { Button("Undo") { model.undo() }.buttonStyle(.bordered) }
            }
        case .finished(let s):
            SummaryView(summary: s.summary) {
                if s.summary.reviewed > 0, let onFinished { onFinished() } else { dismiss() }
            }
        }
    }

    /// The question side: listening cards play audio, personal cards show the learner's picture (G-12).
    @ViewBuilder
    private func question(_ prompt: ReviewPrompt) -> some View {
        if prompt.mode == .minimalPair {
            Text("Which word do you hear?").font(.title3.weight(.semibold))
        } else if prompt.card.direction == .listening {
            ListeningFront(cardId: prompt.card.id, item: prompt.item)
        } else if prompt.item.kind == .custom {
            PersonalCardFace(cardId: prompt.card.id, back: false)
        } else if prompt.exercise != nil {
            Text(prompt.question).font(.japanese(size: 24)).japaneseSpeech()
            if let hint = prompt.hint {
                // FILL_HINT shows the point's title, structure and meaning as the hint (G-05).
                Text(hint).font(.subheadline).foregroundStyle(prompt.mode == .fillHint ? Color.accentColor : Color.secondary)
            }
            if let exercise = prompt.exercise, exercise.example.isAiGenerated { AiBadge() }
        } else {
            ItemGlyph(text: prompt.question, kind: prompt.item.kind)
        }
    }

    @ViewBuilder
    private func answerControls(_ s: ReviewStateAsking) -> some View {
        let prompt = s.prompt
        let key = "\(prompt.card.id)-\(s.done)-\(prompt.practice)"
        if prompt.mode == .writing {
            WritingAnswer(kanji: prompt.item.primaryText) { result in
                writingResult = result
                model.reveal()
            }
            .id("\(prompt.card.id)-\(s.done)")
        } else if prompt.mode == .selfGraded {
            Button("Show answer") { model.reveal() }
                .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
        } else if prompt.mode == .build, let exercise = prompt.exercise {
            BuildAnswer(exercise: exercise) { model.submit($0) }.id(key)
        } else if prompt.mode == .meaningChoice {
            MeaningChoiceAnswer(choices: prompt.choices) { model.submit($0) }.id(key)
        } else if prompt.mode == .production {
            ProductionAnswer { model.submit($0) }.id(key)
            if model.busy { ProgressView("Grading…") }
        } else if prompt.mode == .minimalPair, let pair = prompt.minimalPair {
            MinimalPairAnswer(prompt: pair) { model.submit($0) }.id(key)
        } else {
            AnswerField(mode: prompt.mode == .cloze || prompt.mode == .fillHint ? .reading : prompt.mode) { model.submit($0) }
                .id(key)
            if let hint = s.hint { Text(hint).foregroundStyle(.orange) }
        }
    }

    /// Choice answers are submitted as an index or "a"/"b"; show the text the learner picked.
    private func answerText(_ s: ReviewStateAnswered) -> String {
        if s.prompt.mode == .meaningChoice, let i = Int(s.given), i >= 0, i < s.prompt.choices.count {
            return s.prompt.choices[i]
        }
        if s.prompt.mode == .minimalPair, let pair = s.prompt.minimalPair {
            if s.given == "a" { return pair.pair.a.text }
            if s.given == "b" { return pair.pair.b.text }
        }
        return s.given
    }

    @ViewBuilder
    private func gradeButtons(_ s: ReviewStateRevealed) -> some View {
        ForEach([Rating.again, .hard, .good, .easy], id: \.self) { r in
            let suggested = s.prompt.mode == .writing && writingResult.map { Int($0.suggestedRating) == Int(r.value) } == true
            // The shared string table (G-14): rating names in the app's language, the same as Android.
            Button(SharedText.rating(r)) { model.grade(r) }
                .buttonStyle(.bordered).tint(suggested ? .accentColor : .secondary).frame(maxWidth: .infinity)
        }
    }

    private func verdictText(_ s: ReviewStateAnswered) -> String {
        switch s.verdict {
        case .correct: String(localized: "Correct")
        case .close: String(localized: "Close enough — “\(s.matched ?? "")”")
        default: String(localized: "Not quite")
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
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Session progress"))
        .accessibilityValue(Text("\(done) done · \(left) left"))
    }
}

private struct SummaryView: View {
    let summary: ReviewSummary
    let onDone: () -> Void
    @State private var askReminders = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if summary.reviewed == 0 {
                Text("No reviews due right now.").font(.headline)
            } else {
                Text("Session complete").font(.title2.weight(.semibold))
                Text("\(summary.correct) / \(summary.reviewed) correct (\(Int(summary.accuracy * 100))%)").font(.headline)
                ForEach(summary.kinds, id: \.kind) { k in
                    Text(verbatim: "\(SharedText.kind(k.kind)): \(k.correct)/\(k.total)")
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
        // F-34: reminders are offered after the first finished session, with the reason, never at first launch.
        .task {
            if summary.reviewed > 0, await Reminders.shouldAsk() { askReminders = true }
        }
        .sheet(isPresented: $askReminders) { ReminderPermissionSheet() }
    }
}

/// Reference and automatic checks after drawing a WRITING card.
private struct WritingReveal: View {
    @Environment(AppModel.self) private var app
    let kanji: String
    let result: RawResult?
    @State private var strokes: [KanjiStroke] = []

    var body: some View {
        VStack(spacing: 8) {
            if strokes.isEmpty {
                Text(kanji).font(.japanese(size: 88))
            } else {
                StrokeOrderView(strokes: strokes).frame(width: 160, height: 160)
            }
            if let result {
                Text("\(result.countOk ? "Stroke count ✓" : "Stroke count ✗") · \(result.orderOk ? "Order ✓" : "Order ✗") — suggested rating \(result.suggestedRating)/4")
                    .font(.subheadline)
            }
        }
        .frame(maxWidth: .infinity)
        .task(id: kanji) { strokes = (try? await app.graph.dictionary()?.strokes(literal: kanji)) ?? [] }
    }
}
