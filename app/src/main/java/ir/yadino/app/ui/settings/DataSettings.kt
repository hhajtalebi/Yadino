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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.data.backup.DriveBackupEntry
import ir.yadino.app.data.backup.DriveResult
import ir.yadino.app.data.backup.ExportResult
import ir.yadino.app.data.repository.DataStats
import ir.yadino.app.data.settings.BackupStatus
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupFile
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlan
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

class DataViewModel(c: AppContainer) : SettingsViewModel(c) {
    val status: StateFlow<BackupStatus?> = c.appState.status.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val stats = MutableStateFlow<DataStats?>(null)
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<RestorePreview?>(null)
    val driveFiles = MutableStateFlow<List<DriveBackupEntry>?>(null)
    val snapshots = MutableStateFlow<List<File>>(emptyList())
    val driveConnected = MutableStateFlow(c.drive.isConnected())
    val driveConfigured = c.drive.isConfigured()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        stats.value = c.data.stats()
        snapshots.value = c.backup.safetySnapshots()
        driveConnected.value = c.drive.isConnected()
    }

    fun suggestedName() = c.backup.suggestedFileName()
    fun driveAccount(): String? = c.drive.accountEmail()

    private fun busy(block: suspend () -> Unit) = launchSafe(onError = { busy.value = false }) {
        busy.value = true
        try { block() } finally { busy.value = false }
    }

    fun export(uri: Uri) = busy {
        when (val r = c.backup.exportTo(uri)) {
            is ExportResult.Success -> message(UiMessage.Text(R.string.msg_backup_saved, listOf(r.records.toString())))
            is ExportResult.Failure -> message(UiMessage.Text(R.string.msg_backup_failed_reason, listOf(r.reason)))
        }
    }

    private fun handleRead(r: BackupReadResult, source: Int) {
        when (r) {
            is BackupReadResult.Success -> preview.value = RestorePreview(r.file, source, r.warnings)
            is BackupReadResult.Failure -> message(UiMessage.Text(backupErrorRes(r.error)))
        }
    }

    fun import(uri: Uri) = busy { handleRead(c.backup.readFrom(uri), R.string.source_file) }

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

    fun restoreSnapshot(f: File) = busy { handleRead(c.backup.readSnapshot(f), R.string.source_snapshot) }

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
        val (r, read) = c.backup.downloadFromDrive(entry.id)
        if (r is DriveResult.Success && read != null) handleRead(read, R.string.source_drive) else message(driveMessage(r, R.string.msg_drive_backup_ok))
    }

    fun driveDisconnect() = busy {
        c.drive.disconnect()
        c.appState.setDriveAccount(null)
        update { it.copy(autoDriveBackup = AutoBackupFrequency.OFF) }
        driveConnected.value = false
        message(UiMessage.Text(R.string.msg_drive_disconnected))
    }

    fun closeDriveList() { driveFiles.value = null }

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
                else -> R.string.drive_error
            }
        },
    )

    // ---- data management -------------------------------------------------------------------------
    fun deleteArchived() = busy {
        val n = c.data.deleteArchived()
        message(UiMessage.Text(R.string.msg_deleted_count, listOf(n.toString())))
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
    val fmt = LocalFormatter.current
    val context = LocalContext.current
    CollectMessages(vm.messages)
    var confirm by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LaunchedEffect(Unit) { vm.refresh() }
    val zone = c.clock.zone()

    SettingsPage(stringResource(R.string.settings_data)) {
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(8.dp))
            Text(stringResource(R.string.working))
        }
        SettingsGroup(stringResource(R.string.group_storage_stats)) {
            val st = stats
            SettingRow(stringResource(R.string.local_storage), st?.let {
                stringResource(R.string.storage_summary, fmt.number(it.tasks), fmt.number(it.notes), fmt.number(it.goals), fmt.number(it.habits),
                    fmt.digits(String.format(java.util.Locale.ROOT, "%.1f", it.databaseBytes / 1024.0)))
            } ?: "…", Icons.Default.Storage)
            SettingRow(stringResource(R.string.local_first_note), stringResource(R.string.local_first_desc))
        }
        SettingsGroup(stringResource(R.string.group_backup_status)) {
            val st = status
            SettingRow(stringResource(R.string.last_local_backup), st?.lastLocalBackupAt?.let { fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: stringResource(R.string.never))
            SettingRow(stringResource(R.string.last_drive_backup), st?.lastDriveBackupAt?.let { fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: stringResource(R.string.never))
            st?.lastResult?.let { r -> SettingRow(stringResource(R.string.last_operation), r + (st.lastResultAt?.let { " — " + fmt.dateTime(Instant.ofEpochMilli(it), zone) } ?: "")) }
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
        SettingsGroup(stringResource(R.string.group_drive)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.drive_explanation), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            when {
                !vm.driveConfigured -> SettingRow(stringResource(R.string.drive_status), stringResource(R.string.drive_not_configured_desc), Icons.Default.CloudOff)
                !connected -> SettingRow(stringResource(R.string.drive_connect), stringResource(R.string.drive_connect_desc), Icons.Default.Cloud, enabled = !busy, onClick = {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, vm.driveAuthUrl())
                })
                else -> {
                    SettingRow(stringResource(R.string.drive_connected), vm.driveAccount() ?: stringResource(R.string.drive_connected_desc), Icons.Default.Cloud)
                    SettingRow(stringResource(R.string.drive_backup_now), stringResource(R.string.drive_backup_now_desc), Icons.Default.CloudUpload, enabled = !busy, onClick = { vm.driveBackup() })
                    SettingRow(stringResource(R.string.drive_restore), stringResource(R.string.drive_restore_desc), Icons.Default.CloudDownload, enabled = !busy, onClick = { vm.driveList() })
                    ChoiceSetting(stringResource(R.string.drive_auto), stringResource(R.string.drive_auto_desc), s.autoDriveBackup, AutoBackupFrequency.entries, {
                        stringResource(when (it) { AutoBackupFrequency.OFF -> R.string.off; AutoBackupFrequency.DAILY -> R.string.daily; AutoBackupFrequency.WEEKLY -> R.string.weekly })
                    }) { v -> vm.update { it.copy(autoDriveBackup = v) } }
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
    }
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
                            Text(fmt.digits(String.format(java.util.Locale.ROOT, "%.1f", f.size / 1024.0)) + " KB", style = MaterialTheme.typography.bodySmall,
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.restore_source, stringResource(p.source)))
                Text(stringResource(R.string.restore_created, fmt.dateTime(p.file.info.createdAt, c.clock.zone()), fmt.digits(p.file.info.appVersion)))
                Text(stringResource(R.string.restore_counts, fmt.number(counts.tasks), fmt.number(counts.notes), fmt.number(counts.goals), fmt.number(counts.habits)),
                    style = MaterialTheme.typography.bodySmall)
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
