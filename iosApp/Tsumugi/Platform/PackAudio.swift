import AVFoundation
import Foundation
import Shared

/// Pre-rendered VOICEVOX clips (CLAUDE.md rule 20, BRIEF_V2 §5.6, D-095): the shared `AudioPackRepository` resolves a
/// clip key (`AudioKeys`) to a plain file, or nil when that set isn't installed, and every caller then falls back to
/// the system voice. Only the lookup lives here; keys are always built by the shared `AudioKeys` helpers (D-190).
enum PackAudio {
    /// The clip file for [key], or nil (no key, set not installed, clip missing).
    static func path(_ key: String?, graph: AppGraph) -> String? {
        guard let key, !key.isEmpty else { return nil }
        return SwiftSupport.shared.audioClipPath(graph: graph, key: key)
    }

    static func examKey(ownerId: String, line: Int) -> String {
        AudioKeys.shared.exam(ownerId: ownerId, lineIndex: Int32(line))
    }

    static func dialogueKey(dialogueId: String, line: Int) -> String {
        AudioKeys.shared.dialogue(dialogueId: dialogueId, lineOrd: Int32(line))
    }

    static func grammarKey(pointId: String, example: Int) -> String {
        AudioKeys.shared.grammar(pointId: pointId, exampleOrd: Int32(example))
    }

    static func pairKey(pairId: Int64, sideA: Bool) -> String {
        SwiftSupport.shared.pairClipKey(pairId: pairId, sideA: sideA)
    }
}

extension VoicePlayer {
    /// Plays the pack clip for [key] when it is installed, else speaks [text] with the system voice (rule 20).
    /// Returns true when the pre-rendered clip played.
    @discardableResult
    func say(_ text: String, key: String?, graph: AppGraph, voice: Voice = .any, rate: Float = 1.0) async -> Bool {
        if let path = PackAudio.path(key, graph: graph), await play(file: path, rate: rate) { return true }
        await say(text, voice: voice, rate: rate)
        return false
    }

    /// Script lines in order, each from the pack when its clip is installed.
    func sayLines(_ lines: [(text: String, voice: Voice, key: String?)], graph: AppGraph, rate: Float = 1.0) async {
        for line in lines {
            if Task.isCancelled { break }
            await say(line.text, key: line.key, graph: graph, voice: line.voice, rate: rate)
        }
    }
}

/// A [ClipPlayer] that plays pre-rendered clips when the audio pack has them, and the system voice otherwise.
/// [keys] maps a sentence to its clip key; sentences without a key (the learner's own content) always use TTS.
@MainActor
final class PackClipPlayer: ClipPlayer {
    /// Set by the screen once its environment is available; without it every sentence uses the system voice.
    var graph: AppGraph?
    private let tts = TtsClipPlayer()
    private let voice = VoicePlayer()
    var keys: [String: String] = [:]
    var rate: Float = 1.0 {
        didSet { tts.rate = rate }
    }

    init(graph: AppGraph? = nil) {
        self.graph = graph
    }

    private func clipPath(_ sentence: String) -> String? {
        guard let graph else { return nil }
        return PackAudio.path(keys[sentence], graph: graph)
    }

    /// Clip keys for grammar example sentences: each sentence's position among its point's examples (D-092 `grammar/<point>/<ord>`).
    static func grammarKeys(_ lines: [(pointId: String, japanese: String)], graph: AppGraph) async -> [String: String] {
        guard let grammar = try? await graph.grammar() else { return [:] }
        var out: [String: String] = [:]
        var cache: [String: [String]] = [:]
        for line in lines where !line.pointId.isEmpty {
            if cache[line.pointId] == nil {
                cache[line.pointId] = ((try? await grammar.examples(pointId: line.pointId)) ?? []).map { $0.japanese }
            }
            if let ord = cache[line.pointId]?.firstIndex(of: line.japanese) {
                out[line.japanese] = PackAudio.grammarKey(pointId: line.pointId, example: ord)
            }
        }
        return out
    }

    func play(_ sentence: String) async {
        if let path = clipPath(sentence), await voice.play(file: path, rate: rate) { return }
        await tts.play(sentence)
    }

    func referenceKey(_ sentence: String) -> String {
        if clipPath(sentence) != nil, let key = keys[sentence] { return ReferenceClip.companion.pack(audioKey: key).key }
        return tts.referenceKey(sentence)
    }

    func stop() {
        voice.stop()
        tts.stop()
    }

    /// The clip decoded to 16 kHz mono for the shadowing comparison, else the rendered system voice.
    func referenceSamples(_ sentence: String) async -> [Float]? {
        if let path = clipPath(sentence), let samples = await Self.decode(path: path) { return samples }
        return await tts.referenceSamples(sentence)
    }

    private static func decode(path: String) async -> [Float]? {
        guard let decoder = try? await PcmDecoder.open(url: URL(fileURLWithPath: path)) else { return nil }
        let end = decoder.durationMs()
        guard end > 0 else { return nil }
        return await withCheckedContinuation { (c: CheckedContinuation<[Float]?, Never>) in
            decoder.read(startMs: 0, endMs: end) { floats, _ in
                guard let floats else { c.resume(returning: nil); return }
                var out = [Float](repeating: 0, count: Int(floats.size))
                for i in 0..<out.count { out[i] = floats.get(index: Int32(i)) }
                c.resume(returning: out)
            }
        }
    }
}
