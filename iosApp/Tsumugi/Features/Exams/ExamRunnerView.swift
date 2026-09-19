import Shared
import SwiftUI

/// A timed exam (BRIEF §5.11). All timing, navigation rules and scoring live in the shared ExamSession; this view
/// ticks it once a second, renders the open section and submits at the end.
///
/// The clock shown is `session.remainingMs()`, which the shared session computes from the wall clock, so the timer
/// never drifts or pauses with the 1 Hz UI tick. Back from the background, `tick()` is called until it reports no
/// more expired sections, so every deadline that passed meanwhile is processed (F-24, UI half).
struct ExamRunnerView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    let session: ExamSession
    let onClose: () -> Void

    @State private var version = 0
    @State private var result: ExamResult?
    @State private var voice = VoicePlayer()
    @State private var confirmEndSection = false
    @State private var confirmQuit = false
    @State private var showGrid = false

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            Group {
                if let result {
                    ExamResultView(result: result)
                } else {
                    runner
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if result != nil {
                        Button("Close") { onClose() }
                    } else {
                        Button("Quit") { confirmQuit = true }
                    }
                }
            }
        }
        .onReceive(timer) { _ in tick() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { tick() }
        }
        .onDisappear { voice.stop() }
        .confirmationDialog("End this section?", isPresented: $confirmEndSection, titleVisibility: .visible) {
            Button(isLastSection ? "Submit the test" : "End section", role: .destructive) { endSection() }
        } message: {
            Text(session.form.mode.strict ? "A closed section can't be reopened." : "You can't go back to it afterwards.")
        }
        .confirmationDialog("Quit without scoring?", isPresented: $confirmQuit, titleVisibility: .visible) {
            Button("Submit and see results") { submit() }
            Button("Quit without saving", role: .destructive) { onClose() }
        }
    }

    private var isLastSection: Bool {
        Int(session.sectionIndex) + 1 >= session.form.sections.count
    }

    @ViewBuilder
    private var runner: some View {
        let _ = version
        if let section = session.section, let current = session.current {
            VStack(spacing: 0) {
                header(section)
                Divider()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if showGrid { grid(section) }
                        itemView(current)
                    }
                    .padding()
                }
                Divider()
                footer(section)
            }
            .navigationTitle(section.title)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ProgressView().onAppear { submit() }
        }
    }

    private func header(_ section: FormSection) -> some View {
        HStack {
            if let remaining = session.remainingMs()?.int64Value {
                Label(clockText(seconds: remaining / 1000), systemImage: "timer")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(remaining < 60_000 ? Color.red : Color.primary)
            } else {
                Label("Untimed", systemImage: "timer").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Text("Section \(session.sectionIndex + 1)/\(session.form.sections.count) · \(session.answeredCount)/\(session.totalCount) answered")
                .font(.caption).foregroundStyle(.secondary)
            Button { showGrid.toggle() } label: { Image(systemName: "square.grid.3x3") }
                .accessibilityLabel("Question grid")
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private func grid(_ section: FormSection) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 40), spacing: 6)], spacing: 6) {
            ForEach(Array(section.items.enumerated()), id: \.offset) { i, f in
                let answered = session.choiceFor(itemId: f.item.id) != nil
                Button("\(i + 1)") {
                    session.goTo(i: Int32(i))
                    bump()
                }
                .font(.caption.monospacedDigit())
                .frame(minWidth: 36, minHeight: 32)
                .background(i == Int(session.index) ? Color.accentColor.opacity(0.3) : (answered ? Color.green.opacity(0.18) : Color.secondary.opacity(0.1)), in: RoundedRectangle(cornerRadius: 6))
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Question \(i + 1)"))
                .accessibilityValue(answered ? Text("Answered") : Text("Not answered"))
            }
        }
    }

    @ViewBuilder
    private func itemView(_ f: FormItem) -> some View {
        let item = f.item
        HStack {
            Text("Q\(session.index + 1) · \(f.typeTitle)").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            if item.aiGenerated || (session.passage?.aiGenerated ?? false) { AIBadge() }
        }
        if let passage = session.passage {
            VStack(alignment: .leading, spacing: 6) {
                if !passage.title.isEmpty { Text(passage.title).font(.headline) }
                if !passage.body.isEmpty { ExamText(text: passage.body, size: 17) }
            }
            .padding(10)
            .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
        }
        let script = item.script.isEmpty ? (session.passage?.script ?? []) : item.script
        if !script.isEmpty {
            // Clip keys follow the script's owner: the item for per-item scripts, else its passage (D-092).
            let owner = item.script.isEmpty ? (session.passage?.id ?? item.id) : item.id
            audioButton(itemId: item.id, owner: owner, script: script)
        }
        // JLPT questions are Japanese; DLPT questions and answers are English.
        let japanese = session.form.exam == .jlpt
        ExamText(text: item.stem, size: 19, japanese: japanese)
        let chosen = session.choiceFor(itemId: item.id)?.intValue
        ForEach(Array(item.choices.enumerated()), id: \.offset) { i, choice in
            let isChosen = chosen.map({ Int($0) }) == i
            Button {
                session.choose(choice: Int32(i))
                bump()
            } label: {
                HStack(alignment: .top) {
                    Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    Text(examInline(choice)).font(.japanese(size: 17)).multilineTextAlignment(.leading)
                        .japaneseSpeech(japanese)
                    Spacer()
                    if isChosen { Image(systemName: "largecircle.fill.circle").foregroundStyle(.tint).accessibilityHidden(true) }
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(isChosen ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("Choice \(i + 1) of \(item.choices.count)"))
            .accessibilityAddTraits(isChosen ? .isSelected : [])
        }
    }

    private func audioButton(itemId: String, owner: String, script: [ScriptLine]) -> some View {
        let canPlay = session.canPlayAudio(itemId: itemId)
        return VStack(alignment: .leading, spacing: 4) {
            Button {
                // Strict modes still play once: the session records the play before any audio starts.
                session.audioPlayed(itemId: itemId)
                bump()
                // Pre-rendered VOICEVOX lines when the exam audio pack is installed, system TTS otherwise (rule 20).
                let lines = script.enumerated().map { i, line in
                    (text: line.text, voice: VoicePlayer.Voice(hint: line.voice), key: Optional(PackAudio.examKey(ownerId: owner, line: i)))
                }
                let graph = app.graph
                Task { await voice.sayLines(lines, graph: graph) }
            } label: {
                Label(voice.isSpeaking ? "Playing…" : "Play audio", systemImage: "speaker.wave.2.fill")
            }
            .buttonStyle(.borderedProminent)
            .disabled(!canPlay || voice.isSpeaking)
            if session.form.mode.strict {
                Text(canPlay ? "The audio plays once, as on the real test." : "Already played.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func footer(_ section: FormSection) -> some View {
        HStack {
            Button {
                session.previous()
                bump()
            } label: { Label("Previous", systemImage: "chevron.left") }
                .disabled(session.index == 0)
            Spacer()
            if Int(session.index) + 1 < section.items.count {
                Button {
                    session.next()
                    bump()
                } label: { Label("Next", systemImage: "chevron.right") }
                    .buttonStyle(.borderedProminent)
            } else {
                Button(isLastSection ? "Submit" : "End section") { confirmEndSection = true }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }

    private func bump() { version += 1 }

    private func tick() {
        guard result == nil else { return }
        // Several sections may have run out while the app was away: process every elapsed deadline, not just one.
        // Bounded by the section count, so a shared-side bug can't spin the main thread.
        var closed = 0
        while closed <= session.form.sections.count, session.tick() {
            closed += 1
        }
        if closed > 0 {
            voice.stop()
        }
        if session.finished {
            submit()
        } else {
            bump()
        }
    }

    private func endSection() {
        voice.stop()
        session.nextSection()
        if session.finished { submit() } else { bump() }
    }

    private func submit() {
        guard result == nil else { return }
        voice.stop()
        result = session.submit()
    }
}

/// Scores for one attempt: JLPT scaled groups and pass marks, or a DLPT ILR estimate; by-type table; add missed
/// items to reviews. Saved automatically.
struct ExamResultView: View {
    @Environment(AppModel.self) private var app
    let result: ExamResult

    @State private var savedId: String?
    @State private var saveFailed = false
    @State private var addMessage: String?
    @State private var adding = false

    var body: some View {
        List {
            Section {
                Text(result.summary).font(.headline)
                if let savedId {
                    NavigationLink("Review answers") { AttemptReviewView(attemptId: savedId) }
                } else if saveFailed {
                    Text("Couldn't save this attempt.").foregroundStyle(.red)
                } else {
                    ProgressView()
                }
            } footer: {
                Text(ExamHubView.disclaimer)
            }
            ScoreSections(scoring: result.scoring, isJlpt: result.form.exam == .jlpt)
            Section {
                let refs = result.missedRefs
                Button(adding ? "Adding…" : "Add missed items to SRS") { addMissed(refs) }
                    .disabled(adding || refs.isEmpty || addMessage != nil)
                if refs.isEmpty {
                    Text("No grammar or vocabulary links on the missed items.").font(.caption).foregroundStyle(.secondary)
                }
                if let addMessage { Text(addMessage).font(.caption) }
            }
            if !result.form.shortfalls.isEmpty {
                Section("Bank shortfalls") {
                    ForEach(Array(result.form.shortfalls.enumerated()), id: \.offset) { _, s in
                        Text("\(s.type): \(s.got) of \(s.wanted) items").font(.caption)
                    }
                }
            }
        }
        .navigationTitle("Results")
        .task { await save() }
    }

    private func save() async {
        guard savedId == nil, !saveFailed else { return }
        guard let exams = try? await app.graph.exams(), let id = try? await exams.save(result: result) else {
            saveFailed = true
            return
        }
        savedId = id
    }

    private func addMissed(_ refs: [String]) {
        adding = true
        let graph = app.graph
        Task {
            let added = try? await graph.exams().addToSrs(refs: refs)
            let n = Int(added?.intValue ?? 0)
            addMessage = n == 0 ? "Nothing new to add (items need the grammar or dictionary pack, or are already in reviews)." : "Added \(n) item\(n == 1 ? "" : "s") to your reviews."
            adding = false
        }
    }
}

/// Score tables shared by the result and review screens.
struct ScoreSections: View {
    let scoring: AttemptScoring
    let isJlpt: Bool

    var body: some View {
        if isJlpt && !scoring.groups.isEmpty {
            Section("Scaled scores") {
                ForEach(Array(scoring.groups.enumerated()), id: \.offset) { _, g in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(Self.groupTitle(g.group))
                            Text("\(g.correct)/\(g.administered) correct · minimum \(g.minimum)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(g.scaled)/\(g.scaledMax)").font(.headline.monospacedDigit())
                        Image(systemName: g.metMinimum ? "checkmark.circle" : "xmark.circle").foregroundStyle(g.metMinimum ? .green : .red)
                            .accessibilityLabel(g.metMinimum ? Text("Minimum met") : Text("Below the minimum"))
                    }
                    .accessibilityElement(children: .combine)
                }
                if let total = scoring.total?.intValue {
                    LabeledContent("Total", value: "\(total)/\(scoring.totalMax?.intValue ?? 180)")
                }
                if let pass = scoring.passMark?.intValue {
                    LabeledContent("Pass mark", value: "\(pass)")
                }
                if let passed = scoring.passed?.boolValue {
                    Text(passed ? "Pass (every section met its minimum)" : "Not yet: below the pass mark or a sectional minimum")
                        .font(.headline).foregroundStyle(passed ? .green : .orange)
                }
                if !scoring.complete {
                    Text("Partial test: scaled scores are a linear approximation over only the sections taken.").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Scaled scores use a documented linear approximation, not the JLPT's equating.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        if !isJlpt && (scoring.ilr != nil || scoring.ilrProvisional != nil || !scoring.byLevel.isEmpty) {
            Section("ILR estimate") {
                Text(Self.ilrText(scoring)).font(.headline)
                Text("Highest level with at least 70% correct, sustained over enough items, with consistent performance below it. Practice estimate only.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(Array(scoring.byLevel.enumerated()), id: \.offset) { _, t in
                    LabeledContent("ILR \(t.key)", value: "\(t.correct)/\(t.total)")
                }
            }
        }
        if !scoring.byType.isEmpty {
            Section(isJlpt || scoring.ilr != nil || scoring.ilrProvisional != nil || !scoring.byLevel.isEmpty ? "By item type" : "Breakdown") {
                ForEach(Array(scoring.byType.enumerated()), id: \.offset) { _, t in
                    LabeledContent(Self.typeTitle(t.key), value: "\(t.correct)/\(t.total)")
                }
                if scoring.meanTimeMs > 0 {
                    Text("Mean time per item: \(scoring.meanTimeMs / 1000) s").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        if !scoring.weakAreas.isEmpty {
            Section("Work on") {
                ForEach(scoring.weakAreas, id: \.self) { Text(Self.typeTitle($0)) }
            }
        }
    }

    static func ilrText(_ s: AttemptScoring) -> String {
        if let level = s.ilr {
            let label = String(describing: level)
            return s.ilrConfident
                ? String(localized: "Estimated ILR \(label)")
                : String(localized: "Estimated ILR \(label) (low confidence: few items at that level)")
        }
        if let provisional = s.ilrProvisional {
            let label = String(describing: provisional)
            return String(localized: "Provisional ≈ ILR \(label): too few items for a firm estimate")
        }
        return String(localized: "Not enough correct answers at any level for an estimate")
    }

    static func groupTitle(_ group: String) -> String {
        switch group {
        case "language": String(localized: "Language knowledge (vocabulary, grammar)")
        case "reading": String(localized: "Reading")
        case "listening": String(localized: "Listening")
        case "language_reading": String(localized: "Language knowledge & reading")
        default: group
        }
    }

    static func typeTitle(_ key: String) -> String {
        key.replacingOccurrences(of: "_", with: " ").capitalized
    }
}
