import Shared
import SwiftUI

/// Loads the dictionary pack (installing the bundled copy on first launch) and shows its content,
/// or an honest empty state when this build ships without a pack.
struct DictionaryGate<Content: View>: View {
    @Environment(AppModel.self) private var app
    @ViewBuilder var content: (DictionaryRepository) -> Content

    @State private var state: LoadState = .loading

    enum LoadState {
        case loading
        case missing
        case ready(DictionaryRepository)
    }

    var body: some View {
        Group {
            switch state {
            case .loading:
                ProgressView("Preparing dictionary…")
            case .missing:
                ContentUnavailableView(
                    "Dictionary not installed",
                    systemImage: "character.book.closed",
                    description: Text("This build has no dictionary pack. Build it with `uv run packs/build_all.py` in tools/ and rebuild the app.")
                )
            case .ready(let repo):
                content(repo)
            }
        }
        .task {
            if case .ready = state { return }
            let repo = try? await app.graph.dictionary()
            state = repo.map { .ready($0) } ?? .missing
        }
    }
}

/// Loads one value from the dictionary and renders it.
struct DictionaryLoader<Value, Content: View>: View {
    let key: AnyHashable
    let load: (DictionaryRepository) async throws -> Value?
    @ViewBuilder var content: (Value) -> Content

    var body: some View {
        DictionaryGate { repo in
            LoadedView(key: key, load: { try await load(repo) }, content: content)
        }
    }
}

private struct LoadedView<Value, Content: View>: View {
    let key: AnyHashable
    let load: () async throws -> Value?
    @ViewBuilder var content: (Value) -> Content

    @State private var value: Value?
    @State private var loaded = false

    var body: some View {
        Group {
            if let value {
                content(value)
            } else if loaded {
                ContentUnavailableView("Not found", systemImage: "questionmark")
            } else {
                ProgressView()
            }
        }
        .task(id: key) {
            value = try? await load()
            loaded = true
        }
    }
}
