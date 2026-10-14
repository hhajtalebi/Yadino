package ir.yadino.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ir.yadino.app.BuildConfig
import ir.yadino.app.R
import ir.yadino.app.core.AppClock
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.repository.DataRepository
import ir.yadino.app.data.settings.AppStateStore
import ir.yadino.app.data.settings.SettingsRepository
import ir.yadino.domain.backup.BackupCodec
import ir.yadino.domain.backup.BackupCrypto
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupFile
import ir.yadino.domain.backup.BackupFiles
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.BackupReader
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlan
import ir.yadino.domain.backup.RestorePlanner
import ir.yadino.domain.settings.DashboardCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Result of a local export. */
sealed interface ExportResult {
    data class Success(val records: Int, val encrypted: Boolean) : ExportResult
    data class Failure(val reason: String) : ExportResult
}

/** Raw text of a backup read from a file, a snapshot or Drive. */
sealed interface TextReadResult {
    data class Success(val text: String) : TextReadResult
    data class Failure(val error: BackupError) : TextReadResult
}

/** Result of a backup into the user-chosen folder. */
sealed interface FolderBackupResult {
    data class Success(val records: Int, val encrypted: Boolean) : FolderBackupResult
    data object NotConfigured : FolderBackupResult
    data object AccessLost : FolderBackupResult
    data object PasswordUnavailable : FolderBackupResult
    data object Failed : FolderBackupResult
}

/**
 * Orchestrates backups: local files (Storage Access Framework), automatic backups into a
 * chosen folder, Google Drive, optional password protection, validation, restore planning
 * and atomic restore with an automatic safety snapshot.
 */
