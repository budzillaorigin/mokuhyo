import Shared
import SwiftUI
import UniformTypeIdentifiers

/// One DLPT text-type chip: items of that type in the chosen range, per test kind.
struct DlptTypeOption: Identifiable {
    let textType: String
    var reading = 0
    var listening = 0
    var id: String { textType }
}

/// An exam in progress, presented full screen.
struct RunningExam: Identifiable {
    let id = UUID()
    let session: ExamSession
}

/// Exam simulators (BRIEF §5.11): JLPT mock/section/type drills with coverage, DLPT full/60/30, OPI, history.
struct ExamHubView: View {
    @Environment(AppModel.self) private var app
    @AppStorage("practice.jlpt") private var level = 4

    @State private var exams: ExamService?
    @State private var blueprint: LevelBlueprint?
    @State private var hasBlueprints = false
    @State private var coverage: [String: Int] = [:]
    @State private var dlptReading = 0
    @State private var dlptListening = 0
    @State private var history: [AttemptSummary] = []
    @State private var running: RunningExam?
    @State private var message: String?
    @State private var loading = false
    @State private var loadError: String?
    @State private var building = false
    @State private var inProgress: InProgressAttempt?
    // DLPT range and text-type filter (BRIEF_V2 G-08, §6.16, D-226/D-227, D-268).
    @State private var dlptUpper = false
    @State private var dlptTypes: [DlptTypeOption] = []
    @State private var dlptFilter: Set<String> = []
    @State private var dlptTypesError: String?

    static var disclaimer: String {
        String(localized: "Unofficial practice; not affiliated with the JLPT (JEES/Japan Foundation), DLI or ACTFL. Scores and ratings are estimates.")
    }

