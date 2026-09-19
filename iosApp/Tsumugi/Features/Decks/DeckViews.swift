import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Where a new media deck's text comes from (BRIEF_V2 §6.1).
enum DeckSource: Identifiable {
    case document(id: String, title: String)
    case epub(path: String)
    case subtitles(title: String, text: String, mediaKey: String?)
    case text(title: String, text: String)

    var id: String {
        switch self {
        case .document(let id, _): "doc:\(id)"
        case .epub(let path): "epub:\(path)"
        case .subtitles(let title, _, let key): "subs:\(key ?? title)"
        case .text(let title, let text): "text:\(title):\(text.count)"
        }
    }
}

/// Decks (BRIEF_V2 §6.1): the Core 2k/6k/10k frequency decks, the learner's media decks, and which deck feeds lessons.
struct DecksHomeView: View {
    @Environment(AppModel.self) private var app
    @State private var decks: [MediaDeckSummary]?
    @State private var core: [FrequencyDeckSummary] = []
    @State private var lessons: DeckLessonSettings?
    @State private var loadError: String?
    @State private var creating: DeckSource?
    @State private var pickingDocument = false
    /// One file importer for both kinds (SwiftUI honours only one `.fileImporter` per view).
    private enum FilePick { case epub, subtitles }
    @State private var pickingFile = false
    @State private var filePick = FilePick.epub
    @State private var pasting = false
    @State private var pasteTitle = ""
    @State private var pasteText = ""
    @State private var openDeck: String?
    @State private var note: String?

