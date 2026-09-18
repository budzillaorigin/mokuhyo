import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Reader library (BRIEF §5.8): paste, URL, EPUB, feeds and Aozora Bunko, with difficulty estimates.
struct ReaderLibraryView: View {
    @Environment(AppModel.self) private var app
    @State private var docs: [ReaderDocumentSummary] = []
    @State private var pasting = false
    @State private var addingUrl = false
    @State private var pickingEpub = false
    @State private var input = ""
    @State private var status: String?
    @State private var openDoc: String?
    @State private var importTask: Task<Void, Never>?
    @State private var retryImport: (() -> Void)?
    @State private var analysis: AnalysisProgress?

    var body: some View {
        List {
            Section {
                HStack {
                    Button("Paste") { input = ""; pasting = true }
                    Spacer()
                    Button("URL") { input = ""; addingUrl = true }
                    Spacer()
                    Button("EPUB") { pickingEpub = true }
                }
                .buttonStyle(.borderless)
                NavigationLink("Feeds", value: Route.feeds)
                NavigationLink("Aozora Bunko", value: Route.aozora)
                if let status { Text(status).font(.caption) }
                if importTask != nil {
                    if let analysis { ProgressView("Analyzing…", value: analysis.fraction) }
                    Button("Cancel import", role: .cancel) {
                        importTask?.cancel()
                        importTask = nil
                        status = nil
                    }
                } else if let retryImport {
                    Button("Retry") { retryImport() }
                }
            }
            if docs.isEmpty {
                Text("Paste text, add a web article or EPUB, or pick a public-domain book from Aozora Bunko.")
                    .foregroundStyle(.secondary)
            }
            ForEach(docs, id: \.id) { d in
                NavigationLink(value: Route.read(d.id)) {
                    VStack(alignment: .leading) {
                        Text(d.title).font(.japanese(size: 17)).lineLimit(2)
                        Text([d.levelLabel, d.knownRatio.map { "\(Int($0.doubleValue * 100))% known" }].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button("Delete", role: .destructive) { Task { try? await app.graph.reader.delete(id: d.id); await reload() } }
                }
            }
        }
        .navigationTitle("Reading")
        .navigationDestination(item: $openDoc) { ReaderView(docId: $0) }
        .task { await reload() }
        // Long imports report how far the analysis is (rule 15).
        .task { for await p in app.graph.reader.analysisProgress { analysis = p } }
        .sheet(isPresented: $pasting) {
            NavigationStack {
                TextEditor(text: $input).font(.japanese(size: 17)).padding()
                    .navigationTitle("Paste Japanese text")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { pasting = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Open") {
                                let text = input
                                pasting = false
                                run("Importing") { try await app.graph.reader.importText(text: text, title: nil) }
                            }
                            .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
            }
        }
        .alert("Read a web page", isPresented: $addingUrl) {
            TextField("https://…", text: $input).textInputAutocapitalization(.never).keyboardType(.URL)
            Button("Open") { let url = input; run("Fetching") { try await app.graph.reader.importUrl(url: url) } }
            Button("Cancel", role: .cancel) {}
        }
        .fileImporter(isPresented: $pickingEpub, allowedContentTypes: [UTType(filenameExtension: "epub") ?? .data, .data]) { result in
            guard case .success(let url) = result else { return }
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("import.epub")
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            try? FileManager.default.removeItem(at: target)
            guard (try? FileManager.default.copyItem(at: url, to: target)) != nil else { status = "Couldn't read that file."; return }
            run("Importing EPUB") { try await app.graph.reader.importEpub(path: target.path) }
        }
    }

    private func reload() async {
        do {
            docs = try await app.graph.reader.documents()
        } catch {
            status = "Couldn't load your library: \(error.localizedDescription)"
        }
    }

    /// Runs an import with progress, Cancel, and Retry on failure (rule 15, F-33).
    private func run(_ label: String, _ block: @escaping () async throws -> String) {
        status = "\(label)…"
        retryImport = nil
        importTask?.cancel()
        importTask = Task {
            do {
                let id = try await block()
                status = nil
                importTask = nil
                await reload()
                openDoc = id
            } catch {
                guard !Task.isCancelled else { return }
                status = "\(label) failed: \(error.localizedDescription)"
                importTask = nil
                retryImport = { run(label, block) }
            }
        }
    }
}

/// The reader: furigana modes, tap a word for a non-blocking popup, long-press a sentence for grammar + audio.
struct ReaderView: View {
    @Environment(AppModel.self) private var app
    let docId: String

    @State private var doc: ReaderDocument?
    @State private var ranges: [KotlinIntRange] = []
    @State private var paragraphs: [ReaderParagraph] = []
    @State private var mode: FuriganaMode = .unknownOnly
    @State private var selected: (ReaderToken, ReaderSentence)?
    @State private var summary: EntrySummary?
    @State private var sentencePanel: ReaderSentence?
    @State private var note: String?
    @State private var speech = Speech.shared
    @State private var loadError: String?
    @State private var translation: TranslationOutcome?
    @State private var translating: Task<Void, Never>?

    private let page = 20

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                if let loadError {
                    ContentUnavailableView {
                        Label("Couldn't open this text", systemImage: "doc.questionmark")
                    } description: {
                        Text(loadError)
                    } actions: {
                        Button("Retry") { Task { await open() } }.buttonStyle(.borderedProminent)
                    }
                } else if doc == nil {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let doc {
                    Text(doc.title).font(.japanese(size: 22, weight: .semibold))
                        .accessibilityAddTraits(.isHeader)
                        .japaneseSpeech()
                }
                ForEach(Array(paragraphs.enumerated()), id: \.offset) { i, p in
                    FlowLayout(spacing: 0) {
                        ForEach(Array(p.sentences.enumerated()), id: \.offset) { _, s in
                            ForEach(Array(s.tokens.enumerated()), id: \.offset) { _, t in tokenView(t, s) }
                        }
                    }
                    .onAppear {
                        if i >= paragraphs.count - 3 { Task { await loadMore() } }
                        Task { try? await app.graph.reader.setProgress(id: docId, offset: p.start) }
                    }
                }
            }
            .padding()
        }
        .safeAreaInset(edge: .bottom) { popup }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Furigana", selection: $mode) {
                        Text("Unknown words").tag(FuriganaMode.unknownOnly)
                        Text("All").tag(FuriganaMode.all)
                        Text("None").tag(FuriganaMode.none)
                    }
                } label: { Image(systemName: "textformat.size") }
                .accessibilityLabel(Text("Furigana"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    if speech.speakingRange != nil { speech.stop() } else if let doc, let p = paragraphs.first {
                        let body = doc.body as NSString
                        let length = min(body.length - Int(p.start), 2000)
                        speech.speak(body.substring(with: NSRange(location: Int(p.start), length: max(0, length))), startOffset: Int(p.start))
                    }
                } label: { Image(systemName: speech.speakingRange != nil ? "stop.fill" : "speaker.wave.2") }
                .accessibilityLabel("Read aloud")
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await open() }
        .onDisappear {
            speech.stop()
            translating?.cancel()
        }
    }

    /// Loads the document and its first page; failures show an error with Retry (F-33).
    private func open() async {
        loadError = nil
        do {
            guard let loaded = try await app.graph.reader.document(id: docId) else {
                loadError = String(localized: "This text is no longer in your library.")
                return
            }
            guard let analyzer = try await app.graph.reader.analyzer() else {
                loadError = String(localized: "Reading needs the dictionary pack, which isn't installed in this build.")
                return
            }
            doc = loaded
            ranges = analyzer.paragraphs(body: loaded.body)
            paragraphs = []
            await loadMore()
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func tokenView(_ t: ReaderToken, _ s: ReaderSentence) -> some View {
        let highlighted = speech.speakingRange.map { NSLocationInRange(Int(t.start), $0) } ?? false
        let show = t.showFurigana(mode: mode, learnerJlpt: nil)
        // F-36: ruby sits over each kanji run (the analyzer's segments from Furigana.align), not over
        // the whole token, so okurigana such as the べる of 食べる stay bare.
        let segments = t.furigana.isEmpty ? [FuriganaSegment(ruby: t.surface, rt: t.reading)] : t.furigana
        return HStack(alignment: .bottom, spacing: 0) {
            ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                VStack(spacing: 0) {
                    Text(show ? (seg.rt ?? " ") : " ")
                        .font(.japanese(size: 10, relativeTo: .caption2)).foregroundStyle(.secondary)
                        .lineLimit(1)
                        .fixedSize()
                    Text(seg.ruby)
                        .font(.japanese(size: 20))
                        .foregroundStyle(t.isWord && !t.known ? Color.accentColor : Color.primary)
                }
            }
        }
        .background(highlighted ? Color.yellow.opacity(0.3) : .clear)
        .onTapGesture { if t.isWord { select(t, s) } }
        .onLongPressGesture {
            if sentencePanel?.start != s.start { closeSentence() }
            sentencePanel = s
        }
        // VoiceOver: one element per token, read in Japanese, without the furigana line.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(t.surface)
        .accessibilityAddTraits(t.isWord ? .isButton : [])
        .accessibilityHint(t.isWord ? Text("Double-tap to look up this word.") : Text(""))
        .japaneseSpeech()
    }

    @ViewBuilder
    private var popup: some View {
        if let selected {
            let (token, sentence) = selected
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .lastTextBaseline) {
                    Text(summary?.headword ?? token.dictionaryForm ?? token.surface).font(.japanese(size: 24)).japaneseSpeech()
                    Text(summary?.reading ?? token.reading ?? "").font(.japanese(size: 14)).japaneseSpeech()
                    if let stage = token.stage { TagView(stage.label) }
                }
                if !token.deinflection.isEmpty { Text("← " + token.deinflection.joined(separator: " ← ")).font(.caption2) }
                Text(summary?.glossPreview ?? "").font(.subheadline).lineLimit(3)
                if let note { Text(note).font(.caption).foregroundStyle(.tint) }
                HStack {
                    Button("Add to reviews") {
                        Task { note = (try? await app.graph.reader.mine(token: token, sentence: sentence)) != nil ? "Added with this sentence as context." : "Couldn't add." }
                    }
                    .buttonStyle(.borderedProminent)
                    if let id = token.entryId { NavigationLink("Details", value: Route.entry(id.int64Value)).buttonStyle(.bordered) }
                    Spacer()
                    Button("Close") { self.selected = nil }
                }
            }
            .padding()
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding()
        } else if let s = sentencePanel {
            VStack(alignment: .leading, spacing: 6) {
                Text(s.text).font(.japanese(size: 17))
                if !s.grammarPointIds.isEmpty {
                    Text("Grammar in this sentence").font(.caption.weight(.semibold))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(s.grammarPointIds.prefix(8), id: \.self) { id in
                                NavigationLink(id.split(separator: "-").dropFirst().joined(separator: " "), value: Route.grammarPoint(id))
                                    .buttonStyle(.bordered)
                            }
                        }
                    }
                }
                translationView(s)
                HStack {
                    Button("Listen") { speech.speak(s.text, startOffset: Int(s.start)) }.buttonStyle(.borderedProminent)
                    if translation == nil && translating == nil {
                        Button("Translate") { translate(s) }.buttonStyle(.bordered)
                    }
                    Spacer()
                    Button("Close") { closeSentence() }
                }
            }
            .padding()
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding()
        }
    }

    /// `translate_sentence` through the AI gateway (F-08): labeled AI-generated, or an honest "set up AI" state.
    @ViewBuilder
    private func translationView(_ s: ReaderSentence) -> some View {
        if translating != nil {
            HStack {
                ProgressView()
                Text("Translating…").font(.caption)
                Spacer()
                Button("Cancel") {
                    translating?.cancel()
                    translating = nil
                }
                .font(.caption)
            }
        } else if let t = translation {
            if t.needsSetup {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Enable an AI engine to translate").font(.subheadline.weight(.semibold))
                    if let reason = t.unavailable { Text(reason).font(.caption).foregroundStyle(.secondary) }
                    NavigationLink("AI settings", value: Route.aiSettings).font(.caption.weight(.semibold))
                }
            } else if let engine = t.engine {
                VStack(alignment: .leading, spacing: 4) {
                    AIBadge(engine: engine)
                    Text(t.translation).font(.subheadline).textSelection(.enabled)
                    if !t.literal.isEmpty { Text("Literally: \(t.literal)").font(.caption).foregroundStyle(.secondary) }
                    if !t.notes.isEmpty { Text(t.notes).font(.caption).foregroundStyle(.secondary) }
                }
            } else {
                HStack {
                    Text("Couldn't translate: \(t.unavailable ?? String(localized: "the model failed"))")
                        .font(.caption).foregroundStyle(.orange)
                    Spacer()
                    Button("Retry") { translate(s) }.font(.caption)
                }
            }
        }
    }

    private func translate(_ s: ReaderSentence) {
        translating?.cancel()
        translation = nil
        let ai = app.graph.ai
        let text = s.text
        translating = Task {
            let outcome: TranslationOutcome
            do {
                outcome = try await SwiftSupport.shared.translate(ai: ai, text: text)
            } catch {
                outcome = TranslationOutcome(translation: "", literal: "", notes: "", engine: nil, unavailable: error.localizedDescription, needsSetup: false)
            }
            guard !Task.isCancelled else { return }
            translation = outcome
            translating = nil
        }
    }

    private func closeSentence() {
        translating?.cancel()
        translating = nil
        translation = nil
        sentencePanel = nil
    }

    private func select(_ token: ReaderToken, _ sentence: ReaderSentence) {
        selected = (token, sentence)
        note = nil
        summary = nil
        guard let id = token.entryId else { return }
        Task { summary = (try? await app.graph.dictionary()?.summaries(ids: [id]))?.first }
    }

    private func loadMore() async {
        guard let doc, let analyzer = try? await app.graph.reader.analyzer(), paragraphs.count < ranges.count else { return }
        let next = ranges[paragraphs.count..<min(ranges.count, paragraphs.count + page)]
        for range in next {
            if let p = try? await analyzer.paragraph(body: doc.body, range: range, ruby: [], known: nil) { paragraphs.append(p) }
        }
    }
}

