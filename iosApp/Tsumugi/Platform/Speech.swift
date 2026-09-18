import AVFoundation
import Observation

/// Japanese text-to-speech with the system voice (on-device). `speakingRange` is the character range being
/// spoken, offset into the document, so the reader can highlight it.
@MainActor
@Observable
final class Speech: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = Speech()

    private(set) var speakingRange: NSRange?
    private let synthesizer = AVSpeechSynthesizer()
    private var offset = 0

    override private init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Speaks [text]; [startOffset] is its position in a larger document (UTF-16 units).
    func speak(_ text: String, rate: Float = 1.0, startOffset: Int = 0) {
        synthesizer.stopSpeaking(at: .immediate)
        offset = startOffset
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "ja-JP")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * rate
        AudioSessionController.shared.beginPlayback(self) { [weak self] in self?.stop() }
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        speakingRange = nil
        AudioSessionController.shared.end(self)
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, willSpeakRangeOfSpeechString characterRange: NSRange, utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.speakingRange = NSRange(location: self.offset + characterRange.location, length: characterRange.length)
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.speakingRange = nil
            if !self.synthesizer.isSpeaking { AudioSessionController.shared.end(self) }
        }
    }
}