class BackupManager(
    private val context: Context,
    private val data: DataRepository,
    private val settings: SettingsRepository,
    private val appState: AppStateStore,
    private val clock: AppClock,
    val drive: GoogleDriveClient,
    val passwords: BackupPasswordStore,
) {
    /** Serialises backup/restore operations so they never overlap. */
    private val mutex = Mutex()

    companion object {
        /** Refuse to read files larger than this (protects memory). */
        const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
        private const val SAFETY_DIR = "safety-snapshots"
        private const val SAFETY_KEEP = 3
        private const val MIME = "application/json"
    }

    fun suggestedFileName(): String = BackupFiles.manualName(clock.now(), clock.zone())

    private suspend fun buildBackupJson(): Pair<String, Int> {
        val snapshot = data.snapshot(settings.exportMap(), DashboardCodec.encode(settings.currentDashboard()))
        return BackupCodec.encode(snapshot, BuildConfig.VERSION_NAME, clock.now()) to snapshot.totalRecords
    }

    /**
     * Encrypts [json] when backup protection is on.
     * @throws BackupPasswordUnavailableException if protection is on but the password is unreadable.
     */
    private suspend fun protect(json: String): Pair<String, Boolean> = when (passwords.state()) {
        BackupPasswordStore.State.OFF -> json to false
        BackupPasswordStore.State.UNAVAILABLE -> throw BackupPasswordUnavailableException()
        BackupPasswordStore.State.ON -> {
            val password = passwords.password() ?: throw BackupPasswordUnavailableException()
            try {
                withContext(Dispatchers.Default) {
                    BackupCrypto.encrypt(json, password, BuildConfig.VERSION_NAME, clock.now())
                } to true
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Writes [bytes] to [uri] and reads them back to make sure the file is complete. */
    private fun writeAndVerify(uri: Uri, bytes: ByteArray): Boolean {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use { it.write(bytes) }
        val written = context.contentResolver.openInputStream(uri)?.use { readLimited(it) } ?: return false
        return written.contentEquals(bytes)
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BACKUP_BYTES) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    suspend fun exportTo(uri: Uri): ExportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val (json, records) = buildBackupJson()
                val (content, encrypted) = protect(json)
                if (!writeAndVerify(uri, content.toByteArray(Charsets.UTF_8))) {
                    return@withContext ExportResult.Failure(context.getString(R.string.err_backup_verify))
                }
                val now = clock.now().toEpochMilli()
                appState.recordLocalBackup(now)
                appState.recordResult(context.getString(R.string.backup_result_local_ok), now)
                ExportResult.Success(records, encrypted)
            } catch (e: BackupPasswordUnavailableException) {
                appState.recordResult(context.getString(R.string.backup_result_local_failed), clock.now().toEpochMilli())
                ExportResult.Failure(context.getString(R.string.err_backup_password_unavailable))
            } catch (e: Exception) {
                ErrorReporter.log(context, e, "export")
                appState.recordResult(context.getString(R.string.backup_result_local_failed), clock.now().toEpochMilli())
                ExportResult.Failure(context.getString(R.string.err_file_write))
            }
        }
    }

    /** Reads the raw text of a backup file chosen by the user. */
    suspend fun readText(uri: Uri): TextReadResult = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext TextReadResult.Failure(BackupError.EMPTY_FILE)
            val bytes = input.use { readLimited(it) } ?: return@withContext TextReadResult.Failure(BackupError.INVALID_CONTENT)
            TextReadResult.Success(bytes.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            TextReadResult.Failure(BackupError.EMPTY_FILE)
        } catch (e: SecurityException) {
            TextReadResult.Failure(BackupError.EMPTY_FILE)
        }
    }

    /**
     * Validates a backup. Encrypted backups are opened with [password] (if given) and then
     * with the backup password saved on this device.
     */
    suspend fun decode(text: String, password: CharArray? = null): BackupReadResult = withContext(Dispatchers.Default) {
        val saved = passwords.password()
        try {
            BackupReader.read(text, listOfNotNull(password, saved))
        } finally {
            saved?.fill('\u0000')
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

    // ---- safety snapshots (app-private storage, never encrypted) ---------------------------
    private fun safetyDir() = File(context.filesDir, SAFETY_DIR).apply { mkdirs() }

    private suspend fun writeSafetySnapshot() {
        val (json, _) = buildBackupJson()
        val f = File(safetyDir(), "before-restore-${System.currentTimeMillis()}.${BackupCodec.FILE_EXTENSION}")
        f.writeText(json)
        safetyDir().listFiles()?.sortedByDescending { it.name }?.drop(SAFETY_KEEP)?.forEach { it.delete() }
    }

    fun safetySnapshots(): List<File> = safetyDir().listFiles()?.sortedByDescending { it.name } ?: emptyList()

    suspend fun readSnapshotText(file: File): TextReadResult = withContext(Dispatchers.IO) {
        try {
            TextReadResult.Success(file.readText())
        } catch (e: IOException) {
            TextReadResult.Failure(BackupError.EMPTY_FILE)
        }
    }

    // ---- automatic backups into a user-chosen folder ------------------------------------------
    private fun hasFolderAccess(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    private fun folderDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun folderDisplayName(tree: Uri): String? = try {
        context.contentResolver.query(folderDocument(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** Remembers [tree] (from ACTION_OPEN_DOCUMENT_TREE) as the folder for automatic backups. */
    suspend fun setBackupFolder(tree: Uri): Boolean = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(tree, flags)
        } catch (e: SecurityException) {
            return@withContext false
        }
        val previous = appState.current().backupFolderUri
        if (previous != null && previous != tree.toString()) releaseFolder(Uri.parse(previous))
        appState.setBackupFolder(tree.toString(), folderDisplayName(tree))
        true
    }

    /** Forgets the backup folder and gives the access permission back. Files stay in place. */
    suspend fun clearBackupFolder() = withContext(Dispatchers.IO) {
        appState.current().backupFolderUri?.let { releaseFolder(Uri.parse(it)) }
        appState.setBackupFolder(null, null)
    }

    private fun releaseFolder(tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // not held any more
        }
    }

    suspend fun backupToFolder(automatic: Boolean): FolderBackupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tree = appState.current().backupFolderUri?.let(Uri::parse) ?: return@withContext FolderBackupResult.NotConfigured
            val result = writeFolderBackup(tree)
            val now = clock.now().toEpochMilli()
            if (result is FolderBackupResult.Success) {
                appState.recordFolderBackup(now)
                appState.recordResult(context.getString(if (automatic) R.string.backup_result_folder_auto_ok else R.string.backup_result_folder_ok), now)
            } else {
                appState.recordResult(context.getString(R.string.backup_result_folder_failed), now)
            }
            result
        }
    }

    private suspend fun writeFolderBackup(tree: Uri): FolderBackupResult {
        if (!hasFolderAccess(tree)) return FolderBackupResult.AccessLost
        val resolver = context.contentResolver
        return try {
            val (json, records) = buildBackupJson()
            val (content, encrypted) = protect(json)
            val name = BackupFiles.autoName(clock.now(), clock.zone())
            val created: Uri? = try {
                DocumentsContract.createDocument(resolver, folderDocument(tree), MIME, name)
            } catch (e: FileNotFoundException) {
                return FolderBackupResult.AccessLost // the folder was deleted or moved
            }
            val doc = created ?: return FolderBackupResult.Failed
            if (!writeAndVerify(doc, content.toByteArray(Charsets.UTF_8))) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                return FolderBackupResult.Failed
            }
            pruneFolder(tree, settings.current().folderBackupsToKeep)
            FolderBackupResult.Success(records, encrypted)
        } catch (e: BackupPasswordUnavailableException) {
            FolderBackupResult.PasswordUnavailable
        } catch (e: SecurityException) {
            FolderBackupResult.AccessLost
        } catch (e: Exception) {
            ErrorReporter.log(context, e, "folder-backup")
            FolderBackupResult.Failed
        }
    }

    /** Deletes the oldest automatic backups; files with other names are never touched. */
    private fun pruneFolder(tree: Uri, keep: Int) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val entries = mutableListOf<Pair<String, String>>()
        try {
            resolver.query(
                children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    entries += id to name
                }
            }
            val doomed = BackupFiles.toDelete(entries.map { it.second }, keep).toSet()
            entries.filter { it.second in doomed }.forEach { (id, _) ->
                runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
            }
        } catch (e: Exception) {
            // pruning is best effort; the new backup was written successfully
            ErrorReporter.log(context, e, "folder-prune")
        }
    }

    // ---- Google Drive -------------------------------------------------------------------------
    suspend fun backupToDrive(automatic: Boolean): DriveResult = mutex.withLock {
        if (!drive.isConfigured()) return@withLock DriveResult.NotConfigured
        if (!drive.isConnected()) return@withLock DriveResult.NotConnected
        val (json, _) = withContext(Dispatchers.IO) { buildBackupJson() }
        val (content, encrypted) = try {
            protect(json)
        } catch (e: BackupPasswordUnavailableException) {
            appState.recordResult(context.getString(R.string.backup_result_drive_failed), clock.now().toEpochMilli())
            return@withLock DriveResult.Error(DriveResult.Kind.ENCRYPTION_UNAVAILABLE)
        }
        val props = mapOf(
            "formatVersion" to (if (encrypted) BackupCrypto.FORMAT_VERSION else BackupCodec.FORMAT_VERSION).toString(),
            "app" to "yadino", "auto" to automatic.toString(), "encrypted" to encrypted.toString(),
        )
        val result = drive.upload(suggestedFileName(), content, props)
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

    /** Downloads the raw text of a Drive backup (decode it with [decode]). */
    suspend fun downloadFromDrive(fileId: String): Pair<DriveResult, String?> {
        val (res, body) = drive.download(fileId)
        return if (res is DriveResult.Success && body != null) res to body else res to null
    }
}
