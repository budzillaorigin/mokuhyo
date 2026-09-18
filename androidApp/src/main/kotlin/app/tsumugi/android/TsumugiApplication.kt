package app.tsumugi.android

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import app.tsumugi.android.platform.LlamaJni
import app.tsumugi.android.platform.WhisperJni
import app.tsumugi.api.AppGraph
import app.tsumugi.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TsumugiApplication : Application() {
    lateinit var graph: AppGraph
        private set

    /** Work that should outlive a screen (e.g. syncing after a review session). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(PlatformServices(this))
        // Native bridges are cheap to construct: the .so files and models load on first use.
        graph.ai.llmBridge = LlamaJni(this)
        graph.ai.sttBridge = WhisperJni(this)
        graph.ai.deviceRamGb = deviceRamGb()
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW is still delivered to foreground apps.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) appScope.launch { graph.ai.unload() }
    }

    private fun deviceRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        // totalMem excludes memory the kernel reserves (an "8 GB" phone reports ~7.4), so round up to the nominal size.
        return kotlin.math.ceil(info.totalMem / (1024.0 * 1024.0 * 1024.0))
    }
}
