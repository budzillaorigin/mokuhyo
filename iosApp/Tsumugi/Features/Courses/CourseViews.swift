import Shared
import SwiftUI

// Structured JLPT courses (BRIEF_V2 §6.6, D-230/D-231; iOS UI D-264): a progress bar per level, modules of
// kanji → vocab → grammar → quiz → mock section, the grammar mastery checkbox (independent of SRS) and the
// "one book to pass" list of exactly what remains.

/// A course grammar point, copied (the checkbox state changes locally as the learner ticks it).
private struct CoursePoint: Identifiable {
    let id: String
    let title: String
    let meaning: String
    var mastered: Bool
    let stage: String?
    let aiGenerated: Bool

    init(_ g: CourseGrammar) {
        id = g.pointId
        title = g.title
        meaning = g.meaning
        mastered = g.mastered
        stage = g.stage.map { SharedText.stage($0) }
        aiGenerated = g.aiGenerated
    }
}

private struct CourseQuiz: Identifiable {
    let type: String
    let title: String
    let bankItems: Int
    let best: Double?
    let passed: Bool
    var id: String { type }
}

private struct CourseMock: Identifiable {
    let id: String
    let title: String
    let minutes: Int
    let items: Int
    let best: Double?
    let passed: Bool

    init(_ m: CourseMockSection) {
        id = m.sectionId
        title = m.title
        minutes = Int(m.minutes)
        items = Int(m.itemCount)
        best = m.bestAccuracy?.doubleValue
        passed = m.passed
    }
}

private enum StepKind: Int {
    case kanji, vocab, grammar, quiz, mock

    var title: String {
        switch self {
        case .kanji: String(localized: "Kanji")
        case .vocab: String(localized: "Vocabulary")
        case .grammar: String(localized: "Grammar")
        case .quiz: String(localized: "Quiz")
        case .mock: String(localized: "Mock section")
        }
    }

    var icon: String {
        switch self {
        case .kanji: "character.book.closed.ja"
        case .vocab: "textformat.abc"
        case .grammar: "text.book.closed"
        case .quiz: "checklist"
        case .mock: "timer"
        }
    }
}

private struct ModuleStep: Identifiable {
    let kind: StepKind
    let done: Int
    let total: Int
    var id: Int { kind.rawValue }
    var complete: Bool { done >= total }
}

private struct ModuleData: Identifiable {
    let id: Int
    let kanjiCount: Int
    let kanjiLearned: Int
    let firstPathLevel: Int?
    let wordCount: Int
    let wordsLearned: Int
    var grammar: [CoursePoint]
    let quiz: [CourseQuiz]
    let mock: CourseMock?

    var steps: [ModuleStep] {
        var out: [ModuleStep] = []
        if kanjiCount > 0 { out.append(ModuleStep(kind: .kanji, done: kanjiLearned, total: kanjiCount)) }
        if wordCount > 0 { out.append(ModuleStep(kind: .vocab, done: wordsLearned, total: wordCount)) }
        if !grammar.isEmpty { out.append(ModuleStep(kind: .grammar, done: grammar.filter(\.mastered).count, total: grammar.count)) }
        if !quiz.isEmpty { out.append(ModuleStep(kind: .quiz, done: quiz.filter(\.passed).count, total: quiz.count)) }
        if let mock { out.append(ModuleStep(kind: .mock, done: mock.passed ? 1 : 0, total: 1)) }
        return out
    }

    var complete: Bool { steps.allSatisfy(\.complete) }

    /// The first step not done yet (computed here from the copied counts, the same rule as `CourseModule.nextStep`).
    var nextStep: StepKind? { steps.first { !$0.complete }?.kind }

    init(_ m: CourseModule) {
        id = Int(m.index)
        kanjiCount = m.kanji.count
        kanjiLearned = m.kanji.filter { $0.learned }.count
        firstPathLevel = (m.kanji.first(where: { !$0.learned }) ?? m.kanji.first).map { Int($0.pathLevel) }
        wordCount = m.words.count
        wordsLearned = m.words.filter { $0.learned }.count
        grammar = m.grammar.map { CoursePoint($0) }
        quiz = m.quiz.map { q in
            CourseQuiz(type: q.type, title: q.title, bankItems: Int(q.bankItems), best: q.bestAccuracy?.doubleValue, passed: q.passed)
        }
        mock = m.mock.map { CourseMock($0) }
    }
}

/// Starts JLPT quiz drills and mock sections from a course, like the exam hub does (shared ExamService forms).
@MainActor
@Observable
final class CourseExamLauncher {
    var running: RunningExam?
    var message: String?
    var building = false

