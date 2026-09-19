import Shared
import SwiftUI

// Expression thesaurus and collocations (BRIEF_V2 §6.13; D-272, D-273, D-306). Clusters and expressions are our own
// drafts (AI badge until reviewed); glosses are JMdict (EDRDG, CC BY-SA); Tatoeba examples are human translations.

/// Localized name of a thesaurus register code (casual | neutral | formal | literary).
func thesaurusRegisterLabel(_ code: String) -> String {
    switch code {
    case "casual": String(localized: "Casual")
    case "neutral": String(localized: "Neutral")
    case "formal": String(localized: "Formal")
    case "literary": String(localized: "register.literary", defaultValue: "Literary")
    default: code
    }
}

/// Emotion and scene clusters, with search.
struct ThesaurusHomeView: View {
    @Environment(AppModel.self) private var app

    private enum LoadState {
        case loading
        case missing
        case failed(String)
        case ready(ThesaurusRepository)
    }

    @State private var state: LoadState = .loading
    @State private var kind = "EMOTION"
    @State private var clusters: [ExpressionCluster] = []
    @State private var listError: String?
    @State private var query = ""
    @State private var results: [ExpressionCluster] = []

    var body: some View {
        Group {
            switch state {
            case .loading:
                ProgressView()
            case .missing:
                ContentUnavailableView(
                    "Thesaurus not installed",
                    systemImage: "text.book.closed",
                    description: Text("The expression thesaurus lives in the dictionary pack. Rebuild it with `uv run packs/build_thesaurus.py` in tools/ and rebuild the app.")
                )
            case .failed(let message):
                ContentUnavailableView {
                    Label("Couldn't open the thesaurus", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(verbatim: message)
                } actions: {
                    Button("Retry") { Task { await open() } }.buttonStyle(.borderedProminent)
                }
            case .ready(let repo):
                list(repo)
            }
        }
        .navigationTitle("Expression thesaurus")
        .task {
            if case .ready = state { return }
            await open()
        }
    }

    private func list(_ repo: ThesaurusRepository) -> some View {
        List {
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                Section {
                    Picker("Kind", selection: $kind) {
                        Text("Emotions").tag("EMOTION")
                        Text("Scenes").tag("SCENE")
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text("Descriptive expressions grouped by feeling or scene, with examples.")
                }
                if let listError {
                    ErrorRetryView(message: listError) { Task { await loadClusters(repo) } }
                }
                ForEach(clusters, id: \.id) { c in
                    NavigationLink(value: Route.thesaurusCluster(c.id)) { ClusterRow(cluster: c) }
                }
            } else {
                if results.isEmpty {
                    Text("No matching clusters").foregroundStyle(.secondary)
                }
                ForEach(results, id: \.id) { c in
                    NavigationLink(value: Route.thesaurusCluster(c.id)) { ClusterRow(cluster: c) }
                }
            }
        }
        .searchable(text: $query, prompt: "怒り, rain, 夜…")
        .task(id: kind) { await loadClusters(repo) }
        .task(id: query) {
            let text = query.trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { results = []; return }
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            results = (try? await repo.search(query: text)) ?? []
        }
    }

    private func open() async {
        state = .loading
        do {
            guard let repo = try await app.graph.thesaurus() else { state = .missing; return }
            let available = try await repo.available()
            state = available.boolValue ? .ready(repo) : .missing
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func loadClusters(_ repo: ThesaurusRepository) async {
        do {
            clusters = try await SwiftSupport.shared.thesaurusClusters(repo: repo, kindCode: kind)
            listError = nil
        } catch {
            listError = String(localized: "Couldn't load the clusters: \(error.localizedDescription)")
        }
    }
}

private struct ClusterRow: View {
    let cluster: ExpressionCluster

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(verbatim: cluster.ja).font(.japanese(size: 20)).japaneseSpeech()
                    Text(verbatim: cluster.reading).font(.japanese(size: 13)).foregroundStyle(.secondary).japaneseSpeech()
                }
                Text(verbatim: cluster.en).font(.subheadline)
            }
            Spacer()
            if cluster.isAiGenerated { AiBadge() }
        }
    }
}

/// One cluster: its expressions with nuance, register, strength and examples.
struct ThesaurusClusterView: View {
    let clusterId: String
    @Environment(AppModel.self) private var app
    @State private var detail: ClusterDetail?
    @State private var loaded = false
    @State private var error: String?
    @State private var voice = VoicePlayer()

