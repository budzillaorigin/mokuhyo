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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

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