    var body: some View {
        List {
            Section {
                Text(Self.disclaimer).font(.caption)
                if let message { Text(message).font(.caption).foregroundStyle(.orange) }
                if building {
                    HStack {
                        ProgressView()
                        Text("Building the test…").font(.caption)
                    }
                }
                if let loadError {
                    Label(loadError, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red)
                    Button("Retry") { Task { await load() } }
                }
            }
            if let attempt = inProgress {
                Section("Unfinished attempt") {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(attempt.exam.title) \(attempt.level) · \(attempt.mode.title)").font(.subheadline)
                        Text(attempt.timeUp
                             ? String(localized: "Time ran out while you were away. Resume to see the score.")
                             : String(localized: "\(attempt.answered) of \(attempt.total) answered · \(attempt.sectionTitle)"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Button(attempt.timeUp ? "See the score" : "Resume attempt") { resume() }
                    Button("Discard", role: .destructive) {
                        Task {
                            try? await exams?.discardInProgress()
                            await load()
                        }
                    }
                }
            }
            jlptSection
            dlptSection
            Section("Speaking") {
                NavigationLink(value: Route.opi) {
                    LabeledContent("OPI simulator", value: "Practice interview")
                }
            }
            Section("History") {
                if history.isEmpty {
                    Text("No attempts yet.").foregroundStyle(.secondary)
                }
                ForEach(history, id: \.id) { a in
                    NavigationLink(value: Route.attempt(a.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(a.summary).font(.subheadline)
                            Text("\(a.mode.title) · \(Self.date(a.submittedAt.toEpochMilliseconds()))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section {
                NavigationLink("Import an item bank", value: Route.examBanks)
            }
        }
        .navigationTitle("Exams")
        .task(id: level) { await load() }
        .fullScreenCover(item: $running, onDismiss: { Task { await load() } }) { run in
            ExamRunnerView(session: run.session) { running = nil }
        }
    }

    private var jlptSection: some View {
        Section {
            Picker("Level", selection: $level) {
                ForEach(jlptLevels, id: \.self) { Text("N\($0)").tag($0) }
            }
            .pickerStyle(.segmented)
            if loading {
                ProgressView()
            } else if !hasBlueprints {
                Text("The exam pack isn't installed, so JLPT forms can't be built. Build it with `uv run packs/build_exam.py` in tools/, or import your own bank.")
                    .font(.caption).foregroundStyle(.secondary)
            } else if let blueprint {
                let total = coverage.values.reduce(0, +)
                Text("\(total) items in the bank for N\(level) · the real test has \(blueprint.itemCount) in \(blueprint.totalMinutes) minutes")
                    .font(.caption).foregroundStyle(.secondary)
                if total == 0 {
                    Text("No N\(level) items yet.").foregroundStyle(.secondary)
                } else {
                    Button("Full mock (\(blueprint.totalMinutes) min, strict timing)") {
                        start { try await $0.jlptMock(level: Int32(level), seed: Self.seed()) }
                    }
                    ForEach(blueprint.sections, id: \.id) { section in
                        Button("Section: \(section.title) (\(section.minutes) min)") {
                            let id = section.id
                            start { try await $0.jlptSection(level: Int32(level), sectionId: id, seed: Self.seed()) }
                        }
                    }
                    DisclosureGroup("Item-type drills") {
                        ForEach(blueprint.sections, id: \.id) { section in
                            ForEach(section.items, id: \.type) { spec in
                                let have = coverage[spec.type] ?? 0
                                Button {
                                    let type = spec.type
                                    start { try await $0.jlptTypeDrill(level: Int32(level), type: type, seed: Self.seed()) }
                                } label: {
                                    LabeledContent(spec.title, value: "\(have) items")
                                }
                                .disabled(have == 0)
                            }
                        }
                    }
                }
            } else {
                Text("No blueprint for N\(level).").foregroundStyle(.secondary)
            }
        } header: {
            Text("JLPT")
        }
    }

    private var dlptSection: some View {
        Section {
            Text("\(dlptReading) reading and \(dlptListening) listening items in the bank. Passages are Japanese; questions and answers are English. Listening audio plays once, as on the real test.")
                .font(.caption).foregroundStyle(.secondary)
            Picker("Range", selection: $dlptUpper) {
                Text("Lower range (ILR 0+–3)").tag(false)
                Text("Upper range (ILR 3–4)").tag(true)
            }
            .pickerStyle(.segmented)
            .onChange(of: dlptUpper) { _, _ in
                dlptFilter = []
                Task { await loadDlptTypes() }
            }
            if dlptUpper {
                Text("The upper-range test is for examinees who reach ILR 3 on the lower range.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let dlptTypesError {
                ErrorRetryView(message: dlptTypesError) { Task { await loadDlptTypes() } }
            } else if !dlptTypes.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Text types (none selected = all)").font(.caption.weight(.semibold))
                    FlowLayout(spacing: 6) {
                        ForEach(dlptTypes) { option in
                            let on = dlptFilter.contains(option.textType)
                            Button {
                                if on { dlptFilter.remove(option.textType) } else { dlptFilter.insert(option.textType) }
                            } label: {
                                Text(verbatim: "\(option.textType) · \(option.reading + option.listening)")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .tint(on ? .accentColor : .secondary)
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                }
            }
            ForEach([false, true], id: \.self) { listening in
                let have = dlptAvailable(listening: listening)
                HStack {
                    Text(listening ? "Listening" : "Reading")
                    Spacer()
                    ForEach([180, 60, 30], id: \.self) { minutes in
                        Button(minutes == 180 ? "Full" : "\(minutes)′") {
                            let upper = dlptUpper
                            let types = Array(dlptFilter)
                            start { try await SwiftSupport.shared.dlptFiltered(exams: $0, listening: listening, minutes: Int32(minutes), upper: upper, textTypes: types) }
                        }
                        .buttonStyle(.bordered)
                        .disabled(have == 0)
                    }
                }
            }
        } header: {
            Text(dlptUpper ? "DLPT (ILR 3–4)" : "DLPT (ILR 0+–3)")
        }
    }

    /// Items the filtered form can draw on: the chips' counts when the type list loaded, else the bank totals.
    private func dlptAvailable(listening: Bool) -> Int {
        if dlptTypes.isEmpty { return dlptUpper ? 0 : (listening ? dlptListening : dlptReading) }
        let chosen = dlptTypes.filter { dlptFilter.isEmpty || dlptFilter.contains($0.textType) }
        return chosen.reduce(0) { $0 + (listening ? $1.listening : $1.reading) }
    }

    /// Text types for both DLPT kinds in the chosen range, merged by name (reading and listening counts kept apart).
    private func loadDlptTypes() async {
        guard let exams else { return }
        let upper = dlptUpper
        do {
            let reading = try await SwiftSupport.shared.dlptTextTypes(exams: exams, listening: false, upper: upper)
            let listening = try await SwiftSupport.shared.dlptTextTypes(exams: exams, listening: true, upper: upper)
            var merged: [String: DlptTypeOption] = [:]
            for row in reading {
                merged[row.textType, default: DlptTypeOption(textType: row.textType)].reading += Int(row.items)
            }
            for row in listening {
                merged[row.textType, default: DlptTypeOption(textType: row.textType)].listening += Int(row.items)
            }
            guard upper == dlptUpper else { return }
            dlptTypes = merged.values.sorted { ($0.reading + $0.listening, $1.textType) > ($1.reading + $1.listening, $0.textType) }
            dlptTypesError = nil
        } catch {
            dlptTypes = []
            dlptTypesError = String(localized: "Couldn't list the DLPT text types: \(error.localizedDescription)")
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        let service: ExamService
        do {
            service = try await app.graph.exams()
            loadError = nil
        } catch {
            loadError = String(localized: "Couldn't open the exams: \(error.localizedDescription)")
            return
        }
        exams = service
        let blueprints = try? await service.blueprints()
        hasBlueprints = blueprints != nil
        blueprint = blueprints?.level(level: Int32(level))
        let all = (try? await service.coverage()) ?? []
        var types: [String: Int] = [:]
        var reading = 0
        var listening = 0
        for c in all {
            if c.exam == .jlpt && c.level == "N\(level)" {
                for (k, v) in c.types { types[k] = Int(v.intValue) }
            } else if c.exam == .dlptReading {
                reading += Int(c.total)
            } else if c.exam == .dlptListening {
                listening += Int(c.total)
            }
        }
        coverage = types
        dlptReading = reading
        dlptListening = listening
        history = (try? await service.history(exam: nil, limit: 50)) ?? []
        inProgress = try? await service.inProgress()
        await loadDlptTypes()
    }

    /// Reopens the saved attempt; sections whose time ran out while away are already closed (shared ExamSession).
    private func resume() {
        guard let exams, !building else { return }
        message = nil
        building = true
        Task {
            defer { building = false }
            do {
                guard let session = try await exams.resume() else {
                    message = String(localized: "That attempt can't be restored any more.")
                    await load()
                    return
                }
                running = RunningExam(session: session)
            } catch {
                message = String(localized: "Couldn't resume: \(error.localizedDescription)")
            }
        }
    }

    private func start(_ build: @escaping (ExamService) async throws -> ExamSession?) {
        guard let exams, !building else { return }
        message = nil
        building = true
        Task {
            defer { building = false }
            let built: ExamSession?
            do {
                built = try await build(exams)
            } catch {
                message = String(localized: "Couldn't build that test: \(error.localizedDescription)")
                return
            }
            guard let session = built else {
                message = String(localized: "Couldn't build that test from the installed items.")
                return
            }
            if session.form.isEmpty {
                message = String(localized: "There aren't enough items in the bank for that test yet.")
                return
            }
            // Starts saving the attempt after every answer, so it survives the app being closed (F-24).
            session.begin()
            running = RunningExam(session: session)
        }
    }

    static func seed() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func date(_ epochMs: Int64) -> String {
        Date(timeIntervalSince1970: Double(epochMs) / 1000).formatted(date: .abbreviated, time: .shortened)
    }
}

/// Me → Import → exam item banks: import JSON banks (validated in shared code), list and delete them.
struct ExamBanksView: View {
    @Environment(AppModel.self) private var app
    private struct BankRow: Identifiable {
        let id: String
        let title: String
        let items: Int
    }

    @State private var banks: [BankRow] = []
    @State private var picking = false
    @State private var busy = false
    @State private var message: String?
    @State private var errors: [String] = []

    var body: some View {
        Form {
            Section {
                Button("Import item bank (.json)") { picking = true }.disabled(busy)
                if busy { ProgressView() }
                if let message { Text(message).font(.subheadline) }
                ForEach(Array(errors.prefix(50).enumerated()), id: \.offset) { _, e in
                    Text(e).font(.caption).foregroundStyle(.red)
                }
                if errors.count > 50 { Text("…and \(errors.count - 50) more problems.").font(.caption) }
            } footer: {
                Text("A JSON item bank in the format described in docs/CONTENT_PACKS.md. Every problem is listed if it doesn't validate. Only import banks you have the right to use; official JLPT/DLPT items are copyrighted.")
            }
            Section("Your banks") {
                if banks.isEmpty { Text("None imported.").foregroundStyle(.secondary) }
                ForEach(banks) { b in
                    LabeledContent(b.title, value: "\(b.items) items")
                        .swipeActions {
                            Button("Delete", role: .destructive) { delete(b.id) }
                        }
                }
            }
        }
        .navigationTitle("Exam item banks")
        .task { await refresh() }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.json, .plainText, .data]) { result in
            importBank(result)
        }
    }

    private func importBank(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        let data = try? Data(contentsOf: url)
        if scoped { url.stopAccessingSecurityScopedResource() }
        guard let data, let text = String(data: data, encoding: .utf8) else {
            message = "Couldn't read the file as UTF-8 text."
            return
        }
        busy = true
        errors = []
        message = "Checking…"
        let graph = app.graph
        Task {
            defer { busy = false }
            guard let exams = try? await graph.exams(), let outcome = try? await exams.importBank(text: text) else {
                message = "Import failed."
                return
            }
            switch onEnum(of: outcome) {
            case .imported(let r):
                message = "Imported \(r.bank): \(r.items) items, \(r.passages) passages."
            case .invalid(let r):
                message = "Not imported: \(r.errors.count) problem\(r.errors.count == 1 ? "" : "s")."
                errors = r.errors
            }
            await refresh()
        }
    }

    private func delete(_ id: String) {
        let graph = app.graph
        Task {
            try? await graph.exams().deleteBank(id: id)
            await refresh()
        }
    }

    private func refresh() async {
        guard let exams = try? await app.graph.exams() else { return }
        let list = (try? await exams.userBanks()) ?? []
        banks = list.map { BankRow(id: $0.bank, title: $0.title, items: $0.items.count) }
    }
}
