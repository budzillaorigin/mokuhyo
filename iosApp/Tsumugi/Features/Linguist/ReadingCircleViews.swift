import Shared
import SwiftUI

/// The solo reading circle (BRIEF_V2 §6.14, D-278, D-307): pick a short Aozora text or a library document, read each
/// sentence aloud, explain it in English, and keep the recordings. Sessions sync; recordings stay on the device.
struct ReadingCircleHomeView: View {
    @Environment(AppModel.self) private var app

    private struct TextRow: Identifiable {
        let id: String
        let title: String
        let titleEn: String
        let author: String
        let level: String
        let sentences: Int
    }

    private struct DocRow: Identifiable {
        let id: String
        let title: String
        let author: String
    }

    private struct SessionRow: Identifiable {
        let id: String
        let textId: String
        let title: String
        let done: Int
        let total: Int
        let finished: Bool
        let session: CircleSession
    }

    @State private var texts: [TextRow] = []
    @State private var docs: [DocRow] = []
    @State private var sessions: [SessionRow] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var deleting: SessionRow?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if !loaded {
                ProgressView()
            } else {
                Section {
                    Text("Read a short text sentence by sentence: read each one aloud, then explain it in English. Your recordings and notes are kept.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if !sessions.isEmpty {
                    Section("Your sessions") {
                        ForEach(sessions) { s in
                            NavigationLink(value: Route.circleSession(s.textId)) {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(verbatim: s.title).font(.japanese(size: 16)).japaneseSpeech()
                                        Spacer()
                                        if s.finished {
                                            Label("Finished", systemImage: "checkmark.seal.fill").font(.caption).foregroundStyle(.green)
                                        }
                                    }
                                    ProgressView(value: Double(s.done), total: Double(max(1, s.total)))
                                    Text("\(String(s.done)) of \(String(s.total)) sentences").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) { deleting = s } label: { Label("Delete", systemImage: "trash") }
                            }
                        }
                    }
                }
                Section("Texts") {
                    if texts.isEmpty {
                        Text("The circle texts come with the linguist pack, which isn't installed. Build it with `uv run packs/literature/build_literature.py` in tools/ and rebuild the app. You can still read a document from your library.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(texts) { t in
                        NavigationLink(value: Route.circleSession(t.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(alignment: .firstTextBaseline) {
                                    Text(verbatim: t.title).font(.japanese(size: 17, weight: .semibold)).japaneseSpeech()
                                    Text(verbatim: t.author).font(.japanese(size: 13)).foregroundStyle(.secondary).japaneseSpeech()
                                }
                                if !t.titleEn.isEmpty { Text(verbatim: t.titleEn).font(.caption).foregroundStyle(.secondary) }
                                HStack {
                                    if !t.level.isEmpty { TagView(t.level) }
                                    Text("\(String(t.sentences)) sentences").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                Section("From your library") {
                    if docs.isEmpty {
                        Text("Documents you import into the reader appear here.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(docs) { d in
                        NavigationLink(value: Route.circleSession("doc:" + d.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: d.title).font(.japanese(size: 16)).lineLimit(2).japaneseSpeech()
                                if !d.author.isEmpty { Text(verbatim: d.author).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Reading circle")
        .task { await load() }
        .confirmationDialog(
            "Delete this session?",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete session", role: .destructive) {
                if let row = deleting { delete(row) }
                deleting = nil
            }
        } message: {
            Text("Your notes on this text are removed on every device. The recordings stay in your recordings.")
        }
    }

    private func load() async {
        error = nil
        let graph = app.graph
        do {
            texts = try await graph.readingCircle.texts().map {
                TextRow(id: $0.id, title: $0.title, titleEn: $0.titleEn, author: $0.author, level: $0.level, sentences: Int($0.sentenceCount))
            }
            docs = try await graph.reader.documents().map { DocRow(id: $0.id, title: $0.title, author: $0.author ?? "") }
            sessions = try await graph.readingCircle.sessions().map {
                SessionRow(id: $0.id, textId: $0.textId, title: $0.title, done: Int($0.doneCount), total: Int($0.sentenceCount), finished: $0.finished, session: $0)
            }
            loaded = true
        } catch {
            self.error = String(localized: "Couldn't load the reading circle: \(error.localizedDescription)")
        }
    }

    private func delete(_ row: SessionRow) {
        let graph = app.graph
        let session = row.session
        Task {
            do {
                try await graph.readingCircle.delete(session: session)
                await load()
            } catch {
                self.error = String(localized: "Couldn't delete the session: \(error.localizedDescription)")
            }
        }
    }
}

/// One reading-circle session: the text sentence by sentence, each read aloud and explained in English.
struct CircleSessionView: View {
    @Environment(AppModel.self) private var app
    let textId: String

    @State private var session: CircleSession?
    @State private var reading: CircleReading?
    @State private var summary: String = ""
    @State private var summaryAi = false
    @State private var work: AozoraSource?
    @State private var loaded = false
    @State private var error: String?
    @State private var actionError: String?

    @State private var recorder = Recorder()
    @State private var recordingExplanation = false
    @State private var saving = false
    @State private var explanation = ""
    @State private var voice = VoicePlayer()
    @State private var help: CircleHelpRows?
    @State private var helpError: String?
    @State private var showHelp = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if let session, let reading {
                    content(session, reading)
                } else if loaded {
                    ContentUnavailableView(
                        "Text not available",
                        systemImage: "text.book.closed",
                        description: Text("This text isn't installed or has no sentences.")
                    )
                } else {
                    ProgressView()
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(reading?.title ?? String(localized: "Reading circle"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onDisappear {
            voice.stop()
            if recorder.isRecording { _ = recorder.stop() }
        }
        .sheet(isPresented: $showHelp) {
            NavigationStack {
                helpSheet
                    .navigationTitle("Help")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Close") { showHelp = false } }
                    }
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .entry(let id): EntryView(id: id)
                        case .grammarPoint(let id): GrammarPointView(id: id)
                        default: EmptyView()
                        }
                    }
            }
            .environment(app)
        }
    }

    @ViewBuilder
    private func content(_ s: CircleSession, _ r: CircleReading) -> some View {
        let count = Int(s.sentenceCount)
        let idx = min(max(0, Int(s.position)), max(0, count - 1))
        if !summary.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    SectionHeader("Summary")
                    if summaryAi { AiBadge() }
                }
                Text(verbatim: summary).font(.subheadline)
            }
        }
        VStack(alignment: .leading, spacing: 4) {
            ProgressView(value: Double(s.doneCount), total: Double(max(1, count)))
            Text("\(String(s.doneCount)) of \(String(count)) sentences done").font(.caption).foregroundStyle(.secondary)
        }
        if s.finished {
            Label("You've finished this text. You can still go back to any sentence.", systemImage: "checkmark.seal.fill")
                .font(.subheadline).foregroundStyle(.green)
        }
        if idx < r.sentences.count {
            sentenceCard(s, r, idx: idx)
        }
        HStack {
            Button {
                move(to: idx - 1)
            } label: {
                Label("Previous", systemImage: "chevron.left")
            }
            .disabled(idx == 0 || saving)
            Spacer()
            Text(verbatim: "\(idx + 1) / \(count)").font(.caption.monospacedDigit())
            Spacer()
            Button {
                move(to: idx + 1)
            } label: {
                Label("Next", systemImage: "chevron.right")
            }
            .disabled(idx >= count - 1 || saving)
        }
        .buttonStyle(.bordered)
        if let work { AozoraSourceCard(work: work) }
    }

    @ViewBuilder
    private func sentenceCard(_ s: CircleSession, _ r: CircleReading, idx: Int) -> some View {
        let sentence = r.sentences[idx]
        let entry = s.entry(idx: Int32(idx))
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                Text(verbatim: sentence.text).font(.japanese(size: 20)).textSelection(.enabled).japaneseSpeech()
                Spacer()
                if entry.done { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
            }
            Button {
                openHelp(r, idx: idx)
            } label: {
                Label("Words and grammar", systemImage: "character.book.closed")
            }
            .buttonStyle(.bordered)

            Divider()
            Text("1. Read it aloud").font(.headline)
            if !recordingExplanation || !recorder.isRecording {
                RecordButton(recorder: recorder, label: "Record", disabled: saving || recordingExplanation && recorder.isRecording) { samples in
                    saveRecording(samples, idx: idx, explanation: false)
                }
            }
            if let recId = entry.read {
                HStack {
                    Label("Reading recorded", systemImage: "checkmark").font(.caption).foregroundStyle(.green)
                    Button("Play mine") { play(recId) }.font(.caption)
                }
            }

            Divider()
            Text("2. Explain it in English").font(.headline)
            TextEditor(text: $explanation)
                .frame(minHeight: 80)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
            HStack {
                Button("Save explanation") { saveExplanation(idx: idx) }
                    .buttonStyle(.bordered)
                    .disabled(saving || explanation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || explanation == entry.explain)
                Spacer()
                Button {
                    toggleExplanationRecording(idx: idx)
                } label: {
                    Label(recordingExplanation && recorder.isRecording ? LocalizedStringKey("Stop") : LocalizedStringKey("Say it instead"), systemImage: recordingExplanation && recorder.isRecording ? "stop.circle.fill" : "mic")
                }
                .buttonStyle(.bordered)
                .tint(recordingExplanation && recorder.isRecording ? .red : .accentColor)
                .disabled(saving || (!recordingExplanation && recorder.isRecording))
            }
            if let recId = entry.explainRec {
                HStack {
                    Label("Spoken explanation recorded", systemImage: "checkmark").font(.caption).foregroundStyle(.green)
                    Button("Play") { play(recId) }.font(.caption)
                }
            }
            if let actionError { Text(actionError).font(.caption).foregroundStyle(.red) }

            Button {
                complete(idx: idx)
            } label: {
                Label(entry.done ? LocalizedStringKey("Done") : LocalizedStringKey("Done with this sentence"), systemImage: "checkmark.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(saving || entry.read == nil || !entry.explained)
            if entry.read == nil || !entry.explained {
                Text("Record the reading and add an explanation to finish this sentence.").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
        .onAppear { explanation = entry.explain }
        .id("\(s.id)-\(idx)")
    }

    @ViewBuilder
    private var helpSheet: some View {
        List {
            if let helpError {
                Text(verbatim: helpError).foregroundStyle(.red)
            } else if let help {
                if !help.available {
                    Text("Word help isn't available without the dictionary pack.").foregroundStyle(.secondary)
                } else {
                    Section("Words") {
                        if help.tokens.isEmpty { Text("No dictionary words found.").foregroundStyle(.secondary) }
                        ForEach(Array(help.tokens.enumerated()), id: \.offset) { _, t in
                            NavigationLink(value: Route.entry(t.entryId)) {
                                HStack(alignment: .firstTextBaseline) {
                                    Text(verbatim: t.surface).font(.japanese(size: 18)).japaneseSpeech()
                                    if !t.dictionaryForm.isEmpty && t.dictionaryForm != t.surface {
                                        Text(verbatim: "← \(t.dictionaryForm)").font(.japanese(size: 14)).foregroundStyle(.secondary).japaneseSpeech()
                                    }
                                    Spacer()
                                    Text(verbatim: t.reading).font(.japanese(size: 13)).foregroundStyle(.secondary).japaneseSpeech()
                                }
                            }
                        }
                    }
                    Section("Grammar") {
                        if help.grammar.isEmpty { Text("No grammar points detected.").foregroundStyle(.secondary) }
                        ForEach(help.grammar, id: \.pointId) { g in
                            NavigationLink(value: Route.grammarPoint(g.pointId)) {
                                Text(verbatim: g.title).font(.japanese(size: 15))
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
    }

    // MARK: - Actions

    private func load() async {
        error = nil
        let graph = app.graph
        do {
            reading = try await graph.readingCircle.reading(textId: textId)
            session = try await graph.readingCircle.start(textId: textId)
            if !textId.hasPrefix("doc:"), let detail = try await graph.poetry()?.circleText(id: textId) {
                summary = detail.summaryEn
                summaryAi = detail.isAiGenerated
                work = detail.work
            }
            if let session { explanation = session.entry(idx: session.position).explain }
            loaded = true
        } catch {
            self.error = String(localized: "Couldn't open the text: \(error.localizedDescription)")
        }
    }

    private func move(to idx: Int) {
        guard let current = session, idx >= 0, idx < Int(current.sentenceCount) else { return }
        let graph = app.graph
        help = nil
        Task {
            do {
                let next = try await graph.readingCircle.moveTo(session: current, idx: Int32(idx))
                session = next
                explanation = next.entry(idx: Int32(idx)).explain
                actionError = nil
            } catch {
                actionError = String(localized: "Couldn't save: \(error.localizedDescription)")
            }
        }
    }

    private func saveExplanation(idx: Int) {
        guard let current = session else { return }
        let graph = app.graph
        let text = explanation
        saving = true
        Task {
            do {
                session = try await graph.readingCircle.explain(session: current, idx: Int32(idx), text: text)
                actionError = nil
            } catch {
                actionError = String(localized: "Couldn't save: \(error.localizedDescription)")
            }
            saving = false
        }
    }

    private func toggleExplanationRecording(idx: Int) {
        if recorder.isRecording {
            let samples = recorder.stop()
            saveRecording(samples, idx: idx, explanation: true)
        } else {
            recordingExplanation = true
            Task {
                if !(await recorder.start()) { recordingExplanation = false }
            }
        }
    }

    /// Writes the take as a WAV through the shared recording store, then links it to the sentence.
    private func saveRecording(_ samples: [Float], idx: Int, explanation isExplanation: Bool) {
        recordingExplanation = false
        guard let current = session else { return }
        guard samples.count > Int(AudioCapture.sampleRate * 0.3) else {
            actionError = String(localized: "That recording was too short. Tap Stop after you speak.")
            return
        }
        let graph = app.graph
        saving = true
        Task {
            do {
                let pending = try await graph.recordings.startRecording(fileExtension: "wav")
                let path = pending.path
                let data = WavFile.data(samples: samples)
                try await Task.detached(priority: .utility) {
                    try data.write(to: URL(fileURLWithPath: path), options: .atomic)
                }.value
                let ms = Int64(Double(samples.count) / AudioCapture.sampleRate * 1000)
                if isExplanation {
                    session = try await graph.readingCircle.attachExplanationRecording(session: current, idx: Int32(idx), pending: pending, durationMs: ms)
                } else {
                    session = try await graph.readingCircle.attachReading(session: current, idx: Int32(idx), pending: pending, durationMs: ms)
                }
                actionError = nil
            } catch {
                actionError = String(localized: "Couldn't save the recording: \(error.localizedDescription)")
            }
            saving = false
        }
    }

    private func complete(idx: Int) {
        guard let current = session else { return }
        let graph = app.graph
        saving = true
        Task {
            do {
                let next = try await graph.readingCircle.complete(session: current, idx: Int32(idx))
                session = next
                explanation = next.entry(idx: next.position).explain
                help = nil
                actionError = nil
            } catch {
                actionError = String(localized: "Couldn't save: \(error.localizedDescription)")
            }
            saving = false
        }
    }

    private func play(_ recordingId: String) {
        let graph = app.graph
        Task {
            let path = (try? await SwiftSupport.shared.recordingFilePath(graph: graph, recordingId: recordingId)) ?? ""
            var played = false
            if !path.isEmpty {
                played = await voice.play(file: path)
            }
            if !played {
                actionError = String(localized: "Your recording isn't on this device.")
            }
        }
    }

    private func openHelp(_ r: CircleReading, idx: Int) {
        help = nil
        helpError = nil
        showHelp = true
        let graph = app.graph
        Task {
            do {
                help = try await SwiftSupport.shared.circleHelp(graph: graph, reading: r, idx: Int32(idx))
            } catch {
                helpError = String(localized: "Couldn't load the help: \(error.localizedDescription)")
            }
        }
    }
}
