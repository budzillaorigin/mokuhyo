import Shared
import SwiftUI

struct PathLevelsView: View {
    @Environment(AppModel.self) private var app
    @State private var status: PathStatus?
    @State private var missing = false

    var body: some View {
        Group {
            if missing {
                ContentUnavailableView("Kanji path not installed", systemImage: "square.grid.3x3", description: Text("This build has no kanji-path pack."))
            } else if let status {
                List(1...Int(status.maxLevel), id: \.self) { level in
                    NavigationLink(value: Route.pathLevel(level)) {
                        VStack(alignment: .leading) {
                            Text("Level \(level)")
                            Text(caption(level, status)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Kanji path")
        .task {
            let path = try? await app.graph.path()
            missing = path == nil
            status = try? await path?.status()
        }
    }

    private func caption(_ level: Int, _ s: PathStatus) -> String {
        if level < Int(s.currentLevel) { return String(localized: "Passed") }
        if level == Int(s.currentLevel) {
            let percent = Int(s.levelProgress * 100)
            return String(localized: "Current · \(percent)% of kanji at Guru")
        }
        return String(localized: "Locked")
    }
}

struct PathLevelView: View {
    @Environment(AppModel.self) private var app
    let level: Int
    @State private var entries: [LevelEntry]?

    var body: some View {
        ScrollView {
            if let entries {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 6)], spacing: 6) {
                    ForEach([ItemKind.radical, .kanji, .vocab], id: \.self) { kind in
                        let group = entries.filter { $0.item.kind == kind }
                        if !group.isEmpty {
                            Section {
                                ForEach(group, id: \.item.id) { entry in
                                    NavigationLink(value: Route.pathItem(entry.item.id)) { cell(entry) }
                                        .buttonStyle(.plain)
                                }
                            } header: {
                                Text("\(kind.label) (\(group.filter { $0.stage != nil }.count)/\(group.count))")
                                    .font(.headline).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
                            }
                        }
                    }
                }
                .padding(8)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Level \(level)")
        .task { entries = try? await app.graph.path()?.level(level: Int32(level)) }
    }

    private func cell(_ entry: LevelEntry) -> some View {
        let started = entry.stage != nil
        return VStack(spacing: 2) {
            Text(entry.item.display).font(.japanese(size: 22)).lineLimit(1).minimumScaleFactor(0.5)
            Text(entry.stage?.label ?? "—").font(.caption2)
        }
        .foregroundStyle(started ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
        .frame(maxWidth: .infinity, minHeight: 56)
        .background(started ? AnyShapeStyle(entry.item.kind.color) : AnyShapeStyle(.quaternary.opacity(0.5)), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entry.item.display)
        .accessibilityValue(entry.stage?.label ?? String(localized: "Not started"))
    }
}

struct PathItemView: View {
    @Environment(AppModel.self) private var app
    let id: String
    @State private var detail: PathItemDetail?

    var body: some View {
        ScrollView {
            if let detail {
                VStack(alignment: .leading) {
                    PathItemContent(detail: detail) { story in
                        Task { try? await app.graph.path()?.saveMyStory(itemId: id, story: story) }
                    }
                    if detail.stage == nil { Text("Not started yet.").foregroundStyle(.secondary).padding(.top) }
                }
                .padding()
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Item")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) { detail = try? await app.graph.path()?.detail(id: id) }
    }
}
