package app.mokuhyo.desktop

import app.mokuhyo.settings.Settings
import app.mokuhyo.update.UpdateChecker
import app.mokuhyo.update.UpdateResult
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The optional update check (off by default). When the learner has turned it on, [startup] checks at most once a day;
 * [checkNow] is the Settings button. Nothing is downloaded or installed: the title bar shows a link to the release.
 */
class Updates(private val scope: CoroutineScope, private val settings: Settings, private val checker: UpdateChecker = UpdateChecker(Java.create())) {
    private val _state = MutableStateFlow<UpdateResult?>(null)
    val state: StateFlow<UpdateResult?> = _state

    fun startup() {
        if (!settings.bool(Settings.Key.UPDATE_CHECK)) return
        val last = settings.get(Settings.Key.UPDATE_LAST_CHECK)?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < UpdateChecker.INTERVAL_MS) return
        checkNow()
    }

    fun checkNow() {
        scope.launch {
            _state.value = checker.check(BuildInfo.version)
            settings.put(Settings.Key.UPDATE_LAST_CHECK, System.currentTimeMillis().toString())
        }
    }
}
