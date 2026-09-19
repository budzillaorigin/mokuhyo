import Shared
import SwiftUI

// Graded readers (BRIEF_V2 §6.4, D-200…D-209; iOS UI D-260/D-261): a library by level with genre chips and a
// difficulty badge, then a story page with read-along audio, the vocabulary list, the quiz and the genre tasks.

/// Localized names for the graded-reader genres (tools/packs/readers/tasks.json).
enum ReaderGenre {
    static let all = ["story", "news", "manga", "essay", "email", "notice", "editorial", "academic", "ad", "recipe"]

    static func name(_ genre: String) -> String {
        switch genre {
        case "story": String(localized: "Story")
        case "news": String(localized: "News")
        case "manga": String(localized: "Manga")
        case "essay": String(localized: "Essay")
        case "email": String(localized: "Email")
        case "notice": String(localized: "Notice")
        case "editorial": String(localized: "Editorial")
        case "academic": String(localized: "Academic")
        case "ad": String(localized: "Advert")
        case "recipe": String(localized: "Recipe")
        default: genre
        }
    }

    /// "Level 0" for the N6 level, else the JLPT label.
    static func levelName(_ label: String) -> String {
        label == "Level 0" ? String(localized: "Level 0") : label
    }
}

/// A graded story's §6.4 difficulty ("N4 · ILR 1 · 23") as a small capsule, coloured like `DifficultyBadge`.
struct StoryDifficultyBadge: View {
    let label: String
    let score: Int

    var body: some View {
        Text(verbatim: label.isEmpty ? "\(score)" : "\(label) · \(score)")
            .font(.caption2.weight(.semibold).monospacedDigit())
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
            .accessibilityLabel(Text("Difficulty \(label), score \(String(score)) of 100"))
    }

    private var color: Color {
        switch score {
        case ..<25: .green
        case ..<45: .teal
        case ..<60: .orange
        default: .red
        }
    }
}

// MARK: - Library

private struct StoryRow: Identifiable {
    let id: String
    let title: String
    let titleEn: String
    let genre: String
    let label: String
    let score: Int
    let chars: Int
    let aiGenerated: Bool
}

private struct LevelRow: Identifiable {
    let jlpt: Int
    let label: String
    let count: Int
    var id: Int { jlpt }
}

