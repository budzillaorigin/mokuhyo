import Shared
import SwiftUI

/// Free talk (G-02): open conversation with the model at the learner's rolling level. It needs a model (on device or
/// the learner's own server); without one the screen says so and links to AI settings. Ending the conversation stores
/// it, and checked lines feed the recurring-error log on Me.
struct FreeTalkView: View {
    @Environment(AppModel.self) private var app

    private struct Line: Identifiable {
        let id: Int
        let learner: Bool
        let ja: String
        let en: String
    }

    @State private var session: FreeTalkSession?
    @State private var topic = ""
    @State private var lines: [Line] = []
    @State private var input = ""
    @State private var busy = false
    @State private var failure: String?
    @State private var showEnglish = false
    @State private var recorder = Recorder()
    @State private var voice = VoicePlayer()
    @State private var sttNote: String?
    @State private var feedbackIndex: Int?
    @State private var saved: ConversationRecord?
    @State private var loadError: String?

    var body: some View {
        VStack(spacing: 0) {
            if let saved {
                summary(saved)
            } else if session == nil {
                start
            } else {
                conversation
            }
        }
        .navigationTitle("Free talk")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("End") { finish() }.disabled(busy || session == nil || saved != nil)
            }
        }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
            if saved == nil, let session { Task { _ = try? await session.finish() } }
        }
        .sheet(item: Binding(get: { feedbackIndex.map { FeedbackTarget(index: $0) } }, set: { feedbackIndex = $0?.index })) { target in
            NavigationStack {
                FreeTalkFeedbackSheet(session: session, index: target.index, text: lines.first { $0.id == target.index }?.ja ?? "")
            }
        }
    }

    private struct FeedbackTarget: Identifiable {
        let index: Int
        var id: Int { index }
    }

    private var start: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                AiStatusBanner(scripted: "Free talk needs a model: there's no scripted version. Role-play scenarios work without one.")
                Text("Talk about anything. The partner speaks at your estimated level and keeps the conversation going.")
                    .font(.subheadline)
                TextField("Topic (optional), e.g. weekend plans", text: $topic)
                    .textFieldStyle(.roundedBorder)
                Button {
                    begin()
                } label: {
                    Label("Start", systemImage: "bubble.left.and.bubble.right").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy)
                if busy { ProgressView() }
                if let loadError {
                    Label(loadError, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red)
                    NavigationLink("AI settings", value: Route.aiSettings).font(.caption.weight(.semibold))
                }
            }
            .padding()
        }
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if let session {
                        Text(String(localized: "Partner level: \(session.level)") + (session.currentTopic.isEmpty ? "" : " · \(session.currentTopic)"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(lines) { line in bubble(line) }
                    if busy { ProgressView().padding() }
                    if let failure {
                        VStack(alignment: .leading, spacing: 6) {
                            Label("The partner didn't answer: \(failure)", systemImage: "exclamationmark.bubble")
                                .font(.subheadline).foregroundStyle(.orange)
                            HStack {
                                Button("Retry") { retry() }.buttonStyle(.bordered)
                                NavigationLink("AI settings", value: Route.aiSettings)
                            }
                        }
                    }
                }
                .padding()
            }
            Divider()
            VStack(spacing: 6) {
                HStack {
                    TextField("日本語で返事", text: $input, axis: .vertical)
                        .font(.japanese(size: 17))
                        .lineLimit(1...3)
                        .textFieldStyle(.roundedBorder)
                    Button("Send") { send(input) }
                        .buttonStyle(.borderedProminent)
                        .disabled(busy || failure != nil || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                RecordButton(recorder: recorder, label: "Speak", disabled: busy || failure != nil) { samples in
                    transcribe(samples)
                }
                if let sttNote { Text(sttNote).font(.caption).foregroundStyle(.secondary) }
                Toggle("Show English", isOn: $showEnglish).font(.caption)
            }
            .padding()
        }
    }

    private func bubble(_ line: Line) -> some View {
        HStack {
            if line.learner { Spacer(minLength: 40) }
            VStack(alignment: line.learner ? .trailing : .leading, spacing: 4) {
                Text(line.ja).font(.japanese(size: 17)).textSelection(.enabled).japaneseSpeech()
                if showEnglish && !line.en.isEmpty { Text(line.en).font(.caption).foregroundStyle(.secondary) }
                if line.learner {
                    Button("Check this line") { feedbackIndex = line.id }.font(.caption)
                } else {
                    HStack {
                        AIBadge(engine: nil)
                        Button {
                            Task { await voice.sayPartner(line.ja, graph: app.graph) }
                        } label: {
                            Image(systemName: "speaker.wave.2")
                        }
                        .accessibilityLabel(Text("Listen"))
                    }
                }
            }
            .padding(10)
            .background(line.learner ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            if !line.learner { Spacer(minLength: 40) }
        }
    }

    @ViewBuilder
    private func summary(_ record: ConversationRecord) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("Conversation saved", systemImage: "checkmark.circle").font(.title3.weight(.semibold))
                Text("\(Int(record.learnerTurns)) of your lines.")
                if let estimate = record.levelEstimate {
                    LabeledContent("This conversation", value: "\(estimate.jlptLabel) · ILR \(estimate.ilr)")
                    Text("An estimate for practice, not a rating.").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Say at least three lines for a level estimate.").font(.caption).foregroundStyle(.secondary)
                }
                if !record.errors.isEmpty {
                    Text("Mistakes you checked").font(.headline)
                    ForEach(Array(record.errors.enumerated()), id: \.offset) { _, e in
                        VStack(alignment: .leading) {
                            Text(ConversationPatternsCard.label(e.type)).font(.caption.weight(.semibold))
                            Text(verbatim: "\(e.original) → \(e.replacement)").font(.japanese(size: 15))
                        }
                    }
                }
            }
            .padding()
        }
    }

    private func begin() {
        busy = true
        loadError = nil
        let graph = app.graph
        let t = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                let s = try await graph.freeTalk(topic: t.isEmpty ? nil : t)
                if let first = try await s.start() {
                    session = s
                    append(first)
                    await voice.sayPartner(first.ja, graph: graph)
                } else {
                    loadError = s.unavailable ?? String(localized: "Free talk needs an AI model.")
                }
            } catch {
                loadError = error.localizedDescription
            }
            busy = false
        }
    }

    private func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let session, !trimmed.isEmpty, !busy else { return }
        input = ""
        lines.append(Line(id: lines.count, learner: true, ja: trimmed, en: ""))
        turn { try await session.reply(text: trimmed) }
    }

    private func retry() {
        guard let session else { return }
        turn { try await session.retry() }
    }

    private func turn(_ work: @escaping () async throws -> ConversationTurn?) {
        guard let session else { return }
        busy = true
        failure = nil
        let graph = app.graph
        Task {
            do {
                if let reply = try await work() {
                    if lines.last.map({ !$0.learner && $0.ja == reply.ja }) != true {
                        append(reply)
                        await voice.sayPartner(reply.ja, graph: graph)
                    }
                } else {
                    failure = session.unavailable ?? String(localized: "no reply")
                }
            } catch {
                failure = error.localizedDescription
            }
            busy = false
        }
    }

    private func append(_ turn: ConversationTurn) {
        lines.append(Line(id: lines.count, learner: turn.isLearner, ja: turn.ja, en: turn.en))
    }

    private func transcribe(_ samples: [Float]) {
        sttNote = nil
        let graph = app.graph
        Task {
            let out = await SpeechToText.transcribe(samples, graph: graph)
            if let error = out.error {
                sttNote = error
            } else {
                send(out.text)
            }
        }
    }

    private func finish() {
        guard let session else { return }
        voice.stop()
        Task {
            do {
                if let record = try await session.finish() {
                    saved = record
                } else {
                    loadError = nil
                    self.session = nil
                    lines = []
                }
            } catch {
                failure = String(localized: "Couldn't save the conversation: \(error.localizedDescription)")
            }
        }
    }
}

