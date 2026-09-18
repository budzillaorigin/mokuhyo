package app.tsumugi.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tsumugi.android.MainActivity
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget (G-15, Glance): reviews due now and the streak. Refreshed when the app leaves the screen, after
 * Today loads, and hourly by [WidgetRefreshWorker]. Reads the local database only (works offline).
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as TsumugiApplication).graph
        val lines = try {
            val due = graph.configuredSrs().dueCount()
            val streak = graph.stats.snapshot().streak
            listOf(
                context.resources.getQuantityString(R.plurals.widget_due, due, due),
                context.resources.getQuantityString(R.plurals.widget_streak, streak.current, streak.current) + if (streak.frozenToday) " ❄" else "",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(context.getString(R.string.widget_unavailable))
        }
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    Text("今日", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    lines.forEach { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp)) }
                }
            }
        }
    }

    companion object {
        /** Redraws every placed widget; harmless when none is placed. */
        suspend fun refresh(context: Context) {
            try {
                TodayWidget().updateAll(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        /** Hourly refresh so "due now" follows the clock while the app is closed. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "widget-refresh", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS).build(),
            )
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TodayWidget.refresh(applicationContext)
        return Result.success()
    }
}
