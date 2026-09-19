import Shared
import SwiftUI

// Interest and domain tracks (BRIEF_V2 §6.5, D-210…D-219; iOS UI D-262). The Kotlin `Track` clashes with the
// SQLDelight row class of the same name, so Swift never names it: every screen copies what it shows into the
// structs below.

/// One track as the picker and the tracks screen show it.
struct TrackOption: Identifiable, Hashable {
    let id: String
    let titleEn: String
    let titleJa: String
    let description: String
    let inspiredBy: String
    let levelLabel: String
    let words: Int
    let kanji: Int
    let scenarios: Int
    let dialogues: Int
    let drills: Int
    let wordsLeft: Int
    var selected: Bool
    let aiGenerated: Bool

    init(_ s: TrackSummary) {
        let t = s.track
        id = t.id
        titleEn = t.titleEn
        titleJa = t.titleJa
        description = SwiftSupport.shared.trackDescription(summary: s)
        inspiredBy = t.inspiredBy
        levelLabel = t.levelLabel
        words = Int(t.count(key: "words"))
        kanji = Int(t.count(key: "kanji"))
        scenarios = Int(t.count(key: "scenarios"))
        dialogues = Int(t.count(key: "dialogues"))
        drills = Int(t.count(key: "drills"))
        wordsLeft = Int(s.wordsLeft)
        selected = s.selected
        aiGenerated = t.isAiGenerated
    }

    /// "478 words · 142 kanji · 10 role-plays · 10 dialogues · 40 drills"
    var countsLine: String {
        String(localized: "\(String(words)) words · \(String(kanji)) kanji · \(String(scenarios)) role-plays · \(String(dialogues)) dialogues · \(String(drills)) drills")
    }
}

/// A track row: titles, level, counts and the AI badge.
struct TrackOptionRow: View {
    let option: TrackOption

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline) {
                Text(option.titleEn).font(.headline)
                Text(option.titleJa).font(.japanese(size: 14)).foregroundStyle(.secondary).japaneseSpeech()
            }
            Text(option.description).font(.caption).foregroundStyle(.secondary).lineLimit(3)
            HStack(spacing: 6) {
                TagView(option.levelLabel)
                if option.aiGenerated { AiBadge() }
            }
            Text(verbatim: option.countsLine).font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// Loads the track options; the tracks pack missing is an honest empty state, an error has Retry (F-33).
@MainActor
@Observable
final class TrackOptionsModel {
    var options: [TrackOption]?
    var error: String?

    func load(_ graph: AppGraph) async {
        do {
            options = try await graph.tracks.tracks().map { TrackOption($0) }
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the tracks: \(error.localizedDescription)")
        }
    }
}

