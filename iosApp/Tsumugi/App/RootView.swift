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
    case wordLists
    case wordList(String)
    case grammar
    case grammarLevel(Int)
    case grammarPoint(String)
    case grammarLessons
    case lookup(String)
    case scan
    case library
    case read(String)
    case feeds
    case aozora
    case writingPractice([String])
    case handwriting
    case sync
    case aiSettings
    case scenarios
    case roleplay(String)
    case pronunciation(String)
    case dialogues
    case dialogue(String)
    case minimalPairs
    case media
    case pomodoro
    case opi
    case exams
    case attempt(String)
    case examBanks
}

/// Tab bar from BRIEF.md §6: Today · Reviews · Learn · Practice · Me.
struct RootView: View {
    @Environment(AppModel.self) private var app
    @State private var onboarded: Bool?
    @State private var tab = 0

    var body: some View {
        Group {
            if onboarded == false {
                OnboardingView { openImport in
                    onboarded = true
                    if openImport { tab = 4 }
                }
            } else {
                tabs
            }
        }
        .task { onboarded = (try? await app.graph.onboarding.isDone())?.boolValue ?? true }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            TabStack { TodayView() }
                .tabItem { Label("Today", systemImage: "sun.max") }.tag(0)
            TabStack { TodayView() }
                .tabItem { Label("Reviews", systemImage: "arrow.triangle.2.circlepath") }.tag(1)
            TabStack { LearnHomeView() }
                .tabItem { Label("Learn", systemImage: "book") }.tag(2)
            TabStack { PracticeHubView() }
                .tabItem { Label("Practice", systemImage: "mic") }.tag(3)
            TabStack { MeView() }
                .tabItem { Label("Me", systemImage: "person.crop.circle") }.tag(4)
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
                    case .wordLists: WordListsView()
                    case .wordList(let id): WordListView(listId: id)
                    case .grammar: GrammarLevelsView()
                    case .grammarLevel(let level): GrammarLevelView(level: level)
                    case .grammarPoint(let id): GrammarPointView(id: id)
                    case .grammarLessons: GrammarLessonView()
                    case .lookup(let text): DictionarySearchView(initialQuery: text)
                    case .scan: ScanView()
                    case .library: ReaderLibraryView()
                    case .read(let id): ReaderView(docId: id)
                    case .feeds: FeedsView()
                    case .aozora: AozoraView()
                    case .writingPractice(let kanji): WritingPracticeView(kanji: kanji)
                    case .handwriting: HandwritingSearchView()
                    case .sync: SyncView()
                    case .aiSettings: AiSettingsView()
                    case .scenarios: ScenarioListView()
                    case .roleplay(let id): RoleplayView(scenarioId: id)
                    case .pronunciation(let sentence): PronunciationPracticeView(initialSentence: sentence)
                    case .dialogues: DialogueListView()
                    case .dialogue(let id): DialoguePlayerView(dialogueId: id)
                    case .minimalPairs: MinimalPairsView()
                    case .media: MediaPlayerView()
                    case .pomodoro: PomodoroView()
                    case .opi: OpiView()
                    case .exams: ExamHubView()
                    case .attempt(let id): AttemptReviewView(attemptId: id)
                    case .examBanks: ExamBanksView()
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
            NavigationLink(value: Route.grammar) {
                LabeledContent("Grammar", value: "JLPT N5–N1")
            }
            NavigationLink(value: Route.library) {
                LabeledContent("Reading", value: "Articles, books, feeds")
            }
            NavigationLink(value: Route.dictionary) {
                LabeledContent("Dictionary", value: "JMdict · kanji · examples")
            }
            NavigationLink(value: Route.radicals) {
                LabeledContent("Radical search", value: "Find a kanji by its parts")
            }
            NavigationLink(value: Route.handwriting) {
                LabeledContent("Draw to search", value: "Handwrite a kanji")
            }
            NavigationLink(value: Route.scan) {
                LabeledContent("Scan text", value: "Camera or photo")
            }
            NavigationLink(value: Route.wordLists) {
                LabeledContent("Word lists", value: "Your lists")
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
