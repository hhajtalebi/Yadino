package ir.yadino.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ir.yadino.app.core.AppClock
import ir.yadino.app.core.ErrorReporter
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.Task
import ir.yadino.domain.planning.ReminderCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Schedules local reminders with [AlarmManager].
 *
 * Exact alarms are used when the user granted "Alarms & reminders" (Android 12+);
 * otherwise inexact, Doze-friendly alarms are used and the settings screen explains the
 * difference. Alarms do not survive reboots, so [rescheduleAll] runs on boot, app update,
 * time/time-zone change and whenever relevant settings change.
 */
class ReminderScheduler(
    private val context: Context,
    private val clock: AppClock,
    private val settingsProvider: () -> AppSettings,
) {
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    private fun pending(
        action: String,
        id: String,
        requestCode: Int,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT,
        repeat: Int = 0,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_REPEAT, repeat)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(at: Instant, pi: PendingIntent) {
        val am = alarmManager ?: return
        try {
            if (canScheduleExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
            }
        } catch (e: SecurityException) {
            // permission revoked between the check and the call: fall back to inexact
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
            ErrorReporter.log(context, e, "alarm")
        }
    }

    private fun cancelAlarm(action: String, id: String, requestCode: Int) {
        val pi = pending(action, id, requestCode, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager?.cancel(pi)
        pi.cancel()
    }

    // ---- tasks ----------------------------------------------------------------------------
    fun scheduleTask(task: Task, settings: AppSettings = settingsProvider()) {
        val enabled = settings.notificationsEnabled && settings.taskReminders
        val at = if (enabled) ReminderCalculator.triggerAt(task, settings, clock.zone(), clock.now()) else null
        if (at == null) {
            cancelTask(task.id)
            return
        }
        pending(ReminderReceiver.ACTION_TASK, task.id, ReminderIds.taskAlarm(task.id))?.let { setAlarm(at, it) }
    }

    fun cancelTask(taskId: String) {
        cancelAlarm(ReminderReceiver.ACTION_TASK, taskId, ReminderIds.taskAlarm(taskId))
        cancelAlarm(ReminderReceiver.ACTION_TASK_REPEAT, taskId, ReminderIds.taskRepeatAlarm(taskId))
    }

    /** One-off follow up (snooze or automatic repeat). */
    fun scheduleTaskFollowUp(taskId: String, minutes: Int, repeatIndex: Int = 0) {
        val at = clock.now().plusSeconds(minutes * 60L)
        pending(ReminderReceiver.ACTION_TASK_REPEAT, taskId, ReminderIds.taskRepeatAlarm(taskId), repeat = repeatIndex)
            ?.let { setAlarm(at, it) }
    }

    fun cancelTaskFollowUp(taskId: String) =
        cancelAlarm(ReminderReceiver.ACTION_TASK_REPEAT, taskId, ReminderIds.taskRepeatAlarm(taskId))

    // ---- habits -----------------------------------------------------------------------------
    fun scheduleHabit(habit: Habit, settings: AppSettings = settingsProvider()) {
        val time = habit.reminderTime
        val enabled = settings.notificationsEnabled && settings.habitReminders && !habit.isArchived && time != null
        val at = if (enabled) ReminderCalculator.nextHabitTrigger(time!!, { HabitStats.isScheduled(habit, it) }, settings, clock.zone(), clock.now()) else null
        if (at == null) {
            cancelHabit(habit.id)
            return
        }
        pending(ReminderReceiver.ACTION_HABIT, habit.id, ReminderIds.habitAlarm(habit.id))?.let { setAlarm(at, it) }
    }

    fun cancelHabit(habitId: String) = cancelAlarm(ReminderReceiver.ACTION_HABIT, habitId, ReminderIds.habitAlarm(habitId))

    // ---- goals -------------------------------------------------------------------------------
    /** Reminder N days before a goal deadline at the all-day reminder time. */
    fun scheduleGoal(goal: Goal, settings: AppSettings = settingsProvider()) {
        val deadline = goal.deadline
        val enabled = settings.notificationsEnabled && settings.goalReminders && deadline != null &&
            goal.status == GoalStatus.ACTIVE && !goal.isArchived
        if (!enabled) {
            cancelGoal(goal.id)
            return
        }
        val zone = clock.zone()
        val day: LocalDate = deadline!!.minusDays(settings.goalDeadlineDaysBefore.toLong())
        var local = LocalDateTime.of(day, settings.allDayReminderTime)
        if (settings.quietHoursEnabled) local = ir.yadino.domain.planning.QuietHours.adjust(local, settings.quietHoursStart, settings.quietHoursEnd)
        val at = local.atZone(zone).toInstant()
        if (!at.isAfter(clock.now())) {
            cancelGoal(goal.id)
            return
        }
        pending(ReminderReceiver.ACTION_GOAL, goal.id, ReminderIds.goalAlarm(goal.id))?.let { setAlarm(at, it) }
    }

    fun cancelGoal(goalId: String) = cancelAlarm(ReminderReceiver.ACTION_GOAL, goalId, ReminderIds.goalAlarm(goalId))

    // ---- daily summary & midnight -------------------------------------------------------------
    fun scheduleDailySummary(settings: AppSettings = settingsProvider()) {
        if (!(settings.notificationsEnabled && settings.dailySummary)) {
            cancelAlarm(ReminderReceiver.ACTION_SUMMARY, "summary", ReminderIds.SUMMARY_ALARM)
            return
        }
        val at = ReminderCalculator.nextDaily(settings.dailySummaryTime, clock.zone(), clock.now())
        pending(ReminderReceiver.ACTION_SUMMARY, "summary", ReminderIds.SUMMARY_ALARM)?.let { setAlarm(at, it) }
    }

    /**
     * Inexact alarm shortly after local midnight to refresh date-dependent widgets and
     * daily state. Inexact alarms are batched by the system to save battery.
     */
    fun scheduleMidnightRefresh(zone: ZoneId = clock.zone()) {
        val am = alarmManager ?: return
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val at = tomorrow.atStartOfDay(zone).plusMinutes(1).toInstant()
        pending(ReminderReceiver.ACTION_MIDNIGHT, "midnight", ReminderIds.MIDNIGHT_ALARM)?.let {
            am.set(AlarmManager.RTC, at.toEpochMilli(), it)
        }
    }

    /** Reschedules every reminder from the database. */
    suspend fun rescheduleAll(tasks: List<Task>, habits: List<Habit>, goals: List<Goal>) {
        val settings = settingsProvider()
        tasks.forEach { scheduleTask(it, settings) }
        habits.forEach { scheduleHabit(it, settings) }
        goals.forEach { scheduleGoal(it, settings) }
        scheduleDailySummary(settings)
        scheduleMidnightRefresh()
    }
}
