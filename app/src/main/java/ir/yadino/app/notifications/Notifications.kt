package ir.yadino.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ir.yadino.app.MainActivity
import ir.yadino.app.R
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.ui.navigation.Route

/** Notification channels and builders. All texts are Persian string resources. */
object NotificationHelper {
    const val CHANNEL_TASKS = "tasks"
    const val CHANNEL_HABITS = "habits"
    const val CHANNEL_GOALS = "goals"
    const val CHANNEL_SUMMARY = "summary"
    const val CHANNEL_BACKUP = "backup"

    const val EXTRA_ROUTE = "ir.yadino.app.extra.ROUTE"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(CHANNEL_TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_tasks_desc)
            },
            NotificationChannel(CHANNEL_HABITS, context.getString(R.string.channel_habits), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_habits_desc)
            },
            NotificationChannel(CHANNEL_GOALS, context.getString(R.string.channel_goals), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_goals_desc)
            },
            NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.channel_summary), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_summary_desc)
            },
            NotificationChannel(CHANNEL_BACKUP, context.getString(R.string.channel_backup), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_backup_desc)
            },
        )
        nm.createNotificationChannels(channels)
    }

    /** True when the app is allowed to post notifications (runtime permission on Android 13+). */
    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun openRouteIntent(context: Context, route: Route, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, route.encode())
            action = "ir.yadino.app.OPEN.$requestCode"
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(context: Context, action: String, id: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            ErrorReporter.log(context, e, "notify")
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ContextCompat.getColor(context, R.color.yadino_green))
        .setAutoCancel(true)

    fun showTaskReminder(context: Context, taskId: String, title: String, text: String, completable: Boolean) {
        val nid = ReminderIds.taskNotification(taskId)
        val b = base(context, CHANNEL_TASKS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openRouteIntent(context, Route.TaskEditor(taskId = taskId), nid))
            .setDeleteIntent(actionIntent(context, NotificationActionReceiver.ACTION_DISMISSED, taskId, nid + 3))
            .addAction(0, context.getString(R.string.action_snooze), actionIntent(context, NotificationActionReceiver.ACTION_SNOOZE, taskId, nid + 1))
        if (completable) {
            b.addAction(0, context.getString(R.string.action_done), actionIntent(context, NotificationActionReceiver.ACTION_DONE, taskId, nid + 2))
        }
        notify(context, nid, b)
    }

    fun showHabitReminder(context: Context, habitId: String, title: String, text: String) {
        val nid = ReminderIds.habitNotification(habitId)
        val b = base(context, CHANNEL_HABITS)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openRouteIntent(context, Route.HabitDetail(habitId), nid))
            .addAction(0, context.getString(R.string.action_habit_done), actionIntent(context, NotificationActionReceiver.ACTION_HABIT_DONE, habitId, nid + 1))
        notify(context, nid, b)
    }

    fun showGoalReminder(context: Context, goalId: String, title: String, text: String) {
        val nid = ReminderIds.goalNotification(goalId)
        val b = base(context, CHANNEL_GOALS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openRouteIntent(context, Route.GoalDetail(goalId), nid))
        notify(context, nid, b)
    }

    fun showDailySummary(context: Context, title: String, text: String) {
        val b = base(context, CHANNEL_SUMMARY)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openRouteIntent(context, Route.Dashboard, ReminderIds.SUMMARY_NOTIFICATION))
        notify(context, ReminderIds.SUMMARY_NOTIFICATION, b)
    }

    fun showBackup(context: Context, title: String, text: String) {
        val b = base(context, CHANNEL_BACKUP)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openRouteIntent(context, Route.SettingsData, ReminderIds.BACKUP_NOTIFICATION))
        notify(context, ReminderIds.BACKUP_NOTIFICATION, b)
    }
}

/** Stable request codes / notification ids derived from record ids. */
object ReminderIds {
    const val SUMMARY_NOTIFICATION = 9_000_001
    const val BACKUP_NOTIFICATION = 9_000_002
    const val SUMMARY_ALARM = 9_100_001
    const val MIDNIGHT_ALARM = 9_100_002

    private fun hash(prefix: String, id: String): Int = ((prefix + id).hashCode() and 0x0FFFFFFF) / 8 * 8

    fun taskAlarm(id: String) = hash("ta", id)
    fun taskRepeatAlarm(id: String) = hash("tr", id)
    fun taskNotification(id: String) = hash("tn", id)
    fun habitAlarm(id: String) = hash("ha", id)
    fun habitNotification(id: String) = hash("hn", id)
    fun goalAlarm(id: String) = hash("ga", id)
    fun goalNotification(id: String) = hash("gn", id)
}
