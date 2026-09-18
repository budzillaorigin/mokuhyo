import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Read in Tsumugi" share extension. It never opens the app's database (and doesn't link the Kotlin framework):
/// it writes the shared text or link as one small JSON file into the App Group inbox, and the app imports it into
/// the reader next time it becomes active (ShareInbox.swift in the app).
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        model.onDone = { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
        let host = UIHostingController(rootView: ShareStatusView(model: model))
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

    /// Prefers a web link; otherwise plain text.
    private func collect() {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        let title = items.first?.attributedContentText?.string
        let urlType = UTType.url.identifier
        let textType = UTType.plainText.identifier

        if let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(urlType) }) {
            provider.loadItem(forTypeIdentifier: urlType, options: nil) { item, _ in
                let value = (item as? URL)?.absoluteString ?? (item as? String)
                Task { @MainActor [weak self] in self?.save(kind: "url", value: value, title: nil) }
            }
        } else if let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(textType) }) {
            provider.loadItem(forTypeIdentifier: textType, options: nil) { item, _ in
                let value = (item as? String) ?? (item as? Data).flatMap { String(data: $0, encoding: .utf8) }
                Task { @MainActor [weak self] in self?.save(kind: "text", value: value, title: nil) }
            }
        } else if let title, !title.isEmpty {
            save(kind: "text", value: title, title: nil)
        } else {
            model.phase = .nothing
        }
    }

    private func save(kind: String, value: String?, title: String?) {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            model.phase = .nothing
            return
        }
        model.phase = InboxItem.write(kind: kind, value: value, title: title) ? .saved : .failed
    }
}

/// One shared item. Must match `ShareInbox.Item` in the app.
struct InboxItem: Codable {
    var kind: String
    var value: String
    var title: String?
    var createdAt: Date
    var attempts: Int?

    static let appGroup = "group.app.tsumugi"
    static let folder = "share-inbox"

    /// Writes `<epoch ms>-<uuid>.json` so the app imports items in the order they were shared.
    static func write(kind: String, value: String, title: String?) -> Bool {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            return false
        }
        let dir = container.appendingPathComponent(folder, isDirectory: true)
        let item = InboxItem(kind: kind, value: value, title: title, createdAt: Date(), attempts: nil)
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
final class ShareModel: ObservableObject {
    enum Phase {
        case working, saved, nothing, failed
    }

    @Published var phase = Phase.working
    var onDone: () -> Void = {}
}

struct ShareStatusView: View {
    @ObservedObject var model: ShareModel

    var body: some View {
        VStack(spacing: 16) {
            switch model.phase {
            case .working:
                ProgressView()
            case .saved:
                Image(systemName: "checkmark.circle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.green)
                    .accessibilityHidden(true)
                Text("Saved for Tsumugi").font(.headline)
                Text("Open Tsumugi to read it in the reader.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
            case .nothing:
                Text("Nothing to read").font(.headline)
                Text("Share some Japanese text or a link to a web page.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
            case .failed:
                Text("Couldn't save").font(.headline)
                Text("Tsumugi's shared storage isn't available. Paste the text in the reader instead.")
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