struct GradedLibraryView: View {
    @Environment(AppModel.self) private var app
    @AppStorage("readers.level") private var level = 5
    @State private var levels: [LevelRow]?
    @State private var stories: [StoryRow] = []
    @State private var genre = ""
    @State private var results: [String: String] = [:]
    @State private var error: String?
    @State private var loadingStories = false

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) {
                    Task {
                        self.error = nil
                        await load()
                        await loadStories()
                    }
                }
            } else if let levels {
                if levels.isEmpty {
                    ContentUnavailableView(
                        "Graded readers not installed",
                        systemImage: "books.vertical",
                        description: Text("This build has no graded-reader pack. Build it with `uv run python packs/readers/build_readers.py` in tools/ and rebuild the app.")
                    )
                } else {
                    Section {
                        Picker("Level", selection: $level) {
                            ForEach(levels) { l in Text(verbatim: ReaderGenre.levelName(l.label)).tag(l.jlpt) }
                        }
                        .pickerStyle(.segmented)
                        genreChips
                    } footer: {
                        Text("Stories are written for each level: at least 95% of their words are at the level or easier. They were drafted by AI and are being reviewed.")
                    }
                    Section {
                        if loadingStories {
                            ProgressView()
                        } else if filtered.isEmpty {
                            Text("No stories of this kind at this level yet.").foregroundStyle(.secondary)
                        }
                        ForEach(filtered) { story in
                            NavigationLink(value: Route.gradedStory(story.id)) { row(story) }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Graded readers")
        .task { await load() }
        .task(id: level) { await loadStories() }
    }

    private var filtered: [StoryRow] { genre.isEmpty ? stories : stories.filter { $0.genre == genre } }

    private var genreChips: some View {
        let present = ReaderGenre.all.filter { g in stories.contains { $0.genre == g } }
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip(title: String(localized: "All genres"), value: "")
                ForEach(present, id: \.self) { g in chip(title: ReaderGenre.name(g), value: g) }
            }
        }
    }

    private func chip(title: String, value: String) -> some View {
        Button { genre = value } label: { Text(verbatim: title).font(.caption) }
            .buttonStyle(.bordered)
            .tint(genre == value ? .accentColor : .secondary)
            .accessibilityAddTraits(genre == value ? .isSelected : [])
    }

    private func row(_ story: StoryRow) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(story.title).font(.japanese(size: 18)).japaneseSpeech()
            if !story.titleEn.isEmpty { Text(story.titleEn).font(.caption).foregroundStyle(.secondary) }
            HStack(spacing: 6) {
                TagView(ReaderGenre.name(story.genre))
                StoryDifficultyBadge(label: story.label, score: story.score)
                Text("\(String(story.chars)) characters").font(.caption2).foregroundStyle(.secondary)
                if story.aiGenerated { AiBadge() }
            }
            if let result = results[story.id] {
                Label(result, systemImage: "checkmark.seal").font(.caption2).foregroundStyle(.green)
            }
        }
    }

    private func load() async {
        do {
            let found = try await app.graph.reader.graded.levels()
            levels = found.map { LevelRow(jlpt: Int($0.jlpt), label: $0.label, count: Int($0.storyCount)) }
            if let first = levels?.first, !(levels ?? []).contains(where: { $0.jlpt == level }) { level = first.jlpt }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't open the graded readers: \(error.localizedDescription)")
        }
        let latest = (try? await app.graph.reader.graded.scores.latestByStory(limit: 500)) ?? []
        var map: [String: String] = [:]
        for r in latest { map[r.storyId] = String(localized: "Quiz: \(String(r.correct)) of \(String(r.total))") }
        results = map
    }

    private func loadStories() async {
        loadingStories = true
        defer { loadingStories = false }
        do {
            let found = try await SwiftSupport.shared.gradedStories(graph: app.graph, jlpt: Int32(level))
            stories = found.map { s in
                StoryRow(
                    id: s.id, title: s.title, titleEn: s.titleEn ?? "", genre: s.genre ?? "", label: s.label ?? "",
                    score: s.textScore.map { Int($0.intValue) } ?? 0, chars: Int(s.length), aiGenerated: s.source != "verified"
                )
            }
            if !genre.isEmpty && !stories.contains(where: { $0.genre == genre }) { genre = "" }
        } catch {
            self.error = String(localized: "Couldn't load the stories: \(error.localizedDescription)")
        }
    }
}

// MARK: - Story

/// Swift copy of a graded story (only strings and numbers), plus the Kotlin object for quiz and summary calls.
private struct StoryData {
    struct Line: Identifiable {
        let id: Int
        let text: String
        let voice: String
        let clipKey: String
        /// A new paragraph starts before this sentence.
        let paragraph: Bool
    }

    struct Vocab: Identifiable {
        let id: Int
        let entryId: Int64
        let word: String
        let reading: String
        let gloss: String
    }

    struct Question: Identifiable {
        let id: Int
        let stem: String
        let choices: [String]
        let answer: Int
        let explanation: String
        let japanese: Bool
    }

    struct TaskPrompt {
        let prompt: String
        let english: String
        let japanese: Bool
        let seconds: Int
        let find: [String]
        let minChars: Int
        let maxChars: Int
    }

    let title: String
    let titleEn: String
    let levelLabel: String
    let genre: String
    let label: String
    let score: Int
    let coverage: Double
    let aiGenerated: Bool
    let lines: [Line]
    let timed: Bool
    let vocabulary: [Vocab]
    let questions: [Question]
    let prediction: TaskPrompt?
    let skim: TaskPrompt?
    let close: [TaskPrompt]
    let output: TaskPrompt?
}

private enum StoryTab: String, CaseIterable, Identifiable {
    case read = "Read"
    case words = "Words"
    case quiz = "Quiz"
    case tasks = "Tasks"
    var id: String { rawValue }
}

struct GradedStoryView: View {
    let storyId: String
    @Environment(AppModel.self) private var app
    @State private var story: GradedStory?
    @State private var data: StoryData?
    @State private var error: String?
    @State private var loaded = false
    @State private var tab = StoryTab.read

