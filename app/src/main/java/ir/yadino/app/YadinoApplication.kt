package ir.yadino.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.di.AppContainer
import ir.yadino.app.work.WorkScheduler
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.domain.model.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class YadinoApplication : Application(), Configuration.Provider {

    private val errorHandler = CoroutineExceptionHandler { _, e -> ErrorReporter.log(this, e, "app-scope") }

    /** Process-wide scope for work that must outlive a screen (writes, rescheduling). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)

    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        ErrorReporter.install(this)
        container = AppContainer(this, appScope)
        NotificationHelper.createChannels(this)

        appScope.launch {
            container.categories.ensureDefaults()
            container.rescheduleAllReminders()
        }
        container.syncBackgroundWork()
        observeSettings()
    }

    /** Applies settings that have process-wide effects as soon as they change. */
    private fun observeSettings() {
        val state = container.settings.state.filterNotNull()
        appScope.launch {
            state.collect { ErrorReporter.enabled = it.errorLogEnabled }
        }
        appScope.launch {
            state.map { reminderKey(it) }.distinctUntilChanged().drop(1).collect {
                container.rescheduleAllReminders()
            }
        }
        appScope.launch {
            state.map { WorkScheduler.scheduleKey(it) }
                .distinctUntilChanged().drop(1).collect { container.syncBackgroundWork() }
        }
        appScope.launch {
            state.map { widgetKey(it) }.distinctUntilChanged().drop(1).collect { container.widgets.requestUpdate() }
        }
    }

    private fun reminderKey(s: AppSettings) = listOf(
        s.notificationsEnabled, s.taskReminders, s.habitReminders, s.goalReminders, s.dailySummary, s.dailySummaryTime,
        s.goalDeadlineDaysBefore, s.quietHoursEnabled, s.quietHoursStart, s.quietHoursEnd, s.allDayReminderTime, s.timeZoneId,
    )

    private fun widgetKey(s: AppSettings) = listOf(
        s.numerals, s.firstDayOfWeek, s.use24Hour, s.dateFormat, s.showGregorian, s.occasionDisplay, s.lunarAdjustment,
        s.timeZoneId, s.overdueBehavior, s.showFutureOccurrences,
    )
}
