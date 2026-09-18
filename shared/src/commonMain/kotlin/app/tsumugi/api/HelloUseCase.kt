package app.tsumugi.api

import app.tsumugi.platform.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Phase 0 smoke test: proves both apps are wired to the shared core. Replaced by the Today planner in Phase 3. */
class HelloUseCase(private val platform: Platform) {

    fun greeting(): Flow<Greeting> = flowOf(
        Greeting(
            title = "今日",
            message = "Tsumugi shared core ${SharedInfo.VERSION} on ${platform.name}",
        ),
    )
}

data class Greeting(val title: String, val message: String)

object SharedInfo {
    const val VERSION = "0.0.1"
}