    var body: some View {
        Group {
            if let data, let story {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        header(data)
                        Picker("Section", selection: $tab) {
                            ForEach(StoryTab.allCases) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        switch tab {
                        case .read: ReadAlongPanel(storyId: storyId, data: data)
                        case .words: vocabulary(data)
                        case .quiz: StoryQuizPanel(story: story, questions: data.questions)
                        case .tasks: StoryTasksPanel(story: story, data: data) { tab = .quiz }
                        }
                    }
                    .padding()
                }
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding()
            } else if loaded {
                ContentUnavailableView("Story not found", systemImage: "book.closed")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(data?.title ?? String(localized: "Story"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func header(_ d: StoryData) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(d.title).font(.japanese(size: 26, weight: .semibold, relativeTo: .title)).japaneseSpeech()
                .accessibilityAddTraits(.isHeader)
            if !d.titleEn.isEmpty { Text(d.titleEn).font(.subheadline).foregroundStyle(.secondary) }
            HStack(spacing: 6) {
                TagView(ReaderGenre.levelName(d.levelLabel))
                TagView(ReaderGenre.name(d.genre))
                StoryDifficultyBadge(label: d.label, score: d.score)
                if d.aiGenerated { AiBadge() }
            }
            Text("\(String(Int((d.coverage * 100).rounded())))% of the words are at this level or glossed below.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func vocabulary(_ d: StoryData) -> some View {
        if d.vocabulary.isEmpty {
            Text("This story has no glossed words.").foregroundStyle(.secondary)
        } else {
            Text("Words above the level, glossed for this story.").font(.caption).foregroundStyle(.secondary)
            ForEach(d.vocabulary) { v in
                NavigationLink(value: Route.entry(v.entryId)) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(v.word).font(.japanese(size: 18)).japaneseSpeech()
                        Text(v.reading).font(.japanese(size: 14)).foregroundStyle(.secondary)
                        Spacer()
                        Text(v.gloss).font(.subheadline).multilineTextAlignment(.trailing)
                    }
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
                Divider()
            }
        }
    }

    private func load() async {
        defer { loaded = true }
        do {
            guard let s = try await app.graph.reader.graded.story(id: storyId) else { return }
            let plan = try await SwiftSupport.shared.readAlongPlan(graph: app.graph, story: s)
            story = s
            data = Self.copy(s, plan: plan)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't open the story: \(error.localizedDescription)")
        }
    }

    private static func task(_ t: ReaderTask?) -> StoryData.TaskPrompt? {
        guard let t else { return nil }
        return StoryData.TaskPrompt(
            prompt: t.prompt, english: t.promptEn, japanese: t.prompt == t.promptJa && t.promptJa != t.promptEn,
            seconds: t.seconds.map { Int($0.intValue) } ?? 0, find: t.find,
            minChars: t.minChars.map { Int($0.intValue) } ?? 0, maxChars: t.maxChars.map { Int($0.intValue) } ?? 0
        )
    }

    private static func copy(_ s: GradedStory, plan: ReadAlongPlan) -> StoryData {
        let body = s.body as NSString
        var lines: [StoryData.Line] = []
        var previousEnd = 0
        for row in plan.lines {
            let start = Int(row.start)
            var paragraph = lines.isEmpty
            if start > previousEnd, start <= body.length {
                let gap = body.substring(with: NSRange(location: previousEnd, length: start - previousEnd))
                if gap.contains("\n") { paragraph = true }
            }
            lines.append(StoryData.Line(id: Int(row.index), text: row.text, voice: row.voice, clipKey: row.clipKey, paragraph: paragraph))
            previousEnd = Int(row.end)
        }
        let vocab = s.vocabulary.enumerated().map { i, v in
            StoryData.Vocab(id: i, entryId: v.entryId, word: v.word, reading: v.reading, gloss: v.gloss)
        }
        let questions = s.questions.enumerated().map { i, q in
            StoryData.Question(id: i, stem: q.stem, choices: q.choices, answer: Int(q.answer), explanation: q.explanation, japanese: q.language == "ja")
        }
        return StoryData(
            title: s.title, titleEn: s.titleEn, levelLabel: s.level == "N6" ? "Level 0" : s.level, genre: s.genre, label: s.label,
            score: Int(s.textScore), coverage: s.coverage, aiGenerated: s.isAiGenerated, lines: lines, timed: plan.timed,
            vocabulary: vocab, questions: questions, prediction: task(s.tasks.prediction), skim: task(s.tasks.skim),
            close: s.tasks.close.compactMap { task($0) }, output: task(s.tasks.output)
        )
    }
}

// MARK: - Read along

/// Sentence-by-sentence read-along (D-205, D-207, D-261): with the readers audio pack every sentence plays its
/// pre-rendered clip; without it (or with a partial pack) the system voice reads each sentence. Either way the
/// sentence being spoken is highlighted, because playback goes one sentence at a time.
private struct ReadAlongPanel: View {
    let storyId: String
    let data: StoryData
    @Environment(AppModel.self) private var app
    @State private var voice = VoicePlayer()
    @State private var playing: Int?
    @State private var playTask: Task<Void, Never>?
    @State private var rate = 1.0
    @State private var opening = false
    @State private var openError: String?
    @State private var openDoc: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button {
                    if playTask != nil { stop() } else { play(from: 0) }
                } label: {
                    Label(playTask != nil ? "Stop" : "Read along", systemImage: playTask != nil ? "stop.fill" : "play.fill")
                }
                .buttonStyle(.borderedProminent)
                Spacer()
                Button(opening ? "Opening…" : "Open in reader") { openInReader() }
                    .buttonStyle(.bordered)
                    .disabled(opening)
            }
            HStack {
                Text("Speed \(String(format: "%.1f", rate))×").font(.caption.monospacedDigit())
                Slider(value: $rate, in: 0.6...1.2, step: 0.1)
            }
            Text(data.timed
                 ? String(localized: "Narrated audio. Tap a sentence to start there.")
                 : String(localized: "The readers audio pack isn't installed, so the system voice reads each sentence. Tap a sentence to start there."))
                .font(.caption).foregroundStyle(.secondary)
            if let openError { Text(openError).font(.caption).foregroundStyle(.red) }
            ForEach(data.lines) { line in
                Button { play(from: line.id) } label: {
                    Text(line.text)
                        .font(.japanese(size: 20))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 3)
                        .padding(.horizontal, 4)
                        .background(playing == line.id ? Color.yellow.opacity(0.3) : Color.clear, in: RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)
                .padding(.top, line.paragraph ? 10 : 0)
                .japaneseSpeech()
                .accessibilityHint(Text("Plays from this sentence."))
            }
            if let openDoc {
                NavigationLink("Continue in the reader", value: Route.read(openDoc))
            }
        }
        .onDisappear { stop() }
    }

