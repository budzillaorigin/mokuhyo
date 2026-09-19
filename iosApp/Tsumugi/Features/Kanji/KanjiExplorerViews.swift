import Shared
import SwiftUI

// Kanji explorer (BRIEF_V2 §6.15, D-283, D-301): a capped one-hop graph around a kanji or a word, laid out by the
// shared deterministic force layout (`SwiftSupport.explorerGraph`) and drawn here; functional components, sound
// series, component search and "bookmark to SRS". Everything below is drawing and navigation only.

/// A graph node copied out of the Kotlin row, so the view keeps plain Swift values.
private struct ExplorerNode: Identifiable, Hashable {
    let id: String
    let label: String
    let kind: String
    let isFocus: Bool
    let bucket: Int
    let role: String
    let reading: String
    let gloss: String
    let entryId: Int64
    let x: Double
    let y: Double
}

private struct ExplorerEdge: Hashable {
    let from: String
    let to: String
    let kind: String
}

private struct ExplorerGraph {
    let focusId: String
    let nodes: [ExplorerNode]
    let edges: [ExplorerEdge]
    let hidden: Int

    init(_ rows: ExplorerGraphRows) {
        focusId = rows.focusId
        nodes = rows.nodes.map { n in
            ExplorerNode(
                id: n.id, label: n.label, kind: n.kindCode, isFocus: n.isFocus, bucket: Int(n.bucket), role: n.roleCode,
                reading: n.reading, gloss: n.gloss, entryId: n.entryId, x: n.x, y: n.y
            )
        }
        edges = rows.edges.map { ExplorerEdge(from: $0.from, to: $0.to, kind: $0.kindCode) }
        hidden = Int(rows.hidden)
    }
}

/// Colour of a JLPT / frequency bucket: 0 (N5, most frequent) … 4 (N1, rarest ranked), 5 = unknown.
func explorerBucketColor(_ bucket: Int) -> Color {
    switch bucket {
    case 0: .green
    case 1: .teal
    case 2: .blue
    case 3: .orange
    case 4: .red
    default: .gray
    }
}

/// Loads the kanji explorer (dictionary pack), or an honest state when the pack is missing or failed to open.
struct ExplorerGate<Content: View>: View {
    @Environment(AppModel.self) private var app
    @ViewBuilder var content: (KanjiExplorer) -> Content

    @State private var explorer: KanjiExplorer?
    @State private var loaded = false
    @State private var error: String?

    var body: some View {
        Group {
            if let explorer {
                content(explorer)
            } else if let error {
                ErrorRetryView(message: error) { Task { await open() } }.padding()
            } else if loaded {
                ContentUnavailableView(
                    "Dictionary not installed",
                    systemImage: "character.book.closed",
                    description: Text("The kanji explorer reads the dictionary pack. Build it with `uv run packs/build_all.py` in tools/ and rebuild the app.")
                )
            } else {
                ProgressView()
            }
        }
        .task {
            guard explorer == nil else { return }
            await open()
        }
    }

    private func open() async {
        error = nil
        do {
            explorer = try await app.graph.kanjiExplorer()
        } catch {
            self.error = String(localized: "Couldn't open the dictionary: \(error.localizedDescription)")
        }
        loaded = true
    }
}

struct KanjiExplorerView: View {
    let focusId: String

