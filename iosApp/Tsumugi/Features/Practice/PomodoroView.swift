import Shared
import SwiftUI

/// Pomodoro speaking session (BRIEF §5.10 "Sessions"): a 25-minute block of mini-activities, then a break.
/// The queue comes from the shared PomodoroSession; this screen only renders and reports results.
struct PomodoroView: View {
    @Environment(AppModel.self) private var app
    @AppStorage("practice.jlpt") private var jlpt = 4

    @State private var session: PomodoroSession?
    @State private var missing = false
    @State private var starting = false
    @State private var remaining: Int64 = 0
    @State private var breakRemaining: Int64 = 0
    @State private var version = 0

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let session {
                    running(session)
                } else {
                    setup
                }
            }
            .padding()
        }
        .navigationTitle("Speaking session")
        .onReceive(timer) { _ in refreshClock() }
    }

    private var setup: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("25 minutes of short speaking and listening activities, then a 5-minute break. Works without an AI model.")
                .font(.subheadline)
            Picker("Level", selection: $jlpt) {
                ForEach(jlptLevels, id: \.self) { Text("N\($0)").tag($0) }
            }
            .pickerStyle(.segmented)
            Button(starting ? "Preparing…" : "Start session") { start() }
                .buttonStyle(.borderedProminent)
                .disabled(starting)
            if missing {
                ContentUnavailableView(
                    "Practice pack not installed",
                    systemImage: "waveform.badge.exclamationmark",
                    description: Text("Sessions are built from the speaking & listening pack. Build it with `uv run packs/build_practice.py` in tools/ and rebuild the app.")
                )
            }
        }
    }

    @ViewBuilder
    private func running(_ session: PomodoroSession) -> some View {
        let _ = version
        HStack {
            Label(session.onBreak ? "Break" : "Focus", systemImage: session.onBreak ? "cup.and.saucer" : "timer")
                .font(.headline)
            Spacer()
            Text(clockText(seconds: session.onBreak ? breakRemaining : remaining))
                .font(.title2.monospacedDigit().weight(.semibold))
        }
        Text("\(session.completed.count) done" + (session.accuracy.map { " · \(Int($0.doubleValue * 100))% right" } ?? ""))
            .font(.caption).foregroundStyle(.secondary)
        if session.onBreak {
            Text(breakRemaining > 0 ? "Time for a break. Stand up, stretch, look at something far away." : "Break's over. Start another session when you're ready.")
            Button("New session") { reset() }.buttonStyle(.borderedProminent)
        } else if let activity = session.current {
            Text(activity.title).font(.title3.weight(.semibold))
            ActivityView(activity: activity, graph: app.graph) { correct, score in
                session.complete(correct: correct.map { KotlinBoolean(bool: $0) }, score: score.map { KotlinInt(int: Int32($0)) })
                version += 1
            }
            .id("\(session.index)")
            Button("Skip") {
                session.skip()
                version += 1
            }
            .font(.subheadline)
        } else {
            Text("You've done every activity in this session.").font(.headline)
            Button("New session") { reset() }.buttonStyle(.borderedProminent)
        }
    }

    private func start() {
        starting = true
        missing = false
        Task {
            session = try? await app.graph.pomodoro(jlpt: Int32(jlpt))
            missing = session == nil
            starting = false
            refreshClock()
        }
    }

    private func reset() {
        session = nil
        version += 1
    }

    private func refreshClock() {
        guard let session else { return }
        let s = SwiftSupport.shared
        remaining = s.pomodoroRemainingSeconds(session: session)
        breakRemaining = s.pomodoroBreakRemainingSeconds(session: session)
        version += 1
    }
}

/// Renders one shared `Activity` and reports (correct?, score?).
private struct ActivityView: View {
    let activity: any Activity
    let graph: AppGraph
    let onDone: (Bool?, Int?) -> Void

