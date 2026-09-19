import PhotosUI
import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Reader library (BRIEF §5.8, BRIEF_V2 §6.1/§6.4): paste, URL, EPUB, screenshots, feeds and Aozora Bunko, with each
/// text's coverage and difficulty, and a sort by coverage.
struct ReaderLibraryView: View {
    private enum Sort: String, CaseIterable, Identifiable {
        case recent = "Recent"
        case coverage = "Coverage"
        var id: String { rawValue }
    }

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
    @State private var sort = Sort.recent
    @State private var coverage: [String: LibraryCoverage] = [:]
    @State private var ordered: [LibraryCoverage] = []
    @State private var profiling: Task<Void, Never>?
    @State private var profileDone = 0
    @State private var profileTotal = 0
    @State private var shots: [PhotosPickerItem] = []

    private var unmeasured: Int { ordered.filter { $0.coverage == nil }.count }

    var body: some View {
        List {
            Section {
                HStack {
                    Button("Paste") { input = ""; pasting = true }
                    Spacer()
                    Button("URL") { input = ""; addingUrl = true }
                    Spacer()
                    Button("EPUB") { pickingEpub = true }
                    Spacer()
                    PhotosPicker(selection: $shots, maxSelectionCount: 30, matching: .images) {
                        Text("Screenshots")
                    }
                }
                .buttonStyle(.borderless)
                NavigationLink("Feeds", value: Route.feeds)
                NavigationLink("Aozora Bunko", value: Route.aozora)
                NavigationLink("Decks from your texts", value: Route.decks)
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
            if !docs.isEmpty {
                Section {
                    Picker("Sort", selection: $sort) {
                        ForEach(Sort.allCases) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    if profiling != nil {
                        CancellableProgress(
                            label: String(localized: "Measuring \(profileDone.formatted()) of \(profileTotal.formatted())…"),
                            fraction: profileTotal > 0 ? Double(profileDone) / Double(profileTotal) : nil
                        ) {
                            profiling?.cancel()
                            profiling = nil
                        }
                    } else if unmeasured > 0 {
                        Button("Measure coverage of \(unmeasured.formatted()) more texts") { profileLibrary() }
                            .font(.caption)
                    }
                }
            }
            if docs.isEmpty {
                Text("Paste text, add a web article, EPUB or screenshots, or pick a public-domain book from Aozora Bunko.")
                    .foregroundStyle(.secondary)
            }
            ForEach(sort == .recent ? docs : ordered.map(\.document), id: \.id) { d in
                NavigationLink(value: Route.read(d.id)) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(d.title).font(.japanese(size: 17)).lineLimit(2)
                        if let c = coverage[d.id], let cov = c.coverage {
                            HStack(spacing: 6) {
                                Text("\(Int(cov.knownPercent).formatted())% of words known").font(.caption).foregroundStyle(.secondary)
                                if let difficulty = c.difficulty { DifficultyBadge(score: difficulty) }
                            }
                        } else {
                            Text([d.levelLabel, d.knownRatio.map { String(localized: "\(Int($0.doubleValue * 100).formatted())% known") }].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
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
        .onDisappear { profiling?.cancel() }
        .onChange(of: shots) { _, items in
            guard !items.isEmpty else { return }
            shots = []
            importScreenshots(items)
        }
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
            // Stored profiles only: cheap. Texts never measured come last until "Measure" profiles them (D-153).
            let rows = try await app.graph.coverage.librarySortedByCoverage()
            ordered = rows
            coverage = Dictionary(rows.map { ($0.document.id, $0) }, uniquingKeysWith: { a, _ in a })
        } catch {
            status = "Couldn't load your library: \(error.localizedDescription)"
        }
    }

    /// Profiles the texts that have no coverage yet, one at a time with progress and Cancel (rule 15).
    private func profileLibrary() {
        profileDone = 0
        profileTotal = unmeasured
        let service = app.graph.coverage
        profiling = Task {
            do {
                _ = try await service.profileLibrary { done, total in
                    let d = Int(truncating: done)
                    let t = Int(truncating: total)
                    Task { @MainActor in
                        profileDone = d
                        profileTotal = t
                    }
                }
            } catch {
                if !Task.isCancelled { status = String(localized: "Couldn't measure the library: \(error.localizedDescription)") }
            }
            profiling = nil
            await reload()
        }
    }

    /// Screenshots → on-device OCR → one document with the pictures as page images (§6.4).
    private func importScreenshots(_ items: [PhotosPickerItem]) {
        let graph = app.graph
        run(String(localized: "Reading screenshots")) {
            try await ScreenshotImporter.importPictures(items, graph: graph) { done, total in
                status = String(localized: "Reading picture \(min(done + 1, total).formatted()) of \(total.formatted())…")
            }
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

/// Furigana choices; "above my level" uses the learner's level and known kanji (G-07, `LearnerFurigana`).
enum FuriganaChoice: Hashable {
    case unknownOnly, aboveLevel, all, none
}

/// A span of the document picked for an annotation (character offsets in the body, D-164).
private struct TextSelection: Equatable {
    var start: Int32
    var end: Int32
    var grammarIds: [String]
}

/// The reader: furigana modes, tap a word for a non-blocking popup, long-press a sentence for grammar + audio,
/// pitch-accent marks and comprehension questions (G-07). Phase 11: the coverage card, 1T sentences, "mark known",
/// the document's word list and drill, annotations, page images, a deck from the text, and the immersion log.
struct ReaderView: View {
    @Environment(AppModel.self) private var app
    let docId: String

    @State private var doc: ReaderDocument?
    @State private var ranges: [KotlinIntRange] = []
    @State private var paragraphs: [ReaderParagraph] = []
    @State private var furigana: FuriganaChoice = .unknownOnly
    /// "Only above my level" (G-07): the learner's JLPT level and known kanji, loaded once.
    @State private var learner: LearnerFuriganaFilter?
    @State private var showPitch = false
    /// Pitch marks per token start offset ("は↑し↓", or "?" when the accent is unknown).
    @State private var pitchMarks: [Int32: String] = [:]
    @State private var showQuestions = false
    @State private var selected: (ReaderToken, ReaderSentence)?
    @State private var summary: EntrySummary?
    @State private var sentencePanel: ReaderSentence?
    @State private var note: String?
    @State private var speech = Speech.shared
    @State private var loadError: String?
    @State private var translation: TranslationOutcome?
    @State private var translating: Task<Void, Never>?
    // Phase 11 (BRIEF_V2 §6.1, §6.4, §6.11)
    @State private var ticket: ImmersionTicket?
    @State private var coverage: DocumentCoverage?
    @State private var coverageProgress: Double?
    @State private var coverageTask: Task<Void, Never>?
    @State private var coverageError: String?
    @State private var oneTarget: [OneTargetSentence]?
    @State private var oneTargetTask: Task<Void, Never>?
    @State private var oneTargetProgress: Double?
    @State private var highlightOneTarget = false
    @State private var showOneTargetList = false
    @State private var annotations: [ReaderAnnotation] = []
    @State private var annotating = false
    @State private var anchor: TextSelection?
    @State private var selection: TextSelection?
    @State private var noteDraft = ""
    @State private var showNotes = false
    @State private var showWords = false
    @State private var creatingDeck: DeckSource?
    @State private var pageImages: [PageImageFile] = []
    @State private var markedKnown: Set<Int64> = []
    @State private var scrollTarget: Int?

    private let page = 20

    var body: some View {
        ScrollViewReader { proxy in
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
                        if !pageImages.isEmpty { PageImagesStrip(pages: pageImages) }
                        coverageView
                        if oneTargetTask != nil {
                            CancellableProgress(label: String(localized: "Finding sentences with one new word…"), fraction: oneTargetProgress) {
                                oneTargetTask?.cancel()
                                oneTargetTask = nil
                                highlightOneTarget = false
                            }
                        }
                        if annotating {
                            Label("Tap the first and the last word of a phrase to annotate it.", systemImage: "pencil.tip")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    ForEach(Array(paragraphs.enumerated()), id: \.offset) { i, p in
                        FlowLayout(spacing: 0) {
                            ForEach(Array(p.sentences.enumerated()), id: \.offset) { _, s in
                                ForEach(Array(s.tokens.enumerated()), id: \.offset) { _, t in tokenView(t, s) }
                            }
                        }
                        .id(i)
                        .onAppear {
                            if i >= paragraphs.count - 3 { Task { await loadMore() } }
                            Task { try? await app.graph.reader.setProgress(id: docId, offset: p.start) }
                        }
                    }
                }
                .padding()
            }
            .onChange(of: scrollTarget) { _, target in
                if let target {
                    withAnimation { proxy.scrollTo(target, anchor: .top) }
                    scrollTarget = nil
                }
            }
        }
        .safeAreaInset(edge: .bottom) { popup }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    annotating.toggle()
                    anchor = nil
                    selection = nil
                } label: { Image(systemName: annotating ? "pencil.tip.crop.circle.fill" : "pencil.tip.crop.circle") }
                .accessibilityLabel(Text("Annotate"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Furigana", selection: $furigana) {
                        Text("Unknown words").tag(FuriganaChoice.unknownOnly)
                        Text("Only above my level").tag(FuriganaChoice.aboveLevel)
                        Text("All").tag(FuriganaChoice.all)
                        Text("None").tag(FuriganaChoice.none)
                    }
                    Toggle("Pitch accent marks", isOn: $showPitch)
                    Toggle("Highlight one-new-word sentences", isOn: $highlightOneTarget)
                    Button("One-new-word sentences") {
                        showOneTargetList = true
                        loadOneTarget()
                    }
                    Button("Words in this text") { showWords = true }
                    Button("Notes") { showNotes = true }
                    Button("Comprehension questions") { showQuestions = true }
                    Button("Make a deck from this text") {
                        creatingDeck = .document(id: docId, title: doc?.title ?? "")
                    }
                } label: { Image(systemName: "textformat.size") }
                .accessibilityLabel(Text("Reading options"))
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
        .task(id: furigana) {
            if furigana == .aboveLevel && learner == nil { learner = try? await SwiftSupport.shared.learnerFurigana(graph: app.graph) }
        }
        .task(id: showPitch) { if showPitch { await loadPitch(paragraphs) } }
        .onChange(of: highlightOneTarget) { _, on in if on { loadOneTarget() } }
        .onAppear { startTicket() }
        .sheet(isPresented: $showQuestions) {
            if let doc {
                NavigationStack { ReadingQuestionsSheet(document: doc, jlpt: learner?.jlpt.map { Int($0.intValue) }) }
                    .environment(app)
            }
        }
        .sheet(isPresented: $showOneTargetList) {
            NavigationStack {
                if let oneTarget {
                    OneTargetListView(sentences: oneTarget) { s in jump(to: s) }
                } else {
                    VStack(spacing: 12) {
                        CancellableProgress(label: String(localized: "Finding sentences with one new word…"), fraction: oneTargetProgress) {
                            oneTargetTask?.cancel()
                            oneTargetTask = nil
                            showOneTargetList = false
                        }
                    }
                    .padding()
                }
            }
            .environment(app)
        }
        .sheet(isPresented: $showWords) {
            NavigationStack { DocumentWordsView(docId: docId) }.environment(app)
        }
        .sheet(isPresented: $showNotes) {
            if let doc {
                NavigationStack { AnnotationsListView(document: doc) { Task { await loadAnnotations() } } }.environment(app)
            }
        }
        .sheet(item: $creatingDeck) { source in
            NavigationStack { DeckPreviewView(source: source) { _ in creatingDeck = nil } }.environment(app)
        }
        .onDisappear {
            speech.stop()
            translating?.cancel()
            coverageTask?.cancel()
            oneTargetTask?.cancel()
            stopTicket()
        }
    }

    // MARK: Coverage, immersion, 1T

    @ViewBuilder
    private var coverageView: some View {
        if let coverage {
            CoverageCard(coverage: coverage)
        } else if let coverageError {
            ErrorRetryView(message: coverageError) { loadCoverage() }
        } else if coverageTask != nil {
            CancellableProgress(label: String(localized: "Measuring coverage…"), fraction: coverageProgress) {
                coverageTask?.cancel()
                coverageTask = nil
            }
        }
    }

    /// Reading time goes into the immersion log (§6.11, D-194): from opening the text to leaving it.
    private func startTicket() {
        guard ticket == nil, let doc else { return }
        ticket = app.graph.immersion.start(source: .reader, mode: .active, ref: docId, title: doc.title)
    }

    private func stopTicket() {
        guard let ticket else { return }
        self.ticket = nil
        let log = app.graph.immersion
        Task { _ = try? await log.stop(ticket: ticket) }
    }

    /// "You know X% of the words…" for this text (tokenized once, then cached by the shared code, D-153).
    private func loadCoverage() {
        coverageError = nil
        coverageProgress = nil
        let service = app.graph.coverage
        let id = docId
        coverageTask?.cancel()
        coverageTask = Task {
            do {
                let result = try await service.documentCoverage(documentId: id) { p in
                    let f = p.doubleValue
                    Task { @MainActor in coverageProgress = f }
                }
                guard !Task.isCancelled else { return }
                coverage = result
            } catch {
                if !Task.isCancelled { coverageError = String(localized: "Couldn't measure coverage: \(error.localizedDescription)") }
            }
            coverageTask = nil
        }
    }

    private func loadOneTarget() {
        guard oneTarget == nil, oneTargetTask == nil else { return }
        oneTargetProgress = nil
        let service = app.graph.coverage
        let id = docId
        oneTargetTask = Task {
            do {
                let found = try await service.oneTargetSentences(documentId: id, limit: 30) { p in
                    let f = p.doubleValue
                    Task { @MainActor in oneTargetProgress = f }
                }
                guard !Task.isCancelled else { return }
                oneTarget = found
            } catch {
                if !Task.isCancelled { note = String(localized: "Couldn't find the sentences: \(error.localizedDescription)") }
            }
            oneTargetTask = nil
        }
    }

    /// Scrolls to the paragraph holding [s], loading pages until it is there.
    private func jump(to s: OneTargetSentence) {
        highlightOneTarget = true
        Task {
            var guardCount = 0
            while !paragraphs.contains(where: { $0.end > s.start }) && paragraphs.count < ranges.count && guardCount < 50 {
                await loadMore()
                guardCount += 1
            }
            if let i = paragraphs.firstIndex(where: { $0.start <= s.start && $0.end > s.start }) { scrollTarget = i }
        }
    }

    private func oneTargetFor(_ t: ReaderToken) -> OneTargetSentence? {
        guard highlightOneTarget, let oneTarget else { return nil }
        return oneTarget.first { $0.start <= t.start && t.start < $0.end }
    }

    // MARK: Annotations

    private func loadAnnotations() async {
        guard let doc else { return }
        annotations = ((try? await app.graph.reader.annotations.forDocument(document: doc)) ?? []).filter { !$0.detached }
    }

    private func annotationsOn(_ t: ReaderToken) -> [ReaderAnnotation] {
        annotations.filter { $0.start < t.end && $0.end > t.start }
    }

    /// First tap sets the start, second tap the end (either order); a third tap starts over.
    private func tapForAnnotation(_ t: ReaderToken, _ s: ReaderSentence) {
        if let a = anchor, selection == nil || selection == a {
            let range = TextSelection(start: min(a.start, t.start), end: max(a.end, t.end), grammarIds: Array(Set(a.grammarIds + s.grammarPointIds)).sorted())
            selection = range
            anchor = nil
        } else {
            let one = TextSelection(start: t.start, end: t.end, grammarIds: s.grammarPointIds)
            anchor = one
            selection = one
        }
        noteDraft = ""
    }

    private func quote(_ sel: TextSelection) -> String {
        guard let doc else { return "" }
        let body = doc.body as NSString
        let start = max(0, min(Int(sel.start), body.length))
        let end = max(start, min(Int(sel.end), body.length))
        return body.substring(with: NSRange(location: start, length: end - start))
    }

    private func annotate(_ kind: AnnotationKind, note text: String = "", grammarId: String? = nil) {
        guard let doc, let sel = selection else { return }
        Task {
            do {
                _ = try await app.graph.reader.annotations.add(
                    document: doc, kind: kind, start: sel.start, end: sel.end, note: text, color: nil, grammarPointId: grammarId
                )
                selection = nil
                anchor = nil
                await loadAnnotations()
            } catch {
                note = String(localized: "Couldn't save the annotation: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Tokens

    private func showsFurigana(_ t: ReaderToken) -> Bool {
        switch furigana {
        case .aboveLevel: learner?.show(token: t) ?? t.showFurigana(mode: .unknownOnly, learnerJlpt: nil)
        case .unknownOnly: t.showFurigana(mode: .unknownOnly, learnerJlpt: nil)
        case .all: t.showFurigana(mode: .all, learnerJlpt: nil)
        case .none: false
        }
    }

    /// Pitch marks from the dictionary's accent table for the words of [paragraphs] (G-07 overlay).
    private func loadPitch(_ paragraphs: [ReaderParagraph]) async {
        for p in paragraphs {
            for sentence in p.sentences {
                guard sentence.tokens.contains(where: { pitchMarks[$0.start] == nil && $0.isWord }) else { continue }
                let pitches = (try? await app.graph.reader.pitch(sentence: sentence)) ?? []
                for pitch in pitches {
                    pitchMarks[pitch.start] = SwiftSupport.shared.pitchMarks(pitch: pitch) ?? "?"
                }
            }
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
            startTicket()
            ranges = analyzer.paragraphs(body: loaded.body)
            paragraphs = []
            await loadMore()
            await loadAnnotations()
            pageImages = (try? await app.graph.reader.screenshots.pageImages(documentId: docId)) ?? []
            if coverage == nil && coverageTask == nil { loadCoverage() }
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func isUnknown(_ t: ReaderToken) -> Bool {
        guard t.isWord, !t.known else { return false }
        if let id = t.entryId?.int64Value, markedKnown.contains(id) { return false }
        return true
    }

    private func tokenView(_ t: ReaderToken, _ s: ReaderSentence) -> some View {
        let highlighted = speech.speakingRange.map { NSLocationInRange(Int(t.start), $0) } ?? false
        let show = showsFurigana(t)
        // F-36: ruby sits over each kanji run (the analyzer's segments from Furigana.align), not over
        // the whole token, so okurigana such as the べる of 食べる stay bare.
        let segments = t.furigana.isEmpty ? [FuriganaSegment(ruby: t.surface, rt: t.reading)] : t.furigana
        let marks = showPitch && t.isWord ? pitchMarks[t.start] : nil
        let notes = annotationsOn(t)
        let selected = selection.map { $0.start <= t.start && t.end <= $0.end } ?? false
        let target = oneTargetFor(t)
        let isTarget = target.map { $0.start + $0.targetStart <= t.start && t.start < $0.start + $0.targetEnd } ?? false
        let fill: Color = {
            if highlighted { return Color.yellow.opacity(0.3) }
            if selected { return Color.accentColor.opacity(0.25) }
            if let h = notes.first(where: { $0.kind == .highlight }) { return AnnotationStyle.color(h.color, kind: .highlight).opacity(0.35) }
            if isTarget { return Color.mint.opacity(0.35) }
            if target != nil { return Color.mint.opacity(0.12) }
            return .clear
        }()
        let box = notes.first { $0.kind == .box }
        let grammar = notes.first { $0.kind == .grammar }
        let hasNote = notes.contains { $0.kind == .note || !$0.note.isEmpty }
        return VStack(spacing: 0) {
            HStack(alignment: .bottom, spacing: 0) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                    VStack(spacing: 0) {
                        Text(show ? (seg.rt ?? " ") : " ")
                            .font(.japanese(size: 10, relativeTo: .caption2)).foregroundStyle(.secondary)
                            .lineLimit(1)
                            .fixedSize()
                        Text(seg.ruby)
                            .font(.japanese(size: 20))
                            .foregroundStyle(isUnknown(t) ? Color.accentColor : Color.primary)
                    }
                }
            }
            if showPitch {
                // ↑ rise, ↓ drop, "?" unknown (inflected or not in the accent table; never guessed).
                Text(marks ?? " ")
                    .font(.japanese(size: 9, relativeTo: .caption2)).foregroundStyle(.orange)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .background(fill)
        .overlay {
            if let box { Rectangle().stroke(AnnotationStyle.color(box.color, kind: .box), lineWidth: 1) }
        }
        .overlay(alignment: .bottom) {
            if let grammar { Rectangle().fill(AnnotationStyle.color(grammar.color, kind: .grammar)).frame(height: 2) }
        }
        .overlay(alignment: .topTrailing) {
            if hasNote { Circle().fill(Color.orange).frame(width: 5, height: 5) }
        }
        .onTapGesture {
            if annotating {
                tapForAnnotation(t, s)
            } else if t.isWord {
                select(t, s)
            }
        }
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
        if annotating, let sel = selection {
            annotationPanel(sel)
        } else if let selected {
            let (token, sentence) = selected
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .lastTextBaseline) {
                    Text(summary?.headword ?? token.dictionaryForm ?? token.surface).font(.japanese(size: 24)).japaneseSpeech()
                    Text(summary?.reading ?? token.reading ?? "").font(.japanese(size: 14)).japaneseSpeech()
                    if let stage = token.stage { TagView(SharedText.stage(stage)) }
                }
                if !token.deinflection.isEmpty { Text("← " + token.deinflection.joined(separator: " ← ")).font(.caption2) }
                Text(summary?.glossPreview ?? "").font(.subheadline).lineLimit(3)
                if let note { Text(note).font(.caption).foregroundStyle(.tint) }
                HStack {
                    Button("Add to reviews") {
                        Task { note = (try? await app.graph.reader.mine(token: token, sentence: sentence)) != nil ? String(localized: "Added with this sentence as context.") : String(localized: "Couldn't add.") }
                    }
                    .buttonStyle(.borderedProminent)
                    if let id = token.entryId?.int64Value, !token.known {
                        Button(markedKnown.contains(id) ? "Not known" : "Known") { toggleKnown(id) }
                            .buttonStyle(.bordered)
                    }
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

    /// Highlight, box, note or grammar span over the selected words (D-164).
    private func annotationPanel(_ sel: TextSelection) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(quote(sel)).font(.japanese(size: 17)).lineLimit(3)
            if anchor != nil {
                Text("Tap another word to extend the selection.").font(.caption2).foregroundStyle(.secondary)
            }
            HStack {
                Button { annotate(.highlight) } label: { Label("Highlight", systemImage: "highlighter") }
                Button { annotate(.box) } label: { Label("Box", systemImage: "rectangle.dashed") }
                if !sel.grammarIds.isEmpty {
                    Menu {
                        ForEach(sel.grammarIds, id: \.self) { id in
                            Button(id.split(separator: "-").dropFirst().joined(separator: " ")) { annotate(.grammar, grammarId: id) }
                        }
                    } label: {
                        Label("Grammar", systemImage: "text.book.closed")
                    }
                } else {
                    Button { annotate(.grammar) } label: { Label("Grammar", systemImage: "text.book.closed") }
                }
            }
            .buttonStyle(.bordered)
            .font(.caption)
            HStack {
                TextField("Note", text: $noteDraft)
                    .textFieldStyle(.roundedBorder)
                Button("Save note") { annotate(.note, note: noteDraft) }
                    .disabled(noteDraft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if let note { Text(note).font(.caption).foregroundStyle(.orange) }
            HStack {
                Spacer()
                Button("Cancel") {
                    selection = nil
                    anchor = nil
                }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .padding()
    }

    /// "Mark known" (§6.1): the word counts as known for coverage and 1T without an SRS item (D-151). Synced.
    private func toggleKnown(_ id: Int64) {
        let words = app.graph.knownWords
        let wasKnown = markedKnown.contains(id)
        Task {
            do {
                if wasKnown {
                    try await words.markUnknown(entryIds: [KotlinLong(longLong: id)], source: "MANUAL")
                    markedKnown.remove(id)
                    note = String(localized: "No longer marked known.")
                } else {
                    try await words.markKnown(entryIds: [KotlinLong(longLong: id)], source: "MANUAL")
                    markedKnown.insert(id)
                    note = String(localized: "Marked known. It won't be counted as new in coverage.")
                }
                coverage = nil
                oneTarget = nil
                loadCoverage()
            } catch {
                note = String(localized: "Couldn't save that: \(error.localizedDescription)")
            }
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

    /// Opens the word popup and adds the word to this document's list (D-165) once its gloss is known.
    private func select(_ token: ReaderToken, _ sentence: ReaderSentence) {
        selected = (token, sentence)
        note = nil
        summary = nil
        let graph = app.graph
        let id = docId
        Task {
            if let entryId = token.entryId {
                summary = (try? await graph.dictionary()?.summaries(ids: [entryId]))?.first
            }
            try? await graph.reader.vocabulary.recordLookup(documentId: id, token: token, sentence: sentence, gloss: summary?.glossPreview ?? "")
        }
    }

    private func loadMore() async {
        guard let doc, let analyzer = try? await app.graph.reader.analyzer(), paragraphs.count < ranges.count else { return }
        let next = ranges[paragraphs.count..<min(ranges.count, paragraphs.count + page)]
        for range in next {
            // Aozora and graded-passage ruby are authoritative readings (D-114).
            if let p = try? await analyzer.paragraph(body: doc.body, range: range, ruby: doc.ruby, known: nil) { paragraphs.append(p) }
        }
        if showPitch { await loadPitch(paragraphs) }
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
