package ir.yadino.app.data.backup

import android.content.Context
import android.net.Uri
import ir.yadino.app.BuildConfig
import ir.yadino.app.R
import ir.yadino.app.core.AppClock
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.repository.DataRepository
import ir.yadino.app.data.settings.AppStateStore
import ir.yadino.app.data.settings.SettingsRepository
import ir.yadino.domain.backup.BackupCodec
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupFile
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlan
import ir.yadino.domain.backup.RestorePlanner
import ir.yadino.domain.settings.DashboardCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Result of a local export. */
sealed interface ExportResult {
    data class Success(val records: Int) : ExportResult
    data class Failure(val reason: String) : ExportResult
}

/**
 * Orchestrates backups: local files (Storage Access Framework), Google Drive, validation,
 * restore planning and atomic restore with an automatic safety snapshot.
 */
class BackupManager(
    private val context: Context,
    private val data: DataRepository,
    private val settings: SettingsRepository,
    private val appState: AppStateStore,
    private val clock: AppClock,
    val drive: GoogleDriveClient,
) {
    /** Serialises backup/restore operations so they never overlap. */
    private val mutex = Mutex()

    companion object {
        /** Refuse to read files larger than this (protects memory). */
        const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
        private const val SAFETY_DIR = "safety-snapshots"
        private const val SAFETY_KEEP = 3
    }

    fun suggestedFileName(): String {
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).format(clock.now().atZone(ZoneId.systemDefault()))
        return "yadino-backup-$ts.${BackupCodec.FILE_EXTENSION}"
    }

    private suspend fun buildBackupJson(): Pair<String, Int> {
        val snapshot = data.snapshot(settings.exportMap(), DashboardCodec.encode(settings.currentDashboard()))
        return BackupCodec.encode(snapshot, BuildConfig.VERSION_NAME, clock.now()) to snapshot.totalRecords
    }

    suspend fun exportTo(uri: Uri): ExportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val (json, records) = buildBackupJson()
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext ExportResult.Failure(context.getString(R.string.err_file_write))
                out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                // verify what was written
                val verify = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (verify != null && BackupCodec.decode(verify) !is BackupReadResult.Success) {
                    return@withContext ExportResult.Failure(context.getString(R.string.err_backup_verify))
                }
                val now = clock.now().toEpochMilli()
                appState.recordLocalBackup(now)
                appState.recordResult(context.getString(R.string.backup_result_local_ok), now)
                ExportResult.Success(records)
            } catch (e: Exception) {
                ErrorReporter.log(context, e, "export")
                appState.recordResult(context.getString(R.string.backup_result_local_failed), clock.now().toEpochMilli())
                ExportResult.Failure(context.getString(R.string.err_file_write))
            }
        }
    }

    suspend fun readFrom(uri: Uri): BackupReadResult = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext BackupReadResult.Failure(BackupError.EMPTY_FILE)
            val bytes = input.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BACKUP_BYTES) return@withContext BackupReadResult.Failure(BackupError.INVALID_CONTENT, "too large")
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            BackupCodec.decode(bytes.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            BackupReadResult.Failure(BackupError.EMPTY_FILE, "io")
        } catch (e: SecurityException) {
            BackupReadResult.Failure(BackupError.EMPTY_FILE, "permission")
        }
    }

    suspend fun plan(file: BackupFile, mode: RestoreMode): RestorePlan {
        val local = data.snapshot(emptyMap(), null)
        return RestorePlanner.plan(local, file, mode, appState.current().lastRestoredChecksum)
    }

    /**
     * Applies a restore plan atomically. A safety snapshot of the current data is written
     * first so the operation can be undone from Settings › Data.
     */
    suspend fun applyRestore(plan: RestorePlan, file: BackupFile): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                writeSafetySnapshot()
                data.replaceAll(plan.result)
                if (plan.mode == RestoreMode.REPLACE) {
                    if (file.data.settings.isNotEmpty()) settings.applyMap(file.data.settings)
                    file.data.dashboard?.let { d -> settings.updateDashboard { DashboardCodec.decode(d) } }
                }
                appState.recordRestore(file.info.checksum)
                appState.recordResult(context.getString(R.string.backup_result_restore_ok), clock.now().toEpochMilli())
                true
            } catch (e: Exception) {
                ErrorReporter.log(context, e, "restore")
                appState.recordResult(context.getString(R.string.backup_result_restore_failed), clock.now().toEpochMilli())
                false
            }
        }
    }

    // ---- safety snapshots -------------------------------------------------------------------
    private fun safetyDir() = File(context.filesDir, SAFETY_DIR).apply { mkdirs() }

    private suspend fun writeSafetySnapshot() {
        val (json, _) = buildBackupJson()
        val f = File(safetyDir(), "before-restore-${System.currentTimeMillis()}.${BackupCodec.FILE_EXTENSION}")
        f.writeText(json)
        safetyDir().listFiles()?.sortedByDescending { it.name }?.drop(SAFETY_KEEP)?.forEach { it.delete() }
    }

    fun safetySnapshots(): List<File> = safetyDir().listFiles()?.sortedByDescending { it.name } ?: emptyList()

    suspend fun readSnapshot(file: File): BackupReadResult = withContext(Dispatchers.IO) {
        try {
            BackupCodec.decode(file.readText())
        } catch (e: IOException) {
            BackupReadResult.Failure(BackupError.EMPTY_FILE)
        }
    }

    // ---- Google Drive -------------------------------------------------------------------------
    suspend fun backupToDrive(automatic: Boolean): DriveResult = mutex.withLock {
        if (!drive.isConfigured()) return@withLock DriveResult.NotConfigured
        if (!drive.isConnected()) return@withLock DriveResult.NotConnected
        val (json, _) = withContext(Dispatchers.IO) { buildBackupJson() }
        val props = mapOf("formatVersion" to BackupCodec.FORMAT_VERSION.toString(), "app" to "yadino",
            "auto" to automatic.toString())
        val result = drive.upload(suggestedFileName(), json, props)
        val now = clock.now().toEpochMilli()
        if (result is DriveResult.Success) {
            appState.recordDriveBackup(now)
            appState.recordResult(context.getString(R.string.backup_result_drive_ok), now)
            pruneDrive()
        } else {
            appState.recordResult(context.getString(R.string.backup_result_drive_failed), now)
            if (result is DriveResult.AccessRevoked) appState.setDriveAccount(null)
        }
        result
    }

    private suspend fun pruneDrive() {
        val keep = settings.current().driveBackupsToKeep
        val (res, files) = drive.list()
        if (res is DriveResult.Success && files.size > keep) {
            files.sortedByDescending { it.createdAt }.drop(keep).forEach { drive.delete(it.id) }
        }
    }

    suspend fun listDrive(): Pair<DriveResult, List<DriveBackupEntry>> = drive.list()

    suspend fun downloadFromDrive(fileId: String): Pair<DriveResult, BackupReadResult?> {
        val (res, body) = drive.download(fileId)
        if (res !is DriveResult.Success || body == null) return res to null
        return res to withContext(Dispatchers.Default) { BackupCodec.decode(body) }
    }
}
