import Shared
import SwiftUI

/// Pitch-accent perception test (BRIEF_V2 §6.7, D-284, D-285, D-300). Only the pitch audio pack's items are used, so
/// every accent is the one rendered (rule 20); without the pack the module says so and links to Audio packs.
/// Questions, checking, the adaptive level and the stats all come from the shared `PitchTestService`.
enum PitchText {
    /// A drill code (`PitchDrill` name, "" = adaptive) as a title.
    static func drill(_ code: String) -> String {
        switch code {
        case "": String(localized: "Adaptive test")
        case "PATTERN_TEST": String(localized: "Pattern test")
        case "DOWNSTEP_TEST": String(localized: "Downstep test")
        case "WORD_PAIRS": String(localized: "Same-kana words")
        case "MINIMAL_PAIRS": String(localized: "Minimal pairs")
        default: code
        }
    }

    static func drillDetail(_ code: String) -> String {
        switch code {
        case "": String(localized: "Mixed questions that get harder as you get them right")
        case "PATTERN_TEST": String(localized: "平板, 頭高, 中高 or 尾高?")
        case "DOWNSTEP_TEST": String(localized: "After which mora does the pitch fall?")
        case "WORD_PAIRS": String(localized: "Which of the words (箸, 橋, 端…) was spoken?")
        case "MINIMAL_PAIRS": String(localized: "Pairs on your review schedule")
        default: ""
        }
    }

    /// A question type code (`PitchQuestionMode` name).
    static func mode(_ code: String) -> String {
        switch code {
        case "PATTERN": String(localized: "Pattern")
        case "DOWNSTEP": String(localized: "Downstep")
        case "WORD_PAIR": String(localized: "Which word")
        default: code
        }
    }

    /// The English name of an accent pattern (`AccentPattern` name), shown under its Japanese label.
    static func pattern(_ code: String) -> String {
        switch code {
        case "HEIBAN": String(localized: "flat")
        case "ATAMADAKA": String(localized: "head-high")
        case "NAKADAKA": String(localized: "middle-high")
        case "ODAKA": String(localized: "tail-high")
        default: ""
        }
    }

    static func patternJa(_ code: String) -> String {
        switch code {
        case "HEIBAN": "平板"
        case "ATAMADAKA": "頭高"
        case "NAKADAKA": "中高"
        case "ODAKA": "尾高"
        default: code
        }
    }
}

struct PitchTestHomeView: View {
    @Environment(AppModel.self) private var app

    @State private var loading = true
    @State private var error: String?
    @State private var service: PitchTestService?
    @State private var drills: [String] = []
    @State private var stats: PitchStatsRows?

    var body: some View {
        List {
            if loading {
                ProgressView()
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if service == nil {
                ContentUnavailableView {
                    Label("Pitch audio pack needed", systemImage: "waveform.slash")
                } description: {
                    Text("The test only uses clips rendered with a known accent, so it needs the pitch audio pack. The device voice can't guarantee an accent.")
                } actions: {
                    NavigationLink("Audio packs", value: Route.audioPacks).buttonStyle(.borderedProminent)
                }
            } else {
                Section {
                    Text("Listen to a word said with が, then name its accent. The test adapts: three right in a row moves you up a level, a miss moves you down.")
                        .font(.subheadline)
                    if let stats {
                        LabeledContent("Starting level", value: "\(String(stats.level)) / 5")
                        if stats.total.attempts > 0 {
                            LabeledContent("Answers so far", value: Self.tally(stats.total))
                        }
                    }
                }
                Section("Drills") {
                    NavigationLink(value: Route.pitchSession("")) { drillRow("") }
                    ForEach(drills, id: \.self) { code in
                        if code == "MINIMAL_PAIRS" {
                            NavigationLink(value: Route.minimalPairs) { drillRow(code) }
                        } else {
                            NavigationLink(value: Route.pitchSession(code)) { drillRow(code) }
                        }
                    }
                }
                Section {
                    NavigationLink("Your pitch stats", value: Route.pitchStats)
                }
            }
        }
        .navigationTitle("Pitch-accent test")
        .task { await load() }
    }

    private func drillRow(_ code: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: PitchText.drill(code)).font(.body.weight(.medium))
            Text(verbatim: PitchText.drillDetail(code)).font(.caption).foregroundStyle(.secondary)
        }
    }

    static func tally(_ row: PitchStatRow) -> String {
        let pct = row.percent >= 0 ? " · \(row.percent)%" : ""
        return "\(row.correct) / \(row.attempts)" + pct
    }

    private func load() async {
        loading = true
        error = nil
        do {
            let s = try await app.graph.pitchTest()
            service = s
            if let s {
                drills = try await SwiftSupport.shared.pitchDrillCodes(service: s)
                stats = try await SwiftSupport.shared.pitchStats(service: s)
            }
        } catch {
            self.error = String(localized: "Couldn't open the pitch test: \(error.localizedDescription)")
        }
        loading = false
    }
}

