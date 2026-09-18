package app.tsumugi.android

import android.app.Application
import app.tsumugi.api.AppGraph
import app.tsumugi.platform.PlatformServices

class TsumugiApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(PlatformServices(this))
    }
}