    var body: some View {
        ExplorerGate { explorer in
            ExplorerContent(explorer: explorer, start: focusId)
        }
        .navigationTitle("Kanji explorer")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ExplorerContent: View {
    @Environment(AppModel.self) private var app
    let explorer: KanjiExplorer

    /// Focus history: the last element is shown; Back pops it.
    @State private var stack: [String]
    @State private var typed = ""
    @State private var graph: ExplorerGraph?
    @State private var loading = false
    @State private var error: String?
    @State private var notFound = false
    @AppStorage("explorer.focusMode") private var focusMode = false
    @AppStorage("explorer.byFrequency") private var byFrequency = false
    @AppStorage("explorer.maxNodes") private var maxNodes = 30

    init(explorer: KanjiExplorer, start: String) {
        self.explorer = explorer
        _stack = State(initialValue: start.isEmpty ? [] : [start])
    }

    private var focus: String? { stack.last }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                picker
                if let focus {
                    controls
                    graphArea(focus)
                    detail(focus)
                } else {
                    starters
                }
            }
            .padding()
        }
        .toolbar {
            if stack.count > 1 {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        stack.removeLast()
                    } label: {
                        Label("Back", systemImage: "chevron.backward")
                    }
                }
            }
        }
        .task(id: "\(focus ?? "")|\(maxNodes)|\(byFrequency)") { await load() }
    }

    private var picker: some View {
        HStack {
            TextField("Type a kanji", text: $typed)
                .textFieldStyle(.roundedBorder)
                .font(.japanese(size: 18))
                .autocorrectionDisabled()
                .onSubmit { go() }
            Button("Explore") { go() }
                .buttonStyle(.borderedProminent)
                .disabled(Self.firstKanji(typed) == nil)
        }
    }

    private var starters: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Pick a kanji to see its parts, the kanji built on it, its sound family and the words written with it. Tap any node to move there.")
                .font(.subheadline).foregroundStyle(.secondary)
            FlowLayout(spacing: 8) {
                ForEach(["青", "語", "学", "持", "海", "話", "時", "明"], id: \.self) { k in
                    Button { stack.append("k:" + k) } label: {
                        Text(verbatim: k).font(.japanese(size: 24))
                    }
                    .buttonStyle(.bordered)
                }
            }
            NavigationLink(value: Route.soundSeries) {
                Label("Browse sound families", systemImage: "waveform")
            }
            NavigationLink(value: Route.componentSearch("")) {
                Label("Search by components", systemImage: "square.grid.3x3")
            }
        }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Colour by", selection: $byFrequency) {
                Text("JLPT").tag(false)
                Text("Frequency").tag(true)
            }
            .pickerStyle(.segmented)
            Toggle(isOn: $focusMode) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Focus")
                    Text("Only the direct links of the centre; around a kanji, words are hidden.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Stepper(value: $maxNodes, in: 15...50, step: 5) {
                Text("Up to \(String(maxNodes)) nodes")
            }
            legend
        }
    }

    private var legend: some View {
        HStack(spacing: 10) {
            ForEach(0..<6, id: \.self) { b in
                HStack(spacing: 3) {
                    Circle().fill(explorerBucketColor(b)).frame(width: 9, height: 9)
                    Text(verbatim: Self.legendLabel(b, byFrequency: byFrequency))
                }
            }
        }
        .font(.caption2)
        .accessibilityElement(children: .combine)
    }

    static func legendLabel(_ bucket: Int, byFrequency: Bool) -> String {
        if bucket == 5 { return String(localized: "unranked") }
        if byFrequency {
            return bucket == 0 ? String(localized: "most common") : bucket == 4 ? String(localized: "rarer") : "·"
        }
        return "N\(5 - bucket)"
    }

    @ViewBuilder
    private func graphArea(_ focus: String) -> some View {
        if let error {
            ErrorRetryView(message: error) { Task { await load() } }
        } else if notFound {
            Text("Nothing to show for this character in the dictionary.").foregroundStyle(.secondary)
        } else if let graph {
            let shown = visible(graph)
            ExplorerCanvas(nodes: shown.nodes, edges: shown.edges) { node in tap(node) }
                .frame(height: 360)
                .background(.quaternary.opacity(0.25), in: RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .topTrailing) {
                    if loading { ProgressView().padding(8) }
                }
            HStack {
                if graph.hidden > 0 {
                    Text("+\(String(graph.hidden)) more not shown").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text("Tap a node to move there.").font(.caption2).foregroundStyle(.tertiary)
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
        }
    }

    /// Focus mode: keep the edges that touch the focus; around a kanji also drop the word nodes.
    private func visible(_ graph: ExplorerGraph) -> (nodes: [ExplorerNode], edges: [ExplorerEdge]) {
        guard focusMode else { return (graph.nodes, graph.edges) }
        let hideWords = graph.focusId.hasPrefix("k:")
        let edges = graph.edges.filter { e in
            (e.from == graph.focusId || e.to == graph.focusId) && !(hideWords && e.kind == "WORD")
        }
        let keep = Set(edges.flatMap { [$0.from, $0.to] } + [graph.focusId])
        return (graph.nodes.filter { keep.contains($0.id) }, edges)
    }

    @ViewBuilder
    private func detail(_ focus: String) -> some View {
        if focus.hasPrefix("w:") {
            if let node = graph?.nodes.first(where: { $0.id == focus }) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .lastTextBaseline) {
                        Text(verbatim: node.label).font(.japanese(size: 30)).japaneseSpeech()
                        Text(verbatim: node.reading).font(.japanese(size: 15)).foregroundStyle(.secondary).japaneseSpeech()
                    }
                    if !node.gloss.isEmpty { Text(verbatim: node.gloss).font(.subheadline) }
                    if node.entryId > 0 {
                        NavigationLink(value: Route.entry(node.entryId)) {
                            Label("Open in the dictionary", systemImage: "character.book.closed")
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        } else {
            let literal = String(focus.dropFirst(2))
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 12) {
                    Text(verbatim: literal).font(.japanese(size: 40)).japaneseSpeech()
                    if let node = graph?.nodes.first(where: { $0.id == focus }) {
                        VStack(alignment: .leading) {
                            Text(verbatim: node.gloss).font(.subheadline.weight(.semibold))
                            Text(verbatim: node.reading).font(.japanese(size: 14)).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    NavigationLink(value: Route.kanji(literal)) {
                        Label("Details", systemImage: "info.circle")
                    }
                    .buttonStyle(.bordered)
                }
                KanjiPartsSummary(literal: literal, explorer: explorer) { next in stack.append("k:" + next) }
            }
            .id(literal)
        }
    }

    private func tap(_ node: ExplorerNode) {
        guard node.id != stack.last else { return }
        stack.append(node.id)
    }

    private func go() {
        guard let k = Self.firstKanji(typed) else { return }
        stack.append("k:" + k)
        typed = ""
    }

    /// The first CJK ideograph of [text], if any.
    static func firstKanji(_ text: String) -> String? {
        for ch in text {
            if let v = ch.unicodeScalars.first?.value, (0x4E00...0x9FFF).contains(v) || (0x3400...0x4DBF).contains(v) || (0xF900...0xFAFF).contains(v) {
                return String(ch)
            }
        }
        return nil
    }

    private func load() async {
        guard let focus else { return }
        loading = true
        error = nil
        let graphSupport = SwiftSupport.shared
        do {
            let rows = try await graphSupport.explorerGraph(explorer: explorer, focusId: focus, maxNodes: Int32(maxNodes), byFrequency: byFrequency)
            if let rows {
                graph = ExplorerGraph(rows)
                notFound = false
            } else {
                graph = nil
                notFound = true
            }
        } catch {
            self.error = String(localized: "Couldn't build the graph: \(error.localizedDescription)")
        }
        loading = false
    }
}

/// Edges and node shapes on a Canvas; labels are overlaid buttons at the same scaled positions (tap to re-center).
private struct ExplorerCanvas: View {
    let nodes: [ExplorerNode]
    let edges: [ExplorerEdge]
    let onTap: (ExplorerNode) -> Void

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let byId = Dictionary(nodes.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            ZStack {
                Canvas { ctx, canvasSize in
                    for e in edges {
                        guard let a = byId[e.from], let b = byId[e.to] else { continue }
                        var path = Path()
                        path.move(to: Self.point(a, canvasSize))
                        path.addLine(to: Self.point(b, canvasSize))
                        ctx.stroke(path, with: .color(Self.edgeColor(e.kind)), style: Self.edgeStyle(e.kind))
                    }
                    for n in nodes {
                        let p = Self.point(n, canvasSize)
                        let r = Self.radius(n)
                        let rect = CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)
                        let shape = n.kind == "WORD" ? Path(roundedRect: rect, cornerRadius: 6) : Path(ellipseIn: rect)
                        ctx.fill(shape, with: .color(explorerBucketColor(n.bucket).opacity(n.isFocus ? 0.45 : 0.22)))
                        ctx.stroke(shape, with: .color(explorerBucketColor(n.bucket)), lineWidth: n.isFocus ? 3 : 1.5)
                        if !n.role.isEmpty {
                            let dot = CGRect(x: p.x + r * 0.55, y: p.y - r * 1.05, width: 10, height: 10)
                            ctx.fill(Path(ellipseIn: dot), with: .color(Self.roleColor(n.role)))
                        }
                    }
                }
                ForEach(nodes) { n in
                    Button { onTap(n) } label: {
                        Text(verbatim: Self.shortLabel(n))
                            .font(.japanese(size: n.isFocus ? 22 : (n.kind == "WORD" ? 11 : 16)))
                            .foregroundStyle(.primary)
                            .frame(width: Self.radius(n) * 2 + (n.kind == "WORD" ? 18 : 4), height: Self.radius(n) * 2)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .position(Self.point(n, size))
                    .accessibilityLabel(Text(verbatim: n.label + (n.reading.isEmpty ? "" : " " + n.reading) + (n.gloss.isEmpty ? "" : ", " + n.gloss)))
                    .accessibilityHint(Text("Moves the graph to this node."))
                }
            }
        }
    }

    static func point(_ n: ExplorerNode, _ size: CGSize) -> CGPoint {
        CGPoint(x: n.x * size.width, y: n.y * size.height)
    }

    static func radius(_ n: ExplorerNode) -> CGFloat {
        n.isFocus ? 24 : (n.kind == "WORD" ? 15 : 17)
    }

    static func shortLabel(_ n: ExplorerNode) -> String {
        n.kind == "WORD" && n.label.count > 5 ? String(n.label.prefix(4)) + "…" : n.label
    }

    static func edgeColor(_ kind: String) -> Color {
        switch kind {
        case "SOUND": .orange
        case "PART": .primary.opacity(0.6)
        case "USED_IN": .secondary
        default: .gray.opacity(0.5)
        }
    }

    static func edgeStyle(_ kind: String) -> StrokeStyle {
        switch kind {
        case "SOUND": StrokeStyle(lineWidth: 2.5)
        case "USED_IN": StrokeStyle(lineWidth: 1.2, dash: [4, 3])
        case "PART": StrokeStyle(lineWidth: 1.8)
        default: StrokeStyle(lineWidth: 0.8)
        }
    }

    static func roleColor(_ role: String) -> Color {
        switch role {
        case "PHONETIC": .orange
        case "SEMANTIC": .blue
        default: .gray
        }
    }
}

/// A kanji's functional components (meaning / sound / shape), its sound family, "find kanji with these parts" and
/// "bookmark to SRS". Shared by the explorer and the kanji page. [onSelect] moves to another kanji when given.
struct KanjiPartsSummary: View {
    @Environment(AppModel.self) private var app
    let literal: String
    let explorer: KanjiExplorer
    var onSelect: ((String) -> Void)?

    @State private var parts: [ComponentRoleRow] = []
    @State private var series: SoundSeries?
    @State private var query = ""
    @State private var loaded = false
    @State private var error: String?
    @State private var bookmarked = false
    @State private var bookmarking = false
    @State private var bookmarkNote: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if !loaded {
                ProgressView()
            } else {
                if !parts.isEmpty {
                    SectionHeader("Parts and what they do")
                    ForEach(Array(parts.enumerated()), id: \.offset) { _, p in partRow(p) }
                }
                if let series {
                    seriesView(series)
                }
                HStack {
                    if !query.isEmpty {
                        NavigationLink(value: Route.componentSearch(query)) {
                            Label("Find kanji with these parts", systemImage: "square.grid.3x3")
                        }
                        .buttonStyle(.bordered)
                    }
                    let bookmarkTitle: LocalizedStringKey = bookmarked ? "In your reviews" : "Bookmark to SRS"
                    Button {
                        bookmark()
                    } label: {
                        Label(bookmarkTitle, systemImage: bookmarked ? "bookmark.fill" : "bookmark")
                    }
                    .buttonStyle(.bordered)
                    .disabled(bookmarked || bookmarking)
                }
                .font(.subheadline)
                if let bookmarkNote { Text(bookmarkNote).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .task(id: literal) { await load() }
    }

    private func partRow(_ p: ComponentRoleRow) -> some View {
        HStack(spacing: 10) {
            Button { select(p.component) } label: {
                Text(verbatim: p.component).font(.japanese(size: 26)).frame(minWidth: 40)
            }
            .buttonStyle(.bordered)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(Self.roleLabel(p.roleCode)).font(.subheadline.weight(.semibold)).foregroundStyle(Self.roleTint(p.roleCode))
                    if p.derived && !p.roleCode.isEmpty { DerivedBadge() }
                }
                if !p.reading.isEmpty {
                    Text("Reads \(p.reading) · \(Self.matchLabel(p.match))").font(.caption).japaneseSpeech()
                }
            }
            Spacer()
        }
    }

    private func seriesView(_ s: SoundSeries) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionHeader("Sound family")
                if s.derived { DerivedBadge() }
            }
            Text("Kanji built on \(s.phonetic) that read alike: \(s.readings.joined(separator: "・"))")
                .font(.caption).foregroundStyle(.secondary)
            FlowLayout(spacing: 8) {
                ForEach(Array(s.members.enumerated()), id: \.offset) { _, m in
                    Button { select(m.kanji) } label: {
                        VStack(spacing: 1) {
                            Text(verbatim: m.kanji).font(.japanese(size: 22))
                            Text(verbatim: m.onyomi.prefix(2).joined(separator: "・")).font(.japanese(size: 10)).foregroundStyle(.secondary)
                        }
                        .frame(minWidth: 44)
                    }
                    .buttonStyle(.bordered)
                    .tint(m.kanji == literal ? .accentColor : .secondary)
                    .accessibilityLabel(Text(verbatim: m.kanji + " " + m.onyomi.joined(separator: " ")))
                }
            }
        }
    }

    private func select(_ kanji: String) {
        if let onSelect { onSelect(kanji) }
    }

    static func roleLabel(_ code: String) -> LocalizedStringKey {
        switch code {
        case "SEMANTIC": "意符 · meaning part"
        case "PHONETIC": "音符 · sound part"
        case "FORM": "形 · shape only"
        default: "Part"
        }
    }

    static func roleTint(_ code: String) -> Color {
        switch code {
        case "SEMANTIC": .blue
        case "PHONETIC": .orange
        default: .secondary
        }
    }

    static func matchLabel(_ match: String) -> String {
        switch match {
        case "same": String(localized: "same reading")
        case "related": String(localized: "related reading")
        case "shared": String(localized: "shared by the family")
        default: match
        }
    }

    private func load() async {
        error = nil
        let graph = app.graph
        do {
            parts = try await SwiftSupport.shared.kanjiComponentRoles(explorer: explorer, literal: literal)
            series = try await explorer.seriesOf(literal: literal)
            query = try await explorer.componentQuery(literal: literal)
            bookmarked = (try? await SwiftSupport.shared.isKanjiBookmarked(graph: graph, literal: literal))?.boolValue ?? false
        } catch {
            self.error = String(localized: "Couldn't load the parts: \(error.localizedDescription)")
        }
        loaded = true
    }

    private func bookmark() {
        bookmarking = true
        let graph = app.graph
        let k = literal
        Task {
            do {
                let id = try await SwiftSupport.shared.bookmarkKanji(graph: graph, literal: k)
                bookmarked = id != nil
                bookmarkNote = id != nil ? String(localized: "Added to your reviews.") : String(localized: "This kanji isn't in the dictionary.")
            } catch {
                bookmarkNote = String(localized: "Couldn't add it: \(error.localizedDescription)")
            }
            bookmarking = false
        }
    }
}

