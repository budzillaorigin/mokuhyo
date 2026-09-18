import AVFoundation
import Foundation
import Shared
import SwiftUI

/// 16-bit PCM WAV bytes for 16 kHz mono samples (what `AudioCapture` records). Plain RIFF, no dependencies.
enum WavFile {
    static func data(samples: [Float], sampleRate: Int = Int(AudioCapture.sampleRate)) -> Data {
        let pcmBytes = UInt32(samples.count * 2)
        var out = Data(capacity: 44 + samples.count * 2)
        func ascii(_ s: String) { out.append(contentsOf: Array(s.utf8)) }
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        ascii("RIFF"); u32(36 + pcmBytes); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(1); u32(UInt32(sampleRate)); u32(UInt32(sampleRate * 2)); u16(2); u16(16)
        ascii("data"); u32(pcmBytes)
        for s in samples {
            let v = Int16(max(-1, min(1, s)) * 32767)
            withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) }
        }
        return out
    }
}

/// Keeps a microphone take as a recording on disk (BRIEF_V2 G-03, D-110): the shared store hands out the path, the
/// file is written off the main actor, then `register` indexes it. Recordings live under Application Support and
/// are excluded from backups by the shared store.
enum RecordingSaver {
    static func save(
        _ samples: [Float],
        graph: AppGraph,
        kind: RecordingKind,
        ref: String?,
        referenceKey: String?
    ) async throws -> Recording_ {
        let pending = try await graph.recordings.startRecording(fileExtension: "wav")
        let path = pending.path
        let data = WavFile.data(samples: samples)
        try await Task.detached(priority: .utility) {
            try data.write(to: URL(fileURLWithPath: path), options: .atomic)
        }.value
        let ms = Int64(Double(samples.count) / AudioCapture.sampleRate * 1000)
        return try await graph.recordings.register(
            pending: pending, kind: kind, ref: ref, durationMs: ms, mime: "audio/wav", referenceKey: referenceKey
        )
    }
}

/// Where model audio for a sentence comes from. Today it is the system voice; the pre-rendered audio pack
/// (rule 20, §5.6) plugs in here later as another implementation, without touching the screens.
@MainActor
protocol ClipPlayer: AnyObject {
    /// Plays the model audio for [sentence] and returns when it ends.
    func play(_ sentence: String) async
    /// The same audio as 16 kHz mono samples for the shadowing comparison, or nil when it can't be rendered.
    func referenceSamples(_ sentence: String) async -> [Float]?
    /// The reference key stored with the learner's recording (`tts:` / `pack:`), for side-by-side playback.
    func referenceKey(_ sentence: String) -> String
    func stop()
}

/// The system Japanese voice as a [ClipPlayer]. Reference samples come from `AVSpeechSynthesizer.write`, converted to
/// 16 kHz mono, so the shadowing comparison runs fully on device.
@MainActor
final class TtsClipPlayer: ClipPlayer {
    private let voice = VoicePlayer()
    var rate: Float = 1.0

    func play(_ sentence: String) async { await voice.say(sentence, rate: rate) }

    func referenceKey(_ sentence: String) -> String { ReferenceClip.companion.tts(text: sentence).key }

    func stop() { voice.stop() }

    func referenceSamples(_ sentence: String) async -> [Float]? {
        await SpeechRenderer.render(sentence, rate: rate)
    }
}

