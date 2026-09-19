import Shared
import SwiftUI

/// Poetry corner (BRIEF_V2 §6.14, D-276/D-277, D-307): public-domain 近代詩 from Aozora Bunko by theme. The poem is shown
/// untouched; only our annotations (vocabulary, paraphrase, gloss, note) carry the AI badge until reviewed.
struct PoetryHomeView: View {
    @Environment(AppModel.self) private var app

    private struct ThemeRow: Identifiable {
        let id: String
        let ja: String
        let en: String
        let count: Int
    }

    @State private var themes: [ThemeRow] = []
    @State private var total = 0
    @State private var loaded = false
    @State private var missing = false
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if !loaded {
                ProgressView()
            } else if missing {
                ContentUnavailableView(
                    "Poetry pack not installed",
                    systemImage: "text.book.closed",
                    description: Text("The poems come with the linguist pack. Build it with `uv run packs/literature/build_literature.py` in tools/ and rebuild the app.")
                )
            } else {
                Section {
                    NavigationLink(value: Route.poemTheme("")) {
                        LabeledContent("All poems", value: String(total))
                    }
                }
                Section("Themes") {
                    ForEach(themes) { t in
                        NavigationLink(value: Route.poemTheme(t.id)) {
                            HStack {
                                Text(verbatim: t.ja).font(.japanese(size: 20)).japaneseSpeech()
                                Text(verbatim: t.en).foregroundStyle(.secondary)
                                Spacer()
                                Text(verbatim: String(t.count)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section {
                    Text("Poems by poets who died before 1968, from Aozora Bunko (public domain in Japan). The paraphrases, glosses and notes are drafted by AI and labeled until reviewed.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Poetry corner")
        .task { await load() }
    }

    private func load() async {
        error = nil
        do {
            guard let repo = try await app.graph.poetry(), try await repo.available().boolValue else {
                missing = true
                loaded = true
                return
            }
            let all = try await repo.poems(theme: nil)
            var counts: [String: Int] = [:]
            for p in all { for t in p.themes { counts[t, default: 0] += 1 } }
            themes = try await repo.themes().map { ThemeRow(id: $0.id, ja: $0.ja, en: $0.en, count: counts[$0.id] ?? 0) }
            total = all.count
            missing = false
            loaded = true
        } catch {
            self.error = String(localized: "Couldn't load the poems: \(error.localizedDescription)")
        }
    }
}

/// Poems of one theme ("" = every poem).
struct PoemListView: View {
    @Environment(AppModel.self) private var app
    let themeId: String

    private struct Row: Identifiable {
        let id: String
        let title: String
        let titleEn: String
        let author: String
        let firstLine: String
    }

    @State private var rows: [Row]?
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let rows {
                if rows.isEmpty {
                    Text("No poems here yet.").foregroundStyle(.secondary)
                }
                ForEach(rows) { p in
                    NavigationLink(value: Route.poem(p.id)) {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(verbatim: p.title).font(.japanese(size: 18, weight: .semibold)).japaneseSpeech()
                                Text(verbatim: p.author).font(.japanese(size: 13)).foregroundStyle(.secondary).japaneseSpeech()
                            }
                            if !p.titleEn.isEmpty { Text(verbatim: p.titleEn).font(.caption).foregroundStyle(.secondary) }
                            Text(verbatim: p.firstLine).font(.japanese(size: 14)).lineLimit(1).japaneseSpeech()
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(themeId.isEmpty ? String(localized: "All poems") : String(localized: "Poems"))
        .task { await load() }
    }

    private func load() async {
        error = nil
        do {
            let repo = try await app.graph.poetry()
            let list = try await repo?.poems(theme: themeId.isEmpty ? nil : themeId) ?? []
            rows = list.map { Row(id: $0.id, title: $0.title, titleEn: $0.titleEn, author: $0.author, firstLine: $0.firstLine) }
        } catch {
            self.error = String(localized: "Couldn't load the poems: \(error.localizedDescription)")
        }
    }
}

/// One poem: the text as Aozora has it, then our labeled annotations and the source with its colophon.
struct PoemView: View {
    @Environment(AppModel.self) private var app
    let poemId: String

    @State private var poem: PoemDetail?
    @State private var loaded = false
    @State private var error: String?
    @State private var voice = VoicePlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if let poem {
                    content(poem)
                } else if loaded {
                    ContentUnavailableView("Not found", systemImage: "questionmark")
                } else {
                    ProgressView()
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(poem?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onDisappear { voice.stop() }
    }

    @ViewBuilder
    private func content(_ p: PoemDetail) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: p.title).font(.japanese(size: 24, weight: .semibold)).japaneseSpeech()
            Text(verbatim: p.author).font(.japanese(size: 15)).foregroundStyle(.secondary).japaneseSpeech()
            if !p.titleEn.isEmpty { Text(verbatim: p.titleEn).font(.subheadline).foregroundStyle(.secondary) }
        }
        // The poem itself: public domain, untouched, stanza breaks kept. No badge (D-277).
        Text(verbatim: p.body)
            .font(.japanese(size: 18))
            .lineSpacing(6)
            .textSelection(.enabled)
            .japaneseSpeech()
        Button {
            let text = p.body
            Task { await voice.say(text, rate: 0.85) }
        } label: {
            Label("Listen (system voice)", systemImage: "speaker.wave.2")
        }
        .buttonStyle(.bordered)
        if !p.ruby.isEmpty {
            DisclosureGroup("Readings in the text") {
                FlowLayout(spacing: 8) {
                    ForEach(Array(p.ruby.enumerated()), id: \.offset) { _, r in
                        Text(verbatim: "\(r.base)（\(r.reading)）").font(.japanese(size: 15)).japaneseSpeech()
                    }
                }
                .padding(.top, 4)
            }
        }
        if !p.vocabulary.isEmpty {
            HStack {
                SectionHeader("Vocabulary")
                if p.isAiGenerated { AiBadge() }
            }
            ForEach(Array(p.vocabulary.enumerated()), id: \.offset) { _, w in
                NavigationLink(value: Route.lookup(w.word)) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(verbatim: w.word).font(.japanese(size: 17)).japaneseSpeech()
                        Text(verbatim: w.reading).font(.japanese(size: 13)).foregroundStyle(.secondary).japaneseSpeech()
                        Text(verbatim: w.gloss).font(.subheadline).lineLimit(2)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
        }
        if !p.paraphrase.isEmpty {
            annotation("Plain Japanese", text: p.paraphrase, japanese: true, ai: p.isAiGenerated)
        }
        if !p.gloss.isEmpty {
            annotation("English gloss", text: p.gloss, japanese: false, ai: p.isAiGenerated)
        }
        if !p.note.isEmpty {
            annotation("Note", text: p.note, japanese: false, ai: p.isAiGenerated)
        }
        if let work = p.work {
            AozoraSourceCard(work: work)
        }
    }

    private func annotation(_ title: LocalizedStringKey, text: String, japanese: Bool, ai: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                SectionHeader(title)
                if ai { AiBadge() }
            }
            if japanese {
                Text(verbatim: text).font(.japanese(size: 16)).japaneseSpeech()
            } else {
                Text(verbatim: text)
            }
        }
    }

    private func load() async {
        error = nil
        do {
            poem = try await app.graph.poetry()?.poem(id: poemId)
            loaded = true
        } catch {
            self.error = String(localized: "Couldn't load the poem: \(error.localizedDescription)")
        }
    }
}

/// The Aozora Bunko source of a poem or circle text: author, dates, orthography and the colophon, which Aozora asks
/// to be shown with redistributed texts (D-276).
struct AozoraSourceCard: View {
    let work: AozoraSource

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionHeader("Source")
            Text(verbatim: "\(work.author)（\(work.authorEn), \(work.born)–\(work.died)）").font(.japanese(size: 14)).japaneseSpeech()
            Text(verbatim: "『\(work.title)』 · \(work.orthography)").font(.japanese(size: 14)).japaneseSpeech()
            Text("Public domain (Aozora Bunko)").font(.caption.weight(.semibold))
            if !work.colophon.isEmpty {
                Text(verbatim: work.colophon).font(.japanese(size: 11)).foregroundStyle(.secondary).japaneseSpeech()
            }
            if let url = URL(string: work.cardUrl), !work.cardUrl.isEmpty {
                Link(destination: url) { Label("Aozora Bunko card", systemImage: "safari") }.font(.caption)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
    }
}
