import Shared
import SwiftUI

/// OPI-style practice interview (BRIEF §5.11): voice-first, transcript hidden until the end, then a practice
/// rating (model) or a self-rating checklist (no model), saved to exam history.
struct OpiView: View {
    @Environment(AppModel.self) private var app

    private enum Stage { case setup, interview, rating, done }

    @State private var stage = Stage.setup
    @State private var startLevel = "1"
    @State private var session: OpiSession?
    @State private var missing = false
    @State private var busy = false
    @State private var question: InterviewerLine?
    @State private var questionNumber = 0
    @State private var typed = false
    @State private var typedAnswer = ""
    @State private var sttNote: String?
    @State private var rating: OpiRating?
    @State private var checklist: [ChecklistEntry] = []
    @State private var checked: Set<String> = []
    @State private var transcript: [TranscriptLine] = []
    @State private var savedId: String?
    @State private var saveError: String?
    @State private var recorder = Recorder()
    @State private var voice = VoicePlayer()

    static let disclaimer = "Unofficial practice; not affiliated with DLI, ACTFL or JLPT. Ratings are estimates."

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(Self.disclaimer)
                    .font(.caption)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.yellow.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
                switch stage {
                case .setup: setup
                case .interview: interview
                case .rating: ratingView
                case .done: doneView
                }
            }
            .padding()
        }
        .navigationTitle("OPI simulator")
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
    }

    // MARK: setup

    private var setup: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("A 15–30 minute practice interview: warm-up, level checks, probes, a role-play and a wind-down. Answer out loud; the transcript is shown only at the end.")
                .font(.subheadline)
            AiStatusBanner(scripted: "Without a model, questions come from scripted banks per level and you rate yourself with a checklist at the end.")
            Picker("Starting level (ILR)", selection: $startLevel) {
                ForEach(SwiftSupport.shared.opiStartLevels(), id: \.self) { Text("ILR \($0)").tag($0) }
            }
            Button(busy ? "Preparing…" : "Start interview") { start() }
                .buttonStyle(.borderedProminent)
                .disabled(busy)
            if missing {
                ContentUnavailableView(
                    "No interview banks",
                    systemImage: "person.wave.2",
                    description: Text("The OPI simulator needs the practice pack's scripted interview banks. Build it with `uv run packs/build_practice.py` in tools/ and rebuild the app.")
                )
            }
        }
    }

    // MARK: interview

    private var interview: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let question {
                HStack {
                    Text("Question \(questionNumber) · \(SwiftSupport.shared.opiPhaseTitle(phase: question.phase))")
                        .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    if question.engine != nil { AIBadge(engine: question.engine) }
                }
                HStack {
                    Image(systemName: voice.isSpeaking ? "waveform" : "person.wave.2").font(.largeTitle)
                    Text(voice.isSpeaking ? "The interviewer is speaking…" : "Answer out loud.")
                }
                Button {
                    let text = question.japanese
                    let graph = app.graph
                    Task { await voice.sayPartner(text, graph: graph) }
                } label: {
                    Label("Repeat the question", systemImage: "arrow.counterclockwise")
                }
                .buttonStyle(.bordered)
                if typed {
                    TextField("Type your answer", text: $typedAnswer, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(2...6)
                    Button("Answer") { answer(typedAnswer) }
                        .buttonStyle(.borderedProminent)
                        .disabled(typedAnswer.trimmingCharacters(in: .whitespaces).isEmpty || busy)
                } else {
                    RecordButton(recorder: recorder, label: busy ? "Listening…" : "Answer", disabled: busy || voice.isSpeaking) { samples in
                        transcribe(samples)
                    }
                }
                Toggle("Type instead of speaking", isOn: $typed).font(.caption)
                if let sttNote { Text(sttNote).font(.caption).foregroundStyle(.orange) }
            } else if busy {
                ProgressView("The interviewer is thinking…")
            }
            Button("End interview", role: .destructive) { finish() }
                .disabled(busy)
        }
    }

    // MARK: rating

    @ViewBuilder
    private var ratingView: some View {
        if busy {
            ProgressView("Rating the interview…")
        } else if let rating, !rating.needsSelfRating {
            ratingSummary(rating)
            saveButton(rating)
        } else {
            Text("Rate yourself").font(.headline)
            Text("No model rated this interview. Tick every statement that is true of how you spoke; the level is the highest one whose statements (and every lower level's) you ticked.")
                .font(.subheadline)
            if checklist.isEmpty {
                Text("The installed banks have no checklist.").foregroundStyle(.secondary)
            }
            ForEach(Array(checklist.enumerated()), id: \.offset) { _, item in
                Toggle(isOn: Binding(
                    get: { checked.contains(item.statement) },
                    set: { on in if on { checked.insert(item.statement) } else { checked.remove(item.statement) } }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("ILR \(item.level)").font(.caption.weight(.semibold))
                        Text(item.statement).font(.subheadline)
                    }
                }
            }
            if let session {
                let selfRating = SwiftSupport.shared.opiSelfRate(session: session, checked: Array(checked))
                Text("Self-rating: " + (SwiftSupport.shared.ilrLabel(rating: selfRating).map { "ILR \($0)" } ?? "below the lowest level listed"))
                    .font(.headline)
                saveButton(selfRating)
            }
        }
        transcriptView
    }

    @ViewBuilder
    private func ratingSummary(_ r: OpiRating) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Practice estimate").font(.headline)
                if r.engine != nil { AIBadge(engine: r.engine) }
            }
            Text((SwiftSupport.shared.ilrLabel(rating: r).map { "ILR \($0)" } ?? "Not rated") + (r.actfl.map { " · ACTFL \($0)" } ?? ""))
                .font(.title2.weight(.semibold))
            factor("Functions", r.functions)
            factor("Accuracy", r.accuracy)
            factor("Vocabulary", r.vocabulary)
            factor("Fluency", r.fluency)
            if !r.rationale.isEmpty { Text(r.rationale).font(.subheadline) }
            if !r.strengths.isEmpty {
                Text("Strengths").font(.subheadline.weight(.semibold))
                ForEach(r.strengths, id: \.self) { Text("• \($0)").font(.subheadline) }
            }
            if !r.nextSteps.isEmpty {
                Text("Next steps").font(.subheadline.weight(.semibold))
                ForEach(r.nextSteps, id: \.self) { Text("• \($0)").font(.subheadline) }
            }
        }
    }

    @ViewBuilder
    private func factor(_ name: String, _ value: KotlinInt?) -> some View {
        if let value {
            LabeledContent(name, value: "\(value.intValue) / 5")
        }
    }

    private func saveButton(_ r: OpiRating) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(savedId == nil ? "Save to history" : "Saved") { save(r) }
                .buttonStyle(.borderedProminent)
                .disabled(savedId != nil)
            if let saveError { Text(saveError).font(.caption).foregroundStyle(.red) }
        }
    }

    private var transcriptView: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Transcript").font(.headline).padding(.top, 8)
            if transcript.isEmpty { Text("Nothing was said.").foregroundStyle(.secondary) }
            ForEach(Array(transcript.enumerated()), id: \.offset) { _, line in
                VStack(alignment: .leading, spacing: 1) {
                    Text(line.learner ? "You" : "Interviewer").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                    Text(line.text).font(.japanese(size: 16)).textSelection(.enabled)
                }
            }
        }
    }

    private var doneView: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Saved to your exam history.", systemImage: "checkmark.circle").foregroundStyle(.green)
            if let savedId {
                NavigationLink("Open in history", value: Route.attempt(savedId))
            }
            Button("New interview") { reset() }.buttonStyle(.bordered)
        }
    }

    // MARK: actions

    private func start() {
        busy = true
        missing = false
        let graph = app.graph
        let level = startLevel
        Task {
            session = try? await SwiftSupport.shared.opi(graph: graph, startLevel: level)
            busy = false
            guard session != nil else {
                missing = true
                return
            }
            stage = .interview
            questionNumber = 0
            await nextQuestion()
        }
    }

    private func nextQuestion() async {
        guard let session else { return }
        busy = true
        question = nil
        let line = try? await session.next()
        busy = false
        guard let line else {
            await rate()
            return
        }
        question = line
        questionNumber += 1
        await voice.sayPartner(line.japanese, graph: app.graph)
    }

    private func transcribe(_ samples: [Float]) {
        busy = true
        sttNote = nil
        let graph = app.graph
        Task {
            let out = await SpeechToText.transcribe(samples, graph: graph)
            busy = false
            if let error = out.error {
                sttNote = error
                return
            }
            answer(out.text)
        }
    }

    private func answer(_ text: String) {
        guard let session else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        voice.stop()
        session.answer(text: trimmed)
        typedAnswer = ""
        Task {
            if session.finished { await rate() } else { await nextQuestion() }
        }
    }

    private func finish() {
        voice.stop()
        if recorder.isRecording { _ = recorder.stop() }
        session?.stop()
        Task { await rate() }
    }

    private func rate() async {
        guard let session else { return }
        stage = .rating
        busy = true
        question = nil
        rating = try? await session.rate()
        checklist = SwiftSupport.shared.opiChecklist(session: session)
        transcript = SwiftSupport.shared.opiTranscript(session: session)
        busy = false
    }

    private func save(_ r: OpiRating) {
        guard let session else { return }
        saveError = nil
        let graph = app.graph
        Task {
            do {
                let exams = try await graph.exams()
                savedId = try await SwiftSupport.shared.saveOpi(exams: exams, session: session, rating: r)
                stage = .done
            } catch {
                saveError = "Couldn't save: \(error.localizedDescription)"
            }
        }
    }

    private func reset() {
        session = nil
        rating = nil
        question = nil
        checked = []
        checklist = []
        transcript = []
        savedId = nil
        stage = .setup
    }
}
