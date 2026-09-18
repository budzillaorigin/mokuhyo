import Foundation
import Shared
import SwiftUI

/// Files opened with Tsumugi from Files, Mail or AirDrop (F-42). The document types are declared in
/// iosApp/TsumugiInfo.plist. EPUBs open in the reader and .apkg decks go through the Anki importer. Subtitles and
/// item banks need context the app can't guess (which video, which exam), so they get a message saying where to
/// use them.
@MainActor
struct OpenedFileHandler: ViewModifier {
    let model: AppModel
    @State private var message: String?

    func body(content: Content) -> some View {
        content
            .onOpenURL { url in
                guard url.isFileURL else { return }
                Task { message = await Self.open(url, model: model) }
            }
            .alert(
                "Open file",
                isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(message ?? "")
            }
    }

    /// Imports or explains [url]; returns the message to show, or nil when the result is visible (a reader sheet).
    private static func open(_ url: URL, model: AppModel) async -> String? {
        let name = url.lastPathComponent
        let ext = url.pathExtension.lowercased()
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("opened-file." + ext)
        do {
            // Off the main actor: an EPUB can be tens of MB.
            try await Task.detached {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.copyItem(at: url, to: target)
                // The system's copy in Documents/Inbox is no longer needed.
                if url.path.contains("/Documents/Inbox/") { try? FileManager.default.removeItem(at: url) }
            }.value
        } catch {
            return "Couldn't read \(name): \(error.localizedDescription)"
        }
        do {
            switch ext {
            case "epub":
                let id = try await model.graph.reader.importEpub(path: target.path)
                model.sharedDocument = SharedDocument(id: id)
                return nil
            case "apkg":
                let r = try await model.graph.imports.importAnki(apkgPath: target.path)
                return "Imported \(r.notes) notes, \(r.cards) cards and \(r.reviews) reviews from \(name)."
            case "srt", "vtt":
                return "To use \(name), open Practice → Media player, choose your video, then add the subtitles from Files."
            default:
                return "To use \(name) as an exam item bank, open Exams → Exam item banks → Import item bank (.json)."
            }
        } catch {
            return "Couldn't import \(name): \(error.localizedDescription)"
        }
    }
}

extension View {
    /// Handles files opened with Tsumugi (see `OpenedFileHandler`).
    func handlesOpenedFiles(_ model: AppModel) -> some View {
        modifier(OpenedFileHandler(model: model))
    }
}
