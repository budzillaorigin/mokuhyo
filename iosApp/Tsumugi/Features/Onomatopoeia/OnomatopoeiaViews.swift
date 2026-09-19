import Shared
import SwiftUI

// Onomatopoeia (BRIEF_V2 §6.8, D-235…D-237; iOS UI D-266): theme tiles with our own glyphs, a type filter, search,
// word detail with Tatoeba examples, and the scene ↔ word quiz. Words come from JMdict (CC BY-SA 4.0); the theme,
// type and feel lines are AI-drafted and badged until reviewed.

struct OnoWordRow: Identifiable {
    let id: Int64
    let text: String
    let typeCode: String
    let typeLabel: String
    let theme: String
    let glosses: [String]
    let feel: String
    let feelJa: String
    let aiGenerated: Bool

    init(_ w: OnomatopoeiaWord) {
        id = w.entryId
        text = w.text
        typeCode = SwiftSupport.shared.onomatopoeiaTypeCode(word: w)
        typeLabel = SwiftSupport.shared.onomatopoeiaTypeLabel(word: w)
        theme = w.theme
        glosses = w.glosses
        feel = w.feel
        feelJa = w.feelJa
        aiGenerated = w.aiGenerated
    }
}

private struct ThemeTile: Identifiable {
    let id: String
    let title: String
    let titleJa: String
    let blurb: String
    let svg: String
    let count: Int
}

/// The type filter: "" = all, else an `OnomatopoeiaType` name.
private struct TypeFilter: View {
    @Binding var code: String
    private let types = SwiftSupport.shared.onomatopoeiaTypes()

    var body: some View {
        Picker("Type", selection: $code) {
            Text("All").tag("")
            ForEach(types, id: \.code) { t in Text(verbatim: t.labelJa).tag(t.code) }
        }
        .pickerStyle(.segmented)
    }
}

private struct OnoWordLine: View {
    let word: OnoWordRow

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(word.text).font(.japanese(size: 19)).japaneseSpeech()
                Text(verbatim: word.typeLabel).font(.caption2).foregroundStyle(.secondary)
                Spacer()
                if word.aiGenerated { AiBadge() }
            }
            Text(word.glosses.prefix(3).joined(separator: "; ")).font(.caption).lineLimit(2)
            if !word.feel.isEmpty { Text(word.feel).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
        }
    }
}

struct OnomatopoeiaHomeView: View {
    @Environment(AppModel.self) private var app
    @State private var themes: [ThemeTile]?
    @State private var unavailable = false
    @State private var error: String?
    @State private var query = ""
    @State private var results: [OnoWordRow] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if unavailable {
                    ContentUnavailableView(
                        "Onomatopoeia not installed",
                        systemImage: "waveform",
                        description: Text("The installed dictionary pack was built before the onomatopoeia module. Rebuild it with `uv run python packs/build_all.py` in tools/.")
                    )
                } else if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    if results.isEmpty {
                        Text("No onomatopoeia match that.").foregroundStyle(.secondary)
                    }
                    ForEach(results) { w in
                        NavigationLink(value: Route.onomatopoeiaWord(w.id)) { OnoWordLine(word: w) }
                            .buttonStyle(.plain)
                        Divider()
                    }
                } else if let themes {
                    Text("擬音語 imitate sounds, 擬態語 depict states and manners, 擬情語 depict feelings. Pick a theme, or test yourself.")
                        .font(.subheadline)
                    NavigationLink(value: Route.onomatopoeiaQuiz("")) {
                        Label("Quiz: scenes and words", systemImage: "questionmark.bubble")
                    }
                    .buttonStyle(.borderedProminent)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 10)], spacing: 10) {
                        ForEach(themes) { t in
                            NavigationLink(value: Route.onomatopoeiaTheme(t.id)) { tile(t) }
                                .buttonStyle(.plain)
                        }
                    }
                    Text("Words and glosses: JMdict (EDRDG, CC BY-SA 4.0). Examples: Tatoeba (CC BY 2.0 FR). Glyphs are our own.")
                        .font(.caption2).foregroundStyle(.tertiary)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
        }
        .navigationTitle("Onomatopoeia")
        .searchable(text: $query, prompt: Text("Search ざあざあ, sparkle…"))
        .task { await load() }
        .task(id: query) { await search() }
    }

    private func tile(_ t: ThemeTile) -> some View {
        VStack(spacing: 4) {
            ThemeGlyphView(themeId: t.id, svg: t.svg)
                .frame(width: 44, height: 44)
                .foregroundStyle(.tint)
            Text(t.title).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(t.titleJa).font(.japanese(size: 12)).foregroundStyle(.secondary)
            Text("\(String(t.count)) words").font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 120)
        .padding(6)
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        do {
            guard let repo = try await app.graph.onomatopoeia(), try await repo.available().boolValue else {
                unavailable = true
                return
            }
            themes = try await repo.themes().map {
                ThemeTile(id: $0.id, title: $0.title, titleJa: $0.titleJa, blurb: $0.blurb, svg: $0.svg, count: Int($0.count))
            }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the onomatopoeia: \(error.localizedDescription)")
        }
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else {
            results = []
            return
        }
        try? await Task.sleep(for: .milliseconds(250)) // debounce typing
        guard !Task.isCancelled, let repo = try? await app.graph.onomatopoeia() else { return }
        let found = (try? await repo.search(query: q)) ?? []
        if !Task.isCancelled { results = found.map { OnoWordRow($0) } }
    }
}

