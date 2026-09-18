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

    var body: some View {
        let e = detail.entry
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                FuriganaText(segments: detail.furigana)
                EntryActions(entry: e)
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
                    Text("Also: " + others.joined(separator: "、")).font(.japanese(size: 15))
                }
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
                if !detail.kanji.isEmpty {
                    SectionHeader("Kanji")
                    ForEach(detail.kanji, id: \.literal) { k in
                        NavigationLink(value: Route.kanji(k.literal)) {
                            HStack(spacing: 12) {
                                Text(k.literal).font(.japanese(size: 40, relativeTo: .largeTitle))
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
                if !detail.sentences.isEmpty {
                    SectionHeader("Examples")
                    ForEach(detail.sentences, id: \.id) { s in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.japanese).font(.japanese(size: 17)).textSelection(.enabled)
                            Text(s.english).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    Text("Examples: Tatoeba (CC BY 2.0 FR)").font(.caption2).foregroundStyle(.tertiary)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct SectionHeader: View {
    let title: String
    init(_ title: String) { self.title = title }
    var body: some View {
        Text(title).font(.headline).foregroundStyle(.tint)
    }
}
