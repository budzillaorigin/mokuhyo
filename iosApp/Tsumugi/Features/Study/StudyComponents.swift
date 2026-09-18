import Shared
import SwiftUI

extension ItemKind {
    /// Our own palette per item kind.
    var color: Color {
        switch self {
        case .radical: Color(red: 0.16, green: 0.55, blue: 0.55)
        case .kanji: Color(red: 0.78, green: 0.33, blue: 0.24)
        case .vocab: Color(red: 0.36, green: 0.29, blue: 0.62)
        default: Color(red: 0.29, green: 0.35, blue: 0.42)
        }
    }
}

/// Big glyph on a coloured card: the question in reviews, the header in lessons.
struct ItemGlyph: View {
    let text: String
    let kind: ItemKind

    var body: some View {
        Text(text)
            .font(.japanese(size: text.count <= 2 ? 88 : text.count <= 4 ? 56 : 34, relativeTo: .largeTitle))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .minimumScaleFactor(0.4)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 28)
            .background(kind.color, in: RoundedRectangle(cornerRadius: 16))
            .accessibilityLabel(text)
            .japaneseSpeech()
    }
}

/// Answer input. Reading answers convert romaji to kana as you type ("shi" → し, "nn" → ん).
struct AnswerField: View {
    let mode: AnswerMode
    let onSubmit: (String) -> Void

    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField(mode == .reading ? "答え (reading)" : "Answer (meaning)", text: $text)
            .font(.japanese(size: 24))
            .multilineTextAlignment(.center)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(mode == .reading ? .asciiCapable : .default)
            .submitLabel(.done)
            .textFieldStyle(.roundedBorder)
            .focused($focused)
            .accessibilityLabel(mode == .reading ? Text("Answer: reading in kana") : Text("Answer: meaning in English"))
            .accessibilityHint(mode == .reading ? Text("Type in romaji; it turns into kana as you type.") : Text("Type the English meaning, then press Done."))
            .onChange(of: text) { _, new in
                guard mode == .reading else { return }
                let converted = Romaji.shared.imeConvert(buffer: new).text
                if converted != new { text = converted }
            }
            .onSubmit {
                let answer = mode == .reading ? Romaji.shared.finalize(buffer: text) : text
                let trimmed = answer.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty { onSubmit(trimmed) }
            }
            .onAppear { focused = true }
    }
}

/// Lesson / item page: keyword, readings, parts, stroke order, where it's used, examples, and the user's own
/// mnemonic (myStory — the app ships none).
struct PathItemContent: View {
    @Environment(AppModel.self) private var app
    let detail: PathItemDetail
    let onSaveStory: (String) -> Void

    @State private var strokes: [KanjiStroke] = []
    @State private var sentences: [ExampleSentence] = []

    var body: some View {
        let item = detail.item
        VStack(alignment: .leading, spacing: 14) {
            ItemGlyph(text: item.display, kind: item.kind)
            HStack(alignment: .firstTextBaseline) {
                Text(item.keyword).font(.title2.weight(.semibold))
                Text("Level \(item.level)").font(.caption).foregroundStyle(.secondary)
                if let stage = detail.stage { Text(stage.label).font(.caption).foregroundStyle(.tint) }
            }
            if item.meanings.count > 1 {
                Text("Also: \(item.meanings.dropFirst().prefix(6).joined(separator: ", "))").font(.subheadline)
            }
            if !item.readings.isEmpty {
                SectionHeader("Readings")
                Text(item.readings.joined(separator: "、")).font(.japanese(size: 22)).japaneseSpeech()
            }
            if !strokes.isEmpty {
                StrokeOrderView(strokes: strokes).frame(width: 140, height: 140)
            }
            if !detail.components.isEmpty {
                SectionHeader(item.kind == .vocab ? "Kanji" : "Radicals")
                chips(detail.components.map { "\($0.display)  \($0.keyword)" })
            }
            if !detail.usedIn.isEmpty {
                SectionHeader("Used in")
                chips(detail.usedIn.prefix(12).map(\.display))
            }
            ForEach(sentences.prefix(3), id: \.id) { s in
                VStack(alignment: .leading) {
                    Text(s.japanese).font(.japanese(size: 17)).japaneseSpeech()
                    Text(s.english).font(.caption).foregroundStyle(.secondary)
                }
            }
            MyStoryEditor(itemId: item.id, initial: detail.myStory, onSave: onSaveStory)
        }
        .task(id: item.id) {
            guard let dict = try? await app.graph.dictionary() else { return }
            if item.kind != .vocab { strokes = (try? await dict.strokes(literal: item.display)) ?? [] }
            if let entryId = item.entryId, let entry = try? await dict.entry(id: entryId.int64Value) {
                sentences = entry.sentences
            }
        }
    }

    private func chips(_ labels: [String]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(Array(labels.enumerated()), id: \.offset) { _, label in
                    Text(label).font(.japanese(size: 16))
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(.quaternary.opacity(0.6), in: Capsule())
                        .japaneseSpeech()
                }
            }
        }
    }
}

private struct MyStoryEditor: View {
    let itemId: String
    let initial: String
    let onSave: (String) -> Void

    @State private var story = ""
    @State private var saved = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionHeader("My story")
            Text("Write your own mnemonic from the parts above — the one you make up sticks best.")
                .font(.caption).foregroundStyle(.secondary)
            TextEditor(text: $story)
                .frame(minHeight: 100)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
            if story != saved {
                Button("Save story") {
                    onSave(story)
                    saved = story
                }
            }
        }
        .onAppear {
            story = initial
            saved = initial
        }
    }
}
