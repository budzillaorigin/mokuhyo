import Shared
import SwiftUI
import UIKit

@main
struct TsumugiApp: App {
    /// Background model downloads report through the app delegate when iOS relaunches the app for them (F-13).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                // EPUB, .apkg, subtitles and item banks opened from Files, Mail or AirDrop (Platform/OpenedFiles.swift).
                .handlesOpenedFiles(model)
                // Text and links shared with "Read in Tsumugi" while the app wasn't running.
                .task { await model.importSharedItems() }
                // Free the on-device model under memory pressure; it reloads on next use.
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                    let graph = model.graph
                    Task { try? await graph.ai.unload() }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                let graph = model.graph
                Task { try? await graph.syncIfConfigured() }
                let appModel = model
                Task { await appModel.importSharedItems() }
            }
            // Whenever the app backgrounds: plan the next "reviews are ready" reminder and refresh the widgets.
            if phase == .background {
                let graph = model.graph
                Task {
                    // Reader or player stretches still open end here (D-167, D-194).
                    _ = try? await graph.immersion.stopAll()
                    await Reminders.reschedule(graph: graph)
                    await WidgetSnapshot.write(graph: graph)
                }
            }
        }
    }
}

/// Holds the shared-core composition root for the whole app.
@MainActor
@Observable
final class AppModel {
    let graph = AppGraph(platform: PlatformServices())
    /// The newest document imported from the share extension; RootView opens it in a reader sheet.
    var sharedDocument: SharedDocument?
    /// Text sent to "Look up in Tsumugi" (the Action extension); RootView opens the dictionary on it.
    var sharedLookup: SharedLookup?

    init() {
        // G-14: shared-core labels (stages, kinds, Today blocks…) follow the app's language.
        SharedText.setLanguage()
        // Native engines are platform code; the shared AiService builds models on top of them (tools/models/README.md).
        graph.ai.llmBridge = LlamaBridge()
        graph.ai.sttBridge = WhisperBridge()
        // iOS reports a little less than the installed RAM; round to whole GB for the model recommendation.
        graph.ai.deviceRamGb = (Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824).rounded()
        ModelDownloads.shared.attach(graph: graph)
        // Synthesized VOICEVOX audio left over from the last run (nothing is playing yet; F-30).
        let appGraph = graph
        Task.detached(priority: .utility) {
            SwiftSupport.shared.cleanSynthesized(graph: appGraph)
        }
        // Audio packs the build bundles (D-097) are installed once per version; a no-op when none are bundled.
        // The install itself runs on Dispatchers.IO in the shared code (rule 15).
        Task.detached(priority: .utility) {
            _ = try? await appGraph.audio.ensureBundled()
        }
    }

    /// Imports items waiting in the share inbox into the reader and opens the newest one.
    func importSharedItems() async {
        let result = await ShareInbox.importPending(graph: graph)
        if let last = result.documents.last { sharedDocument = SharedDocument(id: last) }
        if let lookup = result.lookups.last { sharedLookup = SharedLookup(text: lookup) }
    }
}
