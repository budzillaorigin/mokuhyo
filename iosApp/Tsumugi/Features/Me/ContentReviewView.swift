import Shared
import SwiftUI

/// Me → Content review (G-16, D-118), behind the developer toggle in Settings: unverified AI-drafted content from the
/// installed packs, by type. Each item can be accepted, edited or rejected with a note; the verdicts export as JSON
/// for `tools/items/review.py --ingest`. Nothing here removes a badge: that happens when the rebuilt pack ships.
struct ContentReviewView: View {
    @Environment(AppModel.self) private var app
    @State private var counts: [ReviewKindCount]?
    @State private var error: String?
    @State private var exported: URL?
    @State private var reviewer = ""
    @State private var exporting = false

    var body: some View {
        List {
            if let error {
                Section {
                    Label("Couldn't load the review queue: \(error)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { Task { await load() } }
                }
            }
            if let counts {
                if counts.isEmpty {
                    Text("Nothing to review: the installed packs have no unverified AI-drafted content.")
                        .foregroundStyle(.secondary)
                }
                Section("Unverified content") {
                    ForEach(counts, id: \.kind) { c in
                        NavigationLink {
                            ContentReviewListView(kind: c.kind)
                        } label: {
                            LabeledContent(c.kind.label, value: String(localized: "\(Int(c.decided)) of \(Int(c.total)) decided"))
                        }
                    }
                }
                Section {
                    TextField("Reviewer name", text: $reviewer)
                    Button(exporting ? "Exporting…" : "Export verdicts (JSON)") { export() }.disabled(exporting)
                    if let exported { ShareLink(item: exported) { Label("Share verdicts", systemImage: "square.and.arrow.up") } }
                } footer: {
                    Text("Run `tools/items/review.py --ingest verdicts.json` on the export to apply it to the source files.")
                }
            } else if error == nil {
                ProgressView()
            }
        }
        .navigationTitle("Content review")
        .task { await load() }
    }

    private func load() async {
        error = nil
        do {
            let service = try await app.graph.contentReview()
            counts = try await SwiftSupport.shared.reviewCounts(service: service)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func export() {
        exporting = true
        let graph = app.graph
        let name = reviewer.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let service = try await graph.contentReview()
                let json = try await service.exportJson(reviewer: name.isEmpty ? "owner" : name)
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("tsumugi-review-verdicts.json")
                try json.write(to: url, atomically: true, encoding: .utf8)
                exported = url
            } catch {
                self.error = error.localizedDescription
            }
            exporting = false
        }
    }
}

/// Candidates of one kind with their verdicts so far.
private struct ContentReviewListView: View {
    @Environment(AppModel.self) private var app
    let kind: ReviewKind

    @State private var entries: [ReviewEntry]?
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                Button("Retry") { Task { await load() } }
            }
            if let entries {
                ForEach(entries, id: \.candidate.id) { e in
                    NavigationLink {
                        ContentReviewItemView(entry: e) { Task { await load() } }
                    } label: {
                        HStack {
                            Text(e.candidate.title).font(.japanese(size: 16)).lineLimit(2)
                            Spacer()
                            if let code = e.verdictCode { TagView(verdictLabel(code)) }
                        }
                    }
                }
            } else if error == nil {
                ProgressView()
            }
        }
        .navigationTitle(kind.label)
        .task { await load() }
    }

    private func load() async {
        error = nil
        do {
            let service = try await app.graph.contentReview()
            entries = try await SwiftSupport.shared.reviewQueue(service: service, kind: kind)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

func verdictLabel(_ code: String) -> String {
    switch code {
    case "accept": String(localized: "Accepted")
    case "edit": String(localized: "Edited")
    case "reject": String(localized: "Rejected")
    default: code
    }
}

/// One candidate: the read-only context, its editable fields, and accept / edit / reject with a note.
private struct ContentReviewItemView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let entry: ReviewEntry
    let onDecided: () -> Void

    @State private var fields: [String: String] = [:]
    @State private var notes = ""
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                Text(entry.candidate.title).font(.japanese(size: 18, weight: .semibold))
                TagView(entry.candidate.source == "llm" ? String(localized: "AI-generated · unreviewed") : entry.candidate.source)
                if let code = entry.verdictCode {
                    Text("Current verdict: \(verdictLabel(code))").font(.caption)
                }
            }
            if !entry.candidate.display.isEmpty {
                Section("Context") {
                    Text(entry.candidate.display).font(.japanese(size: 15)).textSelection(.enabled)
                }
            }
            Section("Fields") {
                ForEach(fields.keys.sorted(), id: \.self) { key in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(key).font(.caption.weight(.semibold))
                        TextField(key, text: Binding(get: { fields[key] ?? "" }, set: { fields[key] = $0 }), axis: .vertical)
                            .font(.japanese(size: 15))
                    }
                }
            }
            Section("Note") {
                TextField("Why (optional)", text: $notes, axis: .vertical)
            }
            Section {
                Button("Accept") { decide("accept") }
                Button("Save edits") { decide("edit") }.disabled(!edited)
                Button("Reject", role: .destructive) { decide("reject") }
                if let error { Text(error).foregroundStyle(.red) }
            } footer: {
                Text("Edits keep only the fields you changed. An edit that changes nothing counts as accept.")
            }
        }
        .disabled(saving)
        .navigationTitle(entry.candidate.kind.label)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            var merged = entry.candidate.fields
            for (k, v) in entry.edits { merged[k] = v }
            fields = merged
            notes = entry.notes
        }
    }

    private var edited: Bool { fields.contains { entry.candidate.fields[$0.key] != $0.value } }

    private func decide(_ code: String) {
        saving = true
        error = nil
        let graph = app.graph
        let candidate = entry.candidate
        let edits = code == "edit" ? fields : [:]
        let note = notes
        Task {
            do {
                let service = try await graph.contentReview()
                _ = try await SwiftSupport.shared.decideReview(service: service, candidate: candidate, verdictCode: code, notes: note, edits: edits)
                onDecided()
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
            saving = false
        }
    }
}
