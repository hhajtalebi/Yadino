package ir.yadino.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.core.Formatter
import ir.yadino.app.data.backup.DriveResult
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.AutoBackupFrequency
import ir.yadino.domain.model.BackupReminderFrequency
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Daily housekeeping: day rollover, tombstone pruning and defensive alarm rescheduling. */
class DailyMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as YadinoApplication).container
        return try {
            c.onNewDay()
            c.data.pruneTombstones()
            Result.success()
        } catch (e: Exception) {
            ErrorReporter.log(applicationContext, e, "maintenance")
            Result.retry()
        }
    }
}

/** Reminds the user to back up when the last backup is older than the chosen period. */
class BackupReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as YadinoApplication).container
        val settings = c.settings.current()
        if (settings.backupReminder == BackupReminderFrequency.OFF || !settings.notificationsEnabled || !settings.backupNotifications) {
            return Result.success()
        }
        val days = if (settings.backupReminder == BackupReminderFrequency.WEEKLY) 7L else 30L
        val state = c.appState.current()
        val now = System.currentTimeMillis()
        val lastBackup = maxOf(state.lastLocalBackupAt ?: 0L, state.lastDriveBackupAt ?: 0L)
        val lastReminder = state.lastBackupReminderAt ?: 0L
        val period = TimeUnit.DAYS.toMillis(days)
        if (now - lastBackup >= period && now - lastReminder >= period) {
            val stats = c.data.stats()
            if (stats.tasks + stats.notes + stats.goals + stats.habits > 0) {
                val fmt = Formatter(settings)
                val text = if (lastBackup == 0L) applicationContext.getString(R.string.backup_reminder_never)
                else applicationContext.getString(R.string.backup_reminder_since, fmt.number(Duration.ofMillis(now - lastBackup).toDays()))
                NotificationHelper.showBackup(applicationContext, applicationContext.getString(R.string.backup_reminder_title), text)
                c.appState.recordBackupReminder(now)
            }
        }
        return Result.success()
    }
}

/** Optional automatic backup to Google Drive (only when an account is connected). */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as YadinoApplication).container
        if (!c.drive.isConnected()) return Result.success()
        return when (val r = c.backup.backupToDrive(automatic = true)) {
            is DriveResult.Success -> Result.success()
            is DriveResult.NetworkError -> Result.retry()
            else -> {
                if (r is DriveResult.Error) ErrorReporter.log(applicationContext, IllegalStateException(r.kind.name), "drive-auto")
                Result.success()
            }
        }
    }
}

/** Keeps periodic work in sync with the settings. */
object WorkScheduler {
    private const val MAINTENANCE = "yadino-maintenance"
    private const val BACKUP_REMINDER = "yadino-backup-reminder"
    private const val DRIVE_BACKUP = "yadino-drive-backup"

    fun sync(context: Context, settings: AppSettings, zone: ZoneId) {
        val wm = WorkManager.getInstance(context)
        // run shortly after the next local midnight, then every 24h
        val now = LocalDateTime.now(zone)
        val nextRun = now.toLocalDate().plusDays(1).atTime(0, 15)
        val delay = Duration.between(now, nextRun).toMinutes().coerceAtLeast(1)
        wm.enqueueUniquePeriodicWork(
            MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build(),
        )
        if (settings.backupReminder == BackupReminderFrequency.OFF) {
            wm.cancelUniqueWork(BACKUP_REMINDER)
        } else {
            wm.enqueueUniquePeriodicWork(
                BACKUP_REMINDER, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupReminderWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(2, TimeUnit.HOURS).build(),
            )
        }
        if (settings.autoDriveBackup == AutoBackupFrequency.OFF) {
            wm.cancelUniqueWork(DRIVE_BACKUP)
        } else {
            val period = if (settings.autoDriveBackup == AutoBackupFrequency.DAILY) 1L else 7L
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.driveBackupUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            wm.enqueueUniquePeriodicWork(
                DRIVE_BACKUP, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DriveBackupWorker>(period, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
