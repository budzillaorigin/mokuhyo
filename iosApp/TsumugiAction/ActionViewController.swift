import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Look up in Tsumugi" Action extension (BRIEF_V2 G-09). Like the share extension it never opens the app's database
/// and doesn't link the Kotlin framework: it writes the selected text as one small JSON file (kind "lookup") into the
/// App Group inbox, and the app opens the dictionary on it next time it becomes active (ShareInbox.swift in the app).
final class ActionViewController: UIViewController {
    private let model = LookupModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        model.onDone = { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
        let host = UIHostingController(rootView: LookupStatusView(model: model))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
        collect()
    }

    /// The selected text (plain text), or the item's attributed text when no text attachment came along.
    private func collect() {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        let fallback = items.first?.attributedContentText?.string
        let textType = UTType.plainText.identifier

        if let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(textType) }) {
            provider.loadItem(forTypeIdentifier: textType, options: nil) { item, _ in
                let value = (item as? String) ?? (item as? Data).flatMap { String(data: $0, encoding: .utf8) }
                Task { @MainActor [weak self] in self?.save(value) }
            }
        } else if let fallback, !fallback.isEmpty {
            save(fallback)
        } else {
            model.phase = .nothing
        }
    }

    private func save(_ value: String?) {
        let text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            model.phase = .nothing
            return
        }
        // A lookup is a word or phrase: keep it short so a whole page selection doesn't flood the search.
        let query = String(text.prefix(200))
        model.text = query
        model.phase = LookupInboxItem.write(value: query) ? .saved : .failed
    }
}

/// One inbox item. Must match `ShareInbox.Item` in the app (kind "lookup").
struct LookupInboxItem: Codable {
    var kind: String
    var value: String
    var title: String?
    var createdAt: Date
    var attempts: Int?

    static let appGroup = "group.app.tsumugi"
    static let folder = "share-inbox"

    /// Writes `<epoch ms>-<uuid>.json` so the app handles items in the order they arrived.
    static func write(value: String) -> Bool {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            return false
        }
        let dir = container.appendingPathComponent(folder, isDirectory: true)
        let item = LookupInboxItem(kind: "lookup", value: value, title: nil, createdAt: Date(), attempts: nil)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let name = "\(Int64(Date().timeIntervalSince1970 * 1000))-\(UUID().uuidString).json"
            try encoder.encode(item).write(to: dir.appendingPathComponent(name), options: .atomic)
            return true
        } catch {
            return false
        }
    }
}

@MainActor
final class LookupModel: ObservableObject {
    enum Phase {
        case working, saved, nothing, failed
    }

    @Published var phase = Phase.working
    @Published var text = ""
    var onDone: () -> Void = {}
}

struct LookupStatusView: View {
    @ObservedObject var model: LookupModel

    var body: some View {
        VStack(spacing: 16) {
            switch model.phase {
            case .working:
                ProgressView()
            case .saved:
                Image(systemName: "character.book.closed.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                Text(model.text).font(.title2).multilineTextAlignment(.center).lineLimit(4)
                Text("Saved for Tsumugi").font(.headline)
                Text("Open Tsumugi to see it in the dictionary.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
            case .nothing:
                Text("Nothing to look up").font(.headline)
                Text("Select some Japanese text first.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
            case .failed:
                Text("Couldn't save").font(.headline)
                Text("Tsumugi's shared storage isn't available. Copy the text and search in the app instead.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
            }
            if model.phase != .working {
                Button("Done") { model.onDone() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemBackground))
    }
}
