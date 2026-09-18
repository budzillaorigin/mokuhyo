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

    init() {
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
    }

    /// Imports items waiting in the share inbox into the reader and opens the newest one.
    func importSharedItems() async {
        let ids = await ShareInbox.importPending(graph: graph)
        if let last = ids.last { sharedDocument = SharedDocument(id: last) }
    }
}
