import Charts
import Shared
import SwiftUI

// Translation workbench (BRIEF_V2 §6.12; D-270…D-274, D-305, D-308): passages by genre and direction, written and
// timed sight translation, AI grading (labeled, never an official score) with a diff, or the self-assessment rubric
// without a model, the attempt history, and the skill line on Me. Grading, diffing, timing and scoring are shared.

/// Localized names of the shared genre codes.
func translationGenreLabel(_ code: String) -> String {
    switch code {
    case "news": String(localized: "News")
    case "technical": String(localized: "Technical")
    case "legal": String(localized: "Legal")
    case "literary": String(localized: "Literary")
    case "dialogue": String(localized: "Dialogue")
    case "military": String(localized: "Military")
    default: code
    }
}

/// "J→E" / "E→J" for a direction code.
func translationDirectionLabel(_ code: String) -> String {
    code == "EJ" ? "E→J" : "J→E"
}

private func dateText(_ ms: Int64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
}

// MARK: - Home

struct TranslationHomeView: View {
    @Environment(AppModel.self) private var app

    @State private var genre = ""
    @State private var direction = ""
    @State private var available: Bool?
    @State private var passages: [TranslationPassage]?
    @State private var error: String?
    @State private var importTitle = ""
    @State private var importText = ""

    var body: some View {
        List {
            Section {
                Picker("Direction", selection: $direction) {
                    Text("All").tag("")
                    Text(verbatim: "J→E").tag("JE")
                    Text(verbatim: "E→J").tag("EJ")
                }
                .pickerStyle(.segmented)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        genreChip(code: "", label: String(localized: "All genres"))
                        ForEach(SwiftSupport.shared.translationGenreCodes(), id: \.self) { code in
                            genreChip(code: code, label: translationGenreLabel(code))
                        }
                    }
                }
            } footer: {
                Text("Grades are practice feedback from your own model, never an official score.")
            }
            Section {
                DisclosureGroup("Translate your own text") {
                    TextField("Title (optional)", text: $importTitle)
                    TextEditor(text: $importText)
                        .frame(minHeight: 100)
                        .font(.japanese(size: 16))
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))
                    Text("Paste Japanese or English. There's no reference translation, so the model grades against the source.")
                        .font(.caption).foregroundStyle(.secondary)
                    NavigationLink(value: Route.translationImported(text: importText, title: importTitle)) {
                        Label("Start translating", systemImage: "arrow.right.circle")
                    }
                    .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                NavigationLink(value: Route.translationHistory) {
                    Label("History", systemImage: "clock.arrow.circlepath")
                }
            }
            if let error {
                Section {
                    ErrorRetryView(message: error) { Task { await load() } }
                }
            } else if available == false {
                Section {
                    ContentUnavailableView(
                        "Translation passages not installed",
                        systemImage: "character.book.closed",
                        description: Text("This build has no linguist pack. Build it with `uv run packs/build_translation.py` in tools/ and rebuild the app. You can still translate your own text above.")
                    )
                }
            } else if let passages {
                Section("Passages") {
                    if passages.isEmpty {
                        Text("No passages match these filters.").foregroundStyle(.secondary)
                    }
                    ForEach(passages, id: \.id) { p in
                        NavigationLink(value: Route.translationPassage(p.id)) { PassageRow(passage: p) }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Translation workbench")
        .task(id: "\(genre)|\(direction)") { await load() }
    }

    private func genreChip(code: String, label: String) -> some View {
        Button {
            genre = code
        } label: {
            Text(verbatim: label).font(.subheadline)
        }
        .buttonStyle(.bordered)
        .tint(genre == code ? .accentColor : .secondary)
    }

    private func load() async {
        let graph = app.graph
        do {
            let ok = try await graph.translationWorkbench.available().boolValue
            available = ok
            if ok {
                passages = try await SwiftSupport.shared.translationPassages(graph: graph, genre: genre, directionCode: direction)
            } else {
                passages = []
            }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the passages: \(error.localizedDescription)")
        }
    }
}

private struct PassageRow: View {
    let passage: TranslationPassage

    var body: some View {
        let direction = SwiftSupport.shared.passageDirectionCode(passage: passage)
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: passage.title).font(.japanese(size: 17)).lineLimit(2)
            HStack(spacing: 6) {
                Text(verbatim: [translationDirectionLabel(direction), passage.level, translationGenreLabel(passage.genre)]
                    .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                if passage.source == "tatoeba" { TagView("Tatoeba") }
                if passage.isAiGenerated { AiBadge() }
            }
        }
    }
}

