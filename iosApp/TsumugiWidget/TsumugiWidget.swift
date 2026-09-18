import SwiftUI
import WidgetKit

/// Snapshot the app writes to the shared App Group container (see WidgetSnapshot.swift in the app).
/// The widget is pure Swift so the extension stays small; it never opens the database.
struct Snapshot: Codable {
    var dueReviews: Int
    var nextReviewAt: Date?
    var lessons: Int
    var streak: Int
    var kanji: String?
    var keyword: String?
    var reading: String?
    var updatedAt: Date

    static let appGroup = "group.app.tsumugi"
    static let fileName = "widget.json"

    static func load() -> Snapshot? {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent(fileName),
              let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Snapshot.self, from: data)
    }

    static let placeholder = Snapshot(dueReviews: 12, nextReviewAt: nil, lessons: 5, streak: 7, kanji: "語", keyword: "word", reading: "ご", updatedAt: .now)
}

struct Entry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> Entry { Entry(date: .now, snapshot: .placeholder) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(date: .now, snapshot: context.isPreview ? .placeholder : Snapshot.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let snapshot = Snapshot.load()
        // Refresh when the next reviews come due (the app also reloads timelines whenever it backgrounds).
        let next = snapshot?.nextReviewAt.flatMap { $0 > .now ? $0 : nil } ?? Date.now.addingTimeInterval(3600)
        completion(Timeline(entries: [Entry(date: .now, snapshot: snapshot)], policy: .after(next)))
    }
}

struct ReviewsWidgetView: View {
    let entry: Entry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let s = entry.snapshot {
                Text("Reviews").font(.caption).foregroundStyle(.secondary)
                Text("\(s.dueReviews)").font(.system(size: 40, weight: .bold, design: .rounded))
                Text("\(s.lessons) lessons · 🔥 \(s.streak)").font(.caption2)
            } else {
                Text("Open Tsumugi to get started").font(.caption)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct KanjiWidgetView: View {
    let entry: Entry

    var body: some View {
        VStack(spacing: 2) {
            if let s = entry.snapshot, let kanji = s.kanji {
                Text(kanji).font(.custom("HiraginoSans-W6", size: 52))
                if let keyword = s.keyword { Text(keyword).font(.caption.weight(.semibold)) }
                if let reading = s.reading { Text(reading).font(.custom("HiraginoSans-W3", size: 12)).foregroundStyle(.secondary) }
            } else {
                Text("漢字").font(.custom("HiraginoSans-W6", size: 40))
                Text("Kanji of the day").font(.caption2)
            }
        }
    }
}

struct ReviewsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "reviews", provider: Provider()) { entry in
            ReviewsWidgetView(entry: entry).containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Reviews due")
        .description("Reviews waiting, lessons available and your streak.")
        .supportedFamilies([.systemSmall, .accessoryRectangular])
    }
}

struct KanjiWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "kanji", provider: Provider()) { entry in
            KanjiWidgetView(entry: entry).containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Kanji of the day")
        .description("One kanji from your current level.")
        .supportedFamilies([.systemSmall])
    }
}

@main
struct TsumugiWidgets: WidgetBundle {
    var body: some Widget {
        ReviewsWidget()
        KanjiWidget()
    }
}
