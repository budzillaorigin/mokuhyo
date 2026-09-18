import Shared
import SwiftUI
import UIKit

@main
struct TsumugiApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await Reminders.requestPermission() }
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

    init() {
        // Native engines are platform code; the shared AiService builds models on top of them (tools/models/README.md).
        graph.ai.llmBridge = LlamaBridge()
        graph.ai.sttBridge = WhisperBridge()
        // iOS reports a little less than the installed RAM; round to whole GB for the model recommendation.
        graph.ai.deviceRamGb = (Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824).rounded()
    }
}
