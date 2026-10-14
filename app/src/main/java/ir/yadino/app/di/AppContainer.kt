package ir.yadino.app.di

import android.app.Application
import ir.yadino.app.core.AppClock
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.backup.BackupManager
import ir.yadino.app.data.backup.GoogleDriveClient
import ir.yadino.app.data.backup.BackupPasswordStore
import ir.yadino.app.data.backup.SecureTokenStore
import ir.yadino.app.data.local.YadinoDatabase
import ir.yadino.app.data.repository.CategoryRepository
import ir.yadino.app.data.repository.DataRepository
import ir.yadino.app.data.repository.DataSideEffects
import ir.yadino.app.data.repository.GoalRepository
import ir.yadino.app.data.repository.HabitRepository
import ir.yadino.app.data.repository.NoteRepository
import ir.yadino.app.data.repository.TaskRepository
import ir.yadino.app.data.settings.AppStateStore
import ir.yadino.app.data.settings.SettingsRepository
import ir.yadino.app.data.settings.WidgetConfigStore
import ir.yadino.app.notifications.ReminderScheduler
import ir.yadino.app.widget.WidgetUpdater
import ir.yadino.app.work.WorkScheduler
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manual dependency container (no reflection, no annotation processing at runtime).
 * Created once in [ir.yadino.app.YadinoApplication].
 */
class AppContainer(val app: Application, val appScope: CoroutineScope) {

    val database: YadinoDatabase by lazy { YadinoDatabase.build(app) }
    val settings = SettingsRepository(app, appScope)
    val appState = AppStateStore(app)
    val widgetConfigs = WidgetConfigStore(app)
    val clock = AppClock { settings.currentOrDefault() }
    val reminders = ReminderScheduler(app, clock) { settings.currentOrDefault() }
    val widgets = WidgetUpdater(app, appScope)
    val tokenStore = SecureTokenStore(app)
    val drive = GoogleDriveClient(tokenStore)

    private val effects = object : DataSideEffects {
        override fun onTasksChanged(changed: List<Task>, deletedIds: List<String>) {
            appScope.launch {
                val s = settings.awaitLoaded()
                changed.forEach { reminders.scheduleTask(it, s) }
                deletedIds.forEach { reminders.cancelTask(it) }
            }
            widgets.requestUpdate()
        }

        override fun onHabitsChanged(changed: List<Habit>, deletedIds: List<String>) {
            appScope.launch {
                val s = settings.awaitLoaded()
                changed.forEach { reminders.scheduleHabit(it, s) }
                deletedIds.forEach { reminders.cancelHabit(it) }
            }
            widgets.requestUpdate()
        }

        override fun onGoalsChanged(changed: List<Goal>, deletedIds: List<String>) {
            appScope.launch {
                val s = settings.awaitLoaded()
                changed.forEach { reminders.scheduleGoal(it, s) }
                deletedIds.forEach { reminders.cancelGoal(it) }
            }
            widgets.requestUpdate()
        }

        override fun onNotesChanged() = widgets.requestUpdate()

        override fun onEverythingChanged() {
            appScope.launch { rescheduleAllReminders() }
            widgets.requestUpdate()
        }
    }

    val tasks by lazy { TaskRepository(database, clock, settings, effects) }
    val notes by lazy { NoteRepository(database, clock, effects) }
    val goals by lazy { GoalRepository(database, clock, effects) }
    val habits by lazy { HabitRepository(database, clock, effects) }
    val categories by lazy { CategoryRepository(database, clock) }
    val data by lazy { DataRepository(database, { app.getDatabasePath(YadinoDatabase.NAME) }, clock, effects) }
    val backupPasswords = BackupPasswordStore(tokenStore, appState)
    val backup by lazy { BackupManager(app, data, settings, appState, clock, drive, backupPasswords) }

    /** Recomputes all alarms from the database (boot, time change, settings change, restore). */
    suspend fun rescheduleAllReminders() {
        try {
            settings.awaitLoaded()
            reminders.rescheduleAll(tasks.getAll(), habits.getAll(), goals.getAll())
        } catch (e: Exception) {
            ErrorReporter.log(app, e, "reschedule")
        }
    }

    /** Runs at the start of a new day (midnight alarm / daily worker). */
    suspend fun onNewDay() {
        val s = settings.awaitLoaded()
        if (s.overdueBehavior == OverdueBehavior.MOVE_TO_TODAY) tasks.moveOverdueToToday()
        reminders.scheduleMidnightRefresh(clock.zone())
        widgets.updateNow()
    }

    fun syncBackgroundWork() {
        appScope.launch {
            val s = settings.awaitLoaded()
            WorkScheduler.sync(app, s, AppClock.zoneFor(s))
        }
    }
}
