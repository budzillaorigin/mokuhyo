import AVFoundation
import Foundation
import Shared

enum MediaDecodingError: LocalizedError {
    case noAudio
    case exportFailed(String)

    var errorDescription: String? {
        switch self {
        case .noAudio: String(localized: "This file has no audio track.")
        case .exportFailed(let reason): String(localized: "Couldn't cut the clip: \(reason)")
        }
    }
}

/// Decodes one window of a media file's audio to 16 kHz mono floats for Whisper subtitles (G-04, D-113). The shared
/// `SubtitleGenerator` asks for 30 s windows one at a time, so an hour-long episode is never in memory at once.
/// Each window is its own `AVAssetReader` over a time range, on a private queue (never the main actor, rule 15).
final class PcmDecoder: NSObject, PcmWindowReader, @unchecked Sendable {
    private let asset: AVURLAsset
    private let track: AVAssetTrack
    private let duration: Int64
    private let queue = DispatchQueue(label: "app.tsumugi.pcm", qos: .userInitiated)

    private init(asset: AVURLAsset, track: AVAssetTrack, durationMs: Int64) {
        self.asset = asset
        self.track = track
        self.duration = durationMs
    }

    static func open(url: URL) async throws -> PcmDecoder {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard let track = tracks.first else { throw MediaDecodingError.noAudio }
        let duration = try await asset.load(.duration)
        let ms = Int64(max(0, duration.seconds.isFinite ? duration.seconds : 0) * 1000)
        return PcmDecoder(asset: asset, track: track, durationMs: ms)
    }

    func durationMs() -> Int64 { duration }

    func read(startMs: Int64, endMs: Int64, onDone: @escaping (KotlinFloatArray?, String?) -> Void) {
        queue.async { [self] in
            do {
                let samples = try decode(startMs: startMs, endMs: endMs)
                onDone(kotlinFloats(samples), nil)
            } catch {
                onDone(nil, error.localizedDescription)
            }
        }
    }

    private func decode(startMs: Int64, endMs: Int64) throws -> [Float] {
        let reader = try AVAssetReader(asset: asset)
        reader.timeRange = CMTimeRange(
            start: CMTime(value: startMs, timescale: 1000),
            end: CMTime(value: max(startMs + 1, endMs), timescale: 1000)
        )
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: AudioCapture.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        // The audio-mix output downmixes any channel layout to mono while resampling.
        let output = AVAssetReaderAudioMixOutput(audioTracks: [track], audioSettings: settings)
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw MediaDecodingError.noAudio }
        reader.add(output)
        guard reader.startReading() else {
            throw reader.error ?? MediaDecodingError.noAudio
        }
        var samples: [Float] = []
        samples.reserveCapacity(Int(Double(endMs - startMs) / 1000 * AudioCapture.sampleRate))
        while let buffer = output.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            let count = length / MemoryLayout<Float>.size
            guard count > 0 else { continue }
            var chunk = [Float](repeating: 0, count: count)
            let status = chunk.withUnsafeMutableBytes { raw in
                CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: count * MemoryLayout<Float>.size, destination: raw.baseAddress!)
            }
            if status == kCMBlockBufferNoErr { samples.append(contentsOf: chunk) }
        }
        if reader.status == .failed { throw reader.error ?? MediaDecodingError.noAudio }
        return samples
    }
}

/// Cuts [startMs, endMs) of a media file's audio into an .m4a file ("Save clip to SRS", G-04).
enum ClipCutter {
    private final class Box: @unchecked Sendable {
        let session: AVAssetExportSession
        init(_ session: AVAssetExportSession) { self.session = session }
    }

    static func cut(source: URL, startMs: Int64, endMs: Int64, to path: String) async throws {
        let asset = AVURLAsset(url: source)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw MediaDecodingError.exportFailed(String(localized: "this file can't be exported"))
        }
        let out = URL(fileURLWithPath: path)
        try? FileManager.default.removeItem(at: out)
        session.outputURL = out
        session.outputFileType = .m4a
        session.timeRange = CMTimeRange(
            start: CMTime(value: max(0, startMs), timescale: 1000),
            end: CMTime(value: max(startMs + 1, endMs), timescale: 1000)
        )
        let box = Box(session)
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                box.session.exportAsynchronously {
                    switch box.session.status {
                    case .completed:
                        c.resume()
                    case .cancelled:
                        c.resume(throwing: CancellationError())
                    default:
                        c.resume(throwing: MediaDecodingError.exportFailed(box.session.error?.localizedDescription ?? "unknown error"))
                    }
                }
            }
        } onCancel: {
            box.session.cancelExport()
        }
    }
}
