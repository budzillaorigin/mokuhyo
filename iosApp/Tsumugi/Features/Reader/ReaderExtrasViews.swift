import PhotosUI
import Shared
import SwiftUI
import UIKit

// Reader upgrades (BRIEF_V2 §6.4, §6.11): the document's own word list and its context drill, annotations, 1T
// sentences, screenshot import and page images. All lists and checks come from the shared ReaderService.

/// The words looked up in one document (D-165): remove, add all to reviews, or drill them in context.
struct DocumentWordsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let docId: String

    @State private var words: [DocumentWord]?
    @State private var loadError: String?
    @State private var note: String?
    @State private var adding = false
    @State private var drilling = false

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            }
            if let words {
                if words.isEmpty {
                    Text("Words you look up while reading this text collect here.").foregroundStyle(.secondary)
                } else {
                    Section {
                        Button("Drill these words in context") { drilling = true }
                            .buttonStyle(.borderedProminent)
                        Button(adding ? "Adding…" : "Add all to reviews") { addAll() }
                            .disabled(adding)
                        if let note { Text(note).font(.caption) }
                    }
                    Section("\(words.count.formatted()) words") {
                        ForEach(words, id: \.ref) { w in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(alignment: .firstTextBaseline) {
                                    Text(w.text).font(.japanese(size: 18)).japaneseSpeech()
                                    Text(w.reading).font(.japanese(size: 13)).foregroundStyle(.secondary)
                                    Spacer()
                                    if w.lookups > 1 {
                                        Text("×\(Int(w.lookups).formatted())").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                    }
                                }
                                Text(w.gloss).font(.caption).lineLimit(2)
                                Text(w.sentence).font(.japanese(size: 13)).foregroundStyle(.secondary).lineLimit(2)
                            }
                            .swipeActions {
                                Button("Remove", role: .destructive) { remove(w) }
                            }
                        }
                    }
                }
            } else if loadError == nil {
                ProgressView()
            }
        }
        .navigationTitle("Words in this text")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        .task { await load() }
        .navigationDestination(isPresented: $drilling) { ContextDrillView(docId: docId) }
    }

    private func load() async {
        loadError = nil
        do {
            words = try await app.graph.reader.vocabulary.words(documentId: docId)
        } catch {
            loadError = String(localized: "Couldn't load the word list: \(error.localizedDescription)")
        }
    }

    private func remove(_ w: DocumentWord) {
        Task {
            try? await app.graph.reader.vocabulary.remove(documentId: docId, ref: w.ref)
            await load()
        }
    }

    private func addAll() {
        adding = true
        note = nil
        Task {
            do {
                let n = try await app.graph.reader.addDocumentWordsToReviews(documentId: docId)
                note = String(localized: "\(Int(truncating: n).formatted()) words added to reviews with their sentences.")
            } catch {
                note = String(localized: "Couldn't add them: \(error.localizedDescription)")
            }
            adding = false
        }
    }
}

/// Context cards (D-165): the word inside its sentence; type the reading or the meaning, or reveal and grade
/// yourself. A practice pass: it doesn't touch the review schedule.
struct ContextDrillView: View {
    @Environment(AppModel.self) private var app
    let docId: String

    private enum Answer: String, CaseIterable, Identifiable {
        case reading = "Type the reading"
        case meaning = "Type the meaning"
        case reveal = "Self-check"
        var id: String { rawValue }
    }