    private var subtitleTypes: [UTType] {
        [UTType(filenameExtension: "srt"), UTType(filenameExtension: "vtt")].compactMap { $0 } + [.plainText, .data]
    }

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            }
            if let lessons, lessons.active, let deckId = lessons.deckId {
                Section("Lessons") {
                    Text("New lessons come from \(deckTitle(deckId)) (\(modeLabel(lessons.mode))).").font(.subheadline)
                    Button("Stop studying this deck") {
                        Task {
                            try? await app.graph.deckLessons.deactivate()
                            await load()
                        }
                    }
                }
            }
            Section {
                if core.isEmpty {
                    Text("The Core decks need the dictionary pack with its frequency list. Rebuild or install the dictionary pack to see them.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(core, id: \.deck.id) { d in
                    NavigationLink(value: Route.coreDeck(d.deck.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.deck.title).font(.body.weight(.medium))
                            Text("\(Int(d.known).formatted()) of \(Int(d.size).formatted()) known · \(Int(d.learning).formatted()) learning")
                                .font(.caption).foregroundStyle(.secondary)
                            if d.libraryCoverage > 0 {
                                Text("Covers \(Int((d.libraryCoverage * 100).rounded()).formatted())% of the words in your media")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } header: {
                Text("Core decks")
            } footer: {
                Text("The most frequent words, from Tatoeba sentence counts and JMdict.")
            }
            Section("Your media decks") {
                if let decks, decks.isEmpty {
                    Text("Make a deck from a book, an article, subtitles or any text: its words in the order that text needs them.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(decks ?? [], id: \.id) { d in
                    NavigationLink(value: Route.deck(d.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.title).font(.japanese(size: 16)).lineLimit(2)
                            Text("\(Int(d.wordCount).formatted()) words · \(CoverageText.level(jlpt: d.stats.jlpt, ilr: d.stats.ilr)) · \(Int(d.coverage.knownPercent).formatted())% known")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions {
                        Button("Delete", role: .destructive) { delete(d.id) }
                    }
                }
                Menu {
                    Button("From a text in my library") { pickingDocument = true }
                    Button("From an EPUB") {
                        filePick = .epub
                        pickingFile = true
                    }
                    Button("From subtitles (.srt, .vtt)") {
                        filePick = .subtitles
                        pickingFile = true
                    }
                    Button("From pasted text") {
                        pasteTitle = ""
                        pasteText = ""
                        pasting = true
                    }
                } label: {
                    Label("Create a deck", systemImage: "plus.rectangle.on.rectangle")
                }
                if let note { Text(note).font(.caption).foregroundStyle(.orange) }
            }
        }
        .navigationTitle("Decks")
        .task { await load() }
        .refreshable { await load() }
        .navigationDestination(item: $openDeck) { DeckDetailView(deckId: $0) }
        .sheet(item: $creating) { source in
            NavigationStack {
                DeckPreviewView(source: source) { saved in
                    creating = nil
                    Task {
                        await load()
                        openDeck = saved
                    }
                }
            }
            .environment(app)
        }
        .sheet(isPresented: $pickingDocument) {
            NavigationStack {
                LibraryPicker { id, title in
                    pickingDocument = false
                    creating = .document(id: id, title: title)
                }
            }
            .environment(app)
        }
        .sheet(isPresented: $pasting) {
            NavigationStack {
                Form {
                    TextField("Title", text: $pasteTitle)
                    TextEditor(text: $pasteText).font(.japanese(size: 16)).frame(minHeight: 220)
                }
                .navigationTitle("Paste Japanese text")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { pasting = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Preview") {
                            pasting = false
                            let title = pasteTitle.trimmingCharacters(in: .whitespaces)
                            creating = .text(title: title.isEmpty ? String(localized: "Pasted text") : title, text: pasteText)
                        }
                        .disabled(pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
        .fileImporter(
            isPresented: $pickingFile,
            allowedContentTypes: filePick == .epub ? [UTType(filenameExtension: "epub") ?? .data, .data] : subtitleTypes
        ) { result in
            guard case .success(let url) = result else { return }
            switch filePick {
            case .epub: copyEpub(url)
            case .subtitles: readSubtitles(url)
            }
        }
    }

    private func deckTitle(_ id: String) -> String {
        if let d = core.first(where: { $0.deck.id == id }) { return d.deck.title }
        return decks?.first(where: { $0.id == id })?.title ?? String(localized: "a deck")
    }

    private func load() async {
        loadError = nil
        do {
            decks = try await app.graph.decks.decks()
            core = try await app.graph.decks.frequencyDecks()
            lessons = try await app.graph.deckLessons.settings()
        } catch {
            loadError = String(localized: "Couldn't load your decks: \(error.localizedDescription)")
        }
    }

    private func delete(_ id: String) {
        Task {
            do {
                try await app.graph.decks.delete(id: id)
            } catch {
                note = String(localized: "Couldn't delete: \(error.localizedDescription)")
            }
            await load()
        }
    }

    /// Copies a picked EPUB out of its security scope, off the main actor.
    private func copyEpub(_ url: URL) {
        note = nil
        Task {
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("deck-\(UUID().uuidString).epub")
            do {
                try await Task.detached(priority: .userInitiated) {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    try FileManager.default.copyItem(at: url, to: target)
                }.value
                creating = .epub(path: target.path)
            } catch {
                note = String(localized: "Couldn't read that file: \(error.localizedDescription)")
            }
        }
    }

    private func readSubtitles(_ url: URL) {
        note = nil
        Task {
            let text = await Task.detached(priority: .userInitiated) { SubtitleFile.read(url) }.value
            guard let text else {
                note = String(localized: "\(url.lastPathComponent) isn't UTF-8, UTF-16 or Shift-JIS text.")
                return
            }
            creating = .subtitles(title: url.deletingPathExtension().lastPathComponent, text: text, mediaKey: nil)
        }
    }
}

func modeLabel(_ mode: DeckLessonMode) -> String {
    switch mode {
    case .interleave: String(localized: "mixed with the kanji path")
    case .deckOnly: String(localized: "deck only")
    default: String(localized: "off")
    }
}

/// Reads a subtitle or lyric file picked in Files: UTF-8, UTF-16 or Shift-JIS.
enum SubtitleFile {
    static func read(_ url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .utf16)
            ?? String(data: data, encoding: .shiftJIS)
    }
}

/// Picks a document from the reader library.
struct LibraryPicker: View {
    @Environment(AppModel.self) private var app
    let onPick: (String, String) -> Void
    @State private var docs: [ReaderDocumentSummary]?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            if let docs, docs.isEmpty {
                Text("Your reading library is empty. Add a text in Learn → Reading first.").foregroundStyle(.secondary)
            }
            ForEach(docs ?? [], id: \.id) { d in
                Button {
                    onPick(d.id, d.title)
                } label: {
                    Text(d.title).font(.japanese(size: 16)).lineLimit(2)
                }
            }
        }
        .navigationTitle("Choose a text")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        .task { docs = (try? await app.graph.reader.documents()) ?? [] }
    }
}

/// The deck preview (BRIEF_V2 §6.1): the text's words in study order, coverage at 80/90/95/98%, JLPT/ILR, then Save.
/// Tokenizing runs in the shared code on Dispatchers.IO with progress and Cancel (rule 15).
struct DeckPreviewView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let source: DeckSource
    let onSaved: (String) -> Void

    @State private var vocabulary: MediaVocabulary?
    @State private var progress: Double?
    @State private var task: Task<Void, Never>?
    @State private var failure: String?
    @State private var unavailable = false
    @State private var saving = false
    @State private var studyAfterSave = true

    var body: some View {
        List {
            if let failure {
                ErrorRetryView(message: failure) { build() }
            } else if unavailable {
                ContentUnavailableView(
                    "Can't build a deck here",
                    systemImage: "books.vertical",
                    description: Text("Decks need the dictionary and tokenizer packs, and a text with Japanese words.")
                )
            } else if let v = vocabulary {
                content(v)
            } else {
                CancellableProgress(label: String(localized: "Reading the text…"), fraction: progress) {
                    task?.cancel()
                    dismiss()
                }
            }
        }
        .navigationTitle("New deck")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    task?.cancel()
                    dismiss()
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(saving ? "Saving…" : "Save") { save() }
                    .disabled(vocabulary == nil || saving || (vocabulary?.words.isEmpty ?? true))
            }
        }
        .task { if vocabulary == nil && task == nil { build() } }
        .onDisappear { task?.cancel() }
    }

    @ViewBuilder
    private func content(_ v: MediaVocabulary) -> some View {
        Section {
            Text(v.title).font(.japanese(size: 18, weight: .semibold))
            DeckStatsView(stats: v.stats)
            Text(CoverageText.summary(v.coverage)).font(.caption)
            Toggle("Study this deck in lessons", isOn: $studyAfterSave)
        }
        if !v.kanji.isEmpty {
            Section("Kanji (\(v.kanji.count.formatted()))") {
                Text(v.kanji.prefix(80).map(\.kanji).joined(separator: " ")).font(.japanese(size: 18))
            }
        }
        Section("Words in study order") {
            if v.words.isEmpty {
                Text("No dictionary words were found in this text.").foregroundStyle(.secondary)
            }
            ForEach(v.words.prefix(100), id: \.entryId) { w in
                DeckWordRow(word: w)
            }
            if v.words.count > 100 {
                Text("…and \((v.words.count - 100).formatted()) more").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func build() {
        failure = nil
        unavailable = false
        progress = nil
        let decks = app.graph.decks
        let source = source
        task?.cancel()
        task = Task {
            let report: (KotlinDouble) -> Void = { p in
                let f = p.doubleValue
                Task { @MainActor in progress = f }
            }
            do {
                let result: MediaVocabulary?
                switch source {
                case .document(let id, _):
                    result = try await decks.vocabularyForDocument(documentId: id, onProgress: report)
                case .epub(let path):
                    result = try await decks.vocabularyForEpub(path: path, onProgress: report)
                case .subtitles(let title, let text, let key):
                    result = try await decks.vocabularyForSubtitles(title: title, subtitles: text, mediaKey: key, onProgress: report)
                case .text(let title, let text):
                    result = try await decks.vocabularyForText(title: title, text: text, kind: .text, mediaKey: nil, onProgress: report)
                }
                guard !Task.isCancelled else { return }
                if let result { vocabulary = result } else { unavailable = true }
            } catch {
                if !Task.isCancelled { failure = String(localized: "Couldn't build the deck: \(error.localizedDescription)") }
            }
        }
    }

    private func save() {
        guard let v = vocabulary else { return }
        saving = true
        let graph = app.graph
        let study = studyAfterSave
        Task {
            do {
                let summary = try await graph.decks.save(vocabulary: v)
                if study { try? await graph.deckLessons.activate(deckId: summary.id, mode: .interleave) }
                onSaved(summary.id)
            } catch {
                failure = String(localized: "Couldn't save the deck: \(error.localizedDescription)")
            }
            saving = false
        }
    }
}

/// Unique words, words for 80/90/95/98% coverage, the level, kanji and grammar counts.
struct DeckStatsView: View {
    let stats: MediaDeckStats

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent("Level", value: CoverageText.level(jlpt: stats.jlpt, ilr: stats.ilr))
            LabeledContent("Unique words", value: Int(stats.uniqueWords).formatted())
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 2) {
                GridRow {
                    Text("Words to understand").font(.caption).foregroundStyle(.secondary).gridCellColumns(4)
                }
                GridRow {
                    Text("80%: \(Int(stats.words80).formatted())")
                    Text("90%: \(Int(stats.words90).formatted())")
                    Text("95%: \(Int(stats.words95).formatted())")
                    Text("98%: \(Int(stats.words98).formatted())")
                }
                .font(.caption.monospacedDigit())
            }
            Text("\(Int(stats.kanjiCount).formatted()) kanji · \(Int(stats.grammarCount).formatted()) grammar points").font(.caption).foregroundStyle(.secondary)
            if stats.sampled {
                Text("Measured on the first 250,000 characters.").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

/// A deck word: text, reading, occurrences and the learner's state.
struct DeckWordRow: View {
    let word: DeckWord

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 1) {
                Text(word.text).font(.japanese(size: 18)).japaneseSpeech()
                if let reading = word.reading, reading != word.text {
                    Text(reading).font(.japanese(size: 12)).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text("×\(Int(word.count).formatted())").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            WordStateBadge(state: word.state)
        }
    }
}

/// A saved media deck: stats, coverage, lessons from it, its words (mark known), kanji and grammar.
struct DeckDetailView: View {
    @Environment(AppModel.self) private var app
    let deckId: String

    @State private var detail: MediaDeckDetail?
    @State private var lessons: DeckLessonSettings?
    @State private var loadError: String?
    @State private var loaded = false
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var shown = 100
    @State private var note: String?

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            } else if let d = detail {
                Section {
                    Text(d.summary.title).font(.japanese(size: 18, weight: .semibold))
                    DeckStatsView(stats: d.summary.stats)
                    Text(CoverageText.summary(d.summary.coverage)).font(.caption)
                    if d.summary.libraryCoverage > 0 {
                        Text("These words cover \(Int((d.summary.libraryCoverage * 100).rounded()).formatted())% of the words in all your media.")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Section("Lessons") {
                    DeckLessonControls(deckId: deckId, settings: lessons) { await load() }
                    if let note { Text(note).font(.caption) }
                }
                if !d.grammarPointIds.isEmpty {
                    Section("Grammar in this text") {
                        ForEach(d.grammarPointIds.prefix(30), id: \.self) { id in
                            NavigationLink(id.split(separator: "-").dropFirst().joined(separator: " "), value: Route.grammarPoint(id))
                        }
                    }
                }
                if !d.kanji.isEmpty {
                    Section("Kanji (\(d.kanji.count.formatted()))") {
                        Text(d.kanji.prefix(120).map(\.kanji).joined(separator: " ")).font(.japanese(size: 18))
                    }
                }
                Section("Words") {
                    ForEach(d.words.prefix(shown), id: \.entryId) { w in
                        NavigationLink(value: Route.entry(w.entryId)) { DeckWordRow(word: w) }
                            .swipeActions {
                                if w.state == .known {
                                    Button("Not known") { mark([w.entryId], known: false) }
                                } else {
                                    Button("Known") { mark([w.entryId], known: true) }.tint(.green)
                                }
                            }
                    }
                    if d.words.count > shown {
                        Button("Show more") { shown += 200 }
                    }
                }
            } else if loaded {
                ContentUnavailableView("Deck not found", systemImage: "rectangle.stack.badge.minus")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(detail?.summary.title ?? String(localized: "Deck"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Rename") {
                    newTitle = detail?.summary.title ?? ""
                    renaming = true
                }
                .disabled(detail == nil)
            }
        }
        .alert("Rename deck", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Save") {
                let title = newTitle
                Task {
                    try? await app.graph.decks.rename(id: deckId, title: title)
                    await load()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .task { await load() }
    }

    private func load() async {
        loadError = nil
        do {
            detail = try await app.graph.decks.deck(id: deckId)
            lessons = try await app.graph.deckLessons.settings()
        } catch {
            loadError = String(localized: "Couldn't load the deck: \(error.localizedDescription)")
        }
        loaded = true
    }

    private func mark(_ ids: [Int64], known: Bool) {
        let words = app.graph.knownWords
        let boxed = ids.map { KotlinLong(longLong: $0) }
        Task {
            do {
                if known {
                    try await words.markKnown(entryIds: boxed, source: "MANUAL")
                } else {
                    try await words.markUnknown(entryIds: boxed, source: "MANUAL")
                }
            } catch {
                note = String(localized: "Couldn't save that: \(error.localizedDescription)")
            }
            await load()
        }
    }
}

/// "Study this deck" and the mode switch (interleave with the kanji path, or deck only). Synced settings (D-154).
struct DeckLessonControls: View {
    @Environment(AppModel.self) private var app
    let deckId: String
    let settings: DeckLessonSettings?
    let reload: () async -> Void

    var body: some View {
        let isActive = settings?.deckId == deckId && (settings?.active ?? false)
        if isActive, let mode = settings?.mode {
            Picker("Lessons", selection: Binding(get: { mode }, set: { newMode in
                Task {
                    try? await app.graph.deckLessons.setMode(mode: newMode)
                    await reload()
                }
            })) {
                Text("Mixed with the kanji path").tag(DeckLessonMode.interleave)
                Text("This deck only").tag(DeckLessonMode.deckOnly)
            }
            Button("Stop studying this deck") {
                Task {
                    try? await app.graph.deckLessons.deactivate()
                    await reload()
                }
            }
        } else {
            Button("Study this deck") {
                Task {
                    try? await app.graph.deckLessons.activate(deckId: deckId, mode: .interleave)
                    await reload()
                }
            }
            .buttonStyle(.borderedProminent)
            Text("New lessons then come from this deck, most useful words first, mixed with the kanji path. Words you know are skipped.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// A Core frequency deck: its words page by page, mark known, and lessons from it.
struct CoreDeckView: View {
    @Environment(AppModel.self) private var app
    let deckId: String

    @State private var summary: FrequencyDeckSummary?
    @State private var words: [DeckWord] = []
    @State private var lessons: DeckLessonSettings?
    @State private var loadError: String?
    @State private var exhausted = false
    @State private var loading = false

    private let page: Int32 = 100

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await reload() } }
            }
            if let s = summary {
                Section {
                    LabeledContent("Words", value: Int(s.size).formatted())
                    LabeledContent("Known", value: Int(s.known).formatted())
                    LabeledContent("Learning", value: Int(s.learning).formatted())
                    if s.libraryCoverage > 0 {
                        Text("Covers \(Int((s.libraryCoverage * 100).rounded()).formatted())% of the words in your media").font(.caption)
                    }
                }
                Section("Lessons") {
                    DeckLessonControls(deckId: deckId, settings: lessons) { await reload() }
                }
            }
            Section("Words by frequency") {
                ForEach(words, id: \.entryId) { w in
                    NavigationLink(value: Route.entry(w.entryId)) {
                        HStack {
                            Text("\(Int(w.ord).formatted())").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(minWidth: 36, alignment: .trailing)
                            DeckWordRow(word: w)
                        }
                    }
                    .swipeActions {
                        if w.state == .known {
                            Button("Not known") { mark([w.entryId], known: false) }
                        } else {
                            Button("Known") { mark([w.entryId], known: true) }.tint(.green)
                        }
                    }
                }
                if !exhausted && !words.isEmpty {
                    Button(loading ? "Loading…" : "Load more") { Task { await more() } }.disabled(loading)
                }
            }
        }
        .navigationTitle(summary?.deck.title ?? String(localized: "Core deck"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
    }

    private func reload() async {
        loadError = nil
        do {
            summary = try await app.graph.decks.frequencyDecks().first { $0.deck.id == deckId }
            lessons = try await app.graph.deckLessons.settings()
            let count = max(words.count, Int(page))
            words = try await app.graph.decks.frequencyDeckWords(deckId: deckId, offset: 0, limit: Int32(count))
            exhausted = words.count < count
        } catch {
            loadError = String(localized: "Couldn't load the deck: \(error.localizedDescription)")
        }
    }

    private func more() async {
        loading = true
        defer { loading = false }
        do {
            let next = try await app.graph.decks.frequencyDeckWords(deckId: deckId, offset: Int32(words.count), limit: page)
            words += next
            exhausted = next.count < Int(page)
        } catch {
            loadError = String(localized: "Couldn't load the deck: \(error.localizedDescription)")
        }
    }

    private func mark(_ ids: [Int64], known: Bool) {
        let graph = app.graph
        let boxed = ids.map { KotlinLong(longLong: $0) }
        Task {
            if known {
                try? await graph.knownWords.markKnown(entryIds: boxed, source: "MANUAL")
            } else {
                try? await graph.knownWords.markUnknown(entryIds: boxed, source: "MANUAL")
            }
            await reload()
        }
    }
}
