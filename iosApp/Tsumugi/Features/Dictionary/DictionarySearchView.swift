import Shared
import SwiftUI

struct DictionarySearchView: View {
    var initialQuery = ""

    var body: some View {
        DictionaryGate { repo in
            SearchResultsView(repo: repo, query: initialQuery)
        }
        .navigationTitle("Dictionary")
    }
}

/// Holds the shared instant search (BRIEF_V2 §6.15, D-288/D-302): the shared side debounces, cancels stale lookups
/// and publishes only the latest query's results; this only mirrors its state on the main actor.
@MainActor
@Observable
private final class InstantSearchModel {
    var state: InstantSearchState?
    private var search: InstantSearch?

    func open(graph: AppGraph) {
        guard search == nil else { return }
        search = graph.instantSearch { [weak self] next in
            // Called off the main thread by the shared search.
            Task { @MainActor in self?.state = next }
        }
    }

    func update(_ text: String) { search?.update(text: text) }

    func submit(_ text: String) { search?.submit(text: text) }

    func close() {
        search?.close()
        search = nil
    }
}

private struct SearchResultsView: View {
    @Environment(AppModel.self) private var app
    let repo: DictionaryRepository

    @State var query: String
    @State private var model = InstantSearchModel()
    /// The kanji's parts as a component query, when the query is a single kanji.
    @State private var partsQuery = ""

    private var trimmed: String { query.trimmingCharacters(in: .whitespaces) }
    private var results: SearchResults { model.state?.results ?? SearchResults.companion.EMPTY }
    private var searching: Bool { model.state?.searching ?? false }
    private var singleKanji: String? {
        guard trimmed.count == 1, let v = trimmed.unicodeScalars.first?.value else { return nil }
        return (0x4E00...0x9FFF).contains(v) || (0x3400...0x4DBF).contains(v) ? trimmed : nil
    }

    var body: some View {
        List {
            if searching {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Searching…").font(.caption).foregroundStyle(.secondary)
                }
            }
            if let kanji = singleKanji {
                Section {
                    NavigationLink(value: Route.kanjiExplorer("k:" + kanji)) {
                        Label("Explore \(kanji)", systemImage: "point.3.connected.trianglepath.dotted")
                    }
                    if !partsQuery.isEmpty {
                        NavigationLink(value: Route.componentSearch(partsQuery)) {
                            Label("Kanji with the parts of \(kanji)", systemImage: "square.grid.3x3")
                        }
                    }
                }
            }
            if results.mode == .sentence {
                Section("Words in this sentence") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(Array(results.tokens.enumerated()), id: \.offset) { _, token in
                                if let id = token.entryId {
                                    NavigationLink(value: Route.entry(id.int64Value)) {
                                        Text(token.surface).font(.japanese(size: 18)).japaneseSpeech()
                                    }
                                    .buttonStyle(.bordered)
                                } else {
                                    Text(token.surface).font(.japanese(size: 18)).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            ForEach(results.hits, id: \.entry.id) { hit in
                NavigationLink(value: Route.entry(hit.entry.id)) { SearchHitRow(hit: hit) }
            }
            if !trimmed.isEmpty && !searching && results.hits.isEmpty && model.state?.resultsFor == query {
                Text("No matches").foregroundStyle(.secondary)
            }
        }
        .listStyle(.plain)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "漢字, かな, romaji, English, or a sentence")
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .onSubmit(of: .search) { model.submit(query) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink("部首", value: Route.radicals)
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink("Parts", value: Route.componentSearch(""))
            }
        }
        .onAppear {
            model.open(graph: app.graph)
            if !query.isEmpty { model.submit(query) }
        }
        .onDisappear { model.close() }
        .onChange(of: query) { _, text in model.update(text) }
        .task(id: singleKanji ?? "") {
            partsQuery = ""
            guard let kanji = singleKanji, let explorer = try? await app.graph.kanjiExplorer() else { return }
            partsQuery = (try? await explorer.componentQuery(literal: kanji)) ?? ""
        }
    }
}

private struct SearchHitRow: View {
    let hit: SearchHit

    var body: some View {
        let inflection = SwiftSupport.shared.hitInflection(hit: hit)
        let chips = SwiftSupport.shared.hitChips(hit: hit)
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(hit.entry.headword).font(.japanese(size: 22, relativeTo: .title2))
                    if hit.entry.reading != hit.entry.headword {
                        Text(hit.entry.reading).font(.japanese(size: 14)).foregroundStyle(.secondary)
                    }
                }
                .japaneseSpeech()
                Text(hit.entry.glossPreview).font(.subheadline).lineLimit(2)
                if !inflection.isEmpty {
                    // "食べさせられなかった = 食べる + causative + passive + negative + past" (D-288).
                    Text(verbatim: inflection)
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.12), in: Capsule())
                        .foregroundStyle(.tint)
                        .accessibilityLabel(Text("Inflection: \(inflection)"))
                } else if hit.match == .deinflected {
                    Text("← " + hit.deinflection.joined(separator: " ← ")).font(.caption2).foregroundStyle(.tint)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                ForEach(chips, id: \.self) { chip in
                    TagView(chip == "common" ? String(localized: "common") : chip)
                }
            }
        }
    }
}
