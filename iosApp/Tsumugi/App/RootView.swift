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
    // Phase 10 (BRIEF_V2 G-01…G-16)
    case kanaCourse
    case personalCard
    case freeTalk
    case podcasts
    case podcast(String)
    case mediaFile(path: String, title: String, episodeId: String?)
    case leaderboard
    case export
    case integrations
    case contentReview
    // Phase 11 (BRIEF_V2 §6.1–§6.4, §6.11) and audio packs (§5.6)
    case audioPacks
    case decks
    case deck(String)
    case coreDeck(String)
    case lyrics
    case song(String)
    case immersionLog
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
            TabStack { ReviewsHomeView() }
                .tabItem { Label("Reviews", systemImage: "arrow.triangle.2.circlepath") }.tag(1)
            TabStack { LearnHomeView() }
                .tabItem { Label("Learn", systemImage: "book") }.tag(2)
            TabStack { PracticeHubView() }
                .tabItem { Label("Practice", systemImage: "mic") }.tag(3)
            TabStack { MeView() }
                .tabItem { Label("Me", systemImage: "person.crop.circle") }.tag(4)
        }
        // Text selected in Safari (or any app) and sent to "Look up in Tsumugi" opens the dictionary (G-09).
        .sheet(item: Binding(get: { app.sharedLookup }, set: { app.sharedLookup = $0 })) { lookup in
            TabStack {
                DictionarySearchView(initialQuery: lookup.text)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close") { app.sharedLookup = nil }
                        }
                    }
            }
            .environment(app)
        }
        // Text or a link shared with "Read in Tsumugi" opens straight in the reader.
        .sheet(item: Binding(get: { app.sharedDocument }, set: { app.sharedDocument = $0 })) { doc in
            TabStack {
                ReaderView(docId: doc.id)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close") { app.sharedDocument = nil }
                        }
                    }
            }
            .environment(app)
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
                    case .kanaCourse: KanaCourseView()
                    case .personalCard: PersonalCardsView()
                    case .freeTalk: FreeTalkView()
                    case .podcasts: PodcastsView()
                    case .podcast(let id): PodcastEpisodesView(podcastId: id)
                    case .mediaFile(let path, let title, let episodeId): MediaPlayerView(initialFile: path, initialTitle: title, episodeId: episodeId)
                    case .leaderboard: LeaderboardView()
                    case .export: ExportView()
                    case .integrations: IntegrationsView()
                    case .contentReview: ContentReviewView()
                    case .audioPacks: AudioPacksView()
                    case .decks: DecksHomeView()
                    case .deck(let id): DeckDetailView(deckId: id)
                    case .coreDeck(let id): CoreDeckView(deckId: id)
                    case .lyrics: LyricsListView()
                    case .song(let id): KaraokeView(songId: id)
                    case .immersionLog: ImmersionLogView()
                    }
                }
        }
    }
}

struct LearnHomeView: View {
    var body: some View {
        List {
            NavigationLink(value: Route.kanaCourse) {
                LabeledContent("Kana", value: "Hiragana and katakana from zero")
            }
            NavigationLink(value: Route.pathLevels) {
                LabeledContent("Kanji path", value: "60 levels")
            }
            NavigationLink(value: Route.grammar) {
                LabeledContent("Grammar", value: "JLPT N5–N1")
            }
            NavigationLink(value: Route.library) {
                LabeledContent("Reading", value: "Articles, books, feeds")
            }
            NavigationLink(value: Route.decks) {
                LabeledContent("Decks", value: "Core 2k–10k · your media")
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
            NavigationLink(value: Route.personalCard) {
                LabeledContent("Personal card", value: "Your picture and voice")
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
