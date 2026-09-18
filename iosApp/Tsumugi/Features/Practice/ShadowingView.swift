import Shared
import SwiftUI

/// One sentence to shadow (a Swift mirror of the Today plan's `ShadowingSentence`).
struct ShadowLine: Hashable, Identifiable {
    let japanese: String
    let english: String
    let pointTitle: String
    let aiGenerated: Bool
    var id: String { japanese }

    init(japanese: String, english: String, pointTitle: String, aiGenerated: Bool) {
        self.japanese = japanese
        self.english = english
        self.pointTitle = pointTitle
        self.aiGenerated = aiGenerated
    }

    init(_ s: ShadowingSentence) {
        self.init(japanese: s.japanese, english: s.english, pointTitle: s.pointTitle, aiGenerated: s.aiGenerated)
    }
}

/// Today's shadowing block (G-01): listen to each sentence, say it along, then see the pronunciation analysis, the
/// shadowing comparison (timing and intonation against the model audio) and the two recordings side by side.
/// Model audio comes from a [ClipPlayer]: the system voice for now, the pre-rendered pack later.
struct ShadowingView: View {
    @Environment(AppModel.self) private var app
    let lines: [ShadowLine]
    var onFinished: () -> Void = {}

    @State private var index = 0
    @State private var player = TtsClipPlayer()
    @State private var recorder = Recorder()
    @State private var working = false
    @State private var heard: String?
    @State private var note: String?
    @State private var report: PronunciationReport?
    @State private var shadowing: ShadowingReport?
    @State private var recordingId: String?
    @State private var slow = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if lines.isEmpty {
                    ContentUnavailableView(
                        "Nothing to shadow",
                        systemImage: "waveform",
                        description: Text("Shadowing uses example sentences from the grammar you're learning. Install the grammar pack and start a grammar lesson.")
                    )
                } else if index >= lines.count {
                    Label("Shadowing done", systemImage: "checkmark.circle").font(.title3.weight(.semibold))
                    Text("\(lines.count) sentences practised.")
                    Button("Finish") { onFinished() }.buttonStyle(.borderedProminent)
                } else {
                    sentenceView(lines[index])
                }
            }
            .padding()
        }
        .navigationTitle("Shadowing")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            player.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
    }

    @ViewBuilder
    private func sentenceView(_ line: ShadowLine) -> some View {
        ProgressLine(done: index, left: lines.count - index)
        if !line.pointTitle.isEmpty { Text(line.pointTitle).font(.caption.weight(.semibold)).foregroundStyle(.tint) }
        Text(line.japanese).font(.japanese(size: 24)).japaneseSpeech()
        Text(line.english).font(.subheadline).foregroundStyle(.secondary)
        if line.aiGenerated { AiBadge() }
        HStack {
            Button {
                player.rate = slow ? 0.7 : 1.0
                Task { await player.play(line.japanese) }
            } label: {
                Label("Listen", systemImage: "speaker.wave.2")
            }
            .buttonStyle(.bordered)
            Toggle("Slow", isOn: $slow).toggleStyle(.button).font(.caption)
        }
        Text("Listen, then record yourself saying it along with the rhythm.").font(.caption).foregroundStyle(.secondary)
        RecordButton(recorder: recorder, label: working ? "Scoring…" : "Record", disabled: working) { samples in
            score(samples, line: line)
        }
        if working { ProgressView("Comparing with the model audio…") }
        if let heard { Text("Heard: \(heard)").font(.japanese(size: 16)) }
        if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
        if let shadowing { ShadowingPanel(report: shadowing) }
        if let recordingId { SideBySideView(recordingId: recordingId) }
        if let report { PronunciationPanel(report: report) }
        Button(index < lines.count - 1 ? "Next sentence" : "Done") { advance() }
            .buttonStyle(.borderedProminent)
            .disabled(working)
        Text("Heuristic feedback from speech recognition and a pitch tracker. The model voice is the system voice until the audio pack is installed.")
            .font(.caption2).foregroundStyle(.secondary)
    }

    private func advance() {
        player.stop()
        index += 1
        heard = nil
        note = nil
        report = nil
        shadowing = nil
        recordingId = nil
    }

    private func score(_ samples: [Float], line: ShadowLine) {
        player.stop()
        working = true
        note = nil
        heard = nil
        report = nil
        shadowing = nil
        recordingId = nil
        let graph = app.graph
        let clip = player
        Task {
            let out = await SpeechToText.transcribe(samples, graph: graph)
            if let error = out.error { note = error + " " + String(localized: "Scoring pitch and fluency only.") } else { heard = out.text }
            let outcome = try? await SwiftSupport.shared.analyzePronunciation(
                graph: graph, sentence: line.japanese, transcript: out.error == nil ? out.text : nil, samples: kotlinFloats(samples)
            )
            report = outcome?.report
            if report == nil {
                note = [note, String(localized: "Couldn't analyze the recording. Try again.")].compactMap { $0 }.joined(separator: " ")
            }
            if let reference = await clip.referenceSamples(line.japanese) {
                shadowing = try? await graph.pronunciation.shadowing(reference: kotlinFloats(reference), attempt: kotlinFloats(samples))
            }
            recordingId = (try? await RecordingSaver.save(samples, graph: graph, kind: .sentence, ref: line.japanese, referenceKey: clip.referenceKey(line.japanese)))?.id
            working = false
        }
    }
}

/// Timing and intonation against the model audio.
struct ShadowingPanel: View {
    let report: ShadowingReport

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(report.overall)").font(.largeTitle.weight(.bold).monospacedDigit())
                Text("/ 100 shadowing").font(.subheadline).foregroundStyle(.secondary)
            }
            LabeledContent("Timing", value: "\(report.timingScore)")
            LabeledContent("Intonation", value: "\(report.intonationScore)")
            let ratio = String(format: "%.2f", report.durationRatio)
            Text("Your length ÷ model length: \(ratio)").font(.caption).foregroundStyle(.secondary)
            ForEach(report.notes, id: \.self) { n in
                Label(n, systemImage: "info.circle").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 12))
    }
}
