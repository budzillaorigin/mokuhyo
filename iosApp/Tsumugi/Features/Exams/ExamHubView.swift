import Shared
import SwiftUI
import UniformTypeIdentifiers

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
            ForEach([false, true], id: \.self) { listening in
                let have = listening ? dlptListening : dlptReading
                HStack {
                    Text(listening ? "Listening" : "Reading")
                    Spacer()
                    ForEach([180, 60, 30], id: \.self) { minutes in
                        Button(minutes == 180 ? "Full" : "\(minutes)′") {
                            start { try await SwiftSupport.shared.dlpt(exams: $0, listening: listening, minutes: Int32(minutes)) }
                        }
                        .buttonStyle(.bordered)
                        .disabled(have == 0)
                    }
                }
            }
        } header: {
            Text("DLPT (ILR 0+–3)")
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
