import AVFoundation
import Foundation
import Observation
import Shared
import Speech

/// Microphone capture as 16 kHz mono Float32 in [-1, 1], the format the shared speech code expects
/// (BRIEF §5.10). An AVAudioEngine input tap feeds an AVAudioConverter; samples collect in memory only.
/// Recordings never leave the device and are never written to disk.
final class AudioCapture: @unchecked Sendable {
    static let sampleRate = 16_000.0

    enum CaptureError: LocalizedError {
        case noInput
        case format

        var errorDescription: String? {
            switch self {
            case .noInput: "No microphone input is available."
            case .format: "The microphone's audio format isn't supported."
            }
        }
    }

    private let engine = AVAudioEngine()
    private let store = SampleStore()
    private let lock = NSLock()
    private var running = false

    var isRunning: Bool {
        lock.lock(); defer { lock.unlock() }
        return running
    }

    /// Current input level 0…1 (for a simple meter).
    var level: Float { store.level }

    /// The audio session must already be set up for recording (`AudioSessionController.beginRecording`).
    func start() throws {
        if isRunning { _ = stop() }
        _ = store.take()

        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0, inFormat.channelCount > 0 else { throw CaptureError.noInput }
        guard let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: inFormat, to: outFormat) else { throw CaptureError.format }
        let store = self.store
        input.installTap(onBus: 0, bufferSize: 4096, format: inFormat) { buffer, _ in
            AudioCapture.convert(buffer, converter: converter, outFormat: outFormat, into: store)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            throw error
        }
        lock.lock(); running = true; lock.unlock()
    }

    /// Stops recording and returns everything captured since [start].
    func stop() -> [Float] {
        lock.lock()
        let wasRunning = running
        running = false
        lock.unlock()
        if wasRunning {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        return store.take()
    }

    private static func convert(_ buffer: AVAudioPCMBuffer, converter: AVAudioConverter, outFormat: AVAudioFormat, into store: SampleStore) {
        let ratio = outFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return }
        let fed = FedFlag()
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, inputStatus in
            if fed.value {
                inputStatus.pointee = .noDataNow
                return nil
            }
            fed.value = true
            inputStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, let channels = out.floatChannelData, out.frameLength > 0 else { return }
        store.append(UnsafeBufferPointer(start: channels[0], count: Int(out.frameLength)))
    }
}

/// Whether the converter already received the tap's buffer (a class so the input block needn't mutate a captured var).
private final class FedFlag: @unchecked Sendable {
    var value = false
}

/// Samples appended from the audio thread and taken on the main thread.
final class SampleStore: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [Float] = []
    private var lastLevel: Float = 0

    func append(_ chunk: UnsafeBufferPointer<Float>) {
        var sum: Float = 0
        for s in chunk { sum += s * s }
        let rms = chunk.isEmpty ? 0 : (sum / Float(chunk.count)).squareRoot()
        lock.lock()
        samples.append(contentsOf: chunk)
        lastLevel = min(1, rms * 8)
        lock.unlock()
    }

    func take() -> [Float] {
        lock.lock(); defer { lock.unlock() }
        let out = samples
        samples = []
        lastLevel = 0
        return out
    }

    var level: Float {
        lock.lock(); defer { lock.unlock() }
        return lastLevel
    }
}

/// Microphone recording for the speaking screens: asks for permission, records, and hands back the samples.
@MainActor
@Observable
final class Recorder {
    private(set) var isRecording = false
    private(set) var message: String?
    private let capture = AudioCapture()

    /// Starts recording; false (with [message] set) if the microphone is unavailable or not allowed.
    @discardableResult
    func start() async -> Bool {
        guard await AVAudioApplication.requestRecordPermission() else {
            message = "Microphone access is off. Turn it on in the Settings app → Privacy & Security → Microphone, or type your answer."
            return false
        }
        do {
            // A call or Siri stops the microphone; what was said so far is dropped with a note (F-14).
            try AudioSessionController.shared.beginRecording(self) { [weak self] in
                guard let self, self.isRecording else { return }
                _ = self.stop()
                self.message = "Recording stopped by an interruption. Tap Speak to try again."
            }
            try capture.start()
            isRecording = true
            message = nil
            return true
        } catch {
            AudioSessionController.shared.end(self)
            message = "Couldn't start recording: \(error.localizedDescription)"
            return false
        }
    }

    /// Stops and returns the recording (16 kHz mono).
    func stop() -> [Float] {
        isRecording = false
        let samples = capture.stop()
        AudioSessionController.shared.end(self)
        return samples
    }

    var level: Float { capture.level }

    func setMessage(_ text: String?) { message = text }
}

/// Speech-to-text for the speaking screens. The engine chosen in Settings → AI (on-device Whisper or the learner's
/// own Whisper server) runs through the shared code; otherwise Apple's recognizer runs strictly on-device. If the
/// device can't recognize Japanese offline we say so instead of silently sending audio to a server.
enum SpeechToText {
    struct Output: Sendable {
        var text: String
        var engine: String
        var error: String?
    }

    static func transcribe(_ samples: [Float], graph: AppGraph) async -> Output {
        guard samples.count > Int(AudioCapture.sampleRate * 0.2) else {
            return Output(text: "", engine: "", error: "That recording was too short. Hold the button while you speak.")
        }
        if let outcome = try? await SwiftSupport.shared.transcribe(ai: graph.ai, samples: kotlinFloats(samples)) {
            return Output(text: outcome.text, engine: outcome.engine, error: outcome.error)
        }
        return await system(samples)
    }

    private static func system(_ samples: [Float]) async -> Output {
        let status = await withCheckedContinuation { (c: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) }
        }
        guard status == .authorized else {
            return Output(text: "", engine: "", error: "Speech recognition is off for Tsumugi. Allow it in the Settings app, pick Whisper in Settings → AI, or type your answer.")
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "ja-JP")), recognizer.isAvailable else {
            return Output(text: "", engine: "", error: "Japanese speech recognition isn't available on this device. Download a Whisper model in Settings → AI, or type your answer.")
        }
        guard recognizer.supportsOnDeviceRecognition else {
            return Output(text: "", engine: "", error: "This device can't recognize Japanese offline. Download a Whisper model in Settings → AI, or type your answer.")
        }
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioCapture.sampleRate, channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else {
            return Output(text: "", engine: "", error: "Couldn't prepare the recording for recognition.")
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        for i in 0..<samples.count { channel[i] = samples[i] }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        request.append(buffer)
        request.endAudio()

        let once = OnceFlag()
        return await withCheckedContinuation { (c: CheckedContinuation<Output, Never>) in
            let task = recognizer.recognitionTask(with: request) { result, error in
                if let result, result.isFinal {
                    if once.claim() { c.resume(returning: Output(text: result.bestTranscription.formattedString, engine: "on-device Apple speech", error: nil)) }
                } else if let error {
                    if once.claim() { c.resume(returning: Output(text: "", engine: "", error: "Nothing recognized (\(error.localizedDescription)).")) }
                }
            }
            _ = task
        }
    }
}

/// Resumes a continuation at most once.
final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Copies Swift samples into a Kotlin FloatArray for the shared speech code.
func kotlinFloats(_ values: [Float]) -> KotlinFloatArray {
    let array = KotlinFloatArray(size: Int32(values.count))
    for (i, v) in values.enumerated() { array.set(index: Int32(i), value: v) }
    return array
}
