import Shared
import SwiftUI

/// Review of a stored attempt (BRIEF §5.11): every item with the right answer, your answer, the bank's explanation
/// and, on request, an AI explanation (labeled). OPI attempts show the transcript and rating.
struct AttemptReviewView: View {
    @Environment(AppModel.self) private var app
    let attemptId: String

    @State private var review: AttemptReview?
    @State private var loaded = false
    @State private var missedOnly = false
    @State private var transcript: [TranscriptLine] = []

    var body: some View {
        Group {
            if let review {
                content(review)
            } else if loaded {
                ContentUnavailableView("Attempt not found", systemImage: "doc.questionmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Review")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func content(_ review: AttemptReview) -> some View {
        let summary = review.summary
        let isOpi = summary.exam == .opi
        let items = review.items.filter { !missedOnly || !$0.answer.correct }
        return List {
            Section {
                Text(summary.summary).font(.headline)
                Text(ExamHubView.date(summary.submittedAt.toEpochMilliseconds())).font(.caption).foregroundStyle(.secondary)
            } footer: {
                Text(ExamHubView.disclaimer)
            }
            if isOpi {
                OpiScoreSection(scoring: summary.scoring)
                Section("Transcript") {
                    if transcript.isEmpty { Text("No transcript stored.").foregroundStyle(.secondary) }
                    ForEach(Array(transcript.enumerated()), id: \.offset) { _, line in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(line.learner ? "You" : "Interviewer").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                            Text(line.text).font(.japanese(size: 16))
                        }
                    }
                }
            } else {
                ScoreSections(scoring: summary.scoring, isJlpt: summary.exam == .jlpt)
                Section {
                    Toggle("Missed items only", isOn: $missedOnly)
                    if review.items.isEmpty {
                        Text("The items of this attempt are no longer installed (the bank may have been removed).")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                ForEach(Array(items.enumerated()), id: \.offset) { i, reviewed in
                    Section {
                        ReviewedItemView(reviewed: reviewed, passage: reviewed.item.passageId.flatMap { review.passages[$0] })
                    } header: {
                        Text("\(i + 1). \(ScoreSections.typeTitle(reviewed.item.type)) · \(reviewed.answer.correct ? "correct" : "missed")")
                    }
                }
            }
        }
    }

    private func load() async {
        guard review == nil, let exams = try? await app.graph.exams() else {
            loaded = true
            return
        }
        review = try? await exams.attempt(id: attemptId)
        if let review, review.summary.exam == .opi {
            transcript = (try? await SwiftSupport.shared.opiAttemptTranscript(exams: exams, attemptId: attemptId)) ?? []
        }
        loaded = true
    }
}

private struct OpiScoreSection: View {
    let scoring: AttemptScoring

    var body: some View {
        Section("Rating") {
            Text(scoring.ilr.map { "ILR \($0)" } ?? "Not rated").font(.headline)
            ForEach(Array(scoring.byType.enumerated()), id: \.offset) { _, t in
                LabeledContent(t.key.capitalized, value: "\(t.correct) / \(t.total)")
            }
            if !scoring.weakAreas.isEmpty {
                Text("Next steps").font(.subheadline.weight(.semibold))
                ForEach(scoring.weakAreas, id: \.self) { Text("• \($0)").font(.subheadline) }
            }
        }
    }
}

/// One reviewed item with choices marked, the bank explanation, and "Explain with AI".
private struct ReviewedItemView: View {
    @Environment(AppModel.self) private var app
    let reviewed: ReviewedItem
    let passage: ExamPassage?

    @State private var ai: AiExplanation?
    @State private var explaining = false
    @State private var voice = VoicePlayer()

    var body: some View {
        let item = reviewed.item
        let chosen = reviewed.answer.choice?.intValue
        VStack(alignment: .leading, spacing: 10) {
            if item.aiGenerated || (passage?.aiGenerated ?? false) { AIBadge() }
            if let passage {
                DisclosureGroup(passage.title.isEmpty ? "Passage" : passage.title) {
                    ExamText(text: passage.body, size: 16)
                }
            }
            let script = item.script.isEmpty ? (passage?.script ?? []) : item.script
            if !script.isEmpty {
                DisclosureGroup("Audio script") {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(script.enumerated()), id: \.offset) { _, line in
                            Text((line.speaker.isEmpty ? "" : "\(line.speaker): ") + line.text).font(.japanese(size: 15))
                        }
                        Button {
                            // Same clips as the test itself (rule 20); the owner is the item or its passage (D-092).
                            let owner = item.script.isEmpty ? (passage?.id ?? item.id) : item.id
                            let lines = script.enumerated().map { i, line in
                                (text: line.text, voice: VoicePlayer.Voice(hint: line.voice), key: Optional(PackAudio.examKey(ownerId: owner, line: i)))
                            }
                            let graph = app.graph
                            Task { await voice.sayLines(lines, graph: graph) }
                        } label: {
                            Label("Play", systemImage: "speaker.wave.2")
                        }
                    }
                }
            }
            ExamText(text: item.stem, size: 18, japanese: item.exam == .jlpt)
            ForEach(Array(item.choices.enumerated()), id: \.offset) { i, choice in
                let isAnswer = i == Int(item.answer)
                let isChosen = chosen.map { Int($0) } == i
                HStack(alignment: .top) {
                    Image(systemName: isAnswer ? "checkmark.circle.fill" : (isChosen ? "xmark.circle.fill" : "circle"))
                        .foregroundStyle(isAnswer ? Color.green : (isChosen ? Color.red : Color.secondary))
                        .accessibilityHidden(true)
                    Text(examInline(choice)).font(.japanese(size: 16)).japaneseSpeech(item.exam == .jlpt)
                }
                .accessibilityElement(children: .combine)
                .accessibilityValue(isAnswer ? (isChosen ? Text("Correct answer, your choice") : Text("Correct answer")) : (isChosen ? Text("Your choice, wrong") : Text("")))
            }
            if chosen == nil { Text("Not answered").font(.caption).foregroundStyle(.orange) }
            if !item.explanation.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Explanation").font(.caption.weight(.semibold))
                    ExamText(text: item.explanation, size: 15, japanese: false)
                }
            }
            if let ai {
                if let reason = ai.unavailable {
                    Text("No AI explanation: \(reason)").font(.caption).foregroundStyle(.secondary)
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        AIBadge(engine: ai.engine)
                        Text(ai.explanation).font(.subheadline)
                        if !ai.whyWrong.isEmpty { Text(ai.whyWrong).font(.subheadline).foregroundStyle(.secondary) }
                        if !ai.keyPoint.isEmpty { Text("Key point: \(ai.keyPoint)").font(.subheadline.weight(.semibold)) }
                    }
                }
            } else {
                Button(explaining ? "Explaining…" : "Explain with AI") { explain() }
                    .disabled(explaining)
                    .font(.subheadline)
            }
        }
        .padding(.vertical, 4)
        .onDisappear { voice.stop() }
    }

    private func explain() {
        let item = reviewed.item
        let script = (item.script.isEmpty ? (passage?.script ?? []) : item.script).map(\.text).joined(separator: "\n")
        let stimulus = [passage?.body ?? "", script].filter { !$0.isEmpty }.joined(separator: "\n\n")
        let chosen = Int32(reviewed.answer.choice?.intValue ?? -1)
        explaining = true
        let aiService = app.graph.ai
        Task {
            ai = try? await SwiftSupport.shared.explainItem(
                ai: aiService, level: item.level, question: item.stem, choices: item.choices,
                correctIndex: item.answer, chosenIndex: chosen, stimulus: stimulus.isEmpty ? nil : stimulus
            )
            explaining = false
        }
    }
}