/// Renders system TTS to samples. The synthesizer's final (empty) buffer ends the render; a timeout guards against
/// iOS versions that never deliver it.
enum SpeechRenderer {
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var samples: [Float] = []
        var converter: AVAudioConverter?
        let synthesizer = AVSpeechSynthesizer()
        let once = OnceFlag()
    }

    @MainActor
    static func render(_ text: String, rate: Float) async -> [Float]? {
        let state = State()
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "ja-JP")
        utterance.rate = min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * rate)
        guard let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioCapture.sampleRate, channels: 1, interleaved: false) else {
            return nil
        }
        let result: [Float]? = await withCheckedContinuation { (c: CheckedContinuation<[Float]?, Never>) in
            state.synthesizer.write(utterance) { buffer in
                guard let pcm = buffer as? AVAudioPCMBuffer, pcm.frameLength > 0 else {
                    if state.once.claim() {
                        state.lock.lock(); let out = state.samples; state.lock.unlock()
                        c.resume(returning: out.isEmpty ? nil : out)
                    }
                    return
                }
                state.lock.lock()
                if state.converter == nil { state.converter = AVAudioConverter(from: pcm.format, to: outFormat) }
                let converter = state.converter
                state.lock.unlock()
                guard let converter else { return }
                let ratio = outFormat.sampleRate / pcm.format.sampleRate
                guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: AVAudioFrameCount(Double(pcm.frameLength) * ratio) + 64) else { return }
                let fed = OnceFlag()
                var error: NSError?
                let status = converter.convert(to: out, error: &error) { _, inputStatus in
                    if fed.claim() {
                        inputStatus.pointee = .haveData
                        return pcm
                    }
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                guard status != .error, let channel = out.floatChannelData?[0], out.frameLength > 0 else { return }
                let chunk = Array(UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
                state.lock.lock(); state.samples.append(contentsOf: chunk); state.lock.unlock()
            }
            Task {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                if state.once.claim() {
                    state.lock.lock(); let out = state.samples; state.lock.unlock()
                    c.resume(returning: out.isEmpty ? nil : out)
                }
            }
        }
        return result
    }
}

/// The learner's recording next to the model audio (G-03 side-by-side playback). The shared store resolves clip and
/// recording references to files; TTS references are spoken; pack audio waits for the audio-pack helper.
struct SideBySideView: View {
    @Environment(AppModel.self) private var app
    let recordingId: String

    @State private var pair: SideBySide?
    @State private var voice = VoicePlayer()
    @State private var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Compare").font(.subheadline.weight(.semibold))
            HStack {
                Button {
                    playModel()
                } label: {
                    Label("Model", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
                .disabled(pair?.reference == nil)
                Button {
                    playMine()
                } label: {
                    Label("Mine", systemImage: "person.wave.2")
                }
                .buttonStyle(.bordered)
                .disabled(pair == nil)
                Button {
                    Task {
                        await playModelAndWait()
                        await playMineAndWait()
                    }
                } label: {
                    Label("Both", systemImage: "arrow.left.arrow.right")
                }
                .buttonStyle(.bordered)
                .disabled(pair?.reference == nil)
            }
            if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
        }
        .task(id: recordingId) { pair = try? await app.graph.recordings.sideBySide(id: recordingId) }
        .onDisappear { voice.stop() }
    }

    private func playModel() { Task { await playModelAndWait() } }
    private func playMine() { Task { await playMineAndWait() } }

    private func playMineAndWait() async {
        guard let pair else { return }
        if !(await voice.play(file: pair.learnerPath)) { note = String(localized: "Your recording isn't on this device.") }
    }

    private func playModelAndWait() async {
        guard let pair, let reference = pair.reference else { return }
        if let path = pair.referencePath, await voice.play(file: path) { return }
        switch reference.kind {
        case .tts:
            await voice.say(reference.value)
        default:
            note = String(localized: "The model audio isn't available on this device yet.")
        }
    }
}

/// Records the learner saying an item and attaches it as the item's own audio side (G-03 "Add my recording",
/// `PersonalCards.addAudioSide`): a LISTENING card whose front plays their voice.
struct AddMyRecordingButton: View {
    @Environment(AppModel.self) private var app
    let itemId: String

    @State private var recorder = Recorder()
    @State private var saving = false
    @State private var savedId: String?
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionHeader("My recording")
            Text("Record yourself saying it. It becomes a listening card that plays your own voice.")
                .font(.caption).foregroundStyle(.secondary)
            RecordButton(recorder: recorder, label: saving ? "Saving…" : "Add my recording", disabled: saving) { samples in
                save(samples)
            }
            if let savedId {
                Label("Saved. A listening card with your voice was added.", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.green)
                SideBySideView(recordingId: savedId)
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }

    private func save(_ samples: [Float]) {
        guard samples.count > Int(AudioCapture.sampleRate * 0.3) else {
            error = String(localized: "That recording was too short. Tap Stop after you speak.")
            return
        }
        saving = true
        error = nil
        let graph = app.graph
        let id = itemId
        Task {
            do {
                let rec = try await RecordingSaver.save(samples, graph: graph, kind: .card, ref: id, referenceKey: nil)
                _ = try await graph.personalCards.addAudioSide(itemId: id, recordingId: rec.id)
                savedId = rec.id
            } catch {
                self.error = String(localized: "Couldn't save the recording: \(error.localizedDescription)")
            }
            saving = false
        }
    }
}
