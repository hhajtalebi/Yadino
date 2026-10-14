package ir.yadino.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.core.Formatter
import ir.yadino.app.data.backup.BackupPasswordStore
import ir.yadino.app.data.backup.DriveBackupEntry
import ir.yadino.app.data.backup.DriveResult
import ir.yadino.app.data.backup.ExportResult
import ir.yadino.app.data.backup.FolderBackupResult
import ir.yadino.app.data.backup.TextReadResult
import ir.yadino.app.data.export.SpreadsheetExporter
import ir.yadino.app.data.repository.DataStats
import ir.yadino.app.data.settings.BackupStatus
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.domain.backup.BackupCrypto
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupFile
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlan
import ir.yadino.domain.backup.RestorePlanner
import ir.yadino.domain.model.AutoBackupFrequency
import ir.yadino.domain.model.BackupReminderFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

data class RestorePreview(val file: BackupFile, val source: Int, val warnings: List<String>, val plan: RestorePlan? = null, val mode: RestoreMode = RestoreMode.REPLACE)

/** An encrypted backup is waiting for its password. */
data class PasswordPrompt(val source: Int, val wrong: Boolean)

class DataViewModel(c: AppContainer) : SettingsViewModel(c) {
    val status: StateFlow<BackupStatus?> = c.appState.status.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val stats = MutableStateFlow<DataStats?>(null)
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<RestorePreview?>(null)
    val driveFiles = MutableStateFlow<List<DriveBackupEntry>?>(null)
    val snapshots = MutableStateFlow<List<File>>(emptyList())
    val driveConnected = MutableStateFlow(c.drive.isConnected())
    val driveConfigured = c.drive.isConfigured()
    val passwordState = MutableStateFlow(BackupPasswordStore.State.OFF)
    val passwordPrompt = MutableStateFlow<PasswordPrompt?>(null)

