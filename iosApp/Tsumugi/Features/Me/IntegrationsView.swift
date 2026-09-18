import Shared
import SwiftUI

/// Settings → Integrations (G-09, D-119): push items and daily stats to the learner's own Notion databases, and mined
/// cards to desktop Anki through AnkiConnect on the local network. Tokens live in the Keychain (shared secret store)
/// and are sent only to the service they belong to (rule 14). Every push shows progress and can be cancelled.
struct IntegrationsView: View {
    @Environment(AppModel.self) private var app

    // Notion
    @State private var notionConnected = false
    @State private var notionToken = ""
    @State private var itemsDb = ""
    @State private var statsDb = ""
    // AnkiConnect
    @State private var ankiUrl = "http://<lan-ip>:8765"
    @State private var ankiDeck = "Tsumugi::Mined"
    @State private var ankiModel = "Basic"
    @State private var ankiFront = "Front"
    @State private var ankiBack = "Back"
    @State private var ankiKey = ""
    @State private var ankiConfigured = false

    @State private var busy: String?
    @State private var progress: Double?
    @State private var message: String?
    @State private var errorText: String?
    @State private var work: Task<Void, Never>?

    var body: some View {
        Form {
            if let busy {
                Section {
                    HStack {
                        ProgressView()
                        Text(busy)
                        Spacer()
                        Button("Cancel") { work?.cancel(); work = nil; self.busy = nil; progress = nil }
                    }
                    if let progress { ProgressView(value: progress) }
                }
            }
            if let message { Section { Text(message).font(.subheadline) } }
            if let errorText { Section { Label(errorText, systemImage: "exclamationmark.triangle").foregroundStyle(.red) } }

            Section {
                if notionConnected {
                    Label("Connected", systemImage: "checkmark.circle").foregroundStyle(.green)
                    Button("Push items") { pushNotionItems() }.disabled(busy != nil)
                    Button("Push the last 30 days of stats") { pushNotionStats() }.disabled(busy != nil)
                    Button("Disconnect", role: .destructive) { disconnectNotion() }.disabled(busy != nil)
                } else {
                    SecureField("Internal integration token", text: $notionToken)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Items database (URL or id)", text: $itemsDb)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Stats database (URL or id)", text: $statsDb)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Connect") { connectNotion() }
                        .disabled(busy != nil || notionToken.isEmpty || (itemsDb.isEmpty && statsDb.isEmpty))
                }
            } header: {
                Text("Notion")
            } footer: {
                Text("Create an internal integration in Notion, share your databases with it, and paste its token. The token is kept in the Keychain and sent only to api.notion.com. The property names each database needs are listed in docs/INTEGRATIONS.md.")
            }

            Section {
                TextField("AnkiConnect address", text: $ankiUrl)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                TextField("Deck", text: $ankiDeck)
                TextField("Note type", text: $ankiModel)
                TextField("Front field", text: $ankiFront)
                TextField("Back field", text: $ankiBack)
                SecureField("AnkiConnect key (optional)", text: $ankiKey)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(ankiConfigured ? "Check and save again" : "Check and save") { configureAnki() }.disabled(busy != nil || ankiUrl.isEmpty)
                if ankiConfigured {
                    Button("Push mined cards") { pushAnki() }.disabled(busy != nil)
                }
            } header: {
                Text("AnkiConnect")
            } footer: {
                Text("Desktop Anki with the AnkiConnect add-on, on the same network. Pushes the words and clips you mined (reader, media, personal cards); Anki skips duplicates. The address stays on this device.")
            }
        }
        .navigationTitle("Integrations")
        .task { await load() }
        .onDisappear { work?.cancel() }
    }

    private func load() async {
        let graph = app.graph
        notionConnected = graph.notion.isConnected
        if let config = try? await graph.notion.config() {
            itemsDb = config.itemsDatabaseId ?? ""
            statsDb = config.statsDatabaseId ?? ""
        }
        if let anki = try? await graph.ankiConnect.config() {
            ankiUrl = anki.url
            ankiDeck = anki.deck
            ankiModel = anki.model
            ankiFront = anki.frontField
            ankiBack = anki.backField
            ankiConfigured = true
        }
    }

    private func run(_ label: String, _ block: @escaping () async throws -> String) {
        busy = label
        progress = nil
        message = nil
        errorText = nil
        work = Task {
            do {
                message = try await block()
            } catch {
                if !Task.isCancelled { errorText = String(localized: "\(label) failed: \(error.localizedDescription)") }
            }
            busy = nil
            progress = nil
        }
    }

    private func report(_ p: PushProgress) {
        let fraction = p.total > 0 ? Double(p.done) / Double(p.total) : 0
        Task { @MainActor in progress = fraction }
    }

    private func connectNotion() {
        let graph = app.graph
        let token = notionToken, items = itemsDb, stats = statsDb
        run(String(localized: "Connecting to Notion")) {
            let problems = try await graph.notion.connect(token: token, itemsDatabaseId: items.isEmpty ? nil : items, statsDatabaseId: stats.isEmpty ? nil : stats)
            if problems.isEmpty {
                notionToken = ""
                notionConnected = true
                return String(localized: "Connected to Notion.")
            }
            return String(localized: "Not connected. Fix these in Notion first: \(problems.joined(separator: "; "))")
        }
    }

    private func disconnectNotion() {
        let graph = app.graph
        run(String(localized: "Disconnecting")) {
            try await graph.notion.disconnect()
            notionConnected = false
            return String(localized: "Disconnected. The token was removed from the Keychain.")
        }
    }

    private func pushNotionItems() {
        let graph = app.graph
        run(String(localized: "Pushing items to Notion")) {
            let r = try await SwiftSupport.shared.pushItemsToNotion(graph: graph) { p in report(p) }
            return pushSummary(created: Int(r.created), updated: Int(r.updated), failed: r.failed)
        }
    }

    private func pushNotionStats() {
        let graph = app.graph
        run(String(localized: "Pushing stats to Notion")) {
            let r = try await SwiftSupport.shared.pushStatsToNotion(graph: graph, days: 30) { p in report(p) }
            return pushSummary(created: Int(r.created), updated: Int(r.updated), failed: r.failed)
        }
    }

    private func pushSummary(created: Int, updated: Int, failed: [String]) -> String {
        var text = String(localized: "\(created) created, \(updated) updated.")
        if !failed.isEmpty {
            text += " " + String(localized: "\(failed.count) failed: \(failed.prefix(3).joined(separator: "; "))")
        }
        return text
    }

    private func configureAnki() {
        let graph = app.graph
        let config = AnkiConnectConfig(url: ankiUrl, deck: ankiDeck, model: ankiModel, frontField: ankiFront, backField: ankiBack)
        let key = ankiKey
        run(String(localized: "Checking AnkiConnect")) {
            try await graph.ankiConnect.configure(config: config, apiKey: key.isEmpty ? nil : key)
            ankiConfigured = true
            ankiKey = ""
            return String(localized: "Anki answered. Settings saved on this device.")
        }
    }

    private func pushAnki() {
        let graph = app.graph
        run(String(localized: "Pushing to Anki")) {
            let r = try await SwiftSupport.shared.pushMinedToAnki(graph: graph) { p in report(p) }
            var text = String(localized: "\(Int(r.added)) added, \(Int(r.duplicates)) already in Anki.")
            if !r.failed.isEmpty { text += " " + String(localized: "\(r.failed.count) failed: \(r.failed.prefix(3).joined(separator: "; "))") }
            return text
        }
    }
}