struct PitchSessionView: View {
    @Environment(AppModel.self) private var app
    let drillCode: String

    @State private var loading = true
    @State private var error: String?
    @State private var service: PitchTestService?
    @State private var session: PitchTestSession?
    @State private var question: PitchQuestion?
    @State private var feedback: PitchFeedback?
    @State private var shownAt = Date()
    @State private var answering = false
    @State private var voice = VoicePlayer()
    @State private var version = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if loading {
                    ProgressView()
                } else if let error {
                    ErrorRetryView(message: error) { Task { await start() } }
                } else if session == nil {
                    ContentUnavailableView("Pitch audio pack needed", systemImage: "waveform.slash",
                                           description: Text("Install the pitch audio pack in Settings → Audio packs."))
                } else if let session, let question {
                    header(session)
                    questionView(question)
                    if let feedback {
                        feedbackView(feedback, question: question)
                    }
                } else {
                    ContentUnavailableView("No questions fit", systemImage: "questionmark.circle",
                                           description: Text("The installed pitch pack has no items for this drill at your level."))
                }
            }
            .padding()
        }
        .navigationTitle(PitchText.drill(drillCode))
        .navigationBarTitleDisplayMode(.inline)
        .task { await start() }
        .onDisappear { voice.stop() }
    }

    private func header(_ session: PitchTestSession) -> some View {
        let _ = version
        return Text("Level \(String(session.level)) · streak \(String(session.streak)) · \(String(session.correct)) of \(String(session.answered)) right")
            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func questionView(_ q: PitchQuestion) -> some View {
        let mode = SwiftSupport.shared.pitchModeCode(question: q)
        Text(verbatim: PitchText.mode(mode)).font(.caption.weight(.semibold)).foregroundStyle(.tint)
        Button {
            play(q)
        } label: {
            Label(voice.isSpeaking ? LocalizedStringKey("Playing…") : LocalizedStringKey("Play again"), systemImage: "speaker.wave.2.fill").frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        Text(prompt(mode)).font(.headline)
        ForEach(q.options, id: \.id) { option in
            Button {
                answer(option.id)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: option.label).font(.japanese(size: 20)).japaneseSpeech()
                        let detail = mode == "PATTERN" ? PitchText.pattern(option.id) : option.detail
                        if !detail.isEmpty {
                            Text(verbatim: detail).font(mode == "DOWNSTEP" ? .japanese(size: 15) : .caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if let feedback {
                        if option.id == q.expected {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        } else if option.id == feedback.answer {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.bordered)
            .disabled(feedback != nil || answering)
        }
    }

    private func prompt(_ mode: String) -> LocalizedStringKey {
        switch mode {
        case "DOWNSTEP": "After which mora does the pitch fall? (0 = it doesn't)"
        case "WORD_PAIR": "Which word did you hear?"
        default: "Which accent pattern did you hear?"
        }
    }

    @ViewBuilder
    private func feedbackView(_ f: PitchFeedback, question q: PitchQuestion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(f.correct ? LocalizedStringKey("Right") : LocalizedStringKey("Not quite"), systemImage: f.correct ? "checkmark.circle" : "xmark.circle")
                .font(.headline)
                .foregroundStyle(f.correct ? .green : .red)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: q.item.text).font(.japanese(size: 26)).japaneseSpeech()
                Text(verbatim: q.item.reading).font(.japanese(size: 15)).foregroundStyle(.secondary).japaneseSpeech()
            }
            Text(verbatim: q.item.gloss).font(.subheadline)
            LabeledContent("You heard") { Text(verbatim: f.marks).font(.japanese(size: 17)) }
            if let chosen = f.chosenMarks, !f.correct {
                LabeledContent("Your answer") { Text(verbatim: chosen).font(.japanese(size: 17)) }
            }
            if f.nextLevel != q.level {
                if f.nextLevel > q.level {
                    Text("Level up: \(String(f.nextLevel))").font(.caption.weight(.semibold))
                } else {
                    Text("Level down: \(String(f.nextLevel))").font(.caption.weight(.semibold))
                }
            }
            if let service {
                PitchSayItView(service: service, item: q.item, clipKey: q.clipKey)
                    .id(q.item.id + "|" + String(session?.answered ?? 0))
            }
            Button("Next") { next() }.buttonStyle(.borderedProminent)
        }
        .padding(12)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
    }

    private func start() async {
        loading = true
        error = nil
        do {
            let s = try await app.graph.pitchTest()
            service = s
            if let s {
                session = try await SwiftSupport.shared.pitchStart(service: s, drillCode: drillCode)
                next()
            }
        } catch {
            self.error = String(localized: "Couldn't start the test: \(error.localizedDescription)")
        }
        loading = false
    }

    private func next() {
        feedback = nil
        question = session?.next()
        version += 1
        shownAt = Date()
        if let question { play(question) }
    }

    private func play(_ q: PitchQuestion) {
        let graph = app.graph
        let key = q.clipKey
        Task {
            if let path = PackAudio.path(key, graph: graph) {
                _ = await voice.play(file: path)
            }
        }
    }

    private func answer(_ optionId: String) {
        guard let session, feedback == nil, !answering else { return }
        answering = true
        let ms = Int64(Date().timeIntervalSince(shownAt) * 1000)
        Task {
            do {
                feedback = try await session.answer(optionId: optionId, responseMs: ms)
            } catch {
                self.error = String(localized: "Couldn't save the answer: \(error.localizedDescription)")
            }
            answering = false
            version += 1
        }
    }
}

/// Perception → production (D-285): say the word (with が, like the clip) and see the accent the analyzer heard.
private struct PitchSayItView: View {
    @Environment(AppModel.self) private var app
    let service: PitchTestService
    let item: PitchTestItem
    let clipKey: String

    @State private var recorder = Recorder()
    @State private var working = false
    @State private var result: PitchProductionResult?
    @State private var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Now say it with が").font(.subheadline.weight(.semibold))
            RecordButton(recorder: recorder, label: working ? "Scoring…" : "Say it", disabled: working) { samples in
                score(samples)
            }
            if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
            if let result {
                Label(result.matched ? LocalizedStringKey("Your accent matched") : LocalizedStringKey("Your accent was different"),
                      systemImage: result.matched ? "checkmark.seal" : "exclamationmark.bubble")
                    .foregroundStyle(result.matched ? .green : .orange)
                LabeledContent("Expected") { Text(verbatim: result.expectedMarks).font(.japanese(size: 17)) }
                if !result.observedMarks.isEmpty {
                    LabeledContent("Heard") { Text(verbatim: result.observedMarks).font(.japanese(size: 17)) }
                }
                PronunciationPanel(report: result.report)
                if let shadowing = result.shadowing { ShadowingPanel(report: shadowing) }
            }
        }
    }

    private func score(_ samples: [Float]) {
        working = true
        note = nil
        let graph = app.graph
        let service = service
        let item = item
        let key = clipKey
        Task {
            let heard = await SpeechToText.transcribe(samples, graph: graph)
            var reference: KotlinFloatArray?
            if let path = PackAudio.path(key, graph: graph), let decoded = await PackClipPlayer.decodeClip(path: path) {
                reference = kotlinFloats(decoded)
            }
            do {
                result = try await SwiftSupport.shared.pitchProduction(
                    service: service, item: item, samples: kotlinFloats(samples),
                    transcript: heard.error == nil ? heard.text : "", reference: reference
                )
                if result == nil { note = String(localized: "Pronunciation scoring isn't available on this device.") }
            } catch {
                note = String(localized: "Couldn't analyze the recording: \(error.localizedDescription)")
            }
            working = false
        }
    }
}

