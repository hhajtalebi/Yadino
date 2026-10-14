package ir.yadino.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.core.Formatter
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.planning.TaskListFilter
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit

/** Runs [block] off the main thread while keeping the broadcast alive. */
internal fun BroadcastReceiver.runAsync(context: Context, tag: String, block: suspend (YadinoApplication) -> Unit) {
    val app = context.applicationContext as YadinoApplication
    val pending = goAsync()
    app.appScope.launch {
        try {
            block(app)
        } catch (e: Exception) {
            ErrorReporter.log(context, e, tag)
        } finally {
            pending.finish()
        }
    }
}

/** Receives reminder alarms and shows the corresponding notification. */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TASK = "ir.yadino.app.REMINDER_TASK"
        const val ACTION_TASK_REPEAT = "ir.yadino.app.REMINDER_TASK_REPEAT"
        const val ACTION_HABIT = "ir.yadino.app.REMINDER_HABIT"
        const val ACTION_GOAL = "ir.yadino.app.REMINDER_GOAL"
        const val ACTION_SUMMARY = "ir.yadino.app.REMINDER_SUMMARY"
        const val ACTION_MIDNIGHT = "ir.yadino.app.MIDNIGHT"
        const val EXTRA_ID = "id"
        const val EXTRA_REPEAT = "repeat"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val action = intent.action ?: return
        runAsync(context, "reminder") { app ->
            val c = app.container
            val settings = c.settings.current()
            val fmt = Formatter(settings)
            val today = c.clock.today()
            when (action) {
                ACTION_TASK, ACTION_TASK_REPEAT -> {
                    val task = c.tasks.get(id) ?: return@runAsync
                    if (!task.isOpen || task.isArchived) return@runAsync
                    if (!settings.notificationsEnabled || !settings.taskReminders) return@runAsync
                    val date = task.date ?: task.dueDate
                    val parts = buildList {
                        if (date != null) add(fmt.relativeDay(date, today))
                        val time = if (task.date != null) task.startTime else task.dueTime
                        time?.let { add(context.getString(R.string.reminder_at_time, fmt.time(it))) }
                        if (task.dueDate != null && task.date != null) add(context.getString(R.string.reminder_deadline, fmt.shortDate(task.dueDate!!)))
                        task.description.lineSequence().firstOrNull { it.isNotBlank() }?.let { add(it.take(80)) }
                    }
                    NotificationHelper.showTaskReminder(context, task.id, task.title, parts.joinToString(" • "), task.kind != TaskKind.EVENT)
                    val repeat = intent.getIntExtra(EXTRA_REPEAT, 0)
                    if (repeat < settings.reminderRepeatCount) {
                        c.reminders.scheduleTaskFollowUp(task.id, settings.snoozeMinutes, repeat + 1)
                    }
                }
                ACTION_HABIT -> {
                    val habit = c.habits.get(id) ?: return@runAsync
                    val logs = c.habits.getLogsSince(today.minusDays(60))
                    val summary = HabitStats.summarize(habit, logs, today, settings.firstDayOfWeek)
                    if (!summary.isDoneToday && HabitStats.isScheduled(habit, today)) {
                        val text = if (summary.currentStreak > 0) context.getString(R.string.habit_reminder_streak, fmt.number(summary.currentStreak))
                        else context.getString(R.string.habit_reminder_text)
                        NotificationHelper.showHabitReminder(context, habit.id, habit.title, text)
                    }
                    c.reminders.scheduleHabit(habit)
                }
                ACTION_GOAL -> {
                    val goal = c.goals.get(id) ?: return@runAsync
                    val deadline = goal.deadline ?: return@runAsync
                    val days = ChronoUnit.DAYS.between(today, deadline)
                    NotificationHelper.showGoalReminder(
                        context, goal.id, goal.title,
                        context.getString(R.string.goal_reminder_text, fmt.daysLeft(days), fmt.date(deadline)),
                    )
                }
                ACTION_SUMMARY -> {
                    val tasks = c.tasks.getAll()
                    val todayTasks = TaskFilters.filter(tasks, TaskListFilter.TODAY, today, OverdueBehavior.SEPARATE_LIST).filter { it.isOpen }
                    val overdue = TaskFilters.filter(tasks, TaskListFilter.OVERDUE, today, OverdueBehavior.SEPARATE_LIST).size
                    val habits = c.habits.getAll().count { !it.isArchived && HabitStats.isScheduled(it, today) }
                    val text = context.getString(
                        R.string.summary_text, fmt.number(todayTasks.size), fmt.number(overdue), fmt.number(habits),
                    ) + if (todayTasks.isNotEmpty()) "\n" + todayTasks.take(5).joinToString("\n") { "• " + it.title } else ""
                    NotificationHelper.showDailySummary(context, context.getString(R.string.summary_title, fmt.date(today)), text)
                    c.reminders.scheduleDailySummary(settings)
                }
                ACTION_MIDNIGHT -> {
                    c.onNewDay()
                }
            }
        }
    }
}

/** Handles notification action buttons (done, snooze, habit check-in, dismissal). */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DONE = "ir.yadino.app.ACTION_DONE"
        const val ACTION_SNOOZE = "ir.yadino.app.ACTION_SNOOZE"
        const val ACTION_HABIT_DONE = "ir.yadino.app.ACTION_HABIT_DONE"
        const val ACTION_DISMISSED = "ir.yadino.app.ACTION_DISMISSED"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val action = intent.action ?: return
        runAsync(context, "notification-action") { app ->
            val c = app.container
            when (action) {
                ACTION_DONE -> {
                    NotificationHelper.cancel(context, ReminderIds.taskNotification(id))
                    c.reminders.cancelTaskFollowUp(id)
                    c.tasks.get(id)?.let { if (it.isOpen) c.tasks.setCompleted(it, true) }
                }
                ACTION_SNOOZE -> {
                    NotificationHelper.cancel(context, ReminderIds.taskNotification(id))
                    val settings = c.settings.current()
                    c.reminders.scheduleTaskFollowUp(id, settings.snoozeMinutes, settings.reminderRepeatCount)
                }
                ACTION_HABIT_DONE -> {
                    NotificationHelper.cancel(context, ReminderIds.habitNotification(id))
                    c.habits.get(id)?.let { c.habits.setCount(it, c.clock.today(), it.targetPerDay) }
                }
                ACTION_DISMISSED -> c.reminders.cancelTaskFollowUp(id)
            }
        }
    }
}

/**
 * Re-creates alarms and refreshes widgets after events that invalidate them: reboot, app
 * update, clock/time-zone changes and changes of the exact-alarm permission.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_LOCALE_CHANGED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31; never sent on older versions)
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
        if (action !in handled) return
        runAsync(context, "system-event") { app ->
            app.container.rescheduleAllReminders()
            app.container.widgets.requestUpdate()
        }
    }
}