/// Onboarding step (D-218): pick any number of tracks, or none for the main path only.
struct TrackOnboardingStep: View {
    @Environment(AppModel.self) private var app
    let onDone: () -> Void
    @State private var model = TrackOptionsModel()
    @State private var chosen: Set<String> = []
    @State private var saving = false
    @State private var saveError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Any special interests?").font(.title2.weight(.semibold))
            Text("A track adds themed words, kanji, role-plays, dialogues and drills. Its words join your daily lessons next to the main path. You can change tracks any time in Learn → Tracks.")
            if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load(app.graph) } }
            } else if let options = model.options {
                if options.isEmpty {
                    Text("The tracks pack isn't installed in this build, so there's nothing to pick yet.").foregroundStyle(.secondary)
                }
                ForEach(options) { option in
                    Button {
                        if chosen.contains(option.id) { chosen.remove(option.id) } else { chosen.insert(option.id) }
                    } label: {
                        HStack(alignment: .top) {
                            TrackOptionRow(option: option)
                            Spacer()
                            Image(systemName: chosen.contains(option.id) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(chosen.contains(option.id) ? Color.accentColor : Color.secondary)
                                .accessibilityHidden(true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                        .background(.quaternary.opacity(chosen.contains(option.id) ? 0.6 : 0.25), in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(chosen.contains(option.id) ? .isSelected : [])
                }
            } else {
                ProgressView()
            }
            if let saveError { Text(saveError).font(.caption).foregroundStyle(.red) }
            Button(chosen.isEmpty ? String(localized: "Skip — main path only") : String(localized: "Next")) { save() }
                .buttonStyle(.borderedProminent)
                .disabled(saving)
        }
        .task {
            await model.load(app.graph)
            chosen = Set((model.options ?? []).filter(\.selected).map(\.id))
        }
    }

    private func save() {
        saving = true
        saveError = nil
        let ids = (model.options ?? []).map(\.id).filter { chosen.contains($0) }
        let graph = app.graph
        Task {
            defer { saving = false }
            do {
                try await graph.tracks.chooseInOnboarding(trackIds: ids)
                onDone()
            } catch {
                saveError = String(localized: "Couldn't save your tracks: \(error.localizedDescription)")
            }
        }
    }
}

/// Learn → Tracks: every track with its selection; switch any time (D-218).
struct TracksView: View {
    @Environment(AppModel.self) private var app
    @State private var model = TrackOptionsModel()
    @State private var actionError: String?

    var body: some View {
        List {
            if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load(app.graph) } }
            } else if let options = model.options {
                if options.isEmpty {
                    ContentUnavailableView(
                        "Tracks not installed",
                        systemImage: "point.3.connected.trianglepath.dotted",
                        description: Text("This build has no tracks pack. Build it with `uv run python packs/build_tracks.py` in tools/ and rebuild the app.")
                    )
                } else {
                    Section {
                        Text("Selected tracks add their words to your daily lessons (up to half of each batch), next to the main path.")
                            .font(.caption).foregroundStyle(.secondary)
                        if let actionError { Text(actionError).font(.caption).foregroundStyle(.red) }
                    }
                    Section {
                        ForEach(options) { option in
                            VStack(alignment: .leading, spacing: 6) {
                                NavigationLink(value: Route.track(option.id)) { TrackOptionRow(option: option) }
                                HStack {
                                    Toggle(isOn: Binding(get: { option.selected }, set: { on in toggle(option.id, on: on) })) {
                                        Text(option.selected
                                             ? String(localized: "In my lessons · \(String(option.wordsLeft)) words left")
                                             : String(localized: "Add to my lessons"))
                                            .font(.caption)
                                    }
                                }
                                if !option.selected || options.filter(\.selected).count > 1 {
                                    Button("Only this track") { only(option.id) }
                                        .font(.caption)
                                        .buttonStyle(.borderless)
                                }
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Tracks")
        .task { await model.load(app.graph) }
    }

    private func toggle(_ id: String, on: Bool) {
        let graph = app.graph
        Task {
            do {
                if on { try await graph.tracks.select(trackId: id) } else { try await graph.tracks.deselect(trackId: id) }
                actionError = nil
            } catch {
                actionError = String(localized: "Couldn't change your tracks: \(error.localizedDescription)")
            }
            await model.load(graph)
        }
    }

    private func only(_ id: String) {
        let graph = app.graph
        Task {
            do {
                try await graph.tracks.switchTo(trackId: id)
                actionError = nil
            } catch {
                actionError = String(localized: "Couldn't change your tracks: \(error.localizedDescription)")
            }
            await model.load(graph)
        }
    }
}

// MARK: - Track page

/// Everything on a track page, copied out of the Kotlin models.
private struct TrackPage {
    struct Word: Identifiable {
        let id: Int
        let entryId: Int64
        let text: String
        let reading: String
        let gloss: String
        let category: String
        let aiGenerated: Bool
    }

    struct Lesson: Identifiable {
        let id: Int
        let topic: String
        let words: [Word]
    }

    struct Kanji: Identifiable {
        let literal: String
        let keyword: String
        let breakdown: String
        let hint: String
        let aiGenerated: Bool
        var id: String { literal }
    }

    struct Situation: Identifiable {
        let id: String
        let titleEn: String
        let titleJa: String
        let canDo: [CanDoItem]
        let aiGenerated: Bool
    }

    struct CanDoItem: Identifiable {
        let en: String
        let ja: String
        /// The synced key (`<situation id>/<index>`).
        let id: String
    }

    struct DrillCount: Identifiable {
        let type: String
        let count: Int
        var id: String { type }
    }

    struct Culture: Identifiable {
        let id: String
        let titleEn: String
        let titleJa: String
        let place: String
        let before: [String]
        let during: [String]
        let after: [String]
        let phrases: [String]
        let etiquette: [String]
        let aiGenerated: Bool
    }

    struct ReadingQ: Identifiable {
        let id: Int
        let question: String
        let choices: [String]
        let answer: Int
    }

    struct Reading: Identifiable {
        let id: String
        let title: String
        let ilr: String
        let genre: String
        let body: String
        let questions: [ReadingQ]
        let aiGenerated: Bool
    }

    struct LinkRow: Identifiable {
        let id: Int
        let title: String
        let url: String
        let note: String
    }

    struct DialogueRow: Identifiable {
        let id: String
        let title: String
        let jlpt: Int
        let topic: String
        let aiGenerated: Bool
    }

    let option: TrackOption?
    let lessons: [Lesson]
    let kanji: [Kanji]
    let scenarios: [ScenarioSummary]
    let dialogues: [DialogueRow]
    let drillCounts: [DrillCount]
    let situations: [Situation]
    let tasks: [Culture]
    let readings: [Reading]
    let links: [LinkRow]
}

struct TrackPageView: View {
    let trackId: String
    @Environment(AppModel.self) private var app
    @State private var page: TrackPage?
    @State private var missing = false
    @State private var error: String?
    @State private var canDo: Set<String> = []
    @State private var canDoError: String?

    var body: some View {
        List {
            if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else if let page {
                content(page)
            } else if missing {
                ContentUnavailableView("Track not found", systemImage: "questionmark.folder")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(page?.option?.titleEn ?? String(localized: "Track"))
        .task { await load() }
    }

    @ViewBuilder
    private func content(_ p: TrackPage) -> some View {
        if let o = p.option {
            Section {
                TrackOptionRow(option: o)
                if !o.inspiredBy.isEmpty {
                    Text("Structure inspired by \(o.inspiredBy); no content was copied.").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        if !p.lessons.isEmpty {
            Section("Words") {
                ForEach(p.lessons) { lesson in
                    DisclosureGroup {
                        ForEach(lesson.words) { w in
                            NavigationLink(value: Route.entry(w.entryId)) {
                                HStack(alignment: .firstTextBaseline) {
                                    Text(w.text).font(.japanese(size: 18)).japaneseSpeech()
                                    Text(w.reading).font(.japanese(size: 13)).foregroundStyle(.secondary)
                                    Spacer()
                                    Text(w.gloss).font(.caption).multilineTextAlignment(.trailing)
                                }
                            }
                        }
                    } label: {
                        Text("\(String(lesson.id + 1)). \(lesson.topic) · \(String(lesson.words.count)) words")
                    }
                }
            }
        }
        if !p.kanji.isEmpty {
            Section("Kanji") {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 8) {
                        ForEach(p.kanji) { k in
                            NavigationLink(value: Route.kanji(k.literal)) {
                                VStack(spacing: 2) {
                                    Text(k.literal).font(.japanese(size: 30, relativeTo: .title))
                                    Text(k.keyword).font(.caption2).lineLimit(1)
                                }
                                .frame(width: 64, height: 64)
                                .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                let hinted = p.kanji.filter { !$0.hint.isEmpty || !$0.breakdown.isEmpty }
                if !hinted.isEmpty {
                    DisclosureGroup("Breakdowns and memory hints") {
                        ForEach(hinted) { k in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(k.literal).font(.japanese(size: 22))
                                    Text(k.keyword).font(.subheadline.weight(.semibold))
                                    if k.aiGenerated { AiBadge() }
                                }
                                if !k.breakdown.isEmpty { Text(k.breakdown).font(.caption) }
                                if !k.hint.isEmpty { Text(k.hint).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        }
        if !p.drillCounts.isEmpty {
            Section("Drills") {
                ForEach(p.drillCounts) { d in
                    NavigationLink(value: Route.trackDrills(trackId: trackId, type: d.type)) {
                        LabeledContent(TrackDrillText.typeName(d.type), value: String(d.count))
                    }
                }
            }
        }
        if !p.scenarios.isEmpty {
            Section("Role-plays") {
                ForEach(p.scenarios) { s in
                    NavigationLink(value: Route.roleplay(s.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.titleEn)
                            HStack {
                                Text(s.titleJa).font(.japanese(size: 13)).foregroundStyle(.secondary)
                                if s.aiGenerated { AiBadge() }
                            }
                        }
                    }
                }
            }
        }
        if !p.dialogues.isEmpty {
            Section("Dialogues") {
                ForEach(p.dialogues) { d in
                    NavigationLink(value: Route.dialogue(d.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.title).font(.japanese(size: 16))
                            HStack {
                                Text("N\(String(d.jlpt)) · \(d.topic)").font(.caption).foregroundStyle(.secondary)
                                if d.aiGenerated { AiBadge() }
                            }
                        }
                    }
                }
            }
        }
        if !p.situations.isEmpty {
            Section {
                if let canDoError { Text(canDoError).font(.caption).foregroundStyle(.red) }
                ForEach(p.situations) { s in
                    DisclosureGroup {
                        ForEach(s.canDo) { item in
                            Toggle(isOn: Binding(get: { canDo.contains(item.id) }, set: { setCanDo(item.id, $0) })) {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(item.ja).font(.japanese(size: 15)).japaneseSpeech()
                                    Text(item.en).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(s.titleEn)
                                Text(s.titleJa).font(.japanese(size: 13)).foregroundStyle(.secondary)
                            }
                            Spacer()
                            let done = s.canDo.filter { canDo.contains($0.id) }.count
                            Text(verbatim: "\(done)/\(s.canDo.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text("I can…")
            } footer: {
                Text("Tick what you can already do. Your ticks sync to your other devices.")
            }
        }
        if !p.tasks.isEmpty {
            Section("Cultural experiences") {
                ForEach(p.tasks) { t in
                    DisclosureGroup {
                        cultureBody(t)
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(t.titleEn)
                            Text(t.titleJa).font(.japanese(size: 13)).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        if !p.readings.isEmpty {
            Section("ILR readings") {
                ForEach(p.readings) { r in
                    DisclosureGroup {
                        TrackReadingBody(reading: r)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.title).font(.japanese(size: 16))
                            HStack {
                                Text(verbatim: "ILR \(r.ilr) · \(r.genre)").font(.caption).foregroundStyle(.secondary)
                                if r.aiGenerated { AiBadge() }
                            }
                        }
                    }
                }
            }
        }
        if !p.links.isEmpty {
            Section {
                ForEach(p.links) { l in
                    if let url = URL(string: l.url) {
                        Link(destination: url) {
                            VStack(alignment: .leading, spacing: 2) {
                                Label(l.title, systemImage: "arrow.up.right.square")
                                if !l.note.isEmpty { Text(l.note).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            } header: {
                Text("Links")
            } footer: {
                Text("Official texts are linked, never copied. They open in your browser and need a connection.")
            }
        }
    }

    @ViewBuilder
    private func cultureBody(_ t: TrackPage.Culture) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if !t.place.isEmpty { Label(t.place, systemImage: "mappin.and.ellipse").font(.caption) }
            if t.aiGenerated { AiBadge() }
            list(String(localized: "Before"), t.before)
            list(String(localized: "During"), t.during)
            list(String(localized: "After"), t.after)
            list(String(localized: "Phrases"), t.phrases, japanese: true)
            list(String(localized: "Etiquette"), t.etiquette)
        }
    }

    @ViewBuilder
    private func list(_ title: String, _ items: [String], japanese: Bool = false) -> some View {
        if !items.isEmpty {
            Text(verbatim: title).font(.caption.weight(.semibold))
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                Text(verbatim: "• \(item)")
                    .font(japanese ? Font.japanese(size: 15) : Font.subheadline)
                    .japaneseSpeech(japanese)
            }
        }
    }

    private func setCanDo(_ key: String, _ done: Bool) {
        if done { canDo.insert(key) } else { canDo.remove(key) }
        let graph = app.graph
        Task {
            do {
                try await graph.tracks.setCanDo(canDoId: key, done: done)
                canDoError = nil
            } catch {
                canDoError = String(localized: "Couldn't save that: \(error.localizedDescription)")
            }
        }
    }

    private func load() async {
        do {
            guard let repo = try await app.graph.trackRepository(), try await repo.track(id: trackId) != nil else {
                missing = true
                return
            }
            let option = try await app.graph.tracks.tracks().first(where: { $0.track.id == trackId }).map { TrackOption($0) }
            let lessons = try await SwiftSupport.shared.trackLessons(repo: repo, trackId: trackId).enumerated().map { i, l in
                TrackPage.Lesson(id: i, topic: l.topic, words: l.words.map { w in
                    TrackPage.Word(
                        id: Int(w.ord), entryId: w.entryId, text: w.text, reading: w.reading, gloss: w.gloss,
                        category: w.category, aiGenerated: w.isAiGenerated
                    )
                })
            }
            let kanji = try await repo.kanji(trackId: trackId).map { k in
                TrackPage.Kanji(literal: k.literal, keyword: k.keyword, breakdown: k.breakdown, hint: k.hint, aiGenerated: k.isAiGenerated)
            }
            let scenarios = try await repo.scenarios(trackId: trackId).map {
                ScenarioSummary(id: $0.id, titleEn: $0.titleEn, titleJa: $0.titleJa, jlpt: Int($0.jlpt), ilr: $0.ilr, category: $0.category, aiGenerated: $0.isAiGenerated)
            }
            let dialogues = try await repo.dialogues(trackId: trackId).map {
                TrackPage.DialogueRow(id: $0.id, title: $0.title, jlpt: Int($0.jlpt), topic: $0.topic, aiGenerated: $0.isAiGenerated)
            }
            let drills = try await SwiftSupport.shared.trackDrills(repo: repo, trackId: trackId, typeCode: "")
            var counts: [String: Int] = [:]
            var order: [String] = []
            for d in drills {
                let code = SwiftSupport.shared.drillTypeCode(drill: d)
                if counts[code] == nil { order.append(code) }
                counts[code, default: 0] += 1
            }
            let situations = try await repo.situations(trackId: trackId).map { s in
                TrackPage.Situation(
                    id: s.id, titleEn: s.titleEn, titleJa: s.titleJa,
                    canDo: s.canDo.enumerated().map { i, c in TrackPage.CanDoItem(en: c.en, ja: c.ja, id: s.canDoId(index: Int32(i))) },
                    aiGenerated: s.isAiGenerated
                )
            }
            let tasks = try await repo.tasks(trackId: trackId).map { t in
                TrackPage.Culture(
                    id: t.id, titleEn: t.titleEn, titleJa: t.titleJa, place: t.place, before: t.before, during: t.during,
                    after: t.after, phrases: t.phrases, etiquette: t.etiquette, aiGenerated: t.isAiGenerated
                )
            }
            let readings = try await repo.readings(trackId: trackId).map { r in
                TrackPage.Reading(
                    id: r.id, title: r.title, ilr: r.ilr, genre: r.genre, body: r.body,
                    questions: r.questions.enumerated().map { i, q in
                        TrackPage.ReadingQ(id: i, question: q.question, choices: q.choices, answer: Int(q.answer))
                    },
                    aiGenerated: r.isAiGenerated
                )
            }
            let links = try await repo.links(trackId: trackId).enumerated().map { i, l in
                TrackPage.LinkRow(id: i, title: l.title, url: l.url, note: l.note)
            }
            canDo = (try? await app.graph.tracks.canDoDone()) ?? []
            page = TrackPage(
                option: option, lessons: lessons, kanji: kanji, scenarios: scenarios, dialogues: dialogues,
                drillCounts: order.map { TrackPage.DrillCount(type: $0, count: counts[$0] ?? 0) }, situations: situations, tasks: tasks,
                readings: readings, links: links
            )
            error = nil
        } catch {
            self.error = String(localized: "Couldn't open the track: \(error.localizedDescription)")
        }
    }
}

/// An ILR reading with its questions (answers checked on tap).
private struct TrackReadingBody: View {
    let reading: TrackPage.Reading
    @State private var chosen: [Int: Int] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(reading.body).font(.japanese(size: 17)).textSelection(.enabled).japaneseSpeech()
            ForEach(reading.questions) { q in
                VStack(alignment: .leading, spacing: 4) {
                    Text(q.question).font(.subheadline.weight(.semibold))
                    ForEach(Array(q.choices.enumerated()), id: \.offset) { i, choice in
                        Button {
                            if chosen[q.id] == nil { chosen[q.id] = i }
                        } label: {
                            HStack {
                                Text(choice).multilineTextAlignment(.leading)
                                Spacer()
                                if let c = chosen[q.id] {
                                    if i == q.answer { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                    else if i == c { Image(systemName: "xmark.circle.fill").foregroundStyle(.red) }
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
    }
}
