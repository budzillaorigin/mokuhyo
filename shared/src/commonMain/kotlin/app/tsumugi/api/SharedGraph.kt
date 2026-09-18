package app.tsumugi.api

import app.tsumugi.platform.currentPlatform

/**
 * Composition root that both apps call into (Swift: `SharedGraph.shared.helloUseCase()`).
 * Grows into the real dependency graph as modules land.
 */
object SharedGraph {
    fun helloUseCase(): HelloUseCase = HelloUseCase(currentPlatform())
}
