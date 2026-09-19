import Shared
import SwiftUI
import UIKit

/// "Mark known" on a dictionary entry (BRIEF_V2 §6.1, D-151): the word counts as known for coverage, decks and 1T
/// without an SRS item. Synced. Words already at Guru or above in SRS are known anyway.
struct MarkKnownButton: View {
    @Environment(AppModel.self) private var app
    let entryId: Int64

    @State private var state: WordState?
    @State private var marked = false
    @State private var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if marked {
                    Label("Marked known", systemImage: "checkmark.seal").font(.subheadline).foregroundStyle(.green)
                    Button("Undo") { set(false) }.font(.caption)
                } else if state == .known {
                    Label("Known from your reviews", systemImage: "checkmark.seal").font(.subheadline).foregroundStyle(.green)
                } else if state != nil {
                    Button {
                        set(true)
                    } label: {
                        Label("I know this word", systemImage: "checkmark.seal")
                    }
                    .buttonStyle(.bordered)
                    .font(.subheadline)
                }
            }
            if let note { Text(note).font(.caption).foregroundStyle(.orange) }
        }
        .task(id: entryId) { await load() }
    }

    private func load() async {
        state = try? await app.graph.knownWords.state(entryId: entryId)
        let rows = (try? await app.graph.knownWords.rows()) ?? []
        marked = rows.contains { $0.entryId == entryId && $0.known }
    }

    private func set(_ known: Bool) {
        let words = app.graph.knownWords
        let ids = [KotlinLong(longLong: entryId)]
        Task {
            do {
                if known {
                    try await words.markKnown(entryIds: ids, source: "MANUAL")
                } else {
                    try await words.markUnknown(entryIds: ids, source: "MANUAL")
                }
                note = nil
            } catch {
                note = String(localized: "Couldn't save that: \(error.localizedDescription)")
            }
            await load()
        }
    }
}

/// The entry's sentences grouped by source (BRIEF_V2 §6.2, D-160): lines from the learner's own media (clip,
/// frame, "Mine"), Tatoeba, and Immersion Kit when the learner turned it on (live, never stored, D-162).
struct EntrySentencesSection: View {
    @Environment(AppModel.self) private var app
    let entry: DictionaryEntry

    @State private var rows: SentenceSearchRows?
    @State private var loadError: String?
    @State private var voice = VoicePlayer()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader("Sentences")
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            } else if let rows {
                if rows.library.isEmpty && rows.tatoeba.isEmpty && rows.online.isEmpty {
                    Text("No example sentences for this word yet. Lines from your own videos and audio appear here once they have subtitles.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if !rows.library.isEmpty {
                    Text("From your media").font(.subheadline.weight(.semibold))
                    ForEach(groups(rows.library), id: \.title) { group in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(group.title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                            ForEach(Array(group.hits.enumerated()), id: \.offset) { _, hit in
                                LibraryHitRow(hit: hit, entry: entry, voice: voice)
                            }
                        }
                    }
                }
                if !rows.tatoeba.isEmpty {
                    Text("Tatoeba").font(.subheadline.weight(.semibold))
                    ForEach(Array(rows.tatoeba.enumerated()), id: \.offset) { _, hit in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .firstTextBaseline) {
                                HighlightedLine(hit: hit, size: 17)
                                Spacer()
                                Button {
                                    Task { await voice.say(hit.japanese) }
                                } label: { Image(systemName: "speaker.wave.2") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(Text("Listen"))
                            }
                            if let en = hit.english { Text(en).font(.subheadline).foregroundStyle(.secondary) }
                        }
                    }
                    Text("Examples: Tatoeba (CC BY 2.0 FR)").font(.caption2).foregroundStyle(.tertiary)
                }
                onlineSection(rows)
            } else {
                ProgressView()
            }
        }
        .task(id: entry.id) { await load() }
        .onDisappear { voice.stop() }
    }

    @ViewBuilder
    private func onlineSection(_ rows: SentenceSearchRows) -> some View {
        if rows.onlineEnabled {
            Text(rows.onlineSourceName).font(.subheadline.weight(.semibold))
            if let failure = rows.onlineFailure {
                ErrorRetryView(message: String(localized: "\(rows.onlineSourceName) didn't answer: \(failure)")) { Task { await load() } }
            } else if rows.online.isEmpty {
                Text("No lines found there.").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(Array(rows.online.enumerated()), id: \.offset) { _, hit in
                VStack(alignment: .leading, spacing: 2) {
                    HighlightedLine(hit: hit, size: 16)
                    if let en = hit.english, !en.isEmpty { Text(en).font(.caption).foregroundStyle(.secondary) }
                    if let title = hit.mediaTitle { Text(title).font(.caption2).foregroundStyle(.tertiary) }
                }
            }
            Text("Shown live from \(rows.onlineSourceName), not saved. Turn it off in Settings → Example sentences.")
                .font(.caption2).foregroundStyle(.tertiary)
        } else {
            NavigationLink(value: Route.settings) {
                Text("More examples from anime and dramas (Immersion Kit, online, off) — Settings").font(.caption2)
            }
        }
    }

    private struct MediaGroup {
        let title: String
        let hits: [SentenceHit]
    }

    private func groups(_ hits: [SentenceHit]) -> [MediaGroup] {
        var order: [String] = []
        var byTitle: [String: [SentenceHit]] = [:]
        for hit in hits {
            let title = hit.mediaTitle ?? String(localized: "Untitled")
            if byTitle[title] == nil { order.append(title) }
            byTitle[title, default: []].append(hit)
        }
        return order.map { MediaGroup(title: $0, hits: byTitle[$0] ?? []) }
    }

    private func load() async {
        loadError = nil
        do {
            rows = try await SwiftSupport.shared.sentencesForEntry(
                graph: app.graph, entryId: entry.id, word: entry.headword, reading: entry.kana.first?.text, limit: 20
            )
        } catch {
            loadError = String(localized: "Couldn't load sentences: \(error.localizedDescription)")
        }
    }
}