    func start(_ graph: AppGraph, _ build: @escaping (ExamService) async throws -> ExamSession?) {
        guard !building else { return }
        building = true
        message = nil
        Task {
            defer { building = false }
            do {
                let exams = try await graph.exams()
                guard let session = try await build(exams), !session.form.isEmpty else {
                    message = String(localized: "There aren't enough items in the bank for that test yet.")
                    return
                }
                session.begin()
                running = RunningExam(session: session)
            } catch {
                message = String(localized: "Couldn't build that test: \(error.localizedDescription)")
            }
        }
    }

    nonisolated static func seed() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

// MARK: - Overview

private struct LevelBar: Identifiable {
    let level: Int
    let percent: Int
    let fraction: Double
    let empty: Bool
    var id: Int { level }
}

struct CoursesView: View {
    @Environment(AppModel.self) private var app
    @State private var levels: [LevelBar]?
    @State private var current: Int?
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let levels {
                Section {
                    Text("Each level is a course of modules: kanji → vocabulary → grammar → quiz → mock section. The bar counts learned kanji and words, grammar you ticked as mastered, and mock sections passed.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    ForEach(levels) { l in
                        NavigationLink(value: Route.course(l.level)) {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(verbatim: "N\(l.level)").font(.headline)
                                    if current == l.level { TagView(String(localized: "Your level")) }
                                    Spacer()
                                    Text(verbatim: l.empty ? "–" : "\(l.percent)%").font(.subheadline.monospacedDigit())
                                }
                                ProgressView(value: min(1, max(0, l.fraction)))
                                if l.empty {
                                    Text("No content installed for this level yet.").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("JLPT courses")
        .task { await load() }
    }

    private func load() async {
        do {
            let overview = try await app.graph.courses.overview()
            levels = overview.map { LevelBar(level: Int($0.level), percent: Int($0.progress.percent), fraction: $0.progress.fraction, empty: $0.progress.isEmpty) }
            current = (try? await app.graph.courses.courseLevel()).map { Int($0.intValue) }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the courses: \(error.localizedDescription)")
        }
    }
}

// MARK: - One level

struct CourseLevelView: View {
    let level: Int
    @Environment(AppModel.self) private var app
    @State private var modules: [ModuleData]?
    @State private var percent = 0
    @State private var fraction = 0.0
    @State private var error: String?
    @State private var saveError: String?
    @State private var launcher = CourseExamLauncher()
    @State private var expanded: Set<Int> = []

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let modules {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("Progress").font(.headline)
                            Spacer()
                            Text(verbatim: "\(percent)%").font(.headline.monospacedDigit())
                        }
                        ProgressView(value: min(1, max(0, fraction)))
                    }
                    NavigationLink(value: Route.courseRemaining(level)) {
                        Label("One book to pass: what's left for N\(String(level))", systemImage: "book.closed")
                    }
                    if let m = launcher.message { Text(m).font(.caption).foregroundStyle(.orange) }
                    if let saveError { Text(saveError).font(.caption).foregroundStyle(.red) }
                    if launcher.building {
                        HStack {
                            ProgressView()
                            Text("Building the test…").font(.caption)
                        }
                    }
                }
                if modules.isEmpty {
                    Text("No content for N\(String(level)) is installed yet.").foregroundStyle(.secondary)
                }
                ForEach(modules.indices, id: \.self) { i in
                    Section {
                        DisclosureGroup(isExpanded: Binding(
                            get: { expanded.contains(modules[i].id) },
                            set: { if $0 { expanded.insert(modules[i].id) } else { expanded.remove(modules[i].id) } }
                        )) {
                            moduleBody(i)
                        } label: {
                            moduleLabel(modules[i])
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(Text(verbatim: "N\(level)"))
        .task { await load() }
        .fullScreenCover(item: $launcher.running, onDismiss: { Task { await load() } }) { run in
            ExamRunnerView(session: run.session) { launcher.running = nil }
        }
    }

    private func moduleLabel(_ m: ModuleData) -> some View {
        HStack {
            Image(systemName: m.complete ? "checkmark.circle.fill" : "circle.dashed")
                .foregroundStyle(m.complete ? Color.green : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text("Module \(String(m.id))").font(.headline)
                if let next = m.nextStep {
                    Text("Next: \(next.title)").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Done").font(.caption).foregroundStyle(.green)
                }
            }
        }
    }

    @ViewBuilder
    private func moduleBody(_ i: Int) -> some View {
        if let modules {
            let m = modules[i]
            ForEach(m.steps) { step in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Label(step.kind.title, systemImage: step.kind.icon)
                        Spacer()
                        Text(verbatim: "\(step.done)/\(step.total)").font(.caption.monospacedDigit())
                            .foregroundStyle(step.complete ? Color.green : Color.secondary)
                    }
                    ProgressView(value: step.total == 0 ? 1 : Double(step.done) / Double(step.total))
                    stepAction(step.kind, i)
                }
            }
        }
    }

    @ViewBuilder
    private func stepAction(_ kind: StepKind, _ i: Int) -> some View {
        if let modules {
            let m = modules[i]
            switch kind {
            case .kanji:
                if let pathLevel = m.firstPathLevel {
                    NavigationLink("Study on the kanji path (level \(String(pathLevel)))", value: Route.pathLevel(pathLevel)).font(.caption)
                }
            case .vocab:
                NavigationLink("Learn new words", value: Route.lessons).font(.caption)
            case .grammar:
                ForEach(m.grammar.indices, id: \.self) { g in
                    grammarRow(module: i, point: g)
                }
            case .quiz:
                ForEach(m.quiz) { q in
                    Button {
                        let type = q.type
                        let lv = Int32(level)
                        launcher.start(app.graph) { try await $0.jlptTypeDrill(level: lv, type: type, seed: CourseExamLauncher.seed()) }
                    } label: {
                        HStack {
                            Image(systemName: q.passed ? "checkmark.seal.fill" : "play.circle").foregroundStyle(q.passed ? Color.green : Color.accentColor)
                            Text(q.title)
                            Spacer()
                            Text(q.best.map { "\(Int(($0 * 100).rounded()))%" } ?? "–").font(.caption.monospacedDigit())
                        }
                    }
                    .disabled(q.bankItems == 0 || launcher.building)
                }
            case .mock:
                if let mock = m.mock {
                    Button {
                        let id = mock.id
                        let lv = Int32(level)
                        launcher.start(app.graph) { try await $0.jlptSection(level: lv, sectionId: id, seed: CourseExamLauncher.seed()) }
                    } label: {
                        HStack {
                            Image(systemName: mock.passed ? "checkmark.seal.fill" : "timer").foregroundStyle(mock.passed ? Color.green : Color.accentColor)
                            Text("\(mock.title) · \(String(mock.minutes)) min")
                            Spacer()
                            Text(mock.best.map { "\(Int(($0 * 100).rounded()))%" } ?? "–").font(.caption.monospacedDigit())
                        }
                    }
                    .disabled(launcher.building)
                }
            }
        }
    }

    @ViewBuilder
    private func grammarRow(module i: Int, point g: Int) -> some View {
        if let modules {
            let p = modules[i].grammar[g]
            HStack(alignment: .top) {
                Button {
                    toggle(module: i, point: g)
                } label: {
                    Image(systemName: p.mastered ? "checkmark.square.fill" : "square")
                        .foregroundStyle(p.mastered ? Color.green : Color.secondary)
                        .font(.title3)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(p.mastered ? Text("Mastered. Tap to untick.") : Text("Not mastered. Tap to tick."))
                NavigationLink(value: Route.grammarPoint(p.id)) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(p.title).font(.japanese(size: 16)).japaneseSpeech()
                        Text(p.meaning).font(.caption).foregroundStyle(.secondary)
                        HStack(spacing: 4) {
                            if let stage = p.stage { TagView(stage) }
                            if p.aiGenerated { AiBadge() }
                        }
                    }
                }
            }
        }
    }