    @State private var drill: ContextDrill?
    @State private var version = 0
    @State private var mode = Answer.reading
    @State private var typed = ""
    @State private var last: ContextCardResult?
    @State private var revealed = false
    @State private var right = 0
    @State private var total = 0
    @State private var loadError: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let loadError {
                    ErrorRetryView(message: loadError) { Task { await start() } }
                } else if let drill {
                    content(drill)
                } else {
                    ProgressView()
                }
            }
            .padding()
            .id(version)
        }
        .navigationTitle("Drill")
        .navigationBarTitleDisplayMode(.inline)
        .task { if drill == nil { await start() } }
    }

    @ViewBuilder
    private func content(_ drill: ContextDrill) -> some View {
        if drill.cards.isEmpty {
            Text("Look up some words in this text first.").foregroundStyle(.secondary)
        } else if let last {
            resultView(last)
        } else if let card = drill.current {
            Text("\(Int(drill.position + 1).formatted()) of \(drill.cards.count.formatted())").font(.caption).foregroundStyle(.secondary)
            (Text(card.before) + Text(card.word).bold().foregroundColor(.accentColor) + Text(card.after))
                .font(.japanese(size: 22))
                .japaneseSpeech()
            Picker("Answer with", selection: $mode) {
                ForEach(Answer.allCases) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
            }
            .pickerStyle(.segmented)
            switch mode {
            case .reading, .meaning:
                TextField(mode == .reading ? String(localized: "Reading (kana or romaji)") : String(localized: "Meaning in English"), text: $typed)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { check() }
                Button("Check") { check() }.buttonStyle(.borderedProminent)
                    .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            case .reveal:
                if revealed {
                    Text(card.reading).font(.japanese(size: 18))
                    Text(card.gloss).font(.subheadline)
                    HStack {
                        Button("I knew it") { grade(true) }.buttonStyle(.borderedProminent)
                        Button("I didn't") { grade(false) }.buttonStyle(.bordered)
                    }
                } else {
                    Button("Show the answer") { revealed = true }.buttonStyle(.bordered)
                }
            }
        } else {
            Label("Drill done: \(right.formatted()) of \(total.formatted()) right.", systemImage: "checkmark.circle")
                .font(.title3.weight(.semibold))
            Text("This was practice only; your review schedule is unchanged.").font(.caption).foregroundStyle(.secondary)
            Button("Again") { Task { await start() } }.buttonStyle(.bordered)
        }
    }

    @ViewBuilder
    private func resultView(_ r: ContextCardResult) -> some View {
        Label(r.correct ? String(localized: "Right") : String(localized: "Not quite"), systemImage: r.correct ? "checkmark.circle" : "xmark.circle")
            .foregroundStyle(r.correct ? .green : .red)
            .font(.headline)
        (Text(r.card.before) + Text(r.card.word).bold() + Text(r.card.after)).font(.japanese(size: 20))
        Text(r.card.reading).font(.japanese(size: 16))
        Text(r.card.gloss).font(.subheadline).foregroundStyle(.secondary)
        if let id = r.card.entryId {
            NavigationLink("Dictionary", value: Route.entry(id.int64Value)).font(.caption)
        }
        Button("Next") {
            last = nil
            typed = ""
            revealed = false
            version += 1
        }
        .buttonStyle(.borderedProminent)
    }

    private func start() async {
        loadError = nil
        do {
            drill = try await app.graph.reader.vocabulary.drill(documentId: docId, seed: nil)
            right = 0
            total = 0
            last = nil
            typed = ""
            revealed = false
            version += 1
        } catch {
            loadError = String(localized: "Couldn't start the drill: \(error.localizedDescription)")
        }
    }

    private func check() {
        guard let drill, drill.current != nil else { return }
        let given = typed.trimmingCharacters(in: .whitespaces)
        guard !given.isEmpty else { return }
        let r = mode == .reading ? drill.answerReading(given: given) : drill.answerMeaning(given: given)
        record(r)
    }

    private func grade(_ correct: Bool) {
        guard let drill, drill.current != nil else { return }
        record(drill.grade(correct: correct))
    }

    private func record(_ r: ContextCardResult) {
        total += 1
        if r.correct { right += 1 }
        last = r
        version += 1
    }
}

/// Annotation kinds with their labels and colours (the kinds are the shared `AnnotationKind`, D-164).
enum AnnotationStyle {
    static func label(_ kind: AnnotationKind) -> String {
        switch kind {
        case .box: String(localized: "Box")
        case .highlight: String(localized: "Highlight")
        case .note: String(localized: "Note")
        default: String(localized: "Grammar")
        }
    }

    static func icon(_ kind: AnnotationKind) -> String {
        switch kind {
        case .box: "rectangle.dashed"
        case .highlight: "highlighter"
        case .note: "note.text"
        default: "text.book.closed"
        }
    }

    /// Stored colour names (synced as text); the default per kind when none was chosen.
    static func color(_ name: String?, kind: AnnotationKind) -> Color {
        switch name {
        case "yellow": .yellow
        case "green": .green
        case "blue": .blue
        case "pink": .pink
        default:
            switch kind {
            case .highlight: .yellow
            case .note: .orange
            case .box: .blue
            default: .purple
            }
        }
    }
}

