package app.tsumugi.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import app.tsumugi.android.app.Incoming
import app.tsumugi.android.app.TsumugiApp
import app.tsumugi.android.app.displayName
import app.tsumugi.android.platform.AppLanguages
import app.tsumugi.android.widget.TodayWidget
import android.content.Context
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Text from the share sheet or the text-selection menu, waiting for the UI to open it. */
    private val incoming = mutableStateOf<Incoming?>(null)

    /** Android 8–12: the in-app language choice (G-15); Android 13+ gets it from LocaleManager. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguages.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A language change recreates the activity: shared-core labels follow (G-14).
        AppLanguages.applyToShared(this)
        enableEdgeToEdge()
        // Only the first launch reads the intent; after a configuration change it has already been handled.
        if (savedInstanceState == null) incoming.value = parse(intent)
        // No notification permission request at launch (F-34): NotificationPermissionPrompt asks after the first review session.
        setContent { TsumugiApp(incoming.value, onIncomingHandled = { incoming.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { incoming.value = it }
    }

    /**
     * ACTION_SEND text/plain → "Read in Tsumugi"; ACTION_PROCESS_TEXT → "Look up in Tsumugi";
     * ACTION_VIEW on an EPUB, .apkg, subtitle or JSON file → open it (app/OpenedFiles.kt).
     */
    private fun parse(intent: Intent?): Incoming? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { Incoming.Read(it) }
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { Incoming.Lookup(it) }
        Intent.ACTION_VIEW -> intent.data?.let { uri -> Incoming.OpenFile(uri, displayName(this, uri), intent.type) }
        else -> null
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { (application as TsumugiApplication).graph.syncIfConfigured() }
    }

    override fun onStop() {
        super.onStop()
        // Plan the next "reviews are ready" reminder from the current queue whenever the app leaves the screen.
        lifecycleScope.launch { Reminders.reschedule(applicationContext) }
        // The home-screen widget shows what the session changed (G-15).
        (application as TsumugiApplication).appScope.launch { TodayWidget.refresh(applicationContext) }
    }
}
