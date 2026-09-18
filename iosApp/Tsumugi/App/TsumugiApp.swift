import Shared
import SwiftUI

@main
struct TsumugiApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await Reminders.requestPermission() }
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
}
