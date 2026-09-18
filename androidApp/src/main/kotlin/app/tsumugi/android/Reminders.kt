package app.tsumugi.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Local review reminders. The shared ReminderPlanner decides when; this schedules one inexact alarm whenever
 * the app goes to the background, and [ReminderReceiver] posts the notification. No server, no background job.
 */
object Reminders {
    private const val CHANNEL = "reviews"
    private const val REQUEST = 1

    suspend fun reschedule(context: Context) {
        val graph = (context.applicationContext as TsumugiApplication).graph
        val alarms = context.getSystemService(AlarmManager::class.java)
        val reminder = graph.reminders.next()
        val intent = Intent(context, ReminderReceiver::class.java)
        if (reminder == null) {
            alarms.cancel(PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE))
            return
        }
        intent.putExtra("title", reminder.title).putExtra("body", reminder.body)
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.set(AlarmManager.RTC, reminder.at.toEpochMilliseconds(), pending)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(context: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REQUEST, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.show(context, intent.getStringExtra("title") ?: context.getString(R.string.reminder_title), intent.getStringExtra("body").orEmpty())
    }
}

private const val NOTIFICATIONS_ASKED = "notifications.asked"

/**
 * F-34: asks for the notification permission once, after the first completed review session, from a dialog that says
 * what the notifications are for. Nothing shows below Android 13, when it's already granted, or once the learner has
 * answered (either way). That answer is a device-local preference, like the permission itself.
 */
@Composable
fun NotificationPermissionPrompt() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tsumugi.device", Context.MODE_PRIVATE) }
    var show by remember {
        mutableStateOf(
            !prefs.getBoolean(NOTIFICATIONS_ASKED, false) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (!show) return
    val answered = {
        prefs.edit { putBoolean(NOTIFICATIONS_ASKED, true) }
        show = false
    }
    AlertDialog(
        onDismissRequest = answered,
        title = { Text(stringResource(R.string.notifications_ask_title)) },
        text = { Text(stringResource(R.string.notifications_ask_text)) },
        confirmButton = {
            TextButton(onClick = {
                answered()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(stringResource(R.string.notifications_ask_allow)) }
        },
        dismissButton = { TextButton(onClick = answered) { Text(stringResource(R.string.notifications_ask_later)) } },
    )
}