/// "derived": a heuristic's guess not reviewed yet (D-281).
struct DerivedBadge: View {
    var body: some View {
        Text("derived")
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(Color.orange.opacity(0.15), in: Capsule())
            .foregroundStyle(.orange)
            .accessibilityLabel(Text("Derived automatically, not reviewed yet"))
    }
}

/// The kanji page's Phase 13 section: explore the graph, parts and sound family, bookmark.
struct KanjiExplorerSection: View {
    @Environment(AppModel.self) private var app
    let literal: String
    @State private var explorer: KanjiExplorer?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            NavigationLink(value: Route.kanjiExplorer("k:" + literal)) {
                Label("Explore graph", systemImage: "point.3.connected.trianglepath.dotted")
            }
            .buttonStyle(.borderedProminent)
            if let explorer {
                KanjiPartsSummary(literal: literal, explorer: explorer, onSelect: nil)
            }
        }
        .task { explorer = try? await app.graph.kanjiExplorer() }
    }
}

struct ComponentSearchView: View {
    let initialQuery: String

    var body: some View {
        ExplorerGate { explorer in
            ComponentSearchContent(explorer: explorer, query: initialQuery)
        }
        .navigationTitle("Search by components")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ComponentSearchContent: View {
    let explorer: KanjiExplorer
    @State var query: String
    @State private var result: ComponentSearchResult?
    @State private var error: String?
    @State private var attempt = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Parts, e.g. 氵 青 or 言五口", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .font(.japanese(size: 18))
                    .autocorrectionDisabled()
                Text("Every part you type must be in the kanji. Radicals work in their usual form (氵, 亻, 艹).")
                    .font(.caption).foregroundStyle(.secondary)
                if let error {
                    ErrorRetryView(message: error) { attempt += 1 }
                } else if let result {
                    if !result.unknown.isEmpty {
                        Text("No kanji uses: \(result.unknown.joined(separator: " "))").font(.caption).foregroundStyle(.orange)
                    }
                    if result.components.isEmpty {
                        EmptyView()
                    } else if result.kanji.isEmpty {
                        Text("No kanji has all of these parts.").foregroundStyle(.secondary)
                    } else {
                        Text("\(String(result.kanji.count)) kanji with \(result.components.joined(separator: " + "))")
                            .font(.subheadline.weight(.semibold))
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 8)], spacing: 8) {
                            ForEach(result.kanji, id: \.literal) { k in
                                NavigationLink(value: Route.kanji(k.literal)) {
                                    VStack(spacing: 2) {
                                        Text(verbatim: k.literal).font(.japanese(size: 28)).japaneseSpeech()
                                        Text(verbatim: k.keyword).font(.caption2).lineLimit(1)
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 64)
                                    .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding()
        }
        .task(id: "\(attempt)|\(query)") {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            do {
                result = try await explorer.componentSearch(input: query, limit: 120)
                error = nil
            } catch {
                if !Task.isCancelled { self.error = String(localized: "Search failed: \(error.localizedDescription)") }
            }
        }
    }
}

struct SoundSeriesListView: View {
    var body: some View {
        ExplorerGate { explorer in
            SoundSeriesList(explorer: explorer)
        }
        .navigationTitle("Sound families")
    }
}

private struct SoundSeriesList: View {
    let explorer: KanjiExplorer
    @State private var series: [SoundSeries]?
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let series {
                if series.isEmpty {
                    Text("This dictionary pack has no sound families. Rebuild it with `uv run packs/build_phonetics.py` in tools/.")
                        .foregroundStyle(.secondary)
                } else {
                    Section {
                        ForEach(series, id: \.phonetic) { s in
                            NavigationLink(value: Route.kanjiExplorer("k:" + s.phonetic)) {
                                HStack(alignment: .center, spacing: 12) {
                                    Text(verbatim: s.phonetic).font(.japanese(size: 30)).frame(minWidth: 44)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(verbatim: s.family.map(\.kanji).joined(separator: " ")).font(.japanese(size: 17)).lineLimit(1)
                                        Text(verbatim: s.readings.joined(separator: "・")).font(.japanese(size: 13)).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if s.derived { DerivedBadge() }
                                }
                            }
                        }
                    } footer: {
                        Text("Families are derived from KanjiVG component trees and KANJIDIC2 readings, and marked \"derived\" until reviewed.")
                    }
                }
            } else {
                ProgressView()
            }
        }
        .task { await load() }
    }

    private func load() async {
        error = nil
        do {
            series = try await explorer.allSeries()
        } catch {
            self.error = String(localized: "Couldn't load the sound families: \(error.localizedDescription)")
        }
    }
}
