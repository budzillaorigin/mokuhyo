import AVFoundation
import Foundation
import MediaPlayer
import Observation
import Shared

/// Awaitable English speech for drill cues (the Japanese `VoicePlayer` only picks ja-JP voices).
@MainActor
final class EnglishSpeaker: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private var waiter: CheckedContinuation<Void, Never>?
    private var current: ObjectIdentifier?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func say(_ text: String) async {
        stop()
        let utterance = AVSpeechUtterance(string: text)
        let language = Locale.preferredLanguages.first(where: { $0.hasPrefix("en") }) ?? "en-US"
        utterance.voice = AVSpeechSynthesisVoice(language: language) ?? AVSpeechSynthesisVoice(language: "en-US")
        current = ObjectIdentifier(utterance)
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            waiter = c
            synthesizer.speak(utterance)
        }
    }

    func stop() {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        current = nil
        let w = waiter
        waiter = nil
        w?.resume()
    }

    private func finish(_ id: ObjectIdentifier) {
        guard id == current else { return }
        current = nil
        let w = waiter
        waiter = nil
        w?.resume()
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.finish(id) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in self.finish(id) }
    }
}

/// The hands-free speaking-drill player (BRIEF_V2 §6.10, D-224; iOS D-267). The shared `DrillPlan`/`DrillCursor` say
/// what comes next and how long each pause is; this class only plays it: the English cue with the system voice, the
/// model answer from the audio pack (else the Japanese system voice), and silences in between.
///
/// Screen-off playback: `UIBackgroundModes: audio` plus an active `.playback` session through `AudioSessionController`.
/// iOS suspends an app whose session is active but silent, so a silent loop plays under the whole run (the pauses are
/// real silence to the ear, but the app keeps running). Lock-screen and headset controls go through
/// `MPRemoteCommandCenter` (play, pause, next item, previous item), and Now Playing shows the current cue.
@MainActor
@Observable
final class DrillPlayer {
    struct Item {
        let prompt: String
        let answer: String
        let audioKey: String
        let aiGenerated: Bool
    }

    private(set) var items: [Item] = []
    private(set) var title = ""
    private(set) var itemIndex = 0
    /// PROMPT | ANSWER_PAUSE | ANSWER | REPEAT_PAUSE | GAP, or "" before the first step.
    private(set) var stepCode = ""
    private(set) var stepEndsAt: Date?
    private(set) var running = false
    private(set) var finished = false

    @ObservationIgnored private var graph: AppGraph?
    @ObservationIgnored private var cursor: DrillCursor?
    @ObservationIgnored private var totalMs: Int64 = 0
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private let voice = VoicePlayer()
    @ObservationIgnored private let english = EnglishSpeaker()
    @ObservationIgnored private var keepAlive: AVAudioPlayer?
    @ObservationIgnored private var remoteTargets: [(MPRemoteCommand, Any)] = []

    /// Loads a plan and places the cursor at the first step; nothing plays until [play].
    func load(plan: DrillPlan, title: String, graph: AppGraph) {
        stop()
        self.graph = graph
        self.title = title
        items = SwiftSupport.shared.drillPlanItems(plan: plan).map {
            Item(prompt: $0.promptEn, answer: $0.answerJa, audioKey: $0.audioKey, aiGenerated: $0.isAiGenerated)
        }
        cursor = SwiftSupport.shared.drillCursor(plan: plan, start: 0)
        totalMs = plan.totalMs
        itemIndex = 0
        stepCode = ""
        finished = false
    }

    var currentItem: Item? { itemIndex < items.count ? items[itemIndex] : nil }

    func play() {
        guard let cursor, !running else { return }
        if cursor.finished {
            _ = cursor.previousItem() // at the end: replay the last item rather than nothing
        }
        running = true
        finished = false
        AudioSessionController.shared.beginPlayback(self) { [weak self] in self?.pause() }
        startKeepAlive()
        registerRemote()
        runLoop()
    }

    func pause() {
        guard running else { return }
        running = false
        task?.cancel()
        task = nil
        voice.stop()
        english.stop()
        stepEndsAt = nil
        keepAlive?.pause()
        updateNowPlaying()
    }

    func toggle() {
        if running { pause() } else { play() }
    }

    /// Next item's cue (headset "next").
    func skip() {
        guard let cursor else { return }
        let wasRunning = running
        interrupt()
        _ = cursor.skipItem()
        sync()
        if wasRunning { resume() }
    }

    /// Restart this item, or the previous one when already at its cue (headset "previous").
    func back() {
        guard let cursor else { return }
        let wasRunning = running
        interrupt()
        _ = cursor.previousItem()
        sync()
        if wasRunning { resume() }
    }

