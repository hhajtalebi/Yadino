package ir.yadino.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.yadino.app.R
import ir.yadino.app.data.device.DeviceCalendar
import ir.yadino.app.data.device.DeviceCalendarPrefs
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * Settings › Calendar › "تقویم‌های گوشی": showing the events of the device's calendars in
 * Yadino (read-only), with the permission request explained first, a way back when the
 * permission was refused or revoked, and the choice of calendars.
 */
@Composable
fun DeviceCalendarGroup() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by c.deviceCalendars.prefs.collectAsStateWithLifecycle(DeviceCalendarPrefs())
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // the permission may have changed in the system settings
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val granted = remember(refresh) { c.deviceCalendars.hasPermission() }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>?>(null) }
    LaunchedEffect(prefs.enabled, granted, refresh) {
        calendars = if (prefs.enabled && granted) c.deviceCalendars.calendars() else null
    }
    var explain by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        refused = !ok
        if (ok) scope.launch { c.deviceCalendars.setEnabled(true) }
    }

    SettingsGroup(stringResource(R.string.device_calendars_group)) {
        SwitchSetting(stringResource(R.string.device_calendars_show), stringResource(R.string.device_calendars_show_desc), prefs.enabled) { on ->
            when {
                !on -> scope.launch { c.deviceCalendars.setEnabled(false) }
                granted -> scope.launch { c.deviceCalendars.setEnabled(true) }
                else -> explain = true
            }
        }
        if (refused && !granted) {
            SettingRow(stringResource(R.string.device_calendars_refused), null, Icons.Default.EventBusy,
                trailing = { TextButton(onClick = { openAppDetails(context) }) { Text(stringResource(R.string.open_settings)) } })
        } else if (prefs.enabled && !granted) {
            SettingRow(stringResource(R.string.device_calendars_missing), null, Icons.Default.EventBusy,
                trailing = { TextButton(onClick = { launcher.launch(Manifest.permission.READ_CALENDAR) }) { Text(stringResource(R.string.grant)) } })
        }
        val list = calendars
        if (prefs.enabled && granted && list != null) {
            if (list.isEmpty()) {
                SettingRow(stringResource(R.string.device_calendars_none), null)
            } else {
                Text(stringResource(R.string.device_calendars_choose), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                list.forEach { cal ->
                    CalendarSwitch(cal, shown = cal.id !in prefs.hidden) { show -> scope.launch { c.deviceCalendars.setHidden(cal.id, !show) } }
                }
            }
        }
    }
    if (explain) {
        ConfirmDialog(
            stringResource(R.string.device_calendars_permission_title), stringResource(R.string.device_calendars_permission_message),
            stringResource(R.string.grant), onConfirm = { launcher.launch(Manifest.permission.READ_CALENDAR) }, onDismiss = { explain = false },
        )
    }
}

/** One device calendar with its colour, account and a switch. */
@Composable
private fun CalendarSwitch(calendar: DeviceCalendar, shown: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!shown) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(calendar.color)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
            if (calendar.account.isNotBlank() && calendar.account != calendar.name) {
                Text(calendar.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = shown, onCheckedChange = null)
    }
}

private fun openAppDetails(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
}
