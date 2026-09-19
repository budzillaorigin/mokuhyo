import Shared
import SwiftUI

// Writing studio (BRIEF_V2 §6.13; D-275, D-306): synced drafts, and on demand corrections (the learner's model,
// labeled), the rule-based register check, thesaurus suggestions and readability. Checking stays in shared code.

/// Localized name of a writing-studio register code (CASUAL | POLITE | FORMAL).
func studioRegisterLabel(_ code: String) -> String {
    switch code {
    case "CASUAL": String(localized: "Casual")
    case "POLITE": String(localized: "Polite")
    case "FORMAL": String(localized: "Formal")
    default: String(localized: "No target")
    }
}

/// The learner's drafts.
struct WritingStudioView: View {
    @Environment(AppModel.self) private var app
    @State private var drafts: [StudioDraft]?
    @State private var error: String?
    @State private var creating = false
    @State private var openDraftId: String?
    @State private var pendingDelete: StudioDraft?

    var body: some View {
        List {
            Section {
                Button {
                    create()
                } label: {
                    Label(creating ? "Creating…" : "New draft", systemImage: "square.and.pencil")
                }
                .disabled(creating)
            } footer: {
                Text("Write in Japanese, then check corrections, register, expressions and readability. Drafts sync.")
            }
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            }
            if let drafts {
                if drafts.isEmpty {
                    Text("No drafts yet.").foregroundStyle(.secondary)
                }
                ForEach(drafts, id: \.id) { d in
                    NavigationLink(value: Route.writingDraft(d.id)) { row(d) }
                        .swipeActions {
                            Button(role: .destructive) { pendingDelete = d } label: { Label("Delete", systemImage: "trash") }
                        }
                }
            } else if error == nil {
                ProgressView()
            }
        }
        .navigationTitle("Writing studio")
        .navigationDestination(item: $openDraftId) { id in WritingDraftView(draftId: id) }
        .confirmationDialog(
            "Delete this draft?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let d = pendingDelete { delete(d) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        }
        .task { await load() }
    }

    private func row(_ d: StudioDraft) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                if d.title.isEmpty {
                    Text("Untitled").font(.headline).foregroundStyle(.secondary)
                } else {
                    Text(verbatim: d.title).font(.headline)
                }
                if d.readerStoryId != nil { TagView(String(localized: "Reader task")) }
            }
            if !d.body.isEmpty {
                Text(verbatim: d.body).font(.japanese(size: 14)).lineLimit(2).foregroundStyle(.secondary)
            }
            Text(verbatim: Date(timeIntervalSince1970: Double(d.updatedAt) / 1000).formatted(date: .abbreviated, time: .shortened))
                .font(.caption2).foregroundStyle(.tertiary)
        }
    }

    private func load() async {
        do {
            drafts = try await app.graph.writingStudio.drafts()
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load your drafts: \(error.localizedDescription)")
        }
    }

    private func create() {
        creating = true
        let graph = app.graph
        Task {
            defer { creating = false }
            do {
                let d = try await SwiftSupport.shared.createStudioDraft(graph: graph, title: "", body: "", registerCode: "")
                await load()
                openDraftId = d.id
            } catch {
                self.error = String(localized: "Couldn't create a draft: \(error.localizedDescription)")
            }
        }
    }

    private func delete(_ d: StudioDraft) {
        let graph = app.graph
        let id = d.id
        Task {
            do {
                try await graph.writingStudio.delete(id: id)
                await load()
            } catch {
                self.error = String(localized: "Couldn't delete the draft: \(error.localizedDescription)")
            }
        }
    }
}

private enum StudioTool: String, CaseIterable, Identifiable {
    case corrections = "Corrections"
    case register = "Register"
    case suggestions = "Expressions"
    case readability = "Readability"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .corrections: "checkmark.bubble"
        case .register: "textformat"
        case .suggestions: "text.book.closed"
        case .readability: "gauge.medium"
        }
    }
}

/// One draft: the editor, autosave, and the four on-demand checks.
struct WritingDraftView: View {
    let draftId: String
    @Environment(AppModel.self) private var app

    @State private var draft: StudioDraft?
    @State private var loaded = false
    @State private var loadError: String?
    @State private var title = ""
    @State private var text = ""
    @State private var registerCode = ""
    @State private var lastSaved = ""
    @State private var saveError: String?