    /// Stops and releases audio, Now Playing and the remote commands.
    func stop() {
        running = false
        task?.cancel()
        task = nil
        voice.stop()
        english.stop()
        stepEndsAt = nil
        keepAlive?.stop()
        keepAlive = nil
        unregisterRemote()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        AudioSessionController.shared.end(self)
    }

    // MARK: loop

    private func interrupt() {
        task?.cancel()
        task = nil
        voice.stop()
        english.stop()
        stepEndsAt = nil
        running = false
    }

    private func resume() {
        running = true
        keepAlive?.play()
        runLoop()
    }

    private func sync() {
        guard let cursor else { return }
        itemIndex = min(Int(cursor.itemIndex), max(0, items.count - 1))
        stepCode = cursor.current.map { SwiftSupport.shared.drillStepCode(step: $0) } ?? ""
        updateNowPlaying()
    }

    private func runLoop() {
        task?.cancel()
        task = Task { [weak self] in
            await self?.loop()
        }
    }

    private func loop() async {
        while !Task.isCancelled, let cursor, let step = cursor.current {
            await perform(step)
            if Task.isCancelled { return }
            _ = cursor.advance()
        }
        if !Task.isCancelled, cursor?.finished == true { completed() }
    }

    private func perform(_ step: DrillStep) async {
        let code = SwiftSupport.shared.drillStepCode(step: step)
        stepCode = code
        itemIndex = min(Int(step.itemIndex), max(0, items.count - 1))
        updateNowPlaying()
        guard let item = currentItem else { return }
        switch code {
        case "PROMPT":
            await english.say(item.prompt)
        case "ANSWER":
            if let graph {
                await voice.say(item.answer, key: item.audioKey, graph: graph)
            } else {
                await voice.say(item.answer)
            }
        default:
            let ms = max(0, step.durationMs)
            stepEndsAt = Date().addingTimeInterval(Double(ms) / 1000)
            try? await Task.sleep(for: .milliseconds(Int(ms)))
            stepEndsAt = nil
        }
    }

    private func completed() {
        running = false
        finished = true
        stepEndsAt = nil
        keepAlive?.pause()
        updateNowPlaying()
    }

    // MARK: background audio

    private func startKeepAlive() {
        if keepAlive == nil {
            keepAlive = try? AVAudioPlayer(data: Self.silence, fileTypeHint: AVFileType.wav.rawValue)
            keepAlive?.numberOfLoops = -1
            keepAlive?.volume = 0
        }
        keepAlive?.play()
    }

    /// One second of 8 kHz 16-bit mono silence as a WAV file.
    private static let silence: Data = {
        let rate: UInt32 = 8000
        let bytes: UInt32 = rate * 2
        var data = Data()
        func append32(_ v: UInt32) {
            var x = v.littleEndian
            withUnsafeBytes(of: &x) { data.append(contentsOf: $0) }
        }
        func append16(_ v: UInt16) {
            var x = v.littleEndian
            withUnsafeBytes(of: &x) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: Array("RIFF".utf8))
        append32(36 + bytes)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8))
        append32(16)
        append16(1) // PCM
        append16(1) // mono
        append32(rate)
        append32(rate * 2)
        append16(2)
        append16(16)
        data.append(contentsOf: Array("data".utf8))
        append32(bytes)
        data.append(Data(count: Int(bytes)))
        return data
    }()

    private func registerRemote() {
        guard remoteTargets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()
        func add(_ command: MPRemoteCommand, _ action: @escaping @MainActor (DrillPlayer) -> Void) {
            command.isEnabled = true
            let token = command.addTarget { [weak self] _ in
                Task { @MainActor in
                    if let self { action(self) }
                }
                return .success
            }
            remoteTargets.append((command, token))
        }
        add(center.playCommand) { $0.play() }
        add(center.pauseCommand) { $0.pause() }
        add(center.togglePlayPauseCommand) { $0.toggle() }
        add(center.nextTrackCommand) { $0.skip() }
        add(center.previousTrackCommand) { $0.back() }
    }

    private func unregisterRemote() {
        for (command, token) in remoteTargets { command.removeTarget(token) }
        remoteTargets = []
    }

    private func updateNowPlaying() {
        guard let cursor else { return }
        let elapsed = max(0, totalMs - cursor.remainingMs)
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: currentItem?.prompt ?? title,
            MPMediaItemPropertyAlbumTitle: title,
            MPMediaItemPropertyArtist: "Tsumugi",
            MPMediaItemPropertyPlaybackDuration: Double(totalMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(elapsed) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: running ? 1.0 : 0.0,
        ]
        if !items.isEmpty {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = itemIndex
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = items.count
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
