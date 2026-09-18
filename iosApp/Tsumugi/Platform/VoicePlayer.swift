import AVFoundation
import Foundation
import Observation
import Shared

/// Awaitable Japanese TTS for practice screens. Two-voice dialogues pick a female and a male ja-JP system voice
/// when the device has both, otherwise one voice with pitch/rate variation so speakers still sound different.
/// A single "partner" voice can come from the learner's own VOICEVOX engine when configured (Settings → AI).
@MainActor
@Observable
final class VoicePlayer: NSObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    enum Voice {
        case female
        case male
        case any

        /// Voice hint strings used by the practice and exam packs ("female" | "male").
        init(hint: String?) {
            switch hint?.lowercased() {
            case "male": self = .male
            case "female": self = .female
            default: self = .any
            }
        }
    }

    private(set) var isSpeaking = false

    private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var audio: AVAudioPlayer?
    @ObservationIgnored private var waiter: CheckedContinuation<Void, Never>?
    @ObservationIgnored private var currentId: ObjectIdentifier?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Speaks [text] and returns when it has finished (or was stopped).
    func say(_ text: String, voice: Voice = .any, rate: Float = 1.0) async {
        stop()
        let utterance = AVSpeechUtterance(string: text)
        let pick = Self.pick(voice)
        utterance.voice = pick.voice
        utterance.pitchMultiplier = pick.pitch
        utterance.rate = min(AVSpeechUtteranceMaximumSpeechRate, max(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * rate * pick.rate))
        currentId = ObjectIdentifier(utterance)
        isSpeaking = true
        claimAudio()
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            waiter = c
            synthesizer.speak(utterance)
        }
    }

    /// The role-play partner / interviewer voice: VOICEVOX when configured and reachable, else the system voice.
    /// Each synthesis is its own file (F-30), deleted as soon as playback ends or is interrupted.
    func sayPartner(_ text: String, graph: AppGraph, rate: Float = 1.0) async {
        if let path = try? await SwiftSupport.shared.synthesizeToFile(graph: graph, text: text, speed: Double(rate)) {
            defer { SwiftSupport.shared.deleteSynthesized(graph: graph, path: path) }
            if let player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path)) {
                stop()
                player.delegate = self
                audio = player
                currentId = ObjectIdentifier(player)
                isSpeaking = true
                claimAudio()
                await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                    waiter = c
                    if !player.play() { finish(ObjectIdentifier(player)) }
                }
                return
            }
        }
        await say(text, voice: .any, rate: rate)
    }

    /// Speaks script lines in order with their voices.
    func sayLines(_ lines: [(text: String, voice: Voice)], rate: Float = 1.0) async {
        for line in lines {
            if Task.isCancelled { break }
            await say(line.text, voice: line.voice, rate: rate)
        }
    }

    func stop() {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        audio?.stop()
        audio = nil
        currentId = nil
        isSpeaking = false
        AudioSessionController.shared.end(self)
        let w = waiter
        waiter = nil
        w?.resume()
    }

    private func finish(_ id: ObjectIdentifier) {
        guard id == currentId else { return }
        currentId = nil
        audio = nil
        isSpeaking = false
        AudioSessionController.shared.end(self)
        let w = waiter
        waiter = nil
        w?.resume()
    }

    /// Playback category (audible with the silent switch on); an interruption or unplugged headphones stop us (F-14).
    private func claimAudio() {
        AudioSessionController.shared.beginPlayback(self) { [weak self] in self?.stop() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.finish(id) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.finish(id) }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let id = ObjectIdentifier(player)
        Task { @MainActor in self.finish(id) }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        let id = ObjectIdentifier(player)
        Task { @MainActor in self.finish(id) }
    }

    private struct Pick {
        var voice: AVSpeechSynthesisVoice?
        var pitch: Float
        var rate: Float
    }

    /// Best ja-JP voice of the wanted gender; without one, the default voice with a pitch/rate shift.
    private static func pick(_ wanted: Voice) -> Pick {
        let japanese = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language == "ja-JP" }
            .sorted { $0.quality.rawValue > $1.quality.rawValue }
        let fallback = japanese.first ?? AVSpeechSynthesisVoice(language: "ja-JP")
        switch wanted {
        case .any:
            return Pick(voice: fallback, pitch: 1.0, rate: 1.0)
        case .female:
            if let v = japanese.first(where: { $0.gender == .female }) { return Pick(voice: v, pitch: 1.0, rate: 1.0) }
            return Pick(voice: fallback, pitch: 1.2, rate: 1.03)
        case .male:
            if let v = japanese.first(where: { $0.gender == .male }) { return Pick(voice: v, pitch: 1.0, rate: 1.0) }
            return Pick(voice: fallback, pitch: 0.8, rate: 0.95)
        }
    }
}
