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
            // Plan the next "reviews are ready" reminder from the current queue whenever the app backgrounds.
            if phase == .background {
                let graph = model.graph
                Task { await Reminders.reschedule(graph: graph) }
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
