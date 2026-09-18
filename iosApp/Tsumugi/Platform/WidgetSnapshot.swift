import Foundation
import Shared
import WidgetKit

/// Writes the small JSON snapshot the widgets read from the App Group container, then reloads them.
/// Must match `Snapshot` in TsumugiWidget.swift. Without the App Group entitlement (e.g. unsigned CI builds)
/// the container is unavailable and this quietly does nothing.
enum WidgetSnapshot {
    private struct Payload: Codable {
        var dueReviews: Int
        var nextReviewAt: Date?
        var lessons: Int
        var streak: Int
        var kanji: String?
        var keyword: String?
        var reading: String?
        var updatedAt: Date
    }

    static func write(graph: AppGraph) async {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.tsumugi")?
            .appendingPathComponent("widget.json") else { return }
        let status = try? await graph.path()?.status()
        let streak = try? await graph.stats.streak()
        let reminder = try? await graph.reminders.next()
        var kanji: (String, String, String?)?
        if let path = try? await graph.path(), let status {
            let entries = (try? await path.level(level: status.currentLevel)) ?? []
            let kanjiItems = entries.filter { $0.item.kind == .kanji }
            if !kanjiItems.isEmpty {
                let day = Calendar.current.ordinality(of: .day, in: .era, for: .now) ?? 0
                let item = kanjiItems[day % kanjiItems.count].item
                kanji = (item.display, item.keyword, item.readings.first)
            }
        }
        let payload = Payload(
            dueReviews: Int(status?.dueReviews ?? 0),
            nextReviewAt: reminder.map { Date(timeIntervalSince1970: Double($0.at.toEpochMilliseconds()) / 1000) },
            lessons: Int(status?.availableLessons ?? 0),
            streak: Int(streak?.current ?? 0),
            kanji: kanji?.0, keyword: kanji?.1, reading: kanji?.2,
            updatedAt: .now
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(payload) else { return }
        try? data.write(to: url, options: .atomic)
        WidgetCenter.shared.reloadAllTimelines()
    }
}