struct FeedsView: View {
    @Environment(AppModel.self) private var app
    @State private var feeds: [ReaderFeed] = []
    @State private var items: [FeedItem] = []
    @State private var url = ""
    @State private var status: String?
    @State private var openDoc: String?

    var body: some View {
        List {
            Section {
                TextField("Feed URL (RSS or Atom)", text: $url).textInputAutocapitalization(.never).keyboardType(.URL)
                Button("Add feed") {
                    let value = url.trimmingCharacters(in: .whitespaces)
                    Task {
                        do {
                            let added = try await app.graph.reader.feeds.add(url: value)
                            items = added.second as? [FeedItem] ?? []
                            url = ""
                            feeds = (try? await app.graph.reader.feeds.feeds()) ?? []
                            status = nil
                        } catch { status = "Couldn't add: \(error.localizedDescription)" }
                    }
                }
                .disabled(url.isEmpty)
                if let status { Text(status).font(.caption) }
            } footer: {
                Text("Add any RSS/Atom feed (e.g. NHK News Web Easy). Articles are fetched to this device only.")
            }
            Section("Feeds") {
                ForEach(feeds, id: \.id) { f in
                    Button(f.title) { Task { items = (try? await app.graph.reader.feeds.items(feed: f)) ?? [] } }
                }
            }
            Section("Articles") {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    Button {
                        Task { if let id = try? await app.graph.reader.importFeedItem(item: item) { openDoc = id } }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(item.title).font(.japanese(size: 16))
                            if let p = item.published { Text(p).font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
        }
        .navigationTitle("Feeds")
        .navigationDestination(item: $openDoc) { ReaderView(docId: $0) }
        .task { feeds = (try? await app.graph.reader.feeds.feeds()) ?? [] }
    }
}

struct AozoraView: View {
    @Environment(AppModel.self) private var app
    @State private var works: [AozoraWork]?
    @State private var query = ""
    @State private var status: String?
    @State private var openDoc: String?

    var body: some View {
        List {
            if works == nil {
                Button("Load catalogue") {
                    status = "Downloading catalogue…"
                    Task {
                        works = try? await app.graph.reader.aozora.catalogue(zipUrl: "https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip")
                        status = works == nil ? "Couldn't download the catalogue." : nil
                    }
                }
            }
            if let status { Text(status).font(.caption) }
            if let works {
                ForEach(works.filter { query.isEmpty || $0.title.contains(query) || $0.author.contains(query) }.prefix(200), id: \.id) { w in
                    Button {
                        status = "Importing…"
                        Task {
                            do { openDoc = try await app.graph.reader.importAozora(work: w); status = nil } catch { status = "Import failed: \(error.localizedDescription)" }
                        }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(w.title).font(.japanese(size: 16))
                            Text(w.author).font(.japanese(size: 13)).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "Title or author")
        .navigationTitle("Aozora Bunko")
        .navigationDestination(item: $openDoc) { ReaderView(docId: $0) }
    }
}