/// A sentence with the shared highlight span in bold.
struct HighlightedLine: View {
    let hit: SentenceHit
    let size: CGFloat

    var body: some View {
        let ns = hit.japanese as NSString
        if hit.hasHighlight {
            let start = Int(hit.highlightStart)
            let end = Int(hit.highlightEnd)
            (Text(ns.substring(to: start)) + Text(ns.substring(with: NSRange(location: start, length: end - start))).bold().foregroundColor(.accentColor) + Text(ns.substring(from: end)))
                .font(.japanese(size: size))
                .textSelection(.enabled)
                .japaneseSpeech()
        } else {
            Text(hit.japanese).font(.japanese(size: size)).textSelection(.enabled).japaneseSpeech()
        }
    }
}

/// A line from the learner's own media: frame (video), play the clip, and mine it as a word or sentence card.
struct LibraryHitRow: View {
    @Environment(AppModel.self) private var app
    let hit: SentenceHit
    let entry: DictionaryEntry
    let voice: VoicePlayer

    @State private var thumbnail: UIImage?
    @State private var working = false
    @State private var note: String?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFill()
                    .frame(width: 96, height: 54).clipShape(RoundedRectangle(cornerRadius: 6))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 4) {
                HighlightedLine(hit: hit, size: 16)
                if let en = hit.english, !en.isEmpty { Text(en).font(.caption).foregroundStyle(.secondary) }
                HStack {
                    Button {
                        play()
                    } label: {
                        Label("Play", systemImage: "play.circle")
                    }
                    Menu {
                        Button("Word card (with this line)") { mine(.vocab) }
                        Button("Sentence card") { mine(.sentence) }
                    } label: {
                        Label(working ? "Mining…" : "Mine", systemImage: "plus.circle")
                    }
                    .disabled(working || hit.clip == nil)
                }
                .font(.caption)
                .buttonStyle(.borderless)
                if let note { Text(note).font(.caption2).foregroundStyle(.secondary) }
            }
        }
        .task {
            if hit.mediaKind == .video, let ms = hit.clip?.thumbnailMs?.int64Value {
                thumbnail = await ClipCache.thumbnail(locator: hit.locator, ms: ms)
            }
        }
    }

    private func play() {
        guard let clip = hit.clip else { return }
        note = nil
        let locator = hit.locator
        Task {
            do {
                let path = try await ClipCache.audio(locator: locator, startMs: clip.startMs, endMs: clip.endMs)
                if !(await voice.play(file: path)) { note = String(localized: "Couldn't play the clip.") }
            } catch {
                note = error.localizedDescription
            }
        }
    }

    private func mine(_ kind: MineKind) {
        guard let clip = hit.clip, let mediaId = hit.mediaId else { return }
        guard let opened = MediaLocator.open(hit.locator) else {
            note = ClipCache.MissingMedia().errorDescription
            return
        }
        working = true
        note = nil
        let start = hit.hasHighlight ? Int(hit.highlightStart) : 0
        let end = hit.hasHighlight ? Int(hit.highlightEnd) : 0
        let glosses = entry.senses.prefix(3).flatMap { $0.glosses.prefix(2) }
        let line = LineMiner.Line(
            kind: kind, mediaId: mediaId, mediaTitle: hit.mediaTitle ?? "", mediaKind: hit.mediaKind ?? .audio,
            startMs: clip.startMs, endMs: clip.endMs, sentence: hit.japanese,
            wordStart: start, wordEnd: end, reading: entry.kana.first?.text, meanings: Array(glosses),
            translation: hit.english, entryId: entry.id
        )
        let graph = app.graph
        Task {
            defer { MediaLocator.close(opened) }
            do {
                note = try await LineMiner.mine(line, source: opened.url, graph: graph)
            } catch {
                note = String(localized: "Couldn't mine this line: \(error.localizedDescription)")
            }
            working = false
        }
    }
}

/// Settings → Example sentences: the optional Immersion Kit source (D-162), off by default, per device.
struct ImmersionKitSettingsSection: View {
    @Environment(AppModel.self) private var app
    @State private var enabled = false

    var body: some View {
        Section {
            Toggle("Show examples from Immersion Kit", isOn: Binding(get: { enabled }, set: { on in
                enabled = on
                let online = app.graph.onlineExamples
                Task { try? await online.setEnabled(on: on) }
            }))
        } header: {
            Text("Example sentences")
        } footer: {
            Text("Off by default. When on, the dictionary sends the word you look up to Immersion Kit (apiv2.immersionkit.com, no account) and shows the lines it returns, from commercial anime, dramas and games. They are shown live, never saved and can't be mined. Immersion Kit publishes no terms of use, so use it for personal study only. Lines from your own media and Tatoeba always work offline.")
        }
        .task { enabled = (try? await app.graph.onlineExamples.enabled())?.boolValue ?? false }
    }
}
