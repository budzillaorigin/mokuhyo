package app.tsumugi.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import app.tsumugi.android.app.Incoming
import app.tsumugi.android.app.TsumugiApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Text from the share sheet or the text-selection menu, waiting for the UI to open it. */
    private val incoming = mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only the first launch reads the intent; after a configuration change it has already been handled.
        if (savedInstanceState == null) incoming.value = parse(intent)
        if (Build.VERSION.SDK_INT >= 33 && incoming.value == null) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { TsumugiApp(incoming.value, onIncomingHandled = { incoming.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { incoming.value = it }
    }

    /** ACTION_SEND text/plain → "Read in Tsumugi"; ACTION_PROCESS_TEXT → "Look up in Tsumugi". */
    private fun parse(intent: Intent?): Incoming? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { Incoming.Read(it) }
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { Incoming.Lookup(it) }
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
    }
}