    @State private var tool: StudioTool?
    @State private var working = false
    @State private var toolError: String?
    @State private var level = 3
    @State private var corrections: [CorrectionRow]?
    @State private var register: RegisterRows?
    @State private var rewrites: [Int32: RewriteRow] = [:]
    @State private var rewriting: Set<Int32> = []
    @State private var suggestions: [PhraseFlag]?
    @State private var readability: DifficultyScore?
    @State private var readabilityDone = false
    @State private var taskGrade: SummaryGradeRow?
    @State private var grading = false

    private var saveKey: String { "\(title)\u{1}\(text)\u{1}\(registerCode)" }

    var body: some View {
        Group {
            if let loadError {
                VStack { ErrorRetryView(message: loadError) { Task { await load() } } }.padding()
            } else if draft != nil {
                editor
            } else if loaded {
                ContentUnavailableView("Draft not found", systemImage: "questionmark", description: Text("It may have been deleted on another device."))
            } else {
                ProgressView()
            }
        }
        .navigationTitle(title.isEmpty ? String(localized: "Draft") : title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: draftId) { await load() }
        .task(id: saveKey) {
            guard draft != nil, saveKey != lastSaved else { return }
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            await save()
        }
        .onDisappear {
            guard draft != nil, saveKey != lastSaved else { return }
            let graph = app.graph
            let (id, t, b, r) = (draftId, title, text, registerCode)
            Task { _ = try? await SwiftSupport.shared.updateStudioDraft(graph: graph, id: id, title: t, body: b, registerCode: r) }
        }
    }

