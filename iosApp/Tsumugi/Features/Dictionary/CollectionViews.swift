import Shared
import SwiftUI

/// "Add to reviews" and "Add to list" for a dictionary entry.
struct EntryActions: View {
    @Environment(AppModel.self) private var app
    let entry: DictionaryEntry

    @State private var inReviews: Bool?
    @State private var picking = false
    @State private var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Button(inReviews == true ? "In reviews" : "Add to reviews") {
                    Task {
                        _ = try? await app.graph.collection.addToReviews(entry: entry, context: nil)
                        inReviews = true
                        note = String(localized: "Added — first review in 10 minutes.")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(inReviews != false)
                Button("Add to list") { picking = true }.buttonStyle(.bordered)
            }
            if let note { Text(note).font(.caption) }
        }
        .task(id: entry.id) { inReviews = (try? await app.graph.collection.isInReviews(entryId: entry.id))?.boolValue }
        .sheet(isPresented: $picking) {
            ListPicker { listId in
                Task {
                    try? await app.graph.collection.addToList(listId: listId, entry: entry.summary())
                    note = String(localized: "Added to list.")
                }
                picking = false
            }
        }
    }
}

private struct ListPicker: View {
    @Environment(AppModel.self) private var app
    let onPick: (String) -> Void
    @State private var lists: [WordListSummary] = []
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(lists, id: \.id) { l in
                    Button("\(l.name) (\(l.size))") { onPick(l.id) }
                }
                Section("New list") {
                    TextField("Name", text: $newName)
                    Button("Create & add") {
                        Task {
                            if let id = try? await app.graph.collection.createList(name: newName) { onPick(id) }
                        }
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Add to list")
            .navigationBarTitleDisplayMode(.inline)
            .task { lists = (try? await app.graph.collection.lists()) ?? [] }
        }
        .presentationDetents([.medium, .large])
    }
}

struct WordListsView: View {
    @Environment(AppModel.self) private var app
    @State private var lists: [WordListSummary] = []
    @State private var newName = ""

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("New list", text: $newName)
                    Button("Create") {
                        Task {
                            _ = try? await app.graph.collection.createList(name: newName)
                            newName = ""
                            await load()
                        }
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            if lists.isEmpty {
                Text("No word lists yet. Add words from the dictionary, or import an imiwa list from Me → Import.")
                    .foregroundStyle(.secondary)
            }
            ForEach(lists, id: \.id) { l in
                NavigationLink(value: Route.wordList(l.id)) {
                    LabeledContent(l.name, value: "\(l.size) words")
                }
            }
        }
        .navigationTitle("Word lists")
        .task { await load() }
    }

    private func load() async { lists = (try? await app.graph.collection.lists()) ?? [] }
}

struct WordListView: View {
    @Environment(AppModel.self) private var app
    let listId: String
    @State private var entries: [WordListEntry] = []
    @State private var status: String?

    var body: some View {
        List {
            Section {
                Button("Add all to reviews") { addAll() }
                if let status { Text(status).font(.caption) }
            }
            ForEach(entries, id: \.ref) { e in
                Group {
                    if let id = e.entryId {
                        NavigationLink(value: Route.entry(id.int64Value)) { row(e) }
                    } else {
                        row(e)
                    }
                }
                .swipeActions {
                    Button("Remove", role: .destructive) {
                        Task {
                            try? await app.graph.collection.removeFromList(listId: listId, ref: e.ref)
                            await load()
                        }
                    }
                }
            }
        }
        .navigationTitle("Word list")
        .task { await load() }
    }

    private func row(_ e: WordListEntry) -> some View {
        VStack(alignment: .leading) {
            Text(e.text).font(.japanese(size: 20))
            Text("\(e.reading) · \(e.gloss)").font(.caption).lineLimit(2)
        }
    }

    private func load() async { entries = (try? await app.graph.collection.entries(listId: listId)) ?? [] }

    private func addAll() {
        Task {
            guard let dict = try? await app.graph.dictionary() else { return }
            var added = 0
            for e in entries {
                guard let id = e.entryId?.int64Value else { continue }
                if (try? await app.graph.collection.isInReviews(entryId: id))?.boolValue == true { continue }
                if let detail = try? await dict.entry(id: id) {
                    _ = try? await app.graph.collection.addToReviews(entry: detail.entry, context: nil)
                    added += 1
                }
            }
            status = "Added \(added) words to reviews."
        }
    }
}