struct PitchStatsView: View {
    @Environment(AppModel.self) private var app

    @State private var loading = true
    @State private var error: String?
    @State private var missing = false
    @State private var stats: PitchStatsRows?
    @State private var recent: [(text: String, correct: Bool, mode: String)] = []

    var body: some View {
        List {
            if loading {
                ProgressView()
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if missing {
                ContentUnavailableView("Pitch audio pack needed", systemImage: "waveform.slash",
                                       description: Text("Install the pitch audio pack in Settings → Audio packs."))
            } else if let stats {
                if stats.total.attempts == 0 {
                    ContentUnavailableView("No answers yet", systemImage: "chart.bar",
                                           description: Text("Take a test and your results per pattern, length and confusable pair appear here."))
                } else {
                    Section("Overall") {
                        LabeledContent("Right", value: PitchTestHomeView.tally(stats.total))
                        LabeledContent("Next session starts at level", value: String(stats.level))
                    }
                    Section("By pattern") {
                        ForEach(stats.byPattern, id: \.key) { row in
                            LabeledContent {
                                Text(verbatim: PitchTestHomeView.tally(row))
                            } label: {
                                Text(verbatim: PitchText.patternJa(row.key) + " · " + PitchText.pattern(row.key))
                            }
                        }
                    }
                    Section("By length") {
                        ForEach(stats.byMoraCount, id: \.key) { row in
                            LabeledContent {
                                Text(verbatim: PitchTestHomeView.tally(row))
                            } label: {
                                Text("\(row.label) morae")
                            }
                        }
                    }
                    Section("By question type") {
                        ForEach(stats.byMode, id: \.key) { row in
                            LabeledContent {
                                Text(verbatim: PitchTestHomeView.tally(row))
                            } label: {
                                Text(verbatim: PitchText.mode(row.key))
                            }
                        }
                    }
                    if !stats.pairs.isEmpty {
                        Section {
                            ForEach(Array(stats.pairs.enumerated()), id: \.offset) { _, pair in
                                LabeledContent {
                                    Text(verbatim: pair.percent >= 0 ? "\(pair.confusions) / \(pair.attempts) · \(pair.percent)%" : "–")
                                } label: {
                                    Text(verbatim: "\(pair.a) ↔ \(pair.b)").font(.japanese(size: 16))
                                }
                            }
                        } header: {
                            Text("Confused pairs")
                        } footer: {
                            Text("How often a word of one pattern was answered as the other, most confused first.")
                        }
                    }
                    if !recent.isEmpty {
                        Section("Recent answers") {
                            ForEach(Array(recent.enumerated()), id: \.offset) { _, r in
                                HStack {
                                    Image(systemName: r.correct ? "checkmark.circle" : "xmark.circle")
                                        .foregroundStyle(r.correct ? .green : .red)
                                    Text(verbatim: r.text).font(.japanese(size: 17)).japaneseSpeech()
                                    Spacer()
                                    Text(verbatim: PitchText.mode(r.mode)).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Pitch stats")
        .task { await load() }
    }

    private func load() async {
        loading = true
        error = nil
        do {
            guard let service = try await app.graph.pitchTest() else {
                missing = true
                loading = false
                return
            }
            stats = try await SwiftSupport.shared.pitchStats(service: service)
            let rows = try await service.recent(limit: 20)
            recent = rows.map { r in
                (text: service.item(id: r.itemId)?.text ?? r.itemId, correct: r.correct, mode: r.mode)
            }
        } catch {
            self.error = String(localized: "Couldn't load the stats: \(error.localizedDescription)")
        }
        loading = false
    }
}
