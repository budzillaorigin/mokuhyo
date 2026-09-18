import SwiftUI

/// Screens reachable by navigation. Each tab owns a NavigationStack of these.
enum Route: Hashable {
    case dictionary
    case entry(Int64)
    case kanji(String)
    case radicals
    case lessons
    case reviews
    case pathLevels
    case pathLevel(Int)
    case pathItem(String)
    case settings
    case licenses
    case importExport
}

/// Tab bar from BRIEF.md §6: Today · Reviews · Learn · Practice · Me.
struct RootView: View {
    var body: some View {
        TabView {
            TabStack { TodayView() }
                .tabItem { Label("Today", systemImage: "sun.max") }
            TabStack { TodayView() }
                .tabItem { Label("Reviews", systemImage: "arrow.triangle.2.circlepath") }
            TabStack { LearnHomeView() }
                .tabItem { Label("Learn", systemImage: "book") }
            TabStack { ComingSoonView(title: "Practice") }
                .tabItem { Label("Practice", systemImage: "mic") }
            TabStack { MeView() }
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
                    case .lessons: LessonView()
                    case .reviews: ReviewView()
                    case .pathLevels: PathLevelsView()
                    case .pathLevel(let level): PathLevelView(level: level)
                    case .pathItem(let id): PathItemView(id: id)
                    case .settings: SettingsView()
                    case .licenses: LicensesView()
                    case .importExport: ImportView()
                    }
                }
        }
    }
}

struct LearnHomeView: View {
    var body: some View {
        List {
            NavigationLink(value: Route.pathLevels) {
                LabeledContent("Kanji path", value: "60 levels")
            }
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