    private func play(from index: Int) {
        stop()
        let lines = data.lines.filter { $0.id >= index }
        let timed = data.timed
        let graph = app.graph
        let speed = Float(rate)
        let title = data.title
        let id = storyId
        playTask = Task {
            let started = Date()
            for line in lines {
                if Task.isCancelled { break }
                playing = line.id
                if timed, let path = PackAudio.path(line.clipKey, graph: graph), await voice.play(file: path, rate: speed) {
                    continue
                }
                await voice.say(line.text, voice: Self.voice(line.voice), rate: speed)
            }
            // Listening time goes into the immersion log (D-194), like dialogues; stretches under 15 s are dropped.
            let seconds = Int64(Date().timeIntervalSince(started))
            _ = try? await graph.immersion.report(source: .reader, mode: .active, elapsedSeconds: seconds, ref: id, title: title)
            if !Task.isCancelled {
                playing = nil
                playTask = nil
            }
        }
    }

    private func stop() {
        playTask?.cancel()
        playTask = nil
        voice.stop()
        playing = nil
    }

    /// narration | female | male | male-senior → a system voice (D-205).
    private static func voice(_ hint: String) -> VoicePlayer.Voice {
        if hint.hasPrefix("male") { return .male }
        if hint == "female" { return .female }
        return .any
    }