    private var editor: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                TextField("Title", text: $title).textFieldStyle(.roundedBorder)
                Picker("Target register", selection: $registerCode) {
                    ForEach(["", "CASUAL", "POLITE", "FORMAL"], id: \.self) { code in
                        Text(verbatim: studioRegisterLabel(code)).tag(code)
                    }
                }
                if let prompt = draft?.taskPrompt, !prompt.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Task").font(.subheadline.weight(.semibold))
                            AiBadge()
                        }
                        Text(verbatim: prompt).font(.japanese(size: 15)).japaneseSpeech()
                    }
                    .padding(8)
                    .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
                }
                TextEditor(text: $text)
                    .font(.japanese(size: 18))
                    .frame(minHeight: 200)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
                HStack {
                    Text("\(String(text.filter { !$0.isWhitespace }.count)) characters").font(.caption.monospacedDigit())
                    Spacer()
                    Text(saveKey == lastSaved ? "Saved" : "Editing…").font(.caption).foregroundStyle(.secondary)
                }
                if let saveError { ErrorRetryView(message: saveError) { Task { await save() } } }
                if draft?.readerStoryId != nil { readerTask }
                toolButtons
                toolResults
            }
            .padding()
        }
    }

    // MARK: Reader task

    @ViewBuilder
    private var readerTask: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(grading ? "Grading…" : "Grade with the story's rubric") { gradeTask() }
                .buttonStyle(.bordered)
                .disabled(grading || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let g = taskGrade {
                if g.graded {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Score \(String(g.total)) / 6").font(.headline)
                            AIBadge(engine: g.engine)
                        }
                        LabeledContent(String(localized: "Content"), value: "\(g.content) / 2")
                        LabeledContent(String(localized: "Accuracy"), value: "\(g.accuracy) / 2")
                        LabeledContent(String(localized: "Language"), value: "\(g.language) / 2")
                        if !g.corrected.isEmpty {
                            Text("Suggested version").font(.caption.weight(.semibold))
                            Text(verbatim: g.corrected).font(.japanese(size: 16)).japaneseSpeech()
                        }
                        if !g.feedback.isEmpty { Text(verbatim: g.feedback).font(.subheadline) }
                    }
                    .padding(8)
                    .background(Color.purple.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                } else {
                    Label(g.reason, systemImage: "info.circle").font(.caption).foregroundStyle(.orange)
                }
            }
        }
    }

    // MARK: Tools

    private var toolButtons: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Check").font(.headline)
            FlowLayout(spacing: 8) {
                ForEach(StudioTool.allCases) { t in
                    Button {
                        run(t)
                    } label: {
                        Label(LocalizedStringKey(t.rawValue), systemImage: t.symbol)
                    }
                    .buttonStyle(.bordered)
                    .tint(tool == t ? .accentColor : .secondary)
                    .disabled(working || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            if tool == .corrections {
                Picker("Level", selection: $level) {
                    ForEach(jlptLevels, id: \.self) { Text(verbatim: "N\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }

    @ViewBuilder
    private var toolResults: some View {
        if working {
            ProgressView()
        } else if let toolError {
            ErrorRetryView(message: toolError) { if let tool { run(tool) } }
        } else {
            switch tool {
            case .corrections: correctionsView
            case .register: registerView
            case .suggestions: suggestionsView
            case .readability: readabilityView
            case nil: EmptyView()
            }
        }
    }

    @ViewBuilder
    private var correctionsView: some View {
        if let corrections {
            VStack(alignment: .leading, spacing: 10) {
                AiStatusBanner(scripted: "Without a model no corrections are made. The register check, expressions and readability still work.")
                if corrections.isEmpty { Text("Nothing to check.").foregroundStyle(.secondary) }
                ForEach(Array(corrections.enumerated()), id: \.offset) { _, c in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: c.sentence).font(.japanese(size: 16)).japaneseSpeech()
                        if !c.engine.isEmpty {
                            if c.isCorrect {
                                Label("Looks right", systemImage: "checkmark.circle").font(.subheadline).foregroundStyle(.green)
                            } else {
                                Text(verbatim: c.corrected).font(.japanese(size: 16)).foregroundStyle(.tint).japaneseSpeech()
                                ForEach(Array(c.edits.enumerated()), id: \.offset) { _, e in
                                    Text(verbatim: "\(e.original) → \(e.replacement)" + (e.reason.isEmpty ? "" : " · \(e.reason)"))
                                        .font(.caption)
                                }
                            }
                            if !c.explanation.isEmpty { Text(verbatim: c.explanation).font(.caption).foregroundStyle(.secondary) }
                            if c.unsure { Text("The model isn't sure about this one.").font(.caption).foregroundStyle(.orange) }
                            AIBadge(engine: c.engine)
                        } else {
                            Label(c.unavailable, systemImage: "info.circle").font(.caption).foregroundStyle(.orange)
                        }
                    }
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }

    @ViewBuilder
    private var registerView: some View {
        if let r = register {
            VStack(alignment: .leading, spacing: 8) {
                Text("Casual \(String(r.casual)) · Polite \(String(r.polite)) · Formal \(String(r.formal))").font(.subheadline)
                if r.dominantCode.isEmpty {
                    Text("No full sentences to classify yet.").foregroundStyle(.secondary)
                } else {
                    Text("Mostly: \(studioRegisterLabel(r.dominantCode))").font(.subheadline)
                    if r.expectedCode != r.dominantCode {
                        Text("Checked against your target: \(studioRegisterLabel(r.expectedCode))").font(.caption)
                    }
                    if !r.mixed && r.sentences.allSatisfy({ !$0.outlier }) {
                        Label("Every sentence is in the same register.", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.green)
                    }
                }
                ForEach(Array(r.sentences.enumerated()), id: \.offset) { _, s in
                    registerRow(s, expected: r.expectedCode)
                }
                Text("Checked by rules on each sentence's ending and keigo, offline.").font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }

    private func registerRow(_ s: RegisterSentenceRow, expected: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: s.text).font(.japanese(size: 16)).japaneseSpeech()
                Spacer()
                if !s.registerCode.isEmpty { TagView(studioRegisterLabel(s.registerCode)) }
            }
            if !s.markers.isEmpty {
                Text(verbatim: s.markers.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary)
            }
            if s.outlier && !expected.isEmpty {
                if let rw = rewrites[s.start] {
                    if !rw.engine.isEmpty {
                        Text(verbatim: rw.rewrite).font(.japanese(size: 16)).foregroundStyle(.tint).japaneseSpeech()
                        if !rw.notes.isEmpty { Text(verbatim: rw.notes).font(.caption).foregroundStyle(.secondary) }
                        AIBadge(engine: rw.engine)
                    } else {
                        Label(rw.unavailable, systemImage: "info.circle").font(.caption).foregroundStyle(.orange)
                    }
                } else {
                    Button(rewriting.contains(s.start) ? "Rewriting…" : "Rewrite as \(studioRegisterLabel(expected))") {
                        rewrite(s, code: expected)
                    }
                    .buttonStyle(.bordered)
                    .font(.caption)
                    .disabled(rewriting.contains(s.start))
                }
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.outlier ? Color.orange.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var suggestionsView: some View {
        if let suggestions {
            VStack(alignment: .leading, spacing: 8) {
                if suggestions.isEmpty {
                    Text("No plain words the thesaurus can improve on (or the dictionary pack isn't installed).").foregroundStyle(.secondary)
                } else {
                    Text("Plain words with richer expressions in the thesaurus:").font(.subheadline)
                }
                ForEach(Array(suggestions.enumerated()), id: \.offset) { _, f in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: f.surface == f.lemma ? f.surface : "\(f.surface) (\(f.lemma))").font(.japanese(size: 17)).japaneseSpeech()
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(f.clusters, id: \.id) { c in
                                    NavigationLink(value: Route.thesaurusCluster(c.id)) {
                                        Text(verbatim: "\(c.ja) · \(c.en)").font(.japanese(size: 14))
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var readabilityView: some View {
        if readabilityDone {
            if let d = readability {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: d.label).font(.title3.weight(.semibold))
                    Text("Difficulty score \(String(d.score))").font(.subheadline)
                    if let ratio = d.knownWordRatio?.doubleValue {
                        Text("Words you know: \(ratio.formatted(.percent.precision(.fractionLength(0))))").font(.subheadline)
                    }
                    Text("The same score the reader shows for texts (vocabulary, sentence length, kanji density).")
                        .font(.caption).foregroundStyle(.secondary)
                }
            } else {
                Text("Readability needs the dictionary pack.").foregroundStyle(.secondary)
            }
        }
    }

    // MARK: Actions

    private func load() async {
        do {
            let d = try await app.graph.writingStudio.draft(id: draftId)
            draft = d
            if let d {
                title = d.title
                text = d.body
                registerCode = SwiftSupport.shared.draftRegisterCode(draft: d)
                lastSaved = saveKey
            }
            loadError = nil
        } catch {
            loadError = String(localized: "Couldn't open the draft: \(error.localizedDescription)")
        }
        loaded = true
    }

    private func save() async {
        let key = saveKey
        do {
            if let d = try await SwiftSupport.shared.updateStudioDraft(graph: app.graph, id: draftId, title: title, body: text, registerCode: registerCode) {
                draft = d
            }
            lastSaved = key
            saveError = nil
        } catch {
            saveError = String(localized: "Couldn't save: \(error.localizedDescription)")
        }
    }

    private func run(_ t: StudioTool) {
        tool = t
        toolError = nil
        let graph = app.graph
        let body = text
        let target = registerCode
        switch t {
        case .register:
            // Rules, offline and instant.
            register = SwiftSupport.shared.studioRegister(graph: graph, text: body, targetCode: target)
            rewrites = [:]
        case .corrections:
            working = true
            let lvl = "N\(level)"
            Task {
                defer { working = false }
                do {
                    corrections = try await SwiftSupport.shared.studioCorrections(graph: graph, text: body, level: lvl)
                } catch {
                    toolError = String(localized: "Couldn't check the text: \(error.localizedDescription)")
                }
            }
        case .suggestions:
            working = true
            Task {
                defer { working = false }
                do {
                    suggestions = try await graph.writingStudio.suggestions(text: body)
                } catch {
                    toolError = String(localized: "Couldn't find suggestions: \(error.localizedDescription)")
                }
            }
        case .readability:
            working = true
            Task {
                defer { working = false }
                do {
                    readability = try await graph.writingStudio.readability(text: body)
                    readabilityDone = true
                } catch {
                    toolError = String(localized: "Couldn't score the text: \(error.localizedDescription)")
                }
            }
        }
    }

    private func rewrite(_ s: RegisterSentenceRow, code: String) {
        let graph = app.graph
        let start = s.start
        let sentence = s.text
        rewriting.insert(start)
        Task {
            defer { rewriting.remove(start) }
            do {
                rewrites[start] = try await SwiftSupport.shared.studioRewrite(graph: graph, sentence: sentence, registerCode: code)
            } catch {
                rewrites[start] = RewriteRow(sentence: sentence, rewrite: "", notes: "", engine: "", unavailable: error.localizedDescription)
            }
        }
    }

    private func gradeTask() {
        grading = true
        let graph = app.graph
        Task {
            defer { grading = false }
            await save()
            guard let d = draft else { return }
            do {
                taskGrade = try await SwiftSupport.shared.gradeStudioReaderTask(graph: graph, draft: d)
            } catch {
                taskGrade = SummaryGradeRow(
                    graded: false, content: 0, accuracy: 0, language: 0, total: 0, corrected: "", feedback: "", engine: "",
                    reason: String(localized: "Couldn't grade the draft: \(error.localizedDescription)")
                )
            }
        }
    }
}
