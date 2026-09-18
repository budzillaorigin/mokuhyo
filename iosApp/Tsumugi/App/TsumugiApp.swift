import Shared
import SwiftUI

@main
struct TsumugiApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
    }
}

/// Holds the shared-core composition root for the whole app.
@MainActor
@Observable
final class AppModel {
    let graph = AppGraph(platform: PlatformServices())
}