    private func openInReader() {
        opening = true
        openError = nil
        let graph = app.graph
        let id = storyId
        Task {
            defer { opening = false }
            do {
                openDoc = try await graph.reader.openPassage(passageId: id)
                if openDoc == nil { openError = String(localized: "The story isn't in the installed pack any more.") }
            } catch {
                openError = String(localized: "Couldn't open it in the reader: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: - Quiz

private struct StoryQuizPanel: View {
    let story: GradedStory
    let questions: [StoryData.Question]
    @Environment(AppModel.self) private var app
    @State private var chosen: [Int: Int] = [:]
    @State private var submitted = false
    @State private var saving = false
    @State private var result: String?
    @State private var saveError: String?

    var body: some View {
        if questions.isEmpty {
            Text("This story has no questions.").foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(questions) { q in
                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(String(q.id + 1)). \(q.stem)")
                            .font(q.japanese ? Font.japanese(size: 17, weight: .semibold) : Font.headline)
                            .japaneseSpeech(q.japanese)
                        ForEach(Array(q.choices.enumerated()), id: \.offset) { i, choice in
                            Button {
                                if !submitted { chosen[q.id] = i }
                            } label: {
                                HStack {
                                    Text(choice).multilineTextAlignment(.leading)
                                    Spacer()
                                    mark(q, i)
                                }
                            }
                            .buttonStyle(.bordered)
                            .tint(chosen[q.id] == i ? .accentColor : .secondary)
                        }
                        if submitted && !q.explanation.isEmpty {
                            Text(q.explanation).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if submitted {
                    if let result { Text(result).font(.headline) }
                    Button("Try again") {
                        chosen = [:]
                        submitted = false
                        result = nil
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(saving ? "Saving…" : "Check answers") { submit() }
                        .buttonStyle(.borderedProminent)
                        .disabled(chosen.isEmpty || saving)
                }
                if let saveError {
                    ErrorRetryView(message: saveError) { submit() }
                }
            }
        }
    }

    @ViewBuilder
    private func mark(_ q: StoryData.Question, _ i: Int) -> some View {
        if submitted {
            if i == q.answer {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            } else if chosen[q.id] == i {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
            }
        }
    }

    /// Scores in shared code and stores the attempt (D-206); the roadmap's comprehension measure reads it.
    private func submit() {
        saving = true
        saveError = nil
        let graph = app.graph
        let choices = questions.map { KotlinInt(int: Int32(chosen[$0.id] ?? -1)) }
        Task {
            defer { saving = false }
            do {
                let r = try await SwiftSupport.shared.submitGradedQuiz(graph: graph, story: story, choices: choices)
                result = String(localized: "\(String(r.correct)) of \(String(r.total)) correct")
                submitted = true
            } catch {
                saveError = String(localized: "Couldn't save the quiz: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: - Genre tasks

/// The genre task set (D-204): prediction, a timed skim/scan, close-reading prompts, and an output task graded by
/// the learner's model (labeled AI-generated; without a model it says why and grades nothing).
private struct StoryTasksPanel: View {
    let story: GradedStory
    let data: StoryData
    let openQuiz: () -> Void
    @Environment(AppModel.self) private var app
    @State private var prediction = ""
    @State private var skimLeft: Int?
    @State private var skimTask: Task<Void, Never>?
    @State private var skimNotes = ""
    @State private var summary = ""
    @State private var grading = false
    @State private var grade: SummaryGradeRow?
    @State private var gradeError: String?
    @State private var studioDraftId: String?
    @State private var openingStudio = false
    @State private var studioError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if data.prediction == nil && data.skim == nil && data.close.isEmpty && data.output == nil {
                Text("This genre has no tasks in the installed pack.").foregroundStyle(.secondary)
            }
            // The task templates are AI-drafted like the stories (D-204): badge on until reviewed.
            HStack {
                Text("Tasks").font(.headline)
                AiBadge()
            }
            if let t = data.prediction {
                taskBlock(title: String(localized: "Before reading: predict"), t) {
                    TextField("Your guess", text: $prediction, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(1...4)
                }
            }
            if let t = data.skim { skimBlock(t) }
            if !data.close.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Close reading").font(.subheadline.weight(.semibold))
                    ForEach(Array(data.close.enumerated()), id: \.offset) { _, t in
                        prompt(t)
                    }
                    Button("Answer the comprehension questions") { openQuiz() }.buttonStyle(.bordered)
                }
            }
            if let t = data.output { outputBlock(t) }
            // §6.4 output task in the writing studio (D-306): one synced draft per story, carrying the task.
            VStack(alignment: .leading, spacing: 4) {
                Button(openingStudio ? "Opening…" : "Write it in the studio") { openStudio() }
                    .buttonStyle(.bordered)
                    .disabled(openingStudio)
                if let studioError { ErrorRetryView(message: studioError) { openStudio() } }
            }
        }
        .navigationDestination(item: $studioDraftId) { id in WritingDraftView(draftId: id) }
        .onDisappear { skimTask?.cancel() }
    }

    private func openStudio() {
        openingStudio = true
        studioError = nil
        let graph = app.graph
        let id = story.id
        Task {
            defer { openingStudio = false }
            do {
                if let d = try await SwiftSupport.shared.studioDraftForReaderTask(graph: graph, storyId: id) {
                    studioDraftId = d.id
                } else {
                    studioError = String(localized: "This story isn't in the installed pack.")
                }
            } catch {
                studioError = String(localized: "Couldn't open the draft: \(error.localizedDescription)")
            }
        }
    }

    private func prompt(_ t: StoryData.TaskPrompt) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(t.prompt).font(t.japanese ? Font.japanese(size: 16) : Font.subheadline).japaneseSpeech(t.japanese)
            if t.japanese { Text(t.english).font(.caption).foregroundStyle(.secondary) }
        }
    }

    private func taskBlock<Content: View>(title: String, _ t: StoryData.TaskPrompt, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: title).font(.subheadline.weight(.semibold))
            prompt(t)
            content()
        }
    }

    @ViewBuilder
    private func skimBlock(_ t: StoryData.TaskPrompt) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Skim and scan").font(.subheadline.weight(.semibold))
            prompt(t)
            if !t.find.isEmpty {
                Text("Find: \(t.find.joined(separator: " · "))").font(.caption)
            }
            if let left = skimLeft {
                HStack {
                    Image(systemName: "timer")
                    Text(clockText(seconds: Int64(left))).font(.title3.monospacedDigit())
                    Spacer()
                    Button("Stop") { endSkim() }.buttonStyle(.bordered)
                }
                ScrollView {
                    Text(data.lines.map(\.text).joined())
                        .font(.japanese(size: 17))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .japaneseSpeech()
                }
                .frame(maxHeight: 260)
                .padding(6)
                .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
            } else {
                Button(t.seconds > 0 ? String(localized: "Start the \(clockText(seconds: Int64(t.seconds))) timer") : String(localized: "Start")) {
                    startSkim(seconds: max(20, t.seconds))
                }
                .buttonStyle(.borderedProminent)
                TextField("What you found", text: $skimNotes, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
            }
        }
    }

    @ViewBuilder
    private func outputBlock(_ t: StoryData.TaskPrompt) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("After reading: write").font(.subheadline.weight(.semibold))
            prompt(t)
            TextEditor(text: $summary)
                .font(.japanese(size: 17))
                .frame(minHeight: 120)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
            let count = summary.filter { !$0.isWhitespace }.count
            if t.maxChars > 0 {
                Text("\(String(count)) characters (aim for \(String(t.minChars))–\(String(t.maxChars)))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(count >= t.minChars && count <= t.maxChars ? Color.green : Color.secondary)
            }
            AiStatusBanner(scripted: "Without a model your summary isn't graded. You can still write it and compare it with the text.")
            Button(grading ? "Grading…" : "Grade with AI") { gradeSummary() }
                .buttonStyle(.borderedProminent)
                .disabled(grading || summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let gradeError {
                ErrorRetryView(message: gradeError) { gradeSummary() }
            }
            if let grade {
                if grade.graded {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Score \(String(grade.total)) / 6").font(.headline)
                            AIBadge(engine: grade.engine)
                        }
                        LabeledContent(String(localized: "Content"), value: "\(grade.content) / 2")
                        LabeledContent(String(localized: "Accuracy"), value: "\(grade.accuracy) / 2")
                        LabeledContent(String(localized: "Language"), value: "\(grade.language) / 2")
                        if !grade.corrected.isEmpty {
                            Text("Suggested version").font(.caption.weight(.semibold))
                            Text(grade.corrected).font(.japanese(size: 16)).japaneseSpeech()
                        }
                        if !grade.feedback.isEmpty { Text(grade.feedback).font(.subheadline) }
                    }
                    .padding(8)
                    .background(Color.purple.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                } else {
                    Label(grade.reason, systemImage: "info.circle").font(.caption).foregroundStyle(.orange)
                }
            }
        }
    }

    private func startSkim(seconds: Int) {
        skimTask?.cancel()
        skimLeft = seconds
        skimTask = Task {
            while let left = skimLeft, left > 0, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                skimLeft = left - 1
            }
            if !Task.isCancelled { endSkim() }
        }
    }

    private func endSkim() {
        skimTask?.cancel()
        skimTask = nil
        skimLeft = nil
    }

    private func gradeSummary() {
        grading = true
        gradeError = nil
        let graph = app.graph
        let text = summary
        Task {
            defer { grading = false }
            do {
                grade = try await SwiftSupport.shared.gradeReaderSummary(graph: graph, story: story, summary: text)
            } catch {
                gradeError = String(localized: "Couldn't grade the summary: \(error.localizedDescription)")
            }
        }
    }
}