struct OnomatopoeiaThemeView: View {
    let themeId: String
    @Environment(AppModel.self) private var app
    @State private var type = ""
    @State private var words: [OnoWordRow]?
    @State private var theme: ThemeTile?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                if let theme {
                    HStack(spacing: 12) {
                        ThemeGlyphView(themeId: theme.id, svg: theme.svg).frame(width: 48, height: 48).foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(theme.titleJa).font(.japanese(size: 17, weight: .semibold))
                            if !theme.blurb.isEmpty { Text(theme.blurb).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
                TypeFilter(code: $type)
                NavigationLink(value: Route.onomatopoeiaQuiz(themeId)) {
                    Label("Quiz on this theme", systemImage: "questionmark.bubble")
                }
            }
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let words {
                if words.isEmpty {
                    Text("No words of this type in this theme.").foregroundStyle(.secondary)
                }
                ForEach(words) { w in
                    NavigationLink(value: Route.onomatopoeiaWord(w.id)) { OnoWordLine(word: w) }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(theme?.title ?? String(localized: "Onomatopoeia"))
        .task(id: type) { await load() }
    }

    private func load() async {
        do {
            guard let repo = try await app.graph.onomatopoeia() else {
                words = []
                return
            }
            if theme == nil, let t = try await repo.themes().first(where: { $0.id == themeId }) {
                theme = ThemeTile(id: t.id, title: t.title, titleJa: t.titleJa, blurb: t.blurb, svg: t.svg, count: Int(t.count))
            }
            let found = try await SwiftSupport.shared.onomatopoeiaWords(repo: repo, theme: themeId, typeCode: type, withFeelOnly: false)
            words = found.map { OnoWordRow($0) }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the words: \(error.localizedDescription)")
        }
    }
}

struct OnomatopoeiaDetailView: View {
    let entryId: Int64
    @Environment(AppModel.self) private var app
    @State private var word: OnoWordRow?
    @State private var variants: [String] = []
    @State private var theme: ThemeTile?
    @State private var examples: [(japanese: String, english: String)] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var voice = VoicePlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if let word {
                    HStack(alignment: .firstTextBaseline) {
                        Text(word.text).font(.japanese(size: 40, weight: .semibold, relativeTo: .largeTitle)).japaneseSpeech()
                        Button { Task { await voice.say(word.text) } } label: { Image(systemName: "speaker.wave.2") }
                            .accessibilityLabel(Text("Play"))
                    }
                    if !variants.isEmpty {
                        Text("Also: \(variants.joined(separator: "、"))").font(.japanese(size: 15))
                    }
                    HStack(spacing: 6) {
                        TagView(word.typeLabel)
                        if let theme {
                            HStack(spacing: 4) {
                                ThemeGlyphView(themeId: theme.id, svg: theme.svg).frame(width: 18, height: 18)
                                Text(verbatim: "\(theme.title) · \(theme.titleJa)").font(.caption)
                            }
                            .foregroundStyle(.tint)
                        }
                        if word.aiGenerated { AiBadge() }
                    }
                    Text(word.glosses.joined(separator: "; ")).font(.headline)
                    if !word.feel.isEmpty || !word.feelJa.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Feel").font(.caption.weight(.semibold)).foregroundStyle(.tint)
                            if !word.feel.isEmpty { Text(word.feel) }
                            if !word.feelJa.isEmpty { Text(word.feelJa).font(.japanese(size: 16)).japaneseSpeech() }
                        }
                    }
                    SectionHeader("Examples")
                    if examples.isEmpty {
                        Text("No example sentences for this word yet.").font(.subheadline).foregroundStyle(.secondary)
                    }
                    ForEach(Array(examples.enumerated()), id: \.offset) { _, ex in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(ex.japanese).font(.japanese(size: 17)).japaneseSpeech()
                                Spacer()
                                Button { Task { await voice.say(ex.japanese) } } label: { Image(systemName: "speaker.wave.2") }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel(Text("Play example"))
                            }
                            Text(ex.english).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if !examples.isEmpty {
                        Text("Example sentences from Tatoeba (CC BY 2.0 FR).").font(.caption2).foregroundStyle(.tertiary)
                    }
                    NavigationLink("Open in the dictionary", value: Route.entry(entryId))
                } else if loaded {
                    ContentUnavailableView("Word not found", systemImage: "questionmark.circle")
                } else {
                    ProgressView()
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(word?.text ?? String(localized: "Onomatopoeia"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onDisappear { voice.stop() }
    }

    private func load() async {
        defer { loaded = true }
        do {
            guard let repo = try await app.graph.onomatopoeia(), let d = try await repo.detail(entryId: entryId) else { return }
            word = OnoWordRow(d.word)
            variants = d.word.variants.filter { $0 != d.word.text }
            if let t = d.theme {
                theme = ThemeTile(id: t.id, title: t.title, titleJa: t.titleJa, blurb: t.blurb, svg: t.svg, count: Int(t.count))
            }
            examples = d.examples.map { (japanese: $0.japanese, english: $0.english) }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the word: \(error.localizedDescription)")
        }
    }
}

// MARK: - Quiz

private struct OnoQuestion: Identifiable {
    let id: Int
    let scene: Bool
    let prompt: String
    let promptJa: String
    let choices: [String]
    let answer: Int
    let options: [OnoWordRow]
    let aiGenerated: Bool
}

struct OnomatopoeiaQuizView: View {
    /// A theme id, or "" for every theme.
    let themeId: String
    @Environment(AppModel.self) private var app
    @State private var kind = ""
    @State private var questions: [OnoQuestion]?
    @State private var index = 0
    @State private var picked: Int?
    @State private var right = 0
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("Kind", selection: $kind) {
                    Text("Mixed").tag("")
                    Text("Word for a scene").tag("WORD_FOR_SCENE")
                    Text("Scene for a word").tag("SCENE_FOR_WORD")
                }
                .pickerStyle(.segmented)
                if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if let questions {
                    if questions.isEmpty {
                        Text("There aren't enough described words here for a quiz yet.").foregroundStyle(.secondary)
                    } else if index >= questions.count {
                        Text("\(String(right)) of \(String(questions.count)) right").font(.title3.weight(.semibold))
                        Button("New quiz") { Task { await load() } }.buttonStyle(.borderedProminent)
                    } else {
                        question(questions[index], total: questions.count)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
        }
        .navigationTitle("Onomatopoeia quiz")
        .task(id: kind) { await load() }
    }

    @ViewBuilder
    private func question(_ q: OnoQuestion, total: Int) -> some View {
        HStack {
            Text("\(String(index + 1)) of \(String(total))").font(.caption).foregroundStyle(.secondary)
            Spacer()
            if q.aiGenerated { AiBadge() }
        }
        if q.scene {
            Text("Which word fits this scene?").font(.subheadline.weight(.semibold))
            Text(q.prompt).font(.title3)
            if !q.promptJa.isEmpty { Text(q.promptJa).font(.japanese(size: 16)).foregroundStyle(.secondary).japaneseSpeech() }
        } else {
            Text("Which scene fits this word?").font(.subheadline.weight(.semibold))
            Text(q.prompt).font(.japanese(size: 34, weight: .semibold, relativeTo: .largeTitle)).japaneseSpeech()
        }
        ForEach(Array(q.choices.enumerated()), id: \.offset) { i, choice in
            Button {
                guard picked == nil else { return }
                picked = i
                if i == q.answer { right += 1 }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(choice)
                            .font(q.scene ? Font.japanese(size: 19) : Font.body)
                            .multilineTextAlignment(.leading)
                        Spacer()
                        if let picked {
                            if i == q.answer { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                            else if i == picked { Image(systemName: "xmark.circle.fill").foregroundStyle(.red) }
                        }
                    }
                    // After answering, each option shows its word and feel, so the wrong ones teach too (D-237).
                    if picked != nil, i < q.options.count {
                        let o = q.options[i]
                        Text(verbatim: q.scene ? o.feel : "\(o.text) · \(o.glosses.prefix(2).joined(separator: "; "))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .buttonStyle(.bordered)
        }
        if picked != nil {
            HStack {
                if q.answer < q.options.count {
                    NavigationLink("About \(q.options[q.answer].text)", value: Route.onomatopoeiaWord(q.options[q.answer].id))
                }
                Spacer()
                Button("Next") {
                    picked = nil
                    index += 1
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private func load() async {
        index = 0
        right = 0
        picked = nil
        questions = nil
        do {
            guard let repo = try await app.graph.onomatopoeia() else {
                questions = []
                return
            }
            let found = try await SwiftSupport.shared.onomatopoeiaQuiz(
                repo: repo, count: 10, kindCode: kind, theme: themeId, seed: Int64(Date().timeIntervalSince1970 * 1000)
            )
            questions = found.enumerated().map { i, q in
                OnoQuestion(
                    id: i, scene: SwiftSupport.shared.onomatopoeiaQuizIsScene(question: q), prompt: q.prompt, promptJa: q.promptJa,
                    choices: q.choices, answer: Int(q.answer), options: q.options.map { OnoWordRow($0) },
                    aiGenerated: q.target.aiGenerated
                )
            }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't build the quiz: \(error.localizedDescription)")
        }
    }
}
