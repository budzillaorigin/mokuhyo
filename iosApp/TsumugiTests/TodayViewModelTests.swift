import Testing
@testable import Tsumugi

@MainActor
struct TodayViewModelTests {
    @Test func loadsGreetingFromSharedCore() async {
        let model = TodayViewModel()
        let task = Task { await model.observe() }
        await task.value

        #expect(model.title == "今日")
        #expect(model.message?.contains("Tsumugi shared core") == true)
    }
}
