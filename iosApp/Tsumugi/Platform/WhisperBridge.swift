import Foundation
import Shared

/// On-device speech-to-text with whisper.cpp (MIT) behind the shared `LocalSttBridge` (BRIEF §3.2): the
/// optional higher-accuracy engine next to Apple's on-device recognizer, and the source of timestamps for
/// generated subtitles. Results are JSON segments `[{"t0": ms, "t1": ms, "text": "…"}]`.
///
/// whisper is reached through the C shim in `tsumugi_whisper.h` (via the bridging header), never
/// `import whisper`: its ggml.h clashes with llama's in one Swift module on device builds.
final class WhisperBridge: NSObject, LocalSttBridge, @unchecked Sendable {
    /// Mirrors `context != nil` so `isLoaded()` never blocks on (or deadlocks with) the work queue.
    private let loadedFlag = LockedFlag()
    private let queue = DispatchQueue(label: "app.tsumugi.whisper", qos: .userInitiated)

    private var context: UnsafeMutableRawPointer? {
        didSet { let value = context != nil; loadedFlag.withLock { $0 = value } }
    }

    func isLoaded() -> Bool {
        loadedFlag.withLock { $0 }
    }

    func load(modelPath: String, onDone: @escaping (String?) -> Void) {
        queue.async { [self] in
            if let context { tsumugi_whisper_free(context) }
            context = nil
            #if targetEnvironment(simulator)
            let useGpu: Int32 = 0
            #else
            let useGpu: Int32 = 1
            #endif
            guard let c = tsumugi_whisper_init(modelPath, useGpu) else {
                onDone("Couldn't load the speech model at \(modelPath)")
                return
            }
            context = c
            onDone(nil)
        }
    }

    func transcribe(samples: KotlinFloatArray, language: String, onDone: @escaping (String?, String?) -> Void) {
        let count = Int(samples.size)
        var audio = [Float](repeating: 0, count: count)
        for i in 0..<count { audio[i] = samples.get(index: Int32(i)) }
        queue.async { [self] in
            guard let context else { onDone(nil, "No speech model loaded"); return }
            let threads = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount - 2))
            let status: Int32 = language.withCString { lang in
                audio.withUnsafeBufferPointer { tsumugi_whisper_full(context, $0.baseAddress, Int32(count), lang, threads) }
            }
            guard status == 0 else { onDone(nil, "Transcription failed (\(status))"); return }
            var segments: [[String: Any]] = []
            for i in 0..<tsumugi_whisper_n_segments(context) {
                let text = tsumugi_whisper_segment_text(context, i).map { String(cString: $0) } ?? ""
                segments.append([
                    // whisper reports segment times in 10 ms units
                    "t0": tsumugi_whisper_segment_t0(context, i) * 10,
                    "t1": tsumugi_whisper_segment_t1(context, i) * 10,
                    "text": text.trimmingCharacters(in: .whitespaces),
                ])
            }
            let json = (try? JSONSerialization.data(withJSONObject: segments)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
            onDone(json, nil)
        }
    }
}
