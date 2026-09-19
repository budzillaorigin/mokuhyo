import Shared
import SwiftUI

struct EntryView: View {
    let id: Int64

    var body: some View {
        DictionaryLoader(key: id, load: { try await $0.entry(id: id) }) { (detail: EntryDetail) in
            EntryContent(detail: detail)
        }
        .navigationTitle("Word")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct EntryContent: View {
    let detail: EntryDetail
    /// A Japanese paraphrase is shown (monolingual mode, D-265): the English glosses fold away.
    @State private var japaneseShown = false

    var body: some View {
        let e = detail.entry
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                FuriganaText(segments: detail.furigana)
                EntryActions(entry: e)
                MarkKnownButton(entryId: e.id)
                HStack {
                    if e.isCommon { TagView("common") }
                    if let n = jlptLabel(e.jlpt) { TagView("\(n) (unofficial)") }
                }
                if !detail.pitch.isEmpty {
                    HStack(spacing: 16) {
                        ForEach(Array(detail.pitch.enumerated()), id: \.offset) { _, accent in
                            PitchDiagram(reading: e.reading, accent: accent)
                        }
                    }
                }
                let others = e.kanji.dropFirst().map(\.text) + e.kana.dropFirst().map(\.text)
                if !others.isEmpty {
                    Text("Also: \(others.joined(separator: "、"))").font(.japanese(size: 15))
                }
                WordExplanationCard(
                    entryId: e.id, word: e.headword, reading: e.reading,
                    glosses: e.senses.first?.glosses ?? [], jlpt: e.jlpt.map { Int($0.intValue) } ?? 0
                ) { japaneseShown = $0 }
                if japaneseShown {
                    DisclosureGroup("English glosses") { senses(e) }
                } else {
                    senses(e)
                }
                if !detail.kanji.isEmpty {
                    SectionHeader("Kanji")
                    ForEach(detail.kanji, id: \.literal) { k in
                        NavigationLink(value: Route.kanji(k.literal)) {
                            HStack(spacing: 12) {
                                Text(k.literal).font(.japanese(size: 40, relativeTo: .largeTitle)).japaneseSpeech()
                                VStack(alignment: .leading) {
                                    Text(k.meanings.prefix(4).joined(separator: ", ")).fontWeight(.medium)
                                    Text((k.onyomi + k.kunyomi).prefix(6).joined(separator: "、")).font(.japanese(size: 14))
                                }
                                Spacer()
                            }
                            .padding(10)
                            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain)
                    }
                }
                if !detail.conjugations.isEmpty {
                    SectionHeader("Conjugation")
                    Grid(alignment: .leading, verticalSpacing: 6) {
                        ForEach(Array(detail.conjugations.enumerated()), id: \.offset) { _, c in
                            GridRow {
                                Text(c.label).font(.subheadline).foregroundStyle(.secondary)
                                Text(c.text).font(.japanese(size: 17))
                            }
                            Divider()
                        }
                    }
                }
                // Sentences from the learner's own media first, then Tatoeba, then the optional online source (§6.2).
                EntrySentencesSection(entry: e)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

extension EntryContent {
    /// JMdict senses with parts of speech and notes (English, CC BY-SA 4.0).
    func senses(_ e: DictionaryEntry) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(e.senses.enumerated()), id: \.offset) { i, sense in
                VStack(alignment: .leading, spacing: 2) {
                    if !sense.partsOfSpeech.isEmpty && (i == 0 || sense.partsOfSpeech != e.senses[i - 1].partsOfSpeech) {
                        Text(sense.partsOfSpeech.joined(separator: ", ")).font(.caption).foregroundStyle(.tint)
                    }
                    Text("\(i + 1). " + sense.glosses.joined(separator: "; "))
                    let notes = sense.misc + sense.fields + sense.dialects + sense.info
                    if !notes.isEmpty {
                        Text(notes.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

/// A localized section heading (the key is looked up in Localizable.xcstrings). Announced as a header by VoiceOver.
struct SectionHeader: View {
    let title: LocalizedStringKey
    init(_ title: LocalizedStringKey) { self.title = title }
    var body: some View {
        Text(title).font(.headline).foregroundStyle(.tint)
            .accessibilityAddTraits(.isHeader)
    }
}