// MARK: - One passage

struct TranslationPassageView: View {
    @Environment(AppModel.self) private var app
    private let passageId: String?
    private let importedText: String
    private let importedTitle: String

    @State private var passage: TranslationPassage?
    @State private var missing = false
    @State private var error: String?

    init(passageId: String) {
        self.passageId = passageId
        self.importedText = ""
        self.importedTitle = ""
    }

    init(importedText: String, title: String) {
        self.passageId = nil
        self.importedText = importedText
        self.importedTitle = title
    }

    var body: some View {
        Group {
            if let passage {
                TranslationWorkView(passage: passage)
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding()
            } else if missing {
                ContentUnavailableView("Passage not found", systemImage: "questionmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Translate")
        .navigationBarTitleDisplayMode(.inline)
        .task { if passage == nil { await load() } }
    }

    private func load() async {
        let graph = app.graph
        if let passageId {
            do {
                passage = try await graph.translationWorkbench.passage(id: passageId)
                missing = passage == nil
                error = nil
            } catch {
                self.error = String(localized: "Couldn't load the passage: \(error.localizedDescription)")
            }
        } else {
            passage = SwiftSupport.shared.importTranslationPassage(graph: graph, text: importedText, title: importedTitle, genre: "news")
        }
    }
}

private enum TranslationModeChoice: String, CaseIterable, Identifiable {
    case written = "Written"
    case sight = "Sight"
    var id: String { rawValue }
}

private struct TranslationWorkView: View {
    @Environment(AppModel.self) private var app
    let passage: TranslationPassage

    @State private var mode = TranslationModeChoice.written
    @State private var draft = ""
    @State private var startedAt: Date?
    @State private var sightEndedAt: Date?
    @State private var now = Date()
    @State private var showDetails = false
    @State private var recorder = Recorder()
    @State private var transcribing = false
    @State private var sttNote: String?
    @State private var grading = false
    @State private var grade: TranslationGradeRow?
    @State private var gradeError: String?
    @State private var gradedDurationMs: Int64 = 0
    @State private var history: [TranslationAttemptRow] = []

    private let ticker = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    private var direction: String { SwiftSupport.shared.passageDirectionCode(passage: passage) }
    private var sourceIsJapanese: Bool { direction == "JE" }
    private var limitMs: Int64 { app.graph.translationWorkbench.timeLimitMs(passage: passage) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                if grade == nil {
                    Picker("Mode", selection: $mode) {
                        ForEach(TranslationModeChoice.allCases) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .disabled(startedAt != nil && mode == .sight)
                }
                source
                details
                if mode == .written { writtenInput } else { sightInput }
                gradeButton
                if let gradeError {
                    ErrorRetryView(message: gradeError) { runGrade() }
                }
                if let grade {
                    TranslationResultView(passage: passage, attempt: draft, sight: mode == .sight, durationMs: gradedDurationMs, grade: grade) {
                        Task { await loadHistory() }
                    }
                    Button("Try again") { reset() }.buttonStyle(.bordered)
                }
                if !history.isEmpty {
                    SectionHeader("Your attempts on this passage")
                    ForEach(history.sorted { $0.createdAt > $1.createdAt }, id: \.id) { AttemptRowView(attempt: $0) }
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onReceive(ticker) { now = $0 }
        .task { await loadHistory() }
        .onDisappear { if recorder.isRecording { _ = recorder.stop() } }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: passage.title).font(.japanese(size: 20)).fontWeight(.semibold)
            HStack(spacing: 6) {
                Text(verbatim: [translationDirectionLabel(direction), passage.level, passage.ilr.isEmpty ? "" : "ILR \(passage.ilr)", translationGenreLabel(passage.genre)]
                    .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                if passage.isAiGenerated { AiBadge() }
            }
        }
    }

    @ViewBuilder
    private var source: some View {
        if mode == .sight && startedAt == nil && grade == nil {
            Text("The passage appears when you start the timer. Translate it aloud as you read.")
                .font(.subheadline).foregroundStyle(.secondary)
        } else if sourceIsJapanese {
            Text(verbatim: passage.text).font(.japanese(size: 19)).textSelection(.enabled).japaneseSpeech()
        } else {
            Text(verbatim: passage.text).font(.body).textSelection(.enabled)
        }
    }

    private var details: some View {
        DisclosureGroup("Register, key points and notes", isExpanded: $showDetails) {
            VStack(alignment: .leading, spacing: 6) {
                let register = SwiftSupport.shared.passageRegister(passage: passage)
                if !register.isEmpty {
                    Text("Register: \(register)").font(.subheadline)
                }
                ForEach(Array(passage.keyPoints.enumerated()), id: \.offset) { _, point in
                    Label { Text(verbatim: point) } icon: { Image(systemName: "key") }.font(.subheadline)
                }
                if !passage.notes.isEmpty {
                    Text(verbatim: passage.notes).font(.caption).foregroundStyle(.secondary)
                }
                if register.isEmpty && passage.keyPoints.isEmpty && passage.notes.isEmpty {
                    Text("No notes for this passage.").font(.caption).foregroundStyle(.secondary)
                }
                if passage.isAiGenerated { AiBadge() }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.subheadline)
    }

    private var writtenInput: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(sourceIsJapanese ? LocalizedStringKey("Your English translation") : LocalizedStringKey("Your Japanese translation")).font(.headline)
            TextEditor(text: $draft)
                .frame(minHeight: 140)
                .font(sourceIsJapanese ? .body : .japanese(size: 17))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))
                .disabled(grade != nil)
                .onChange(of: draft) { _, _ in if startedAt == nil { startedAt = Date() } }
            if let startedAt, grade == nil {
                Text("Time: \(clockText(seconds: Int64(now.timeIntervalSince(startedAt))))")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var sightInput: some View {
        VStack(alignment: .leading, spacing: 8) {
            let limitSeconds = limitMs / 1000
            if let startedAt {
                let elapsed = Int64((sightEndedAt ?? now).timeIntervalSince(startedAt))
                let left = limitSeconds - elapsed
                if left >= 0 {
                    Text("Time left: \(clockText(seconds: left))").font(.title3.monospacedDigit().weight(.semibold))
                } else {
                    Text("Over time by \(clockText(seconds: -left))").font(.title3.monospacedDigit().weight(.semibold)).foregroundStyle(.orange)
                }
                if grade == nil {
                    RecordButton(recorder: recorder, label: transcribing ? "Transcribing…" : "Speak your translation", disabled: transcribing) { samples in
                        sightEndedAt = Date()
                        transcribe(samples)
                    }
                    if let sttNote { Text(verbatim: sttNote).font(.caption).foregroundStyle(.orange) }
                }
                Text("Transcript (you can correct it)").font(.subheadline.weight(.semibold))
                TextEditor(text: $draft)
                    .frame(minHeight: 100)
                    .font(sourceIsJapanese ? .body : .japanese(size: 17))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))
                    .disabled(grade != nil)
            } else {
                Text("Time limit: \(clockText(seconds: limitSeconds))").font(.subheadline)
                Text(sourceIsJapanese ? LocalizedStringKey("Speak your English translation. It's transcribed on this device.") : LocalizedStringKey("Speak your Japanese translation. It's transcribed on this device."))
                    .font(.caption).foregroundStyle(.secondary)
                Button {
                    startedAt = Date()
                    sightEndedAt = nil
                } label: {
                    Label("Start", systemImage: "timer").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    @ViewBuilder
    private var gradeButton: some View {
        if grade == nil {
            Button {
                runGrade()
            } label: {
                if grading {
                    HStack { ProgressView(); Text("Grading…") }.frame(maxWidth: .infinity)
                } else {
                    Label("Grade", systemImage: "checkmark.seal").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(grading || recorder.isRecording || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func durationMs() -> Int64 {
        guard let startedAt else { return 0 }
        let end = mode == .sight ? (sightEndedAt ?? Date()) : Date()
        return Int64(max(0, end.timeIntervalSince(startedAt)) * 1000)
    }

    private func transcribe(_ samples: [Float]) {
        transcribing = true
        sttNote = nil
        let graph = app.graph
        let english = sourceIsJapanese
        Task {
            let out: SpeechToText.Output
            if english {
                out = await SpeechToText.transcribeEnglish(samples)
            } else {
                out = await SpeechToText.transcribe(samples, graph: graph)
            }
            if let error = out.error {
                sttNote = error
            } else {
                draft = draft.isEmpty ? out.text : draft + (english ? " " : "") + out.text
            }
            transcribing = false
        }
    }

    private func runGrade() {
        let graph = app.graph
        let p = passage
        let text = draft
        let sight = mode == .sight
        let ms = durationMs()
        gradedDurationMs = ms
        grading = true
        gradeError = nil
        Task {
            do {
                let row = try await SwiftSupport.shared.gradeTranslation(graph: graph, passage: p, attempt: text, sight: sight, durationMs: ms)
                grade = row
                showDetails = true
                if row.graded { await loadHistory() }
            } catch {
                gradeError = String(localized: "Couldn't grade: \(error.localizedDescription)")
            }
            grading = false
        }
    }

    private func reset() {
        grade = nil
        gradeError = nil
        draft = ""
        startedAt = nil
        sightEndedAt = nil
        sttNote = nil
    }

    private func loadHistory() async {
        history = (try? await SwiftSupport.shared.translationHistory(graph: app.graph, passageId: passage.id)) ?? []
    }
}

// MARK: - Result: AI grade or self-assessment

private struct TranslationResultView: View {
    @Environment(AppModel.self) private var app
    let passage: TranslationPassage
    let attempt: String
    let sight: Bool
    let durationMs: Int64
    let grade: TranslationGradeRow
    let onSaved: () -> Void

    @State private var scores: [Int] = [2, 2, 2, 2]
    @State private var saving = false
    @State private var saved: TranslationAttemptRow?
    @State private var saveError: String?

    private var rubric: [TranslationCriterion] { SwiftSupport.shared.translationRubric() }
    private var referenceIsJapanese: Bool { SwiftSupport.shared.passageDirectionCode(passage: passage) == "EJ" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if grade.graded {
                graded
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Label("Not graded by a model", systemImage: "info.circle").font(.headline)
                    Text(verbatim: grade.reason).font(.caption).foregroundStyle(.secondary)
                    Text("Compare with the reference and score yourself on the rubric below.").font(.subheadline)
                }
            }
            if passage.hasReference {
                SectionHeader("Reference translation")
                if referenceIsJapanese {
                    Text(verbatim: passage.reference).font(.japanese(size: 17)).textSelection(.enabled).japaneseSpeech()
                } else {
                    Text(verbatim: passage.reference).textSelection(.enabled)
                }
                if passage.isAiGenerated { AiBadge() }
            }
            if grade.hasDiff {
                TranslationDiffView(segments: grade.diff, overlapPercent: Int(grade.overlapPercent), japanese: referenceIsJapanese)
            }
            if !grade.graded { selfAssessment }
        }
    }

    @ViewBuilder
    private var graded: some View {
        HStack {
            AIBadge(engine: grade.engine)
            Spacer()
            Text(verbatim: "\(grade.percent) / 100").font(.title2.monospacedDigit().weight(.bold))
        }
        Text("Practice feedback, not an official score.").font(.caption).foregroundStyle(.secondary)
        let values = [grade.accuracy, grade.completeness, grade.registerScore, grade.naturalness].map { Int($0) }
        ForEach(Array(rubric.enumerated()), id: \.offset) { i, c in
            let score = i < values.count ? values[i] : 0
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(LocalizedStringKey(c.label)).font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(verbatim: "\(score) / 4").font(.subheadline.monospacedDigit())
                }
                if score >= 0 && score < c.levels.count {
                    Text(LocalizedStringKey(c.levels[score])).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        if !grade.feedback.isEmpty {
            Text(verbatim: grade.feedback)
        }
        if !grade.issues.isEmpty {
            SectionHeader("Issues")
            ForEach(Array(grade.issues.enumerated()), id: \.offset) { _, issue in
                VStack(alignment: .leading, spacing: 2) {
                    Text(LocalizedStringKey(issue.kind)).font(.caption.weight(.semibold)).foregroundStyle(.orange)
                    if !issue.attemptSpan.isEmpty { Text("Yours: \(issue.attemptSpan)").font(.subheadline) }
                    if !issue.referenceSpan.isEmpty { Text("Reference: \(issue.referenceSpan)").font(.subheadline) }
                    Text(verbatim: issue.note).font(.caption)
                }
            }
        }
        if !grade.better.isEmpty {
            SectionHeader("A better version")
            if referenceIsJapanese {
                Text(verbatim: grade.better).font(.japanese(size: 17)).textSelection(.enabled).japaneseSpeech()
            } else {
                Text(verbatim: grade.better).textSelection(.enabled)
            }
        }
    }

    @ViewBuilder
    private var selfAssessment: some View {
        SectionHeader("Score yourself")
        ForEach(Array(rubric.enumerated()), id: \.offset) { i, c in
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringKey(c.label)).font(.subheadline.weight(.semibold))
                Text(LocalizedStringKey(c.question)).font(.caption).foregroundStyle(.secondary)
                Picker(LocalizedStringKey(c.label), selection: Binding(get: { scores[i] }, set: { scores[i] = $0 })) {
                    ForEach(0..<5) { Text(verbatim: String($0)).tag($0) }
                }
                .pickerStyle(.segmented)
                .disabled(saved != nil)
                let level = scores[i]
                if level < c.levels.count {
                    Text(LocalizedStringKey(c.levels[level])).font(.caption)
                }
            }
        }
        if let saved {
            Label("Saved: \(String(saved.score)) / 100 (self-assessed)", systemImage: "checkmark.circle").foregroundStyle(.green)
        } else {
            Button(saving ? LocalizedStringKey("Saving…") : LocalizedStringKey("Save my scores")) { save() }
                .buttonStyle(.borderedProminent)
                .disabled(saving || attempt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        if let saveError {
            ErrorRetryView(message: saveError) { save() }
        }
    }

    private func save() {
        let graph = app.graph
        let p = passage
        let text = attempt
        let s = scores
        let ms = durationMs
        let sightMode = sight
        saving = true
        saveError = nil
        Task {
            do {
                saved = try await SwiftSupport.shared.selfAssessTranslation(
                    graph: graph, passage: p, attempt: text, sight: sightMode, durationMs: ms,
                    accuracy: Int32(s[0]), completeness: Int32(s[1]), registerScore: Int32(s[2]), naturalness: Int32(s[3])
                )
                onSaved()
            } catch {
                saveError = String(localized: "Couldn't save: \(error.localizedDescription)")
            }
            saving = false
        }
    }
}

/// The offline word/character diff: same text plain, the reference's words you left out underlined in green,
/// your own extra words struck through in red.
private struct TranslationDiffView: View {
    let segments: [DiffSegmentRow]
    let overlapPercent: Int
    let japanese: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionHeader("Compared with the reference")
            diffText
                .font(japanese ? .japanese(size: 17) : .body)
                .textSelection(.enabled)
            HStack(spacing: 12) {
                Text("Missing from yours").foregroundStyle(.green).underline()
                Text("Only in yours").foregroundStyle(.red).strikethrough()
            }
            .font(.caption2)
            let overlap = String(overlapPercent) + "%"
            Text("Overlap with the reference: \(overlap)").font(.caption).foregroundStyle(.secondary)
            Text("A rough word match, not a grade: a different wording can be just as right.").font(.caption2).foregroundStyle(.secondary)
        }
    }

    private var diffText: Text {
        let sep = japanese ? "" : " "
        var out = Text(verbatim: "")
        for (i, s) in segments.enumerated() {
            let piece = (i == 0 ? "" : sep) + s.text
            switch s.kindCode {
            case "MISSING":
                out = out + Text(verbatim: piece).foregroundColor(.green).underline()
            case "EXTRA":
                out = out + Text(verbatim: piece).foregroundColor(.red).strikethrough()
            default:
                out = out + Text(verbatim: piece)
            }
        }
        return out
    }
}

// MARK: - History

private struct AttemptRowView: View {
    let attempt: TranslationAttemptRow

    var body: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: attempt.attemptText)
                    .font(attempt.directionCode == "EJ" ? .japanese(size: 16) : .body)
                    .textSelection(.enabled)
                Text(verbatim: "\(attempt.accuracy) · \(attempt.completeness) · \(attempt.registerScore) · \(attempt.naturalness)")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                if !attempt.feedback.isEmpty { Text(verbatim: attempt.feedback).font(.caption) }
                if !attempt.better.isEmpty {
                    Text(verbatim: attempt.better)
                        .font(attempt.directionCode == "EJ" ? .japanese(size: 15) : .caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(verbatim: "\(attempt.score) / 100").font(.headline.monospacedDigit())
                    if attempt.aiGraded { AIBadge(engine: attempt.engine.isEmpty ? nil : attempt.engine) } else { TagView(String(localized: "Self-assessed")) }
                    Spacer()
                }
                HStack(spacing: 6) {
                    Text(verbatim: [translationDirectionLabel(attempt.directionCode), translationGenreLabel(attempt.genre), attempt.level]
                        .filter { !$0.isEmpty }.joined(separator: " · "))
                    Text(attempt.sight ? LocalizedStringKey("Sight") : LocalizedStringKey("Written"))
                    if attempt.overTime { Text("Over time").foregroundStyle(.orange) }
                }
                .font(.caption).foregroundStyle(.secondary)
                Text(verbatim: dateText(attempt.createdAt)).font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }
}

struct TranslationHistoryView: View {
    @Environment(AppModel.self) private var app
    @State private var attempts: [TranslationAttemptRow]?
    @State private var error: String?
    @State private var pendingDelete: TranslationAttemptRow?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let attempts {
                if attempts.isEmpty {
                    ContentUnavailableView("No attempts yet", systemImage: "text.badge.checkmark", description: Text("Graded and self-assessed translations appear here."))
                }
                ForEach(attempts, id: \.id) { a in
                    AttemptRowView(attempt: a)
                        .swipeActions {
                            Button("Delete", role: .destructive) { pendingDelete = a }
                        }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Translation history")
        .task { await load() }
        .confirmationDialog(
            "Delete this attempt on all your devices?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let a = pendingDelete { delete(a) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        }
    }

    private func load() async {
        do {
            let rows = try await SwiftSupport.shared.translationHistory(graph: app.graph, passageId: "")
            attempts = rows.sorted { $0.createdAt > $1.createdAt }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the history: \(error.localizedDescription)")
        }
    }

    private func delete(_ a: TranslationAttemptRow) {
        let graph = app.graph
        let id = a.id
        Task {
            do {
                _ = try await graph.translationWorkbench.delete(id: id)
                await load()
            } catch {
                self.error = String(localized: "Couldn't delete: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: - Skill line on Me

struct TranslationSkillCard: View {
    @Environment(AppModel.self) private var app
    @State private var skill: TranslationSkillRows?
    @State private var failed = false

    private static let dayParser: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    var body: some View {
        Section("Translation") {
            if failed {
                ErrorRetryView(message: String(localized: "Couldn't load your translation results.")) { Task { await load() } }
            } else if let skill {
                if skill.attempts == 0 {
                    Text("No translation attempts yet.").foregroundStyle(.secondary)
                    NavigationLink("Translation workbench", value: Route.translation)
                } else {
                    content(skill)
                    NavigationLink("Translation history", value: Route.translationHistory)
                }
            } else {
                ProgressView()
            }
        }
        .task { await load() }
    }

    @ViewBuilder
    private func content(_ skill: TranslationSkillRows) -> some View {
        let days: [(date: Date, score: Int)] = skill.daily.compactMap { d in
            Self.dayParser.date(from: d.key).map { (date: $0, score: Int(d.score)) }
        }
        if !days.isEmpty {
            Chart {
                ForEach(Array(days.enumerated()), id: \.offset) { _, d in
                    LineMark(x: .value("Day", d.date), y: .value("Score", d.score))
                        .foregroundStyle(Color.indigo)
                    PointMark(x: .value("Day", d.date), y: .value("Score", d.score))
                        .foregroundStyle(Color.indigo)
                }
            }
            .chartYScale(domain: 0...100)
            .frame(height: 150)
            .accessibilityLabel(Text("Chart of your daily average translation score"))
        }
        LabeledContent {
            Text(verbatim: String(skill.attempts))
        } label: {
            Text("Attempts")
        }
        if skill.hasTrend {
            let trend = Int(skill.trend)
            LabeledContent {
                Text(verbatim: trend > 0 ? "+\(trend)" : String(trend)).foregroundStyle(trend > 0 ? Color.green : trend < 0 ? Color.red : Color.secondary)
            } label: {
                Text("Trend (last five vs. the five before)")
            }
        }
        ForEach(skill.recentByDirection, id: \.key) { r in
            LabeledContent {
                Text(verbatim: "\(r.score) / 100")
            } label: {
                Text("Recent average, \(translationDirectionLabel(r.key))")
            }
        }
        ForEach(skill.recentByGenre, id: \.key) { r in
            LabeledContent {
                Text(verbatim: "\(r.score) / 100")
            } label: {
                Text("Recent average, \(translationGenreLabel(r.key))")
            }
        }
        Text("AI grades are practice feedback, never an official score.").font(.caption2).foregroundStyle(.secondary)
    }

    private func load() async {
        do {
            skill = try await SwiftSupport.shared.translationSkill(graph: app.graph)
            failed = false
        } catch {
            failed = true
        }
    }
}
