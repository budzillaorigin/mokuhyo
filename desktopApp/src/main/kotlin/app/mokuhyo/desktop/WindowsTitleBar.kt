package app.mokuhyo.desktop

import app.mokuhyo.platform.Os
import java.io.File

/**
 * Dark title bar on Windows (D-027). The frame is drawn by Windows, not Compose; `mokuhyo_win.dll`
 * (native/win/dark_titlebar.c) asks DWM for dark mode and the app's caption colour. No-op elsewhere or when the
 * helper is missing.
 */
object WindowsTitleBar {
    private val loaded: Boolean by lazy {
        if (Os.current != Os.WINDOWS) return@lazy false
        val dll = Resources.dir?.let { File(it, "native/mokuhyo_win.dll") }?.takeIf { it.isFile } ?: return@lazy false
        runCatching { System.load(dll.absolutePath) }.isSuccess
    }

    @JvmStatic
    private external fun nativeDarken(): Int

    /** Call once the window is visible. */
    fun darken() {
        if (loaded) runCatching { nativeDarken() }
    }
}