    /** Raw text of an encrypted backup until the user enters its password. */
    private var pendingText: String? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        stats.value = c.data.stats()
        snapshots.value = c.backup.safetySnapshots()
        driveConnected.value = c.drive.isConnected()
        passwordState.value = c.backupPasswords.state()
    }

    fun suggestedName() = c.backup.suggestedFileName()
    fun driveAccount(): String? = c.drive.accountEmail()

    private fun busy(block: suspend () -> Unit) = launchSafe(onError = { busy.value = false }) {
        busy.value = true
        try { block() } finally { busy.value = false }
    }

    private suspend fun number(n: Int): String = Formatter(c.settings.current()).number(n)

    fun export(uri: Uri) = busy {
        when (val r = c.backup.exportTo(uri)) {
            is ExportResult.Success -> message(UiMessage.Text(if (r.encrypted) R.string.msg_backup_saved_encrypted else R.string.msg_backup_saved, listOf(number(r.records))))
            is ExportResult.Failure -> message(UiMessage.Text(R.string.msg_backup_failed_reason, listOf(r.reason)))
        }
        refresh()
    }

    fun suggestedCsvName() = c.spreadsheet.suggestedFileName()

    fun exportCsv(uri: Uri) = busy {
        when (val r = c.spreadsheet.exportTo(uri)) {
            is SpreadsheetExporter.Result.Success -> message(UiMessage.Text(R.string.msg_csv_saved, listOf(number(r.rows))))
            is SpreadsheetExporter.Result.Failure -> message(UiMessage.Text(R.string.msg_csv_failed, listOf(r.reason)))
        }
    }

    /** Validates [text]; encrypted backups open the password prompt when needed. */
    private suspend fun handleText(text: String, source: Int, password: CharArray? = null) {
        val r = try {
            c.backup.decode(text, password)
        } finally {
            password?.fill('\u0000')
        }
        if (r is BackupReadResult.Failure && (r.error == BackupError.PASSWORD_REQUIRED || r.error == BackupError.WRONG_PASSWORD)) {
            pendingText = text
            passwordPrompt.value = PasswordPrompt(source, wrong = password != null)
            return
        }
        pendingText = null
        passwordPrompt.value = null
        when (r) {
            is BackupReadResult.Success -> preview.value = RestorePreview(r.file, source, r.warnings)
            is BackupReadResult.Failure -> message(UiMessage.Text(backupErrorRes(r.error)))
        }
    }

    private suspend fun handleRead(r: TextReadResult, source: Int) {
        when (r) {
            is TextReadResult.Success -> handleText(r.text, source)
            is TextReadResult.Failure -> message(UiMessage.Text(backupErrorRes(r.error)))
        }
    }

    fun import(uri: Uri) = busy { handleRead(c.backup.readText(uri), R.string.source_file) }

    fun submitPassword(password: CharArray) = busy {
        val text = pendingText
        val source = passwordPrompt.value?.source
        if (text == null || source == null) {
            password.fill('\u0000')
            return@busy
        }
        handleText(text, source, password)
    }

    fun cancelPassword() {
        pendingText = null
        passwordPrompt.value = null
    }

    fun choose(mode: RestoreMode) = busy {
        val p = preview.value ?: return@busy
        preview.value = p.copy(mode = mode, plan = c.backup.plan(p.file, mode))
    }

    fun apply() = busy {
        val p = preview.value ?: return@busy
        val plan = p.plan ?: c.backup.plan(p.file, p.mode)
        preview.value = null
        if (c.backup.applyRestore(plan, p.file)) message(UiMessage.Text(R.string.msg_restore_done)) else message(UiMessage.Text(R.string.msg_restore_failed))
        refresh()
    }

    fun dismissPreview() { preview.value = null }

    fun restoreSnapshot(f: File) = busy { handleRead(c.backup.readSnapshotText(f), R.string.source_snapshot) }

    // ---- password protection --------------------------------------------------------------------
    fun setPassword(password: CharArray) = busy {
        try {
            c.backupPasswords.set(password)
        } finally {
            password.fill('\u0000')
        }
        message(UiMessage.Text(R.string.msg_backup_password_set))
        refresh()
    }

    fun disableEncryption() = busy {
        c.backupPasswords.clear()
        message(UiMessage.Text(R.string.msg_backup_password_removed))
        refresh()
    }

    // ---- automatic backups into a folder ------------------------------------------------------
    fun chooseFolder(uri: Uri) = busy {
        message(UiMessage.Text(if (c.backup.setBackupFolder(uri)) R.string.msg_folder_set else R.string.folder_backup_access_lost))
    }

    fun clearFolder() = busy {
        c.backup.clearBackupFolder()
        update { it.copy(autoFolderBackup = AutoBackupFrequency.OFF) }
        message(UiMessage.Text(R.string.msg_folder_cleared))
    }

    fun folderBackupNow() = busy {
        message(
            when (val r = c.backup.backupToFolder(automatic = false)) {
                is FolderBackupResult.Success -> UiMessage.Text(
                    if (r.encrypted) R.string.msg_folder_backup_ok_encrypted else R.string.msg_folder_backup_ok, listOf(number(r.records)),
                )
                FolderBackupResult.NotConfigured -> UiMessage.Text(R.string.folder_not_chosen)
                FolderBackupResult.AccessLost -> UiMessage.Text(R.string.folder_backup_access_lost)
                FolderBackupResult.PasswordUnavailable -> UiMessage.Text(R.string.err_backup_password_unavailable)
                FolderBackupResult.Failed -> UiMessage.Text(R.string.folder_backup_failed)
            },
        )
        refresh()
    }

    // ---- Drive --------------------------------------------------------------------------------
    fun driveAuthUrl(): Uri = c.drive.authorizationUrl()

    fun driveBackup() = busy {
        message(driveMessage(c.backup.backupToDrive(automatic = false), R.string.msg_drive_backup_ok))
        refresh()
    }

    fun driveList() = busy {
        val (r, files) = c.backup.listDrive()
        if (r is DriveResult.Success) driveFiles.value = files else message(driveMessage(r, R.string.msg_drive_backup_ok))
        driveConnected.value = c.drive.isConnected()
    }

    fun driveDownload(entry: DriveBackupEntry) = busy {
        driveFiles.value = null
        val (r, body) = c.backup.downloadFromDrive(entry.id)
        if (r is DriveResult.Success && body != null) handleText(body, R.string.source_drive) else message(driveMessage(r, R.string.msg_drive_backup_ok))
    }

    fun driveDisconnect() = busy {
        c.drive.disconnect()
        c.appState.setDriveAccount(null)
        update { it.copy(autoDriveBackup = AutoBackupFrequency.OFF) }
        driveConnected.value = false
        message(UiMessage.Text(R.string.msg_drive_disconnected))
    }

    fun closeDriveList() { driveFiles.value = null }

    fun noBrowser() = message(UiMessage.Text(R.string.err_no_browser))

    private fun driveMessage(r: DriveResult, ok: Int): UiMessage = UiMessage.Text(
        when (r) {
            is DriveResult.Success -> ok
            DriveResult.NotConfigured -> R.string.drive_not_configured
            DriveResult.NotConnected -> R.string.drive_not_connected
            DriveResult.NetworkError -> R.string.drive_network_error
            DriveResult.AccessRevoked -> R.string.drive_access_revoked
            is DriveResult.Error -> when (r.kind) {
                DriveResult.Kind.QUOTA -> R.string.drive_quota
                DriveResult.Kind.FORBIDDEN -> R.string.drive_forbidden
                DriveResult.Kind.ENCRYPTION_UNAVAILABLE -> R.string.err_backup_password_unavailable
                else -> R.string.drive_error
            }
        },
    )

    // ---- data management -------------------------------------------------------------------------
    fun deleteArchived() = busy {
        val n = c.data.deleteArchived()
        message(UiMessage.Text(R.string.msg_deleted_count, listOf(number(n))))
        refresh()
    }

    fun deleteAll() = busy {
        c.data.deleteAllData()
        message(UiMessage.Text(R.string.msg_all_deleted))
        refresh()
    }
}

