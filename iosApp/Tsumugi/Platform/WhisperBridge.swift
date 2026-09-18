import Foundation
import Shared
#if canImport(whisper)
import whisper
#endif

/// On-device speech-to-text with whisper.cpp (MIT) behind the shared `LocalSttBridge` (BRIEF §3.2): the
/// optional higher-accuracy engine next to Apple's on-device recognizer, and the source of timestamps for
/// generated subtitles. Results are JSON segments `[{"t0": ms, "t1": ms, "text": "…"}]`.
final class WhisperBridge: NSObject, LocalSttBridge, @unchecked Sendable {
    /// Mirrors `context != nil` so `isLoaded()` never blocks on (or deadlocks with) the work queue.
    private let loadedFlag = LockedFlag()
    private let queue = DispatchQueue(label: "app.tsumugi.whisper", qos: .userInitiated)

    #if canImport(whisper)
    private var context: OpaquePointer? {
        didSet { let value = context != nil; loadedFlag.withLock { $0 = value } }
    }
    #endif

    func isLoaded() -> Bool {
        #if canImport(whisper)
        return loadedFlag.withLock { $0 }
        #else
        return false
        #endif
    }

    func load(modelPath: String, onDone: @escaping (String?) -> Void) {
        #if canImport(whisper)
        queue.async { [self] in
            if let context { whisper_free(context) }
            context = nil
            var params = whisper_context_default_params()
            #if targetEnvironment(simulator)
            params.use_gpu = false
            #else
            params.use_gpu = true
            #endif
            guard let c = whisper_init_from_file_with_params(modelPath, params) else {
                onDone("Couldn't load the speech model at \(modelPath)")
                return
            }
            context = c
            onDone(nil)
        }
        #else
        onDone("Whisper isn't available in this build")
        #endif
    }

    func transcribe(samples: KotlinFloatArray, language: String, onDone: @escaping (String?, String?) -> Void) {
        #if canImport(whisper)
        let count = Int(samples.size)
        var audio = [Float](repeating: 0, count: count)
        for i in 0..<count { audio[i] = samples.get(index: Int32(i)) }
        queue.async { [self] in
            guard let context else { onDone(nil, "No speech model loaded"); return }
            var params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY)
            params.translate = false
            params.no_timestamps = false
            params.token_timestamps = false
            params.print_progress = false
            params.print_realtime = false
            params.print_timestamps = false
            params.print_special = false
            params.n_threads = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount - 2))
            let status: Int32 = language.withCString { lang in
                params.language = lang
                return audio.withUnsafeBufferPointer { whisper_full(context, params, $0.baseAddress, Int32(count)) }
            }
            guard status == 0 else { onDone(nil, "Transcription failed (\(status))"); return }
            var segments: [[String: Any]] = []
            for i in 0..<whisper_full_n_segments(context) {
                let text = whisper_full_get_segment_text(context, i).map { String(cString: $0) } ?? ""
                segments.append([
                    // whisper reports segment times in 10 ms units
                    "t0": whisper_full_get_segment_t0(context, i) * 10,
                    "t1": whisper_full_get_segment_t1(context, i) * 10,
                    "text": text.trimmingCharacters(in: .whitespaces),
                ])
            }
            let json = (try? JSONSerialization.data(withJSONObject: segments)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
            onDone(json, nil)
        }
        #else
        onDone(nil, "Whisper isn't available in this build")
        #endif
    }
}
