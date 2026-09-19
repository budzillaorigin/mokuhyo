import Shared
import SwiftUI

/// Heuristic pronunciation feedback (BRIEF §5.10): a 0–100 composite with labeled sub-scores, per-word pitch
/// marks (↑ rise, ↓ drop) expected vs. heard, and the analyzer's plain-language caveats.
struct PronunciationPanel: View {
    let report: PronunciationReport

    /// The big overall score follows Dynamic Type.
    @ScaledMetric(relativeTo: .largeTitle) private var scoreSize: CGFloat = 44

    private var subScores: [(label: String, value: Int)] {
        let order = ["mora", "pitch", "fluency"]
        let keys = report.subScores.keys.sorted { (order.firstIndex(of: $0) ?? 99) < (order.firstIndex(of: $1) ?? 99) }
        return keys.map { key in (label: Self.label(key), value: Int(report.subScores[key]?.intValue ?? 0)) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(report.composite)").font(.system(size: scoreSize, weight: .bold, design: .rounded))
                Text("/ 100 overall").font(.subheadline).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Overall score"))
            .accessibilityValue(Text("\(Int(report.composite)) out of 100"))
            ForEach(subScores, id: \.label) { s in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(s.label).font(.subheadline)
                        Spacer()
                        Text("\(s.value)").font(.subheadline.monospacedDigit())
                    }
                    ProgressView(value: Double(s.value), total: 100)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(s.label))
                .accessibilityValue(Text("\(s.value) out of 100"))
            }
            let rate = String(format: "%.1f", report.rateMoraPerSec)
            Text("Speaking rate \(rate) morae/s · \(report.pauses.count) long pauses")
                .font(.caption).foregroundStyle(.secondary)
            if !report.words.isEmpty {
                Text("Pitch accent by word").font(.subheadline.weight(.semibold)).padding(.top, 4)
                ForEach(Array(report.words.enumerated()), id: \.offset) { _, w in
                    HStack(alignment: .top) {
                        Image(systemName: Self.icon(w.verdict)).foregroundStyle(Self.color(w.verdict))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Expected  \(w.expectedMarks)").font(.japanese(size: 16))
                            Text("Heard  \(w.observedMarks.isEmpty ? "—" : w.observedMarks)").font(.japanese(size: 16)).foregroundStyle(.secondary)
                            Text(Self.verdictText(w.verdict)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            ForEach(report.notes, id: \.self) { note in
                Label(note, systemImage: "info.circle").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 12))
    }

    static func label(_ key: String) -> String {
        switch key {
        case "mora": String(localized: "Mora accuracy (what the recognizer heard)")
        case "pitch": String(localized: "Pitch accent (word by word)")
        case "fluency": String(localized: "Fluency (rate and pauses)")
        default: key.capitalized
        }
    }

    static func verdictText(_ v: PitchVerdict) -> String {
        switch v {
        case .match: String(localized: "Pitch pattern matches")
        case .flat: String(localized: "Sounded flat; try a clearer rise and fall")
        case .wrongDrop: String(localized: "The drop is in a different place")
        case .unclear: String(localized: "Couldn't tell from the recording")
        default: String(localized: "No dictionary accent for this word")
        }
    }

    static func icon(_ v: PitchVerdict) -> String {
        switch v {
        case .match: "checkmark.circle"
        case .flat, .wrongDrop: "arrow.up.arrow.down.circle"
        default: "questionmark.circle"
        }
    }

    static func color(_ v: PitchVerdict) -> Color {
        switch v {
        case .match: .green
        case .flat, .wrongDrop: .orange
        default: .secondary
        }
    }
}

/// Pronunciation check for any sentence: listen, record, see the panel. Also opened from dialogue lines.
struct PronunciationPracticeView: View {
    @Environment(AppModel.self) private var app
    let initialSentence: String

    @State private var sentence = ""
    @State private var recorder = Recorder()
    @State private var voice = VoicePlayer()
    @State private var heard: String?
    @State private var note: String?
    @State private var report: PronunciationReport?
    @State private var working = false
    /// The saved take, for side-by-side playback with the model audio (G-03).
    @State private var recordingId: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Type or paste a Japanese sentence, listen to it, then record yourself saying it.")
                    .font(.subheadline).foregroundStyle(.secondary)
                TextField("日本語の文", text: $sentence, axis: .vertical)
                    .font(.japanese(size: 20))
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button {
                    Task { await voice.say(sentence) }
                } label: {
                    Label("Listen", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
                .disabled(sentence.trimmingCharacters(in: .whitespaces).isEmpty)
                RecordButton(recorder: recorder, label: working ? "Scoring…" : "Record", disabled: working || sentence.trimmingCharacters(in: .whitespaces).isEmpty) { samples in
                    score(samples)
                }
                if let heard { Text("Heard: \(heard)").font(.japanese(size: 16)) }
                if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
                if working { ProgressView() }
                if let recordingId { SideBySideView(recordingId: recordingId) }
                if let report { PronunciationPanel(report: report) }
                Text("This is heuristic feedback from speech recognition and a pitch tracker, not a phoneme-level assessment. Accents come from the dictionary pack when installed.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            .padding()
        }
        .navigationTitle("Pronunciation")
        .onAppear { if sentence.isEmpty { sentence = initialSentence } }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
    }

    private func score(_ samples: [Float]) {
        voice.stop()
        working = true
        note = nil
        heard = nil
        recordingId = nil
        let target = sentence
        let graph = app.graph
        Task {
            let key = ReferenceClip.companion.tts(text: target).key
            recordingId = (try? await RecordingSaver.save(samples, graph: graph, kind: .sentence, ref: target, referenceKey: key))
            let out = await SpeechToText.transcribe(samples, graph: graph)
            if let error = out.error {
                note = error + " Scoring pitch and fluency only."
            } else {
                heard = out.text
            }
            let outcome = try? await SwiftSupport.shared.analyzePronunciation(graph: graph, sentence: target, transcript: out.error == nil ? out.text : nil, samples: kotlinFloats(samples))
            report = outcome?.report
            if report == nil {
                note = (note ?? "") + " Couldn't analyze the recording" + (outcome?.error.map { ": \($0)." } ?? ".") + " Try again."
            }
            working = false
        }
    }
}