/// Corrections and a natural version of one learner line (AI-generated, labeled).
private struct FreeTalkFeedbackSheet: View {
    @Environment(\.dismiss) private var dismiss
    let session: FreeTalkSession?
    let index: Int
    let text: String

    @State private var feedback: TurnFeedback?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("You said").font(.caption.weight(.semibold))
                Text(text).font(.japanese(size: 18))
                if let feedback {
                    TurnFeedbackDetails(feedback: feedback)
                } else if let error {
                    Label("Couldn't check this line: \(error)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { Task { await load() } }
                } else {
                    ProgressView("Checking your sentence…")
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Feedback")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        .task { await load() }
    }

    private func load() async {
        guard let session else { return }
        error = nil
        do {
            feedback = try await session.feedback(index: Int32(index))
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Corrections (diff) and the natural version from `correct_sentence` / `natural_rewrite`.
struct TurnFeedbackDetails: View {
    let feedback: TurnFeedback

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let reason = feedback.unavailable {
                Text("No feedback: \(reason)").font(.subheadline)
            }
            if feedback.engine != nil { AIBadge(engine: feedback.engine) }
            if let c = feedback.correction {
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
            if let n = feedback.natural {
                Text("Natural version").font(.headline)
                Text(n.rewrite).font(.japanese(size: 20)).textSelection(.enabled)
                if !n.notes.isEmpty { Text(n.notes).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}