fun backupErrorRes(e: BackupError): Int = when (e) {
    BackupError.EMPTY_FILE -> R.string.backup_err_empty
    BackupError.NOT_JSON -> R.string.backup_err_not_json
    BackupError.NOT_A_YADINO_BACKUP -> R.string.backup_err_not_yadino
    BackupError.NEWER_FORMAT -> R.string.backup_err_newer
    BackupError.CHECKSUM_MISMATCH -> R.string.backup_err_checksum
    BackupError.INVALID_CONTENT -> R.string.backup_err_invalid
    BackupError.PASSWORD_REQUIRED -> R.string.backup_err_password_required
    BackupError.WRONG_PASSWORD -> R.string.backup_err_wrong_password
}

@Composable
fun SettingsDataScreen() {
    val c = LocalContainer.current
    val vm: DataViewModel = viewModel { DataViewModel(c) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val driveFiles by vm.driveFiles.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val connected by vm.driveConnected.collectAsStateWithLifecycle()
    val passwordState by vm.passwordState.collectAsStateWithLifecycle()
    val passwordPrompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val fmt = LocalFormatter.current
    val context = LocalContext.current
    CollectMessages(vm.messages)
    var confirm by remember { mutableStateOf<String?>(null) }
    var passwordDialog by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.chooseFolder(uri) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportCsv(uri) }
    LaunchedEffect(Unit) { vm.refresh() }
    val zone = c.clock.zone()
    val frequencyLabel: @Composable (AutoBackupFrequency) -> String = {
        stringResource(when (it) { AutoBackupFrequency.OFF -> R.string.off; AutoBackupFrequency.DAILY -> R.string.daily; AutoBackupFrequency.WEEKLY -> R.string.weekly })
    }

    SettingsPage(stringResource(R.string.settings_data)) {
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(8.dp))
            Text(stringResource(R.string.working))
        }
        SettingsGroup(stringResource(R.string.group_storage_stats)) {
            val st = stats
            SettingRow(stringResource(R.string.local_storage), st?.let {
                stringResource(R.string.storage_summary, fmt.number(it.tasks), fmt.number(it.notes), fmt.number(it.goals), fmt.number(it.habits),
                    fmt.number(it.templates), fmt.digits(String.format(java.util.Locale.ROOT, "%.1f", it.databaseBytes / 1024.0)))
            } ?: "…", Icons.Default.Storage)
            SettingRow(stringResource(R.string.local_first_note), stringResource(R.string.local_first_desc))
        }
        SettingsGroup(stringResource(R.string.group_backup_status)) {
            val st = status
            SettingRow(stringResource(R.string.last_local_backup), st?.lastLocalBackupAt?.let { fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: stringResource(R.string.never))
            if (st?.backupFolderUri != null) {
                SettingRow(stringResource(R.string.last_folder_backup), st.lastFolderBackupAt?.let { fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: stringResource(R.string.never))
            }
            SettingRow(stringResource(R.string.last_drive_backup), st?.lastDriveBackupAt?.let { fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: stringResource(R.string.never))
            st?.lastResult?.let { r -> SettingRow(stringResource(R.string.last_operation), r + (st.lastResultAt?.let { " — " + fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: "")) }
        }
        SettingsGroup(stringResource(R.string.group_backup_encryption)) {
            when (passwordState) {
                BackupPasswordStore.State.OFF ->
                    SettingRow(stringResource(R.string.backup_encryption), stringResource(R.string.backup_encryption_off_desc), Icons.Default.LockOpen,
                        enabled = !busy, onClick = { passwordDialog = true })
                BackupPasswordStore.State.ON -> {
                    SettingRow(stringResource(R.string.backup_encryption_on), stringResource(R.string.backup_encryption_on_desc), Icons.Default.Lock)
                    SettingRow(stringResource(R.string.backup_password_change), stringResource(R.string.backup_password_change_desc), Icons.Default.Password,
                        enabled = !busy, onClick = { passwordDialog = true })
                    SettingRow(stringResource(R.string.backup_encryption_disable), stringResource(R.string.backup_encryption_disable_desc), Icons.Default.LockOpen,
                        enabled = !busy, onClick = { confirm = "encryption" })
                }
                BackupPasswordStore.State.UNAVAILABLE -> {
                    Text(stringResource(R.string.err_backup_password_unavailable), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    SettingRow(stringResource(R.string.backup_password_reenter), stringResource(R.string.backup_password_reenter_desc), Icons.Default.Password,
                        enabled = !busy, onClick = { passwordDialog = true })
                    SettingRow(stringResource(R.string.backup_encryption_disable), stringResource(R.string.backup_encryption_disable_desc), Icons.Default.LockOpen,
                        enabled = !busy, onClick = { confirm = "encryption" })
                }
            }
        }
        SettingsGroup(stringResource(R.string.group_local_backup)) {
            SettingRow(stringResource(R.string.export_backup), stringResource(R.string.export_backup_desc), Icons.Default.FileUpload, enabled = !busy,
                onClick = { exportLauncher.launch(vm.suggestedName()) })
            SettingRow(stringResource(R.string.import_backup), stringResource(R.string.import_backup_desc), Icons.Default.FileDownload, enabled = !busy,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) })
            ChoiceSetting(stringResource(R.string.backup_reminder), stringResource(R.string.backup_reminder_desc), s.backupReminder, BackupReminderFrequency.entries, {
                stringResource(when (it) { BackupReminderFrequency.OFF -> R.string.off; BackupReminderFrequency.WEEKLY -> R.string.weekly; BackupReminderFrequency.MONTHLY -> R.string.monthly })
            }) { v -> vm.update { it.copy(backupReminder = v) } }
            snapshots.forEach { f ->
                val at = f.name.substringAfter("before-restore-").substringBefore('.').toLongOrNull()
                SettingRow(stringResource(R.string.safety_snapshot), stringResource(R.string.safety_snapshot_desc, at?.let { fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: f.name),
                    Icons.Default.History, enabled = !busy, onClick = { vm.restoreSnapshot(f) })
            }
        }
        SettingsGroup(stringResource(R.string.group_spreadsheet_export)) {
            SettingRow(stringResource(R.string.export_csv), stringResource(R.string.export_csv_desc), Icons.Default.TableChart, enabled = !busy, onClick = {
                // The CSV file is never encrypted: say so first when the user protects backups.
                if (passwordState == BackupPasswordStore.State.OFF) csvLauncher.launch(vm.suggestedCsvName()) else confirm = "csv"
            })
        }
        SettingsGroup(stringResource(R.string.group_folder_backup)) {
            val st = status
            val hasFolder = st?.backupFolderUri != null
            Text(stringResource(R.string.folder_backup_explanation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            SettingRow(
                stringResource(R.string.folder_backup_folder),
                if (hasFolder) st?.backupFolderName ?: stringResource(R.string.folder_chosen_unnamed) else stringResource(R.string.folder_not_chosen),
                Icons.Default.Folder, enabled = !busy, onClick = { folderLauncher.launch(null) },
            )
            if (st?.folderBackupFailing == true) {
                Text(stringResource(R.string.folder_backup_failing_hint), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            ChoiceSetting(stringResource(R.string.folder_backup_auto), stringResource(R.string.folder_backup_auto_desc), s.autoFolderBackup,
                AutoBackupFrequency.entries, frequencyLabel, enabled = hasFolder) { v -> vm.update { it.copy(autoFolderBackup = v) } }
            ChoiceSetting(stringResource(R.string.folder_backup_keep), stringResource(R.string.folder_backup_keep_desc), s.folderBackupsToKeep,
                listOf(3, 5, 10, 20, 50), { fmt.number(it) }, enabled = hasFolder) { v -> vm.update { it.copy(folderBackupsToKeep = v) } }
            SettingRow(stringResource(R.string.folder_backup_now), stringResource(R.string.folder_backup_now_desc), Icons.Default.Backup,
                enabled = hasFolder && !busy, onClick = { vm.folderBackupNow() })
            if (hasFolder) {
                SettingRow(stringResource(R.string.folder_backup_forget), stringResource(R.string.folder_backup_forget_desc), Icons.Default.FolderOff,
                    enabled = !busy, onClick = { confirm = "folder" })
            }
        }
        SettingsGroup(stringResource(R.string.group_drive)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.drive_explanation), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            when {
                !vm.driveConfigured -> SettingRow(stringResource(R.string.drive_status), stringResource(R.string.drive_not_configured_desc), Icons.Default.CloudOff)
                !connected -> SettingRow(stringResource(R.string.drive_connect), stringResource(R.string.drive_connect_desc), Icons.Default.Cloud, enabled = !busy, onClick = {
                    try {
                        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, vm.driveAuthUrl())
                    } catch (e: android.content.ActivityNotFoundException) {
                        vm.noBrowser()
                    }
                })
                else -> {
                    SettingRow(stringResource(R.string.drive_connected), vm.driveAccount() ?: stringResource(R.string.drive_connected_desc), Icons.Default.Cloud)
                    SettingRow(stringResource(R.string.drive_backup_now), stringResource(R.string.drive_backup_now_desc), Icons.Default.CloudUpload, enabled = !busy, onClick = { vm.driveBackup() })
                    SettingRow(stringResource(R.string.drive_restore), stringResource(R.string.drive_restore_desc), Icons.Default.CloudDownload, enabled = !busy, onClick = { vm.driveList() })
                    ChoiceSetting(stringResource(R.string.drive_auto), stringResource(R.string.drive_auto_desc), s.autoDriveBackup, AutoBackupFrequency.entries,
                        frequencyLabel) { v -> vm.update { it.copy(autoDriveBackup = v) } }
                    SwitchSetting(stringResource(R.string.drive_unmetered), stringResource(R.string.drive_unmetered_desc), s.driveBackupUnmeteredOnly,
                        enabled = s.autoDriveBackup != AutoBackupFrequency.OFF) { v -> vm.update { it.copy(driveBackupUnmeteredOnly = v) } }
                    ChoiceSetting(stringResource(R.string.drive_keep), stringResource(R.string.drive_keep_desc), s.driveBackupsToKeep, listOf(3, 5, 10, 20, 50),
                        { fmt.number(it) }) { v -> vm.update { it.copy(driveBackupsToKeep = v) } }
                    SettingRow(stringResource(R.string.drive_disconnect), stringResource(R.string.drive_disconnect_desc), Icons.Default.CloudOff, onClick = { confirm = "drive" })
                }
            }
        }
        SettingsGroup(stringResource(R.string.group_data_management)) {
            SettingRow(stringResource(R.string.delete_archived), stringResource(R.string.delete_archived_desc), Icons.Default.DeleteSweep, onClick = { confirm = "archived" })
            SettingRow(stringResource(R.string.delete_all_data), stringResource(R.string.delete_all_data_desc), Icons.Default.DeleteForever, onClick = { confirm = "all" })
        }
    }

    when (confirm) {
        "drive" -> ConfirmDialog(stringResource(R.string.drive_disconnect), stringResource(R.string.drive_disconnect_confirm), stringResource(R.string.disconnect),
            onConfirm = { vm.driveDisconnect() }, onDismiss = { confirm = null })
        "archived" -> ConfirmDialog(stringResource(R.string.delete_archived), stringResource(R.string.delete_archived_confirm), stringResource(R.string.delete),
            onConfirm = { vm.deleteArchived() }, onDismiss = { confirm = null }, destructive = true)
        "all" -> DeleteAllDialog(onConfirm = { vm.deleteAll() }, onDismiss = { confirm = null })
        "encryption" -> ConfirmDialog(stringResource(R.string.backup_encryption_disable), stringResource(R.string.backup_encryption_disable_confirm),
            stringResource(R.string.turn_off), onConfirm = { vm.disableEncryption() }, onDismiss = { confirm = null }, destructive = true)
        "folder" -> ConfirmDialog(stringResource(R.string.folder_backup_forget), stringResource(R.string.folder_backup_forget_confirm),
            stringResource(R.string.forget), onConfirm = { vm.clearFolder() }, onDismiss = { confirm = null })
        "csv" -> ConfirmDialog(stringResource(R.string.export_csv), stringResource(R.string.export_csv_unencrypted_confirm),
            stringResource(R.string.export_csv_continue), onConfirm = { csvLauncher.launch(vm.suggestedCsvName()) }, onDismiss = { confirm = null })
    }
    if (passwordDialog) {
        SetPasswordDialog(
            changing = passwordState == BackupPasswordStore.State.ON,
            onSave = { vm.setPassword(it) },
            onDismiss = { passwordDialog = false },
        )
    }
    passwordPrompt?.let { p -> PasswordPromptDialog(p, busy, onSubmit = { vm.submitPassword(it) }, onDismiss = { vm.cancelPassword() }) }
    preview?.let { p -> RestoreDialog(p, vm) }
    driveFiles?.let { files ->
        AlertDialog(
            onDismissRequest = { vm.closeDriveList() },
            title = { Text(stringResource(R.string.drive_backups)) },
            text = {
                if (files.isEmpty()) Text(stringResource(R.string.drive_no_backups))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(files, key = { it.id }) { f ->
                        Column(Modifier.fillMaxWidth().clickable { vm.driveDownload(f) }.padding(vertical = 10.dp)) {
                            Text(f.createdAt?.let { fmt.dateTime(it, zone) } ?: f.name)
                            val size = fmt.digits(String.format(java.util.Locale.ROOT, "%.1f", f.size / 1024.0)) + " KB"
                            Text(if (f.encrypted) size + " · " + stringResource(R.string.encrypted_label) else size, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeDriveList() }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun RestoreDialog(p: RestorePreview, vm: DataViewModel) {
    val fmt = LocalFormatter.current
    val c = LocalContainer.current
    val counts = p.file.info.counts
    AlertDialog(
        onDismissRequest = { vm.dismissPreview() },
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.restore_source, stringResource(p.source)))
                Text(stringResource(R.string.restore_created, fmt.dateTime(p.file.info.createdAt, c.clock.zone()), fmt.digits(p.file.info.appVersion)))
                if (p.file.info.encrypted) Text(stringResource(R.string.restore_encrypted), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.restore_counts, fmt.number(counts.tasks), fmt.number(counts.notes), fmt.number(counts.goals), fmt.number(counts.habits)),
                    style = MaterialTheme.typography.bodySmall)
                if (counts.templates > 0) {
                    Text(stringResource(R.string.restore_counts_templates, fmt.number(counts.templates)), style = MaterialTheme.typography.bodySmall)
                }
                if (RestorePlanner.keepsLocalTemplates(p.file)) {
                    Text(stringResource(R.string.restore_keeps_templates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.warnings.isNotEmpty()) Text(stringResource(R.string.restore_warnings, fmt.number(p.warnings.size)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.restore_mode), style = MaterialTheme.typography.labelLarge)
                RestoreMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { vm.choose(m) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(p.mode == m, { vm.choose(m) })
                        Column {
                            Text(stringResource(if (m == RestoreMode.REPLACE) R.string.mode_replace else R.string.mode_merge))
                            Text(stringResource(if (m == RestoreMode.REPLACE) R.string.mode_replace_desc else R.string.mode_merge_desc), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                p.plan?.let { plan ->
                    Text(stringResource(R.string.restore_plan_stats, fmt.number(plan.stats.added), fmt.number(plan.stats.updated), fmt.number(plan.stats.kept), fmt.number(plan.stats.removed)),
                        style = MaterialTheme.typography.bodySmall)
                    if (plan.alreadyRestored) Text(stringResource(R.string.restore_already), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.restore_safety_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { vm.apply() }) { Text(stringResource(R.string.restore_confirm)) } },
        dismissButton = { TextButton(onClick = { vm.dismissPreview() }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            stringResource(if (visible) R.string.hide_password else R.string.show_password),
        )
    }
}

private fun passwordProblemRes(p: BackupCrypto.PasswordProblem): Int = when (p) {
    BackupCrypto.PasswordProblem.TOO_SHORT -> R.string.backup_password_too_short
    BackupCrypto.PasswordProblem.TOO_LONG -> R.string.backup_password_too_long
    BackupCrypto.PasswordProblem.BLANK -> R.string.backup_password_blank
}

/** Sets or changes the backup password: entered twice, with an explicit acknowledgement. */
@Composable
private fun SetPasswordDialog(changing: Boolean, onSave: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val fmt = LocalFormatter.current
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    val problem = if (first.isEmpty()) null else BackupCrypto.checkPassword(first.toCharArray())
    val mismatch = second.isNotEmpty() && BackupCrypto.normalize(first.toCharArray()) != BackupCrypto.normalize(second.toCharArray())
    val canSave = first.isNotEmpty() && second.isNotEmpty() && problem == null && !mismatch && acknowledged
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (changing) R.string.backup_password_change else R.string.backup_password_set_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_password_explanation), style = MaterialTheme.typography.bodySmall)
                if (changing) {
                    Text(stringResource(R.string.backup_password_change_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    first, { first = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    isError = problem != null,
                    supportingText = {
                        Text(problem?.let { stringResource(passwordProblemRes(it)) }
                            ?: stringResource(R.string.backup_password_rule, fmt.number(BackupCrypto.MIN_PASSWORD_LENGTH)))
                    },
                    trailingIcon = { PasswordVisibilityToggle(visible) { visible = !visible } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    second, { second = it },
                    label = { Text(stringResource(R.string.backup_password_repeat)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text(stringResource(R.string.backup_password_mismatch)) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(acknowledged, role = Role.Checkbox) { acknowledged = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(acknowledged, null)
                    Text(stringResource(R.string.backup_password_acknowledge), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(first.toCharArray()); onDismiss() }, enabled = canSave) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for the password of an encrypted backup that is being restored. */
@Composable
private fun PasswordPromptDialog(prompt: PasswordPrompt, busy: Boolean, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var text by remember(prompt) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val submit = { if (text.isNotEmpty() && !busy) onSubmit(text.toCharArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_password_prompt_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = prompt.wrong,
                    supportingText = if (prompt.wrong) ({ Text(stringResource(R.string.backup_err_wrong_password)) }) else null,
                    trailingIcon = { PasswordVisibilityToggle(visible) { visible = !visible } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(stringResource(R.string.backup_password_checking), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = text.isNotEmpty() && !busy) { Text(stringResource(R.string.open_backup)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Deleting everything requires typing a confirmation word. */
@Composable
private fun DeleteAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val word = stringResource(R.string.delete_confirm_word)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_all_data)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_all_confirm, word))
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(word) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, enabled = text.trim() == word) {
                Text(stringResource(R.string.delete_everything), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
