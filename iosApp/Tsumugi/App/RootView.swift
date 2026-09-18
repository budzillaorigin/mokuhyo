import SwiftUI

/// Screens reachable by navigation. Each tab owns a NavigationStack of these.
enum Route: Hashable {
    case dictionary
    case entry(Int64)
    case kanji(String)
    case radicals
}

/// Tab bar from BRIEF.md §6: Today · Reviews · Learn · Practice · Me.
struct RootView: View {
    var body: some View {
        TabView {
            TabStack { TodayView() }
                .tabItem { Label("Today", systemImage: "sun.max") }
            TabStack { ComingSoonView(title: "Reviews") }
                .tabItem { Label("Reviews", systemImage: "arrow.triangle.2.circlepath") }
            TabStack { LearnHomeView() }
                .tabItem { Label("Learn", systemImage: "book") }
            TabStack { ComingSoonView(title: "Practice") }
                .tabItem { Label("Practice", systemImage: "mic") }
            TabStack { ComingSoonView(title: "Me") }
                .tabItem { Label("Me", systemImage: "person.crop.circle") }
        }
    }
}

/// A tab's navigation stack with the global search button (reachable from every tab).
struct TabStack<Root: View>: View {
    @ViewBuilder var root: () -> Root
    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            root()
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        NavigationLink(value: Route.dictionary) { Image(systemName: "magnifyingglass") }
                            .accessibilityLabel("Search dictionary")
                    }
                }
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .dictionary: DictionarySearchView()
                    case .entry(let id): EntryView(id: id)
                    case .kanji(let literal): KanjiView(literal: literal)
                    case .radicals: RadicalSearchView()
                    }
                }
        }
    }
}

struct LearnHomeView: View {
    var body: some View {
        List {
            NavigationLink(value: Route.dictionary) {
                LabeledContent("Dictionary", value: "JMdict · kanji · examples")
            }
            NavigationLink(value: Route.radicals) {
                LabeledContent("Radical search", value: "Find a kanji by its parts")
            }
        }
        .navigationTitle("Learn")
    }
}

struct ComingSoonView: View {
    let title: String

    var body: some View {
        ContentUnavailableView(title, systemImage: "hammer", description: Text("Coming soon"))
            .navigationTitle(title)
    }
}