/// Every annotation of a document, including ones whose text changed (detached), with edit and delete.
struct AnnotationsListView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let document: ReaderDocument
    let onChange: () -> Void

    @State private var items: [ReaderAnnotation]?
    @State private var editing: ReaderAnnotation?
    @State private var draft = ""
    @State private var loadError: String?

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            }
            if let items, items.isEmpty {
                Text("Tap the pencil in the reader, then tap the first and last word of a phrase to highlight, box, note or mark it as grammar.")
                    .foregroundStyle(.secondary)
            }
            ForEach(items ?? [], id: \.id) { a in
                VStack(alignment: .leading, spacing: 3) {
                    Label(AnnotationStyle.label(a.kind), systemImage: AnnotationStyle.icon(a.kind))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AnnotationStyle.color(a.color, kind: a.kind))
                    Text(a.quote).font(.japanese(size: 16)).lineLimit(3)
                    if !a.note.isEmpty { Text(a.note).font(.subheadline) }
                    if let g = a.grammarPointId {
                        NavigationLink(g.split(separator: "-").dropFirst().joined(separator: " "), value: Route.grammarPoint(g)).font(.caption)
                    }
                    if a.detached {
                        Text("The text changed and this passage wasn't found again.").font(.caption2).foregroundStyle(.orange)
                    }
                }
                .swipeActions {
                    Button("Delete", role: .destructive) { delete(a) }
                    Button("Edit note") {
                        draft = a.note
                        editing = a
                    }
                }
            }
        }
        .navigationTitle("Notes")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        .task { await load() }
        .alert("Note", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("Note", text: $draft)
            Button("Save") {
                if let a = editing { save(a, note: draft) }
                editing = nil
            }
            Button("Cancel", role: .cancel) { editing = nil }
        }
    }

    private func load() async {
        loadError = nil
        do {
            items = try await app.graph.reader.annotations.forDocument(document: document)
        } catch {
            loadError = String(localized: "Couldn't load the notes: \(error.localizedDescription)")
        }
    }

    private func delete(_ a: ReaderAnnotation) {
        Task {
            try? await app.graph.reader.annotations.delete(id: a.id)
            await load()
            onChange()
        }
    }

    private func save(_ a: ReaderAnnotation, note: String) {
        Task {
            _ = try? await app.graph.reader.annotations.update(id: a.id, note: note, color: nil, kind: nil, grammarPointId: nil)
            await load()
            onChange()
        }
    }
}

/// 1T sentences of a document (§6.11): exactly one unknown word each, best first, with "Mine".
struct OneTargetListView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let sentences: [OneTargetSentence]
    let onJump: (OneTargetSentence) -> Void

    @State private var mined: Set<Int64> = []
    @State private var failed: String?

    var body: some View {
        List {
            Section {
                Text("Each sentence has exactly one word you don't know yet. Learn that word and the whole sentence is readable.")
                    .font(.caption).foregroundStyle(.secondary)
                if let failed { Text(failed).font(.caption).foregroundStyle(.orange) }
            }
            if sentences.isEmpty {
                Text("No sentence here has exactly one new word.").foregroundStyle(.secondary)
            }
            ForEach(sentences, id: \.entryId) { s in
                OneTargetRow(sentence: s, mined: mined.contains(s.entryId), mine: { mine(s) }, jump: {
                    onJump(s)
                    dismiss()
                })
            }
        }
        .navigationTitle("One new word")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
    }

    private func mine(_ s: OneTargetSentence) {
        Task {
            do {
                if try await app.graph.coverage.mineOneTarget(sentence: s) != nil {
                    mined.insert(s.entryId)
                } else {
                    failed = String(localized: "That word isn't in the dictionary pack.")
                }
            } catch {
                failed = String(localized: "Couldn't add it: \(error.localizedDescription)")
            }
        }
    }
}

/// A 1T sentence with its target word highlighted.
struct OneTargetRow: View {
    let sentence: OneTargetSentence
    let mined: Bool
    let mine: () -> Void
    let jump: (() -> Void)?

