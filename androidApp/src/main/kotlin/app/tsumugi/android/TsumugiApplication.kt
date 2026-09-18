package app.tsumugi.android

import android.app.Application
import app.tsumugi.api.AppGraph
import app.tsumugi.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TsumugiApplication : Application() {
    lateinit var graph: AppGraph
        private set

    /** Work that should outlive a screen (e.g. syncing after a review session). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(PlatformServices(this))
    }
}
