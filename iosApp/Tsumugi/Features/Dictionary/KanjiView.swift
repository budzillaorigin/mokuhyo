import Shared
import SwiftUI

struct KanjiView: View {
    let literal: String

    var body: some View {
        DictionaryLoader(key: literal, load: { try await $0.kanji(literal: literal) }) { (k: KanjiDetail) in
            KanjiContent(k: k)
        }
        .navigationTitle(literal)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct KanjiContent: View {
    let k: KanjiDetail

    var body: some View {
        let info = k.info
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 16) {
                    if k.strokes.isEmpty {
                        Text(info.literal).font(.japanese(size: 96, relativeTo: .largeTitle)).japaneseSpeech()
                    } else {
                        StrokeOrderView(strokes: k.strokes).frame(width: 170, height: 170)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(info.keyword).font(.title2.weight(.semibold))
                        Text("\(info.strokeCount) strokes")
                        if let grade = info.grade?.intValue {
                            Text(grade <= 6 ? "Jōyō grade \(grade)" : grade == 8 ? "Jōyō (secondary)" : "Jinmeiyō")
                        }
                        if let n = jlptLabel(info.jlpt) { Text("JLPT \(n) (unofficial)") }
                        if let h = info.heisig6?.intValue { Text("Heisig #\(h)") }
                        if !k.strokes.isEmpty { Text("Tap to replay").font(.caption2).foregroundStyle(.tertiary) }
                    }
                    .font(.subheadline)
                }
                Text(info.meanings.joined(separator: ", "))
                if !info.onyomi.isEmpty { labeled("On", info.onyomi) }
                if !info.kunyomi.isEmpty { labeled("Kun", info.kunyomi) }
                if !info.nanori.isEmpty { labeled("Names", info.nanori) }
                if !k.components.isEmpty {
                    SectionHeader("Components")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(k.components, id: \.self) { c in
                                NavigationLink(value: Route.kanji(c)) { Text(c).font(.japanese(size: 22)) }
                                    .buttonStyle(.bordered)
                            }
                        }
                    }
                }
                if !k.words.isEmpty {
                    SectionHeader("Words")
                    ForEach(k.words, id: \.id) { w in
                        NavigationLink(value: Route.entry(w.id)) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(w.headword).font(.japanese(size: 18)).frame(width: 110, alignment: .leading).japaneseSpeech()
                                VStack(alignment: .leading) {
                                    Text(w.reading).font(.japanese(size: 12)).foregroundStyle(.secondary)
                                    Text(w.glossPreview).font(.subheadline).lineLimit(1)
                                }
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                Text("Stroke order: KanjiVG (CC BY-SA 3.0) · KANJIDIC2 (EDRDG, CC BY-SA 4.0)")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func labeled(_ label: LocalizedStringKey, _ values: [String]) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).font(.subheadline).foregroundStyle(.secondary).frame(minWidth: 60, alignment: .leading)
            Text(values.joined(separator: "、")).font(.japanese(size: 17)).japaneseSpeech()
        }
    }
}
