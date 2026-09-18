import Shared
import SwiftUI

/// Minimal-pairs ear training (BRIEF §5.9): hear one word of a pair, pick which one it was.
struct MinimalPairsView: View {
    var body: some View {
        PracticeGate { repo in MinimalPairsDrill(repo: repo) }
            .navigationTitle("Minimal pairs")
    }
}

private struct PairData: Identifiable {
    struct Word {
        let text: String
        let reading: String
        let accent: Int?
        let gloss: String
    }

    let id: Int64
    let category: String
    let a: Word
    let b: Word
}

private enum PairFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case length = "Long vowels"
    case gemination = "っ"
    case voicing = "Voicing"
    case nasal = "ん"
    case pitch = "Pitch"

    var id: String { rawValue }

    var category: MinimalPairCategory? {
        switch self {
        case .all: nil
        case .length: .length
        case .gemination: .gemination
        case .voicing: .voicing
        case .nasal: .nasal
        case .pitch: .pitch
        }
    }
}

private struct MinimalPairsDrill: View {
    let repo: PracticeRepository

    @State private var filter = PairFilter.all
    @State private var pairs: [PairData] = []
    @State private var loaded = false
    @State private var index = 0
    @State private var playA = true
    @State private var answered: Bool?
    @State private var right = 0
    @State private var total = 0
    @State private var voice = VoicePlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Kind", selection: $filter) {
                    ForEach(PairFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                if loaded && pairs.isEmpty {
                    Text("No pairs of this kind in the installed pack.").foregroundStyle(.secondary)
                } else if index < pairs.count {
                    drill(pairs[index])
                }
                if total > 0 {
                    Text("Score: \(right) / \(total)").font(.subheadline.monospacedDigit())
                }
                Text("Words are spoken by the device's Japanese voice; pitch pairs rely on it reading the kanji with the right accent.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            .padding()
        }
        .task(id: filter) { await load() }
        .onDisappear { voice.stop() }
    }

    @ViewBuilder
    private func drill(_ pair: PairData) -> some View {
        Text(pair.category).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
        Button {
            Task { await voice.say(playA ? pair.a.text : pair.b.text, rate: 0.9) }
        } label: {
            Label("Play", systemImage: "speaker.wave.2.fill").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        Text("Which word did you hear?").font(.headline)
        HStack(spacing: 12) {
            choice(pair.a, isA: true)
            choice(pair.b, isA: false)
        }
        if let answered {
            Label(answered ? "Correct" : "That was \((playA ? pair.a : pair.b).text)", systemImage: answered ? "checkmark.circle" : "xmark.circle")
                .foregroundStyle(answered ? .green : .red)
            HStack {
                Button("Hear \(pair.a.text)") { Task { await voice.say(pair.a.text, rate: 0.9) } }
                Button("Hear \(pair.b.text)") { Task { await voice.say(pair.b.text, rate: 0.9) } }
            }
            .buttonStyle(.bordered)
            Button("Next pair") { next() }.buttonStyle(.borderedProminent)
        }
    }

    private func choice(_ word: PairData.Word, isA: Bool) -> some View {
        Button {
            guard answered == nil else { return }
            let ok = isA == playA
            answered = ok
            total += 1
            if ok { right += 1 }
        } label: {
            VStack(spacing: 4) {
                Text(word.text).font(.japanese(size: 26))
                Text(word.reading + (word.accent.map { " [\($0)]" } ?? "")).font(.japanese(size: 14))
                if answered != nil { Text(word.gloss).font(.caption).lineLimit(2) }
            }
            .frame(maxWidth: .infinity, minHeight: 90)
        }
        .buttonStyle(.bordered)
    }

    private func next() {
        answered = nil
        index = (index + 1) % max(1, pairs.count)
        playA = Bool.random()
        let pair = index < pairs.count ? pairs[index] : nil
        if let pair { Task { await voice.say(playA ? pair.a.text : pair.b.text, rate: 0.9) } }
    }

    private func load() async {
        let list = (try? await repo.minimalPairs(category: filter.category, limit: 50)) ?? []
        pairs = list.map { p in
            PairData(
                id: p.id,
                category: Self.label(p.category),
                a: PairData.Word(text: p.a.text, reading: p.a.reading, accent: p.a.accent.map { Int($0.intValue) }, gloss: p.a.gloss),
                b: PairData.Word(text: p.b.text, reading: p.b.reading, accent: p.b.accent.map { Int($0.intValue) }, gloss: p.b.gloss)
            )
        }.shuffled()
        index = 0
        answered = nil
        playA = Bool.random()
        loaded = true
    }

    private static func label(_ c: MinimalPairCategory) -> String {
        switch c {
        case .length: "Short vs. long vowel"
        case .gemination: "Single vs. double consonant (っ)"
        case .voicing: "Unvoiced vs. voiced"
        case .nasal: "With or without ん"
        case .pitch: "Pitch accent"
        default: "Minimal pair"
        }
    }
}
