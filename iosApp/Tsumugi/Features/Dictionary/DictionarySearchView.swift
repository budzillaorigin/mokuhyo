import Shared
import SwiftUI

struct DictionarySearchView: View {
    var body: some View {
        DictionaryGate { repo in
            SearchResultsView(repo: repo)
        }
        .navigationTitle("Dictionary")
    }
}

private struct SearchResultsView: View {
    let repo: DictionaryRepository

    @State private var query = ""
    @State private var results: SearchResults = SearchResults.companion.EMPTY

    var body: some View {
        List {
            if results.mode == .sentence {
                Section("Words in this sentence") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(Array(results.tokens.enumerated()), id: \.offset) { _, token in
                                if let id = token.entryId {
                                    NavigationLink(value: Route.entry(id.int64Value)) {
                                        Text(token.surface).font(.japanese(size: 18))
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
            if !query.trimmingCharacters(in: .whitespaces).isEmpty && results.hits.isEmpty && results.query == query.trimmingCharacters(in: .whitespaces) {
                Text("No matches").foregroundStyle(.secondary)
            }
        }
        .listStyle(.plain)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "漢字, かな, romaji, English, or a sentence")
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink("部首", value: Route.radicals)
            }
        }
        .task(id: query) {
            try? await Task.sleep(for: .milliseconds(120))
            guard !Task.isCancelled else { return }
            if let r = try? await repo.search(rawQuery: query, limit: 40) { results = r }
        }
    }
}

private struct SearchHitRow: View {
    let hit: SearchHit

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(hit.entry.headword).font(.japanese(size: 22, relativeTo: .title2))
                    if hit.entry.reading != hit.entry.headword {
                        Text(hit.entry.reading).font(.japanese(size: 14)).foregroundStyle(.secondary)
                    }
                }
                Text(hit.entry.glossPreview).font(.subheadline).lineLimit(2)
                if hit.match == .deinflected {
                    Text("← " + hit.deinflection.joined(separator: " ← ")).font(.caption2).foregroundStyle(.tint)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                if hit.entry.isCommon { TagView("common") }
                if let n = jlptLabel(hit.entry.jlpt) { TagView(n) }
            }
        }
    }
}