    var body: some View {
        Group {
            if let error {
                VStack { ErrorRetryView(message: error) { Task { await load() } } }.padding()
            } else if let detail {
                content(detail)
            } else if loaded {
                ContentUnavailableView("Not found", systemImage: "questionmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(detail?.cluster.ja ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: clusterId) { await load() }
        .onDisappear { voice.stop() }
    }

    private func content(_ d: ClusterDetail) -> some View {
        let c = d.cluster
        return List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .lastTextBaseline, spacing: 8) {
                        Text(verbatim: c.ja).font(.japanese(size: 30)).japaneseSpeech()
                        Text(verbatim: c.reading).font(.japanese(size: 15)).foregroundStyle(.secondary).japaneseSpeech()
                    }
                    Text(verbatim: c.en).font(.headline)
                    Text(verbatim: SwiftSupport.shared.clusterDescription(cluster: c)).font(.subheadline)
                    if c.isAiGenerated { AiBadge() }
                }
            }
            if d.expressions.isEmpty {
                Text("This cluster has no expressions yet.").foregroundStyle(.secondary)
            }
            ForEach(Array(d.expressions.enumerated()), id: \.offset) { _, e in
                Section { expression(e, aiGenerated: c.isAiGenerated) }
            }
            Section {
                Text("Glosses: JMdict (EDRDG, CC BY-SA 4.0) · Examples: Tatoeba (CC BY 2.0 FR)")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }

    @ViewBuilder
    private func expression(_ e: ThesaurusExpression, aiGenerated: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(verbatim: e.text).font(.japanese(size: 22)).japaneseSpeech()
                if e.reading != e.text {
                    Text(verbatim: e.reading).font(.japanese(size: 13)).foregroundStyle(.secondary).japaneseSpeech()
                }
                Spacer()
                Button {
                    let text = e.text
                    Task { await voice.say(text) }
                } label: {
                    Image(systemName: "speaker.wave.2")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Listen")
            }
            if !e.gloss.isEmpty { Text(verbatim: e.gloss).font(.subheadline) }
            if !e.nuance.isEmpty { Text(verbatim: e.nuance).font(.caption).foregroundStyle(.secondary) }
            HStack(spacing: 8) {
                let register = SwiftSupport.shared.expressionRegister(expression: e)
                if !register.isEmpty { TagView(thesaurusRegisterLabel(register)) }
                intensity(Int(e.intensity))
            }
        }
        if !e.exampleJa.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: e.exampleJa).font(.japanese(size: 16)).japaneseSpeech()
                Text(verbatim: e.exampleEn).font(.caption).foregroundStyle(.secondary)
                if aiGenerated { AiBadge() }
            }
        }
        ForEach(e.tatoeba, id: \.sentenceId) { ex in
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: ex.ja).font(.japanese(size: 16)).japaneseSpeech()
                Text(verbatim: ex.en).font(.caption).foregroundStyle(.secondary)
            }
        }
        if let id = e.entryId {
            NavigationLink("Dictionary entry", value: Route.entry(id.int64Value))
                .font(.subheadline)
        }
    }

    private func intensity(_ n: Int) -> some View {
        let level = max(1, min(3, n))
        return Text(verbatim: String(repeating: "●", count: level) + String(repeating: "○", count: 3 - level))
            .font(.caption).foregroundStyle(.orange)
            .accessibilityLabel(Text("Strength \(String(level)) of 3"))
    }

    private func load() async {
        do {
            guard let repo = try await app.graph.thesaurus() else {
                loaded = true
                return
            }
            detail = try await repo.cluster(id: clusterId)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the cluster: \(error.localizedDescription)")
        }
        loaded = true
    }
}

/// On a dictionary entry: the thesaurus clusters that list it, and its strongest collocations (D-273).
/// Hidden when there is neither.
struct EntryExpressionsSection: View {
    let entryId: Int64
    @Environment(AppModel.self) private var app
    @State private var clusters: [ExpressionCluster] = []
    @State private var collocations: [CollocationPair] = []
    @State private var error: String?

    private static let patterns = ["NV", "AN", "AV"]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            }
            if !clusters.isEmpty {
                SectionHeader("Expressions")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(clusters, id: \.id) { c in
                            NavigationLink(value: Route.thesaurusCluster(c.id)) {
                                Text(verbatim: "\(c.ja) · \(c.en)").font(.japanese(size: 15))
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
            if !collocations.isEmpty {
                SectionHeader("Collocations")
                ForEach(Self.patterns, id: \.self) { code in
                    let rows = collocations.filter { SwiftSupport.shared.collocationPatternCode(pair: $0) == code }
                    if !rows.isEmpty {
                        Text(Self.patternTitle(code)).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(Array(rows.enumerated()), id: \.offset) { _, pair in
                            row(pair)
                        }
                    }
                }
                Text("Counted in the Tatoeba corpus (CC BY 2.0 FR).").font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .task(id: entryId) { await load() }
    }

    private func row(_ pair: CollocationPair) -> some View {
        let otherId = pair.firstId == entryId ? pair.secondId : pair.firstId
        return NavigationLink(value: Route.entry(otherId)) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .lastTextBaseline) {
                    Text(verbatim: pair.phrase).font(.japanese(size: 18)).japaneseSpeech()
                    Spacer()
                    Text(verbatim: "×\(pair.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                if let ex = pair.example {
                    Text(verbatim: ex.ja).font(.japanese(size: 14)).japaneseSpeech()
                    Text(verbatim: ex.en).font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }

    private static func patternTitle(_ code: String) -> String {
        switch code {
        case "NV": String(localized: "Noun + particle + verb")
        case "AN": String(localized: "Adjective + noun")
        default: String(localized: "Adverb + verb")
        }
    }

    private func load() async {
        do {
            guard let repo = try await app.graph.thesaurus() else { return }
            clusters = try await SwiftSupport.shared.clustersForEntry(repo: repo, entryId: entryId)
            collocations = try await repo.collocations(entryId: entryId, limit: 30)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load expressions and collocations: \(error.localizedDescription)")
        }
    }
}