    @State private var voice = VoicePlayer()
    @State private var recorder = Recorder()
    @State private var chosen: String?
    @State private var report: PronunciationReport?
    @State private var heard: String?
    @State private var note: String?
    @State private var working = false
    @State private var answers: [Int: Int] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch onEnum(of: activity) {
            case .roleplayTurn(let a):
                Text("\(a.scenario.titleJa) · \(a.scenario.titleEn)").font(.japanese(size: 17))
                Text("Have a few turns of this conversation, then come back.").font(.subheadline)
                NavigationLink(value: Route.roleplay(a.scenario.id)) {
                    Label("Open role-play", systemImage: "bubble.left.and.bubble.right")
                }
                .buttonStyle(.bordered)
                Button("Done") { onDone(nil, nil) }.buttonStyle(.borderedProminent)
            case .sentenceRepeat(let a):
                let text = a.line.japanese
                let hint = a.speaker?.voice
                Text(text).font(.japanese(size: 20))
                Text(a.line.english).font(.caption).foregroundStyle(.secondary)
                Button {
                    Task { await voice.say(text, voice: VoicePlayer.Voice(hint: hint)) }
                } label: {
                    Label("Listen", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
                RecordButton(recorder: recorder, label: working ? "Scoring…" : "Repeat it", disabled: working) { samples in
                    score(samples, target: text)
                }
                if let heard { Text("Heard: \(heard)").font(.japanese(size: 15)) }
                if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
                if let report { PronunciationPanel(report: report) }
                Button("Next") { onDone(nil, report.map { Int($0.composite) }) }
                    .buttonStyle(.borderedProminent)
            case .whatDoYouHear(let a):
                let answer = a.answer
                let options = [a.pair.a.text, a.pair.b.text]
                Button {
                    Task { await voice.say(answer, rate: 0.9) }
                } label: {
                    Label("Play", systemImage: "speaker.wave.2.fill")
                }
                .buttonStyle(.bordered)
                .task { await voice.say(answer, rate: 0.9) }
                choiceButtons(options, correct: answer)
            case .pickAWord(let a):
                let line = a.line.japanese
                let hint = a.speaker?.voice
                Text(a.prompt).font(.japanese(size: 20))
                Button {
                    Task { await voice.say(line, voice: VoicePlayer.Voice(hint: hint)) }
                } label: {
                    Label("Play line", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
                choiceButtons(a.choices, correct: a.gap.text)
            case .storyTime(let a):
                let d = a.dialogue
                let lines = d.lines.map { l in (text: l.japanese, voice: VoicePlayer.Voice(hint: d.speaker(id: l.speaker)?.voice)) }
                let questions = d.questions.enumerated().map { i, q in
                    DialogueData.Question(id: i, question: q.question, choices: q.choices, answer: Int(q.answer))
                }
                StoryTimeView(lines: lines, questions: questions, voice: voice) { right in
                    onDone(right, nil)
                }
            }
        }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
    }

    @ViewBuilder
    private func choiceButtons(_ options: [String], correct: String) -> some View {
        ForEach(options, id: \.self) { option in
            Button {
                if chosen == nil { chosen = option }
            } label: {
                HStack {
                    Text(option).font(.japanese(size: 20))
                    Spacer()
                    if chosen != nil && option == correct { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                    else if chosen == option { Image(systemName: "xmark.circle.fill").foregroundStyle(.red) }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        if let chosen {
            Button("Next") { onDone(chosen == correct, nil) }.buttonStyle(.borderedProminent)
        }
    }

    private func score(_ samples: [Float], target: String) {
        voice.stop()
        working = true
        note = nil
        let g = graph
        Task {
            let out = await SpeechToText.transcribe(samples, graph: g)
            heard = out.error == nil ? out.text : nil
            if let error = out.error { note = error }
            report = try? await SwiftSupport.shared.analyzePronunciation(graph: g, sentence: target, transcript: heard, samples: kotlinFloats(samples))
            working = false
        }
    }
}

/// Story time: listen to a short dialogue, then answer its questions.
private struct StoryTimeView: View {
    let lines: [(text: String, voice: VoicePlayer.Voice)]
    let questions: [DialogueData.Question]
    let voice: VoicePlayer
    let onDone: (Bool) -> Void

    @State private var chosen: [Int: Int] = [:]
    @State private var listened = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                Task {
                    await voice.sayLines(lines)
                    listened = true
                }
            } label: {
                Label(voice.isSpeaking ? "Playing…" : "Listen to the story", systemImage: "play.fill")
            }
            .buttonStyle(.bordered)
            .disabled(voice.isSpeaking)
            if listened || !chosen.isEmpty {
                ForEach(questions) { q in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(q.question).font(.subheadline.weight(.semibold))
                        ForEach(Array(q.choices.enumerated()), id: \.offset) { i, choice in
                            Button {
                                if chosen[q.id] == nil { chosen[q.id] = i }
                            } label: {
                                HStack {
                                    Text(choice).multilineTextAlignment(.leading)
                                    Spacer()
                                    if chosen[q.id] != nil && i == q.answer {
                                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                                    } else if chosen[q.id] == i {
                                        Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                                    }
                                }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                if chosen.count == questions.count {
                    let allRight = questions.allSatisfy { chosen[$0.id] == $0.answer }
                    Button("Next") { onDone(allRight) }.buttonStyle(.borderedProminent)
                }
            } else {
                Text("Questions appear after you listen.").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
