import Observation
import Shared

/// Thin wrapper over the shared `HelloUseCase` flow (SKIE exposes Kotlin `Flow` as an `AsyncSequence`).
@MainActor
@Observable
final class TodayViewModel {
    private(set) var title: String?
    private(set) var message: String?

    private let useCase: HelloUseCase

    init(useCase: HelloUseCase = SharedGraph.shared.helloUseCase()) {
        self.useCase = useCase
    }

    func observe() async {
        for await greeting in useCase.greeting() {
            title = greeting.title
            message = greeting.message
        }
    }
}
