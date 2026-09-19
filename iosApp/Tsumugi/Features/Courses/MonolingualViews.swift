import Shared
import SwiftUI

// Monolingual mode (BRIEF_V2 §6.6, D-232…D-234; iOS UI D-265). One synced setting: the easiest JLPT level whose
// explanations are shown in Japanese only. Grammar uses our own Japanese text from the pack; words use a cached LLM
// paraphrase, generated only on a detail screen and always labeled AI-generated. Lists never generate.

/// Settings → Monolingual mode: on/off and "Japanese only from N…".
struct MonolingualSettingsSection: View {
    @Environment(AppModel.self) private var app
    /// 0 = off, else the JLPT level (2 = N2 and N1).
    @State private var fromLevel: Int?
    @State private var error: String?

    var body: some View {
        Section {
            if let fromLevel {
                Toggle("Japanese-only explanations", isOn: Binding(get: { fromLevel > 0 }, set: { save($0 ? 2 : 0) }))
                if fromLevel > 0 {
                    Picker("From level", selection: Binding(get: { fromLevel }, set: { save($0) })) {
                        ForEach([1, 2, 3, 4, 5], id: \.self) { level in
                            Text(level == 1 ? String(localized: "N1 only") : String(localized: "N\(String(level)) and harder")).tag(level)
                        }
                    }
                }
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else {
                ProgressView()
            }
        } header: {
            Text("Monolingual mode")
        } footer: {
            Text("Grammar at these levels is explained in Japanese (our own text), and words get a Japanese paraphrase written by your AI model when you open them. Both carry the AI-generated label. Syncs to your other devices.")
        }
        .task { await load() }
    }

    private func load() async {
        do {
            let value = try await SwiftSupport.shared.monolingualFromLevel(graph: app.graph)
            fromLevel = Int(value.intValue)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't read the setting: \(error.localizedDescription)")
        }
    }

    private func save(_ level: Int) {
        let previous = fromLevel
        fromLevel = level
        let graph = app.graph
        Task {
            do {
                _ = try await SwiftSupport.shared.setMonolingualFromLevel(graph: graph, level: Int32(level))
                error = nil
            } catch {
                fromLevel = previous
                self.error = String(localized: "Couldn't save the setting: \(error.localizedDescription)")
            }
        }
    }
}

/// A grammar explanation, copied from `GrammarExplanation`.
struct GrammarExplanationData {
    let japanese: Bool
    let meaning: String
    let nuance: String
    let aiGenerated: Bool
    let japaneseMissing: Bool

    init(_ e: GrammarExplanation) {
        japanese = SwiftSupport.shared.explanationIsJapanese(explanation: e)
        meaning = e.meaning
        nuance = e.nuance
        aiGenerated = e.aiGenerated
        japaneseMissing = e.japaneseMissing
    }
}

/// The meaning and nuance of a grammar point in the language monolingual mode asks for, with a switch to the other.
/// Falls back to the English text (and says so) when the pack has no Japanese for the point yet.
struct GrammarExplanationView: View {
    let point: GrammarPoint
    @Environment(AppModel.self) private var app
    @State private var shown: GrammarExplanationData?
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let shown {
                if shown.japanese {
                    HStack {
                        TagView("日本語で")
                        if shown.aiGenerated { AiBadge() }
                    }
                    Text(shown.meaning).font(.japanese(size: 17, weight: .semibold)).japaneseSpeech()
                    Text(shown.nuance).font(.japanese(size: 16)).japaneseSpeech()
                    Button("Show in English") { Task { await load(english: true) } }.font(.caption)
                } else {
                    Text(point.meaning).font(.headline)
                    Text(point.nuance)
                    if shown.japaneseMissing {
                        Text("There's no Japanese explanation for this point yet, so it's shown in English.")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        Button("日本語で説明") { Task { await loadJapanese() } }.font(.caption)
                    }
                }
            } else {
                Text(point.meaning).font(.headline)
                Text(point.nuance)
                if let error { Text(error).font(.caption).foregroundStyle(.red) }
            }
        }
        .task(id: point.id) { await load(english: false) }
    }

    private func load(english: Bool) async {
        do {
            let e = try await SwiftSupport.shared.grammarExplanation(graph: app.graph, point: point, english: english)
            shown = GrammarExplanationData(e)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the explanation: \(error.localizedDescription)")
        }
    }

    private func loadJapanese() async {
        do {
            let e = try await SwiftSupport.shared.grammarExplanationJapanese(graph: app.graph, point: point)
            shown = GrammarExplanationData(e)
            error = nil
        } catch {
            self.error = String(localized: "Couldn't load the explanation: \(error.localizedDescription)")
        }
    }
}

/// A word's Japanese paraphrase on the dictionary entry (D-234). With monolingual mode on for the word's level it is
/// generated once when the entry opens (then cached); otherwise the learner asks for it. [onJapanese] tells the entry
/// to fold the English glosses away while the Japanese is shown.
struct WordExplanationCard: View {
    let entryId: Int64
    let word: String
    let reading: String
    let glosses: [String]
    let jlpt: Int
    let onJapanese: (Bool) -> Void
    @Environment(AppModel.self) private var app
    @State private var paraphrase: String?
    @State private var example = ""
    @State private var note = ""
    @State private var engine: String?
    @State private var unavailable: String?
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let paraphrase {
                HStack {
                    Text("日本語で").font(.caption.weight(.semibold))
                    AIBadge(engine: engine)
                }
                Text(paraphrase).font(.japanese(size: 17)).japaneseSpeech()
                if !example.isEmpty { Text(example).font(.japanese(size: 15)).foregroundStyle(.secondary).japaneseSpeech() }
                if !note.isEmpty { Text(note).font(.japanese(size: 13)).foregroundStyle(.secondary) }
                Button("This paraphrase is wrong") { forget() }.font(.caption)
            } else if busy {
                ProgressView("Writing a Japanese paraphrase…")
            } else {
                Button("Explain in Japanese") { Task { await load(generate: true, japanese: true) } }
                    .font(.subheadline)
                if let unavailable { Text(unavailable).font(.caption).foregroundStyle(.orange) }
            }
            if let error {
                ErrorRetryView(message: error) { Task { await load(generate: true, japanese: true) } }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
        .task(id: entryId) { await load(generate: true, japanese: false) }
    }

    private func load(generate: Bool, japanese: Bool) async {
        busy = true
        defer { busy = false }
        do {
            let e = try await SwiftSupport.shared.wordExplanation(
                graph: app.graph, entryId: entryId, word: word, reading: reading, glosses: glosses, jlpt: Int32(jlpt),
                generate: generate, japanese: japanese
            )
            error = nil
            if e.aiGenerated {
                paraphrase = e.paraphrase
                example = e.example
                note = e.note
                engine = e.engine
                unavailable = nil
                onJapanese(true)
            } else {
                paraphrase = nil
                onJapanese(false)
                // "not generated yet" only means the cache was empty; anything else is worth showing (no model …).
                if let reason = e.unavailableReason, reason != "not generated yet", japanese { unavailable = reason } else { unavailable = nil }
            }
        } catch {
            self.error = String(localized: "Couldn't get a paraphrase: \(error.localizedDescription)")
        }
    }

    private func forget() {
        let graph = app.graph
        let id = entryId
        let w = word
        let r = reading
        Task {
            _ = try? await SwiftSupport.shared.forgetWordParaphrase(graph: graph, entryId: id, word: w, reading: r)
            paraphrase = nil
            onJapanese(false)
        }
    }
}