    private func toggle(module i: Int, point g: Int) {
        guard var list = modules else { return }
        let id = list[i].grammar[g].id
        let now = !list[i].grammar[g].mastered
        list[i].grammar[g].mastered = now
        modules = list
        let graph = app.graph
        Task {
            do {
                _ = try await graph.courses.setMastered(pointId: id, mastered: now)
                saveError = nil
            } catch {
                saveError = String(localized: "Couldn't save the checkbox: \(error.localizedDescription)")
                await load()
            }
        }
    }

    private func load() async {
        do {
            let course = try await app.graph.courses.course(level: Int32(level))
            let copied = course.modules.map { ModuleData($0) }
            percent = Int(course.progress.percent)
            fraction = course.progress.fraction
            if modules == nil, let current = copied.first(where: { !$0.complete }) { expanded = [current.id] }
            modules = copied
            error = nil
            _ = try? await app.graph.courses.setCourseLevel(level: Int32(level))
        } catch {
            self.error = String(localized: "Couldn't load the course: \(error.localizedDescription)")
        }
    }
}

// MARK: - One book to pass

struct CourseRemainingView: View {
    let level: Int
    @Environment(AppModel.self) private var app
    @State private var kanji: [(text: String, keyword: String)] = []
    @State private var words: [(text: String, reading: String, meaning: String, entryId: Int64?)] = []
    @State private var grammar: [CoursePoint] = []
    @State private var mocks: [CourseMock] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var launcher = CourseExamLauncher()

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if !loaded {
                ProgressView()
            } else if kanji.isEmpty && words.isEmpty && grammar.isEmpty && mocks.isEmpty {
                ContentUnavailableView("Nothing left", systemImage: "checkmark.seal", description: Text("Everything in the N\(String(level)) course is learned, mastered or passed."))
            } else {
                Section {
                    Text("Exactly what remains for N\(String(level)), in course order: \(String(kanji.count)) kanji, \(String(words.count)) words, \(String(grammar.count)) grammar points, \(String(mocks.count)) mock sections.")
                        .font(.subheadline)
                    if let m = launcher.message { Text(m).font(.caption).foregroundStyle(.orange) }
                }
                if !grammar.isEmpty {
                    Section("Grammar to master") {
                        ForEach(grammar.indices, id: \.self) { i in
                            HStack {
                                Button {
                                    toggle(i)
                                } label: {
                                    Image(systemName: grammar[i].mastered ? "checkmark.square.fill" : "square")
                                        .foregroundStyle(grammar[i].mastered ? Color.green : Color.secondary)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(grammar[i].mastered ? Text("Mastered. Tap to untick.") : Text("Not mastered. Tap to tick."))
                                NavigationLink(value: Route.grammarPoint(grammar[i].id)) {
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(grammar[i].title).font(.japanese(size: 16))
                                        Text(grammar[i].meaning).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                if !mocks.isEmpty {
                    Section("Mock sections to pass") {
                        ForEach(mocks) { mock in
                            Button("\(mock.title) · \(String(mock.minutes)) min") {
                                let id = mock.id
                                let lv = Int32(level)
                                launcher.start(app.graph) { try await $0.jlptSection(level: lv, sectionId: id, seed: CourseExamLauncher.seed()) }
                            }
                            .disabled(launcher.building)
                        }
                    }
                }
                if !kanji.isEmpty {
                    Section("Kanji to learn") {
                        FlowLayout(spacing: 6) {
                            ForEach(Array(kanji.enumerated()), id: \.offset) { _, k in
                                NavigationLink(value: Route.kanji(k.text)) {
                                    Text(k.text).font(.japanese(size: 22))
                                        .frame(minWidth: 36, minHeight: 36)
                                        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 6))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text(verbatim: "\(k.text) \(k.keyword)"))
                            }
                        }
                    }
                }
                if !words.isEmpty {
                    Section("Words to learn") {
                        ForEach(Array(words.prefix(300).enumerated()), id: \.offset) { _, w in
                            wordRow(w)
                        }
                        if words.count > 300 {
                            Text("…and \(String(words.count - 300)) more.").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle(Text("One book to pass"))
        .task { await load() }
        .fullScreenCover(item: $launcher.running, onDismiss: { Task { await load() } }) { run in
            ExamRunnerView(session: run.session) { launcher.running = nil }
        }
    }

    @ViewBuilder
    private func wordRow(_ w: (text: String, reading: String, meaning: String, entryId: Int64?)) -> some View {
        let row = HStack(alignment: .firstTextBaseline) {
            Text(w.text).font(.japanese(size: 17))
            Text(w.reading).font(.japanese(size: 13)).foregroundStyle(.secondary)
            Spacer()
            Text(w.meaning).font(.caption).multilineTextAlignment(.trailing)
        }
        if let id = w.entryId {
            NavigationLink(value: Route.entry(id)) { row }
        } else {
            row
        }
    }

    private func toggle(_ i: Int) {
        let id = grammar[i].id
        let now = !grammar[i].mastered
        grammar[i].mastered = now
        let graph = app.graph
        Task {
            do {
                _ = try await graph.courses.setMastered(pointId: id, mastered: now)
            } catch {
                self.error = String(localized: "Couldn't save the checkbox: \(error.localizedDescription)")
            }
        }
    }

    private func load() async {
        do {
            let r = try await app.graph.courses.remaining(level: Int32(level))
            kanji = r.kanji.map { (text: $0.text, keyword: $0.keyword) }
            words = r.words.map { (text: $0.text, reading: $0.reading, meaning: $0.meaning, entryId: $0.entryId?.int64Value) }
            grammar = r.grammar.map { CoursePoint($0) }
            mocks = r.mockSections.map { CourseMock($0) }
            loaded = true
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load what's left: \(error.localizedDescription)")
        }
    }
}
