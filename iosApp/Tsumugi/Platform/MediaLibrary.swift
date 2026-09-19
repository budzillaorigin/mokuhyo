import AVFoundation
import CryptoKit
import Foundation
import Shared
import UIKit
import UniformTypeIdentifiers

/// How the sentence bank finds a media file again (D-191). The shared index stores this string as the item's
/// `locator`; only this file writes or reads it.
/// - `home:<path>`: a file inside the app's container (podcast downloads, lyrics audio), relative to the home
///   directory, because the container's absolute path can change between installs.
/// - `bookmark:<base64>`: a file picked in Files, as bookmark data, so it can be reopened later with its security scope.
enum MediaLocator {
    static func make(for url: URL) -> String? {
        let home = URL(fileURLWithPath: NSHomeDirectory()).standardizedFileURL.path
        let path = url.standardizedFileURL.path
        if path.hasPrefix(home + "/") {
            return "home:" + String(path.dropFirst(home.count + 1))
        }
        guard let data = try? url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil) else {
            return nil
        }
        return "bookmark:" + data.base64EncodedString()
    }

    /// The file for [locator], with its security scope opened when it needs one (call [close] when done).
    static func open(_ locator: String?) -> (url: URL, scoped: Bool)? {
        guard let locator else { return nil }
        if locator.hasPrefix("home:") {
            let url = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(String(locator.dropFirst(5)))
            return FileManager.default.fileExists(atPath: url.path) ? (url, false) : nil
        }
        if locator.hasPrefix("bookmark:"), let data = Data(base64Encoded: String(locator.dropFirst(9))) {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { return nil }
            let scoped = url.startAccessingSecurityScopedResource()
            return (url, scoped)
        }
        return nil
    }

    static func close(_ opened: (url: URL, scoped: Bool)) {
        if opened.scoped { opened.url.stopAccessingSecurityScopedResource() }
    }

    /// VIDEO when the file type is a movie, else AUDIO (the bank shows frames for video only).
    static func kind(of url: URL) -> MediaKind {
        if let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .movie) || type.conforms(to: .video) {
            return .video
        }
        return .audio
    }
}

/// Clips and frames cut on demand from the learner's own files for sentence-bank hits (D-160, D-191). Cached in
/// Caches (the system may purge them; they are recut when needed). Cutting and decoding run off the main actor.
enum ClipCache {
    struct MissingMedia: LocalizedError {
        var errorDescription: String? {
            String(localized: "The media file for this line isn't available any more. Open it in the media player again to re-link it.")
        }
    }

    private static var directory: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("sentence-clips", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private static func name(_ locator: String, _ a: Int64, _ b: Int64, _ ext: String) -> URL {
        let digest = SHA256.hash(data: Data("\(locator)|\(a)|\(b)".utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(digest).\(ext)")
    }

    /// The cut audio of [span] as a playable file path.
    static func audio(locator: String?, startMs: Int64, endMs: Int64) async throws -> String {
        guard let locator else { throw MissingMedia() }
        let out = name(locator, startMs, endMs, "m4a")
        if FileManager.default.fileExists(atPath: out.path) { return out.path }
        guard let opened = MediaLocator.open(locator) else { throw MissingMedia() }
        defer { MediaLocator.close(opened) }
        try await ClipCutter.cut(source: opened.url, startMs: startMs, endMs: endMs, to: out.path)
        return out.path
    }

    /// A frame at [ms] (video only), cached as JPEG.
    static func thumbnail(locator: String?, ms: Int64) async -> UIImage? {
        guard let locator else { return nil }
        let out = name(locator, ms, -1, "jpg")
        if let cached = UIImage(contentsOfFile: out.path) { return cached }
        guard let opened = MediaLocator.open(locator) else { return nil }
        defer { MediaLocator.close(opened) }
        guard let jpeg = await FrameGrabber.jpeg(url: opened.url, ms: ms) else { return nil }
        try? jpeg.write(to: out, options: .atomic)
        return UIImage(data: jpeg)
    }
}

/// One video frame as JPEG with AVAssetImageGenerator (BRIEF_V2 §6.2).
enum FrameGrabber {
    static func jpeg(url: URL, ms: Int64, maxSide: CGFloat = 480) async -> Data? {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxSide, height: maxSide)
        generator.requestedTimeToleranceBefore = CMTime(value: 200, timescale: 1000)
        generator.requestedTimeToleranceAfter = CMTime(value: 200, timescale: 1000)
        do {
            let result = try await generator.image(at: CMTime(value: max(0, ms), timescale: 1000))
            return UIImage(cgImage: result.image).jpegData(compressionQuality: 0.8)
        } catch {
            return nil
        }
    }
}

/// "Mine this line" (BRIEF_V2 §6.2, D-161): the shared miner makes the card and hands out the files; the clip audio
/// is cut with the Phase 10 path (`ClipCutter`) and the frame grabbed for video, then both are attached. Either may
/// fail: the card still works with the system voice and no picture.
enum LineMiner {
    struct Line {
        var kind: MineKind
        var mediaId: String
        var mediaTitle: String
        var mediaKind: MediaKind
        var startMs: Int64
        var endMs: Int64
        var sentence: String
        var wordStart: Int
        var wordEnd: Int
        var reading: String?
        var meanings: [String]
        var translation: String?
        var entryId: Int64?
    }

    /// Returns a note for the learner (what was attached).
    static func mine(_ line: Line, source: URL, graph: AppGraph) async throws -> String {
        let draft = try await graph.sentenceMiner.mineLine(
            kind: line.kind, mediaId: line.mediaId, mediaTitle: line.mediaTitle, mediaKind: line.mediaKind,
            startMs: line.startMs, endMs: line.endMs, sentence: line.sentence,
            wordStart: Int32(line.wordStart), wordEnd: Int32(line.wordEnd), reading: line.reading,
            meanings: line.meanings, translation: line.translation, entryId: line.entryId.map { KotlinLong(longLong: $0) }
        )
        var durationMs: Int64 = 0
        do {
            try await ClipCutter.cut(source: source, startMs: draft.startMs, endMs: draft.endMs, to: draft.audio.path)
            durationMs = max(1, draft.endMs - draft.startMs)
        } catch {
            durationMs = 0
        }
        var imageWritten = false
        if let image = draft.image, let at = draft.thumbnailMs?.int64Value, let jpeg = await FrameGrabber.jpeg(url: source, ms: at) {
            let path = image.path
            imageWritten = (try? await Task.detached(priority: .utility) { try jpeg.write(to: URL(fileURLWithPath: path), options: .atomic) }.value) != nil
        }
        _ = try await graph.sentenceMiner.attachMedia(draft: draft, audioDurationMs: durationMs, imageWritten: imageWritten)
        switch (durationMs > 0, imageWritten) {
        case (true, true): return String(localized: "Card added with the clip and a picture.")
        case (true, false): return String(localized: "Card added with the clip.")
        case (false, true): return String(localized: "Card added with a picture; the audio couldn't be cut, so reviews use the system voice.")
        default: return String(localized: "Card added. The audio couldn't be cut, so reviews use the system voice.")
        }
    }
}
