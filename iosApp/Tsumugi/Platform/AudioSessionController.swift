import AVFoundation
import Foundation

/// The single owner of `AVAudioSession` (BRIEF_V2 F-14, DECISIONS D-074). Every player and the recorder go through it:
///
/// - Playback uses `.playback`, so speech and media are audible with the ring/silent switch on silent.
/// - Recording uses `.playAndRecord` with `.defaultToSpeaker`. While anyone records, playback shares that category.
/// - When the last client ends, the session is deactivated with `.notifyOthersOnDeactivation` (after a short grace
///   period, so consecutive lines of a dialogue don't toggle other apps' audio between lines).
/// - An interruption (a call, Siri, an alarm) pauses every client. Unplugging headphones pauses playback clients,
///   as iOS users expect; audio never jumps to the loudspeaker.
@MainActor
final class AudioSessionController {
    static let shared = AudioSessionController()

    private struct Client {
        weak var owner: AnyObject?
        let recording: Bool
        let pause: () -> Void
    }

    private var clients: [ObjectIdentifier: Client] = [:]
    private var configuredForRecording: Bool?
    private var active = false
    private var deactivation: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    private init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let began = raw.flatMap { AVAudioSession.InterruptionType(rawValue: $0) } == .began
            MainActor.assumeIsolated {
                AudioSessionController.shared.interrupted(began: began)
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { note in
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            let reason = raw.flatMap { AVAudioSession.RouteChangeReason(rawValue: $0) }
            MainActor.assumeIsolated {
                AudioSessionController.shared.routeChanged(reason: reason)
            }
        })
    }

    /// Call before starting playback. [onPause] is called on an interruption or when headphones are unplugged.
    func beginPlayback(_ owner: AnyObject, onPause: @escaping () -> Void) {
        clients[ObjectIdentifier(owner)] = Client(owner: owner, recording: false, pause: onPause)
        do {
            try apply()
        } catch {
            // Playback still works with the previous category; nothing to show the learner.
        }
    }

    /// Call before starting the microphone. Throws when the session can't be set up for recording.
    func beginRecording(_ owner: AnyObject, onPause: @escaping () -> Void) throws {
        clients[ObjectIdentifier(owner)] = Client(owner: owner, recording: true, pause: onPause)
        do {
            try apply()
        } catch {
            clients[ObjectIdentifier(owner)] = nil
            throw error
        }
    }

    /// Call when [owner] stopped playing or recording. The session is released once nobody uses it.
    func end(_ owner: AnyObject) {
        guard clients.removeValue(forKey: ObjectIdentifier(owner)) != nil else { return }
        prune()
        if clients.isEmpty {
            scheduleDeactivation()
        } else {
            try? apply()
        }
    }

    private func prune() {
        clients = clients.filter { $0.value.owner != nil }
    }

    private func apply() throws {
        prune()
        deactivation?.cancel()
        deactivation = nil
        let recording = clients.values.contains { $0.recording }
        let session = AVAudioSession.sharedInstance()
        if configuredForRecording != recording {
            if recording {
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            } else {
                try session.setCategory(.playback, mode: .default, options: [])
            }
            configuredForRecording = recording
        }
        if !active {
            try session.setActive(true)
            active = true
        }
    }

    private func scheduleDeactivation() {
        deactivation?.cancel()
        deactivation = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled else { return }
            self.prune()
            guard self.clients.isEmpty, self.active else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            self.active = false
        }
    }

    private func interrupted(began: Bool) {
        guard began else { return } // no auto-resume: the learner presses play again
        active = false // the system deactivated the session; the next begin… reactivates it
        prune()
        for client in Array(clients.values) { client.pause() }
    }

    private func routeChanged(reason: AVAudioSession.RouteChangeReason?) {
        guard reason == .oldDeviceUnavailable else { return }
        prune()
        for client in Array(clients.values) where !client.recording { client.pause() }
    }
}
