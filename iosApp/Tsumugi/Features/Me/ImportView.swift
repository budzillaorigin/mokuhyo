import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Anki .apkg import/export, imiwa word lists, and WaniKani (BRIEF §5.4, §9).
struct ImportView: View {
    @Environment(AppModel.self) private var app

    @State private var busy = false
    @State private var message: String?
    @State private var token = ""
    @State private var wkStatus = ""
    @State private var pickingAnki = false
    @State private var pickingList = false
    @State private var pickingBunpro = false
    @State private var exported: URL?

    var body: some View {
        Form {
            if busy { ProgressView() }
            if let message { Text(message).font(.subheadline) }

            Section {
                Button("Import .apkg") { pickingAnki = true }.disabled(busy)
                Button("Export everything to .apkg") { export() }.disabled(busy)
                if let exported {
                    ShareLink(item: exported) { Label("Share exported deck", systemImage: "square.and.arrow.up") }
                }
            } header: {
                Text("Anki")
            } footer: {
                Text("Any .apkg, including NihongoShark decks: your myStory notes and review history come along.")
            }

            Section {
                Button("Import word list") { pickingList = true }.disabled(busy)
            } header: {
                Text("Word lists")
            } footer: {
                Text("imiwa? exports or any CSV/TSV of word, reading, meaning.")
            }

            Section {
                Button("Import Bunpro CSV") { pickingBunpro = true }.disabled(busy)
            } header: {
                Text("Bunpro")
            } footer: {
                Text("A CSV export of your Bunpro grammar (title + SRS level). Only your progress is imported.")
            }

            Section {
                NavigationLink("Exam item banks", value: Route.examBanks)
            } header: {
                Text("Exams")
            } footer: {
                Text("Import JLPT/DLPT practice item banks (JSON). Invalid banks are rejected with every problem listed.")
            }

            Section {
                Text(wkStatus)
                SecureField("API v2 token", text: $token)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Connect") { connect() }.disabled(busy || token.isEmpty)
                Button("Import progress") { importWaniKani() }.disabled(busy || !app.graph.imports.wanikani.isConnected())
            } header: {
                Text("WaniKani")
            } footer: {
                Text("Paste a Personal Access Token (WaniKani → Settings → API tokens). It's kept in the Keychain and only sent to api.wanikani.com. Progress maps onto the kanji path; mnemonics are never copied.")
            }
        }
        .navigationTitle("Import & export")
        .onAppear { wkStatus = app.graph.imports.wanikani.isConnected() ? "Connected" : "Not connected" }
        .fileImporter(isPresented: $pickingAnki, allowedContentTypes: [.data]) { result in
            withFile(result, name: "import.apkg") { path in
                let r = try await app.graph.imports.importAnki(apkgPath: path)
                var text = "Imported \(r.notes) notes, \(r.cards) cards and \(r.reviews) reviews"
                if r.nihongoSharkNotes > 0 { text += " (\(r.nihongoSharkNotes) NihongoShark kanji with your stories)" }
                return text + "." + (r.warnings.isEmpty ? "" : "\n" + r.warnings.prefix(5).joined(separator: "\n"))
            }
        }
        .fileImporter(isPresented: $pickingBunpro, allowedContentTypes: [.commaSeparatedText, .tabSeparatedText, .plainText, .data]) { result in
            withFile(result, name: "bunpro.csv") { path in
                guard let r = try await app.graph.imports.importBunpro(filePath: path) else { return "The grammar pack isn't installed." }
                let missing = r.unmatched.prefix(8).joined(separator: "、")
                return "Matched \(r.matched) of \(r.rows) grammar points." + (r.unmatched.isEmpty ? "" : " Not matched: \(missing)")
            }
        }
        .fileImporter(isPresented: $pickingList, allowedContentTypes: [.plainText, .commaSeparatedText, .tabSeparatedText, .data]) { result in
            withFile(result, name: "wordlist.txt") { path in
                let r = try await app.graph.imports.importWordList(filePath: path, listName: "Imported list")
                return "Imported \(r.imported) words (\(r.resolved) matched to the dictionary, \(r.skipped) skipped)."
            }
        }
    }

    /// Copies a picked file somewhere readable (security-scoped URLs expire), then runs [work] on it.
    private func withFile(_ result: Result<URL, Error>, name: String, work: @escaping (String) async throws -> String) {
        guard case .success(let url) = result else { return }
        let target = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.copyItem(at: url, to: target)
        } catch {
            message = "Couldn't read the file: \(error.localizedDescription)"
            return
        }
        run("Importing") { try await work(target.path) }
    }

    private func export() {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("tsumugi.apkg")
        run("Exporting") {
            _ = try await app.graph.imports.exportAnki(outPath: out.path, itemIds: [], deckName: "Tsumugi")
            exported = out
            return "Exported. Share it to Anki or save it to Files."
        }
    }

    private func connect() {
        let value = token
        run("Connecting to WaniKani") {
            let user = try await app.graph.imports.connectWaniKani(token: value)
            token = ""
            wkStatus = "Connected as \(user.username) (level \(user.level))"
            return wkStatus
        }
    }

    private func importWaniKani() {
        run("Importing from WaniKani") {
            let result = try await app.graph.imports.importWaniKani { progress in
                Task { @MainActor in message = progress }
            }
            guard let r = result else { return "The kanji path pack isn't installed." }
            return "Imported: \(r.matchedToPath) items on the path, \(r.wanikaniOnlyItems) WaniKani-only, \(r.reviewsImported) reviews. Your WaniKani level is \(r.wanikaniLevel)."
        }
    }

    private func run(_ label: String, _ block: @escaping () async throws -> String) {
        busy = true
        message = "\(label)…"
        Task {
            do { message = try await block() } catch { message = "\(label) failed: \(error.localizedDescription)" }
            busy = false
        }
    }
}
