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
    // Phase 12 (BRIEF_V2 §6.4–§6.10, §6.16)
    case gradedReaders
    case gradedStory(String)
    case tracks
    case track(String)
    case trackDrills(trackId: String, type: String)
    case trackDrill(String)
    case courses
    case course(Int)
    case courseRemaining(Int)
    case onomatopoeia
    case onomatopoeiaTheme(String)
    case onomatopoeiaWord(Int64)
    /// A theme id, or "" for all themes.
    case onomatopoeiaQuiz(String)
    case drillSets
    case drillSet(String)
    // Phase 13 (BRIEF_V2 §6.7, §6.9, §6.12–§6.16)
    case pitchTest
    /// A `PitchDrill` code, or "" for the adaptive mix.
    case pitchSession(String)
    case pitchStats
    /// `k:<kanji>`, `w:<entry id>`, or "" to pick a kanji first.
    case kanjiExplorer(String)
    /// Typed components ("氵 青"), or "".
    case componentSearch(String)
    case soundSeries
    case games
    case reflex
    case atom
    case translation
    case translationPassage(String)
    case translationImported(text: String, title: String)
    case translationHistory
    case thesaurus
    case thesaurusCluster(String)
    case writingStudio
    case writingDraft(String)
    case poetry
    /// A theme id, or "" for every poem.
    case poemTheme(String)
    case poem(String)
    case readingCircle
    /// A circle text id (`doc:<id>` for a library document); starts or resumes its session.
    case circleSession(String)
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
                    case .gradedReaders: GradedLibraryView()
                    case .gradedStory(let id): GradedStoryView(storyId: id)
                    case .tracks: TracksView()
                    case .track(let id): TrackPageView(trackId: id)
                    case .trackDrills(let trackId, let type): TrackDrillsView(trackId: trackId, type: type)
                    case .trackDrill(let id): TrackDrillView(drillId: id)
                    case .courses: CoursesView()
                    case .course(let level): CourseLevelView(level: level)
                    case .courseRemaining(let level): CourseRemainingView(level: level)
                    case .onomatopoeia: OnomatopoeiaHomeView()
                    case .onomatopoeiaTheme(let id): OnomatopoeiaThemeView(themeId: id)
                    case .onomatopoeiaWord(let id): OnomatopoeiaDetailView(entryId: id)
                    case .onomatopoeiaQuiz(let theme): OnomatopoeiaQuizView(themeId: theme)
                    case .drillSets: DrillSetsView()
                    case .drillSet(let id): DrillSetPlayerView(setId: id)
                    case .pitchTest: PitchTestHomeView()
                    case .pitchSession(let drill): PitchSessionView(drillCode: drill)
                    case .pitchStats: PitchStatsView()
                    case .kanjiExplorer(let focus): KanjiExplorerView(focusId: focus)
                    case .componentSearch(let query): ComponentSearchView(initialQuery: query)
                    case .soundSeries: SoundSeriesListView()
                    case .games: GamesHomeView()
                    case .reflex: ReflexGameView()
                    case .atom: AtomGameView()
                    case .translation: TranslationHomeView()
                    case .translationPassage(let id): TranslationPassageView(passageId: id)
                    case .translationImported(let text, let title): TranslationPassageView(importedText: text, title: title)
                    case .translationHistory: TranslationHistoryView()
                    case .thesaurus: ThesaurusHomeView()
                    case .thesaurusCluster(let id): ThesaurusClusterView(clusterId: id)
                    case .writingStudio: WritingStudioView()
                    case .writingDraft(let id): WritingDraftView(draftId: id)
                    case .poetry: PoetryHomeView()
                    case .poemTheme(let theme): PoemListView(themeId: theme)
                    case .poem(let id): PoemView(poemId: id)
                    case .readingCircle: ReadingCircleHomeView()
                    case .circleSession(let textId): CircleSessionView(textId: textId)
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
            NavigationLink(value: Route.courses) {
                LabeledContent("JLPT courses", value: "Modules, mastery, what's left")
            }
            NavigationLink(value: Route.grammar) {
                LabeledContent("Grammar", value: "JLPT N5–N1")
            }
            NavigationLink(value: Route.gradedReaders) {
                LabeledContent("Graded readers", value: "Stories by level, with audio")
            }
            NavigationLink(value: Route.library) {
                LabeledContent("Reading", value: "Articles, books, feeds")
            }
            NavigationLink(value: Route.tracks) {
                LabeledContent("Tracks", value: "Gaming, business, family…")
            }
            NavigationLink(value: Route.onomatopoeia) {
                LabeledContent("Onomatopoeia", value: "擬音語・擬態語・擬情語")
            }
            NavigationLink(value: Route.decks) {
                LabeledContent("Decks", value: "Core 2k–10k · your media")
            }
            NavigationLink(value: Route.dictionary) {
                LabeledContent("Dictionary", value: "JMdict · kanji · examples")
            }
            NavigationLink(value: Route.kanjiExplorer("")) {
                LabeledContent("Kanji explorer", value: "Parts, sound families, words")
            }
            NavigationLink(value: Route.radicals) {
                LabeledContent("Radical search", value: "Find a kanji by its parts")
            }
            NavigationLink(value: Route.componentSearch("")) {
                LabeledContent("Search by components", value: "氵 + 青 → 清")
            }
            Section("Language arts") {
                NavigationLink(value: Route.thesaurus) {
                    LabeledContent("Expression thesaurus", value: "Feelings, scenes, collocations")
                }
                NavigationLink(value: Route.writingStudio) {
                    LabeledContent("Writing studio", value: "Drafts, corrections, register")
                }
                NavigationLink(value: Route.translation) {
                    LabeledContent("Translation workbench", value: "J→E and E→J passages")
                }
                NavigationLink(value: Route.poetry) {
                    LabeledContent("Poetry corner", value: "Modern poems, public domain")
                }
                NavigationLink(value: Route.readingCircle) {
                    LabeledContent("Reading circle", value: "Read aloud, explain in English")
                }
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
