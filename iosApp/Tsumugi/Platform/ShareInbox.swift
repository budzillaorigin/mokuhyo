import Foundation
import Shared

/// Items shared to "Read in Tsumugi" (the TsumugiShare extension) and text sent to "Look up in Tsumugi" (the
/// TsumugiAction extension, kind "lookup"). The extension can't open the database, so it
/// writes one small JSON file per shared item into the App Group container; the app imports them into the reader
/// when it launches or becomes active. File format: must match `InboxItem` in TsumugiShare/ShareViewController.swift.
/// Without the App Group entitlement (e.g. unsigned CI builds) the container is unavailable and this does nothing.
@MainActor
enum ShareInbox {
    static let appGroup = "group.app.tsumugi"
    static let folder = "share-inbox"
    /// A link that keeps failing (e.g. the page is gone) is dropped after this many tries.
    private static let maxAttempts = 3
    private static var running = false

    private struct Item: Codable {
        var kind: String
        var value: String
        var title: String?
        var createdAt: Date
        var attempts: Int?
    }

    struct Result {
        /// New reader document ids, oldest first.
        var documents: [String] = []
        /// Texts to look up in the dictionary, oldest first.
        var lookups: [String] = []
    }

    /// Imports every pending item: shared text and links go into the reader, lookups are returned for the dictionary.
    static func importPending(graph: AppGraph) async -> Result {
        guard !running,
              let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
                  .appendingPathComponent(folder, isDirectory: true)
        else { return Result() }
        running = true
        defer { running = false }

        // File names start with a millisecond timestamp, so name order is share order.
        let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard !files.isEmpty else { return Result() }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        var out = Result()
        for file in files {
            guard let data = try? Data(contentsOf: file), var item = try? decoder.decode(Item.self, from: data) else {
                try? FileManager.default.removeItem(at: file)
                continue
            }
            let value = item.value.trimmingCharacters(in: .whitespacesAndNewlines)
            if value.isEmpty {
                try? FileManager.default.removeItem(at: file)
                continue
            }
            if item.kind == "lookup" {
                // A lookup never touches the network: it opens the offline dictionary.
                out.lookups.append(String(value.prefix(200)))
                try? FileManager.default.removeItem(at: file)
                continue
            }
            do {
                let id: String
                if item.kind == "url" || isWebUrl(value) {
                    id = try await graph.reader.importUrl(url: value)
                } else {
                    id = try await graph.reader.importText(text: value, title: item.title)
                }
                out.documents.append(id)
                try? FileManager.default.removeItem(at: file)
            } catch {
                // Usually offline: keep the item for the next activation, up to maxAttempts.
                let attempts = (item.attempts ?? 0) + 1
                if attempts >= maxAttempts {
                    try? FileManager.default.removeItem(at: file)
                } else {
                    item.attempts = attempts
                    if let updated = try? encoder.encode(item) { try? updated.write(to: file, options: .atomic) }
                }
            }
        }
        return out
    }

    /// Shared plain text that is just one http(s) link is read as a web page.
    private static func isWebUrl(_ text: String) -> Bool {
        guard !text.contains(where: { $0.isWhitespace }), let url = URL(string: text), let scheme = url.scheme?.lowercased() else {
            return false
        }
        return (scheme == "http" || scheme == "https") && url.host != nil
    }
}

/// Text from the Action extension to look up (sheet item).
struct SharedLookup: Identifiable, Equatable {
    let text: String
    var id: String { text }
}

/// A reader document opened from the share inbox (sheet item).
struct SharedDocument: Identifiable, Equatable {
    let id: String
}