    var body: some View {
        let parts = OneTargetRow.split(sentence)
        VStack(alignment: .leading, spacing: 4) {
            (Text(parts.0) + Text(parts.1).bold().foregroundColor(.accentColor) + Text(parts.2))
                .font(.japanese(size: 17))
                .japaneseSpeech()
            HStack {
                Text("\(sentence.targetLemma)\(sentence.targetReading.map { "（\($0)）" } ?? "")").font(.japanese(size: 13)).foregroundStyle(.secondary)
                Spacer()
                if let jump {
                    Button("Show") { jump() }.buttonStyle(.borderless).font(.caption)
                }
                Button(mined ? "Added" : "Mine") { mine() }
                    .buttonStyle(.bordered)
                    .font(.caption)
                    .disabled(mined)
            }
        }
    }

    /// Before / target / after, by the shared UTF-16 offsets.
    static func split(_ s: OneTargetSentence) -> (String, String, String) {
        let ns = s.text as NSString
        let start = max(0, min(Int(s.targetStart), ns.length))
        let end = max(start, min(Int(s.targetEnd), ns.length))
        return (ns.substring(to: start), ns.substring(with: NSRange(location: start, length: end - start)), ns.substring(from: end))
    }
}

/// The screenshots a document was made from (§6.4 screenshot import): tap one to see it full size.
struct PageImagesStrip: View {
    let pages: [PageImageFile]
    @State private var shown: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(pages.enumerated()), id: \.offset) { i, page in
                    if let path = page.path, let image = UIImage(contentsOfFile: path) {
                        Button {
                            shown = path
                        } label: {
                            Image(uiImage: image).resizable().scaledToFill()
                                .frame(width: 64, height: 96).clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        .accessibilityLabel(Text("Page \((i + 1).formatted())"))
                    } else {
                        RoundedRectangle(cornerRadius: 6).fill(.quaternary).frame(width: 64, height: 96)
                            .overlay(Image(systemName: "photo").foregroundStyle(.secondary))
                            .accessibilityLabel(Text("Page image not on this device"))
                    }
                }
            }
        }
        .sheet(item: Binding(get: { shown.map { ShownImage(path: $0) } }, set: { shown = $0?.path })) { img in
            NavigationStack {
                ScrollView([.horizontal, .vertical]) {
                    if let image = UIImage(contentsOfFile: img.path) {
                        Image(uiImage: image).resizable().scaledToFit()
                    }
                }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { shown = nil } } }
            }
        }
    }

    private struct ShownImage: Identifiable {
        let path: String
        var id: String { path }
    }
}

/// Screenshot import (§6.4, D-169): each picture is OCR'd on device (Vision, like Scan), kept as a page image, and
/// the text becomes one reader document. Pictures are decoded and written off the main actor (rule 15).
enum ScreenshotImporter {
    struct NoTextError: LocalizedError {
        var errorDescription: String? { String(localized: "No Japanese text was found in those pictures.") }
    }

    static func importPictures(
        _ items: [PhotosPickerItem],
        graph: AppGraph,
        onProgress: @escaping @MainActor (Int, Int) -> Void
    ) async throws -> String {
        var pages: [ScreenshotPage] = []
        for (i, item) in items.enumerated() {
            try Task.checkCancellation()
            await onProgress(i, items.count)
            guard let data = try await item.loadTransferable(type: Data.self) else { continue }
            let prepared = await Task.detached(priority: .userInitiated) { () -> (CGImage, Data)? in
                guard let image = UIImage(data: data), let cg = image.cgImage, let jpeg = image.jpegData(compressionQuality: 0.8) else { return nil }
                return (cg, jpeg)
            }.value
            guard let prepared else { continue }
            let (cg, jpeg) = prepared
            let lines = await ScanView.recognize(cg)
            let pending = try await graph.reader.screenshots.screenshotFile(extension: "jpg")
            let path = pending.path
            try await Task.detached(priority: .utility) {
                try jpeg.write(to: URL(fileURLWithPath: path), options: .atomic)
            }.value
            pages.append(ScreenshotPage(image: pending, ocrText: lines.joined(separator: "\n")))
        }
        await onProgress(items.count, items.count)
        guard pages.contains(where: { !$0.ocrText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw NoTextError() }
        try Task.checkCancellation()
        return try await graph.reader.importScreenshots(pages: pages, title: nil)
    }
}
