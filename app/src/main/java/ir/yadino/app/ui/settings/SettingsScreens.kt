package ir.yadino.app.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.BuildConfig
import ir.yadino.app.R
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.local.YadinoDatabase
import ir.yadino.app.di.AppContainer
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.components.ColorDot
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.NoteColorPicker
import ir.yadino.app.ui.components.noteColorName
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.notes.sortLabel
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.app.widget.CalendarWidgetProvider
import ir.yadino.app.widget.ClockWidgetProvider
import ir.yadino.app.widget.CountdownWidgetProvider
import ir.yadino.app.widget.GoalWidgetProvider
import ir.yadino.app.widget.HabitsWidgetProvider
import ir.yadino.app.widget.QuickNoteWidgetProvider
import ir.yadino.app.widget.TasksWidgetProvider
import ir.yadino.app.widget.TodayWidgetProvider
import ir.yadino.app.widget.WidgetConfigActivity
import ir.yadino.app.widget.WidgetUpdater
import ir.yadino.calendar.CalendarFormatter
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.NumeralStyle
import ir.yadino.calendar.OccasionDisplay
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.model.AnimationLevel
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.CalendarView
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.CategoryKind
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.ListLayout
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.NoteSort
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.Palette
import ir.yadino.domain.model.ProgressStyle
import ir.yadino.domain.model.RecurringCompletionMode
import ir.yadino.domain.model.StartScreen
import ir.yadino.domain.model.ThemeMode
import ir.yadino.domain.settings.SettingsCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

@Composable
private fun settingsVm(): SettingsViewModel {
    val c = LocalContainer.current
    return viewModel { SettingsViewModel(c) }
}

// ================================================================================================
// Root
// ================================================================================================
@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    SettingsPage(stringResource(R.string.settings)) {
        SettingsGroup(stringResource(R.string.settings_sections)) {
            SettingRow(stringResource(R.string.settings_general), stringResource(R.string.settings_general_desc), Icons.Default.Tune, onClick = { nav.navigate(Route.SettingsGeneral) })
            SettingRow(stringResource(R.string.settings_calendar), stringResource(R.string.settings_calendar_desc), Icons.Default.CalendarMonth, onClick = { nav.navigate(Route.SettingsCalendar) })
            SettingRow(stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_desc), Icons.Default.Notifications, onClick = { nav.navigate(Route.SettingsNotifications) })
            SettingRow(stringResource(R.string.settings_notes_goals), stringResource(R.string.settings_notes_goals_desc), Icons.AutoMirrored.Filled.StickyNote2, onClick = { nav.navigate(Route.SettingsNotesGoals) })
            SettingRow(stringResource(R.string.settings_widgets), stringResource(R.string.settings_widgets_desc), Icons.Default.Widgets, onClick = { nav.navigate(Route.SettingsWidgets) })
            SettingRow(stringResource(R.string.settings_data), stringResource(R.string.settings_data_desc), Icons.Default.Backup, onClick = { nav.navigate(Route.SettingsData) })
            SettingRow(stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_desc), Icons.Default.Settings, onClick = { nav.navigate(Route.SettingsAdvanced) })
        }
        SettingsGroup(stringResource(R.string.settings_personalize)) {
            SettingRow(stringResource(R.string.categories), stringResource(R.string.categories_desc), Icons.Default.Category, onClick = { nav.navigate(Route.Categories) })
            SettingRow(stringResource(R.string.customize_dashboard), stringResource(R.string.customize_dashboard_desc), Icons.Default.DashboardCustomize, onClick = { nav.navigate(Route.DashboardCustomize) })
            SettingRow(stringResource(R.string.about), stringResource(R.string.about_desc), Icons.Default.Info, onClick = { nav.navigate(Route.About) })
        }
    }
}

// ================================================================================================
// General
// ================================================================================================
@Composable
fun startScreenLabel(s: StartScreen) = stringResource(
    when (s) {
        StartScreen.DASHBOARD -> R.string.tab_dashboard
        StartScreen.CALENDAR -> R.string.tab_calendar
        StartScreen.TASKS -> R.string.tab_tasks
        StartScreen.NOTES -> R.string.tab_notes
        StartScreen.GOALS -> R.string.tab_goals
    },
)

private val COMMON_ZONES = listOf("Asia/Tehran", "Asia/Dubai", "Asia/Kabul", "Europe/Istanbul", "Europe/London", "Europe/Berlin", "Europe/Paris",
    "America/Toronto", "America/New_York", "America/Los_Angeles", "Australia/Sydney", "Asia/Tokyo")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsGeneralScreen() {
    val vm = settingsVm()
    val s by vm.settings.collectAsStateWithLifecycle()
    val fmt = LocalFormatter.current
    val c = LocalContainer.current
    var confirmReset by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.settings_general)) {
        SettingsGroup(stringResource(R.string.group_language_start)) {
            SettingRow(stringResource(R.string.language), stringResource(R.string.language_desc))
            ChoiceSetting(stringResource(R.string.start_screen), stringResource(R.string.start_screen_desc), s.startScreen, StartScreen.entries, { startScreenLabel(it) }) { v ->
                vm.update { it.copy(startScreen = v) }
            }
        }
        SettingsGroup(stringResource(R.string.group_date_time)) {
            val sample = c.clock.today()
            ChoiceSetting(stringResource(R.string.date_format), stringResource(R.string.date_format_desc), s.dateFormat, DateFormatStyle.entries,
                { CalendarFormatter.formatJalali(JalaliDate.from(sample), it, s.numerals) }) { v -> vm.update { it.copy(dateFormat = v) } }
            SwitchSetting(stringResource(R.string.use_24h), stringResource(R.string.use_24h_desc, fmt.time(LocalTime.of(14, 30))), s.use24Hour) { v -> vm.update { it.copy(use24Hour = v) } }
            ChoiceSetting(stringResource(R.string.time_zone), stringResource(R.string.time_zone_desc), s.timeZoneId, listOf<String?>(null) + COMMON_ZONES,
                { it ?: stringResource(R.string.time_zone_device, ZoneId.systemDefault().id) }) { v -> vm.update { it.copy(timeZoneId = v) } }
            ChoiceSetting(stringResource(R.string.first_day_of_week), stringResource(R.string.first_day_of_week_desc), s.firstDayOfWeek,
                listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY), { PersianNames.weekday(it) }) { v -> vm.update { it.copy(firstDayOfWeek = v) } }
            ChoiceSetting(stringResource(R.string.numerals), stringResource(R.string.numerals_desc), s.numerals, NumeralStyle.entries,
                { stringResource(if (it == NumeralStyle.PERSIAN) R.string.numerals_persian else R.string.numerals_latin) }) { v -> vm.update { it.copy(numerals = v) } }
            SwitchSetting(stringResource(R.string.show_gregorian), stringResource(R.string.show_gregorian_desc), s.showGregorian) { v -> vm.update { it.copy(showGregorian = v) } }
            SwitchSetting(stringResource(R.string.show_week_numbers), stringResource(R.string.show_week_numbers_desc), s.showWeekNumbers) { v -> vm.update { it.copy(showWeekNumbers = v) } }
        }
        SettingsGroup(stringResource(R.string.group_appearance)) {
            ChoiceSetting(stringResource(R.string.theme), stringResource(R.string.theme_desc), s.themeMode, ThemeMode.entries, {
                stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
            }) { v -> vm.update { it.copy(themeMode = v) } }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.palette), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.palette_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Palette.entries.filter { it != Palette.DYNAMIC || Build.VERSION.SDK_INT >= 31 }.forEach { p ->
                        FilterChip(s.palette == p, { vm.update { it.copy(palette = p) } }, { Text(paletteLabel(p)) },
                            leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(paletteSwatch(p))) })
                    }
                }
            }
            SliderSetting(stringResource(R.string.font_scale), stringResource(R.string.font_scale_desc), s.fontScale, AppSettings.MIN_FONT_SCALE..AppSettings.MAX_FONT_SCALE, 10,
                { fmt.percent((it * 100).toInt()) }) { v -> vm.update { it.copy(fontScale = (Math.round(v * 20) / 20f)) } }
            SliderSetting(stringResource(R.string.display_scale), stringResource(R.string.display_scale_desc), s.displayScale, AppSettings.MIN_DISPLAY_SCALE..AppSettings.MAX_DISPLAY_SCALE, 6,
                { fmt.percent((it * 100).toInt()) }) { v -> vm.update { it.copy(displayScale = (Math.round(v * 20) / 20f)) } }
            ChoiceSetting(stringResource(R.string.animations), stringResource(R.string.animations_desc), s.animationLevel, AnimationLevel.entries, {
                stringResource(when (it) { AnimationLevel.FULL -> R.string.anim_full; AnimationLevel.REDUCED -> R.string.anim_reduced; AnimationLevel.OFF -> R.string.anim_off })
            }) { v -> vm.update { it.copy(animationLevel = v) } }
        }
        SettingsGroup(stringResource(R.string.group_reset)) {
            SettingRow(stringResource(R.string.reset_settings), stringResource(R.string.reset_settings_desc), Icons.Default.RestartAlt, onClick = { confirmReset = true })
        }
    }
    if (confirmReset) {
        ConfirmDialog(stringResource(R.string.reset_settings), stringResource(R.string.reset_settings_confirm), stringResource(R.string.reset),
            onConfirm = { c.appScope.launch { c.settings.resetToDefaults() } }, onDismiss = { confirmReset = false })
    }
}

@Composable
fun paletteLabel(p: Palette) = stringResource(
    when (p) {
        Palette.YADINO -> R.string.palette_yadino
        Palette.OCEAN -> R.string.palette_ocean
        Palette.ROSE -> R.string.palette_rose
        Palette.AMETHYST -> R.string.palette_amethyst
        Palette.DYNAMIC -> R.string.palette_dynamic
    },
)

private fun paletteSwatch(p: Palette): Color = when (p) {
    Palette.YADINO -> Color(0xFF1F5C45)
    Palette.OCEAN -> Color(0xFF1D5C8C)
    Palette.ROSE -> Color(0xFF9C3550)
    Palette.AMETHYST -> Color(0xFF6A4C93)
    Palette.DYNAMIC -> Color(0xFF888888)
}

// ================================================================================================
// Calendar & planning
// ================================================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsCalendarScreen() {
    val vm = settingsVm()
    val s by vm.settings.collectAsStateWithLifecycle()
    val fmt = LocalFormatter.current
    SettingsPage(stringResource(R.string.settings_calendar)) {
        SettingsGroup(stringResource(R.string.group_calendar_view)) {
            ChoiceSetting(stringResource(R.string.default_view), stringResource(R.string.default_view_desc), s.defaultCalendarView, CalendarView.entries, {
                stringResource(when (it) { CalendarView.MONTH -> R.string.view_month; CalendarView.WEEK -> R.string.view_week; CalendarView.DAY -> R.string.view_day })
            }) { v -> vm.update { it.copy(defaultCalendarView = v) } }
            ChoiceSetting(stringResource(R.string.occasions), stringResource(R.string.occasions_desc), s.occasionDisplay, OccasionDisplay.entries, {
                stringResource(when (it) { OccasionDisplay.NONE -> R.string.occasions_none; OccasionDisplay.HOLIDAYS_ONLY -> R.string.occasions_holidays; OccasionDisplay.ALL -> R.string.occasions_all })
            }) { v -> vm.update { it.copy(occasionDisplay = v) } }
            ChoiceSetting(stringResource(R.string.lunar_adjustment), stringResource(R.string.lunar_adjustment_desc), s.lunarAdjustment, listOf(-2, -1, 0, 1, 2),
                { if (it == 0) stringResource(R.string.no_adjustment) else (if (it > 0) "+" else "−") + fmt.number(kotlin.math.abs(it)) + " " + stringResource(R.string.unit_day) }) { v ->
                vm.update { it.copy(lunarAdjustment = v) }
            }
            SwitchSetting(stringResource(R.string.show_future_occurrences), stringResource(R.string.show_future_occurrences_desc), s.showFutureOccurrences) { v ->
                vm.update { it.copy(showFutureOccurrences = v) }
            }
        }
        SettingsGroup(stringResource(R.string.group_work)) {
            TimeSetting(stringResource(R.string.work_start), stringResource(R.string.work_start_desc), s.workDayStart) { v -> if (v != null) vm.update { it.copy(workDayStart = v) } }
            TimeSetting(stringResource(R.string.work_end), stringResource(R.string.work_end_desc), s.workDayEnd) { v -> if (v != null) vm.update { it.copy(workDayEnd = v) } }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.work_days), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.work_days_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WeekMath.orderedDays(s.firstDayOfWeek).forEach { d ->
                        FilterChip(d in s.workDays, { vm.update { it.copy(workDays = if (d in it.workDays) it.workDays - d else it.workDays + d) } }, { Text(PersianNames.weekday(d)) })
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.group_task_defaults)) {
            TimeSetting(stringResource(R.string.default_task_time), stringResource(R.string.default_task_time_desc), s.defaultTaskTime, allowClear = true,
                clearLabel = stringResource(R.string.all_day)) { v -> vm.update { it.copy(defaultTaskTime = v) } }
            ChoiceSetting(stringResource(R.string.default_duration), stringResource(R.string.default_duration_desc), s.defaultTaskDurationMinutes, listOf(15, 30, 45, 60, 90, 120, 180),
                { fmt.duration(it.toLong()) }) { v -> vm.update { it.copy(defaultTaskDurationMinutes = v) } }
            ChoiceSetting(stringResource(R.string.default_reminder), stringResource(R.string.default_reminder_desc), s.defaultReminderOffsetMinutes,
                listOf(null, 0, 5, 10, 15, 30, 60, 1440), { it?.let { m -> fmt.minutesOffset(m) } ?: stringResource(R.string.no_reminder) }) { v ->
                vm.update { it.copy(defaultReminderOffsetMinutes = v) }
            }
            TimeSetting(stringResource(R.string.all_day_reminder_time), stringResource(R.string.all_day_reminder_time_desc), s.allDayReminderTime) { v ->
                if (v != null) vm.update { it.copy(allDayReminderTime = v) }
            }
        }
        SettingsGroup(stringResource(R.string.group_behavior)) {
            ChoiceSetting(stringResource(R.string.recurring_completion), stringResource(R.string.recurring_completion_desc), s.recurringCompletion, RecurringCompletionMode.entries, {
                stringResource(if (it == RecurringCompletionMode.MOVE_TO_NEXT) R.string.recurring_move else R.string.recurring_keep_copy)
            }) { v -> vm.update { it.copy(recurringCompletion = v) } }
            ChoiceSetting(stringResource(R.string.overdue_behavior), stringResource(R.string.overdue_behavior_desc), s.overdueBehavior, OverdueBehavior.entries, {
                stringResource(when (it) {
                    OverdueBehavior.SHOW_IN_TODAY -> R.string.overdue_show_today
                    OverdueBehavior.SEPARATE_LIST -> R.string.overdue_separate
                    OverdueBehavior.MOVE_TO_TODAY -> R.string.overdue_move
                })
            }) { v -> vm.update { it.copy(overdueBehavior = v) } }
        }
    }
}

// ================================================================================================
// Notifications
// ================================================================================================
@Composable
fun SettingsNotificationsScreen() {
    val vm = settingsVm()
    val s by vm.settings.collectAsStateWithLifecycle()
    val fmt = LocalFormatter.current
    val context = LocalContext.current
    val c = LocalContainer.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    val canPost = remember(refresh) { NotificationHelper.canPost(context) }
    val exact = remember(refresh) { c.reminders.canScheduleExact() }
    val batteryOk = remember(refresh) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val enabled = s.notificationsEnabled

    SettingsPage(stringResource(R.string.settings_notifications)) {
        SettingsGroup(stringResource(R.string.group_permissions)) {
            SettingRow(
                stringResource(R.string.perm_notifications),
                stringResource(if (canPost) R.string.perm_notifications_ok else R.string.perm_notifications_missing),
                Icons.Default.NotificationsActive,
                trailing = {
                    if (!canPost) TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings(context)
                    }) { Text(stringResource(R.string.grant)) }
                },
            )
            if (Build.VERSION.SDK_INT >= 31) {
                SettingRow(
                    stringResource(R.string.perm_exact_alarms),
                    stringResource(if (exact) R.string.perm_exact_ok else R.string.perm_exact_missing),
                    Icons.Default.Notifications,
                    trailing = {
                        if (!exact) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                        }) { Text(stringResource(R.string.grant)) }
                    },
                )
            }
            SettingRow(
                stringResource(R.string.battery_optimization),
                stringResource(if (batteryOk) R.string.battery_ok else R.string.battery_note),
                Icons.Default.BatteryAlert,
                trailing = {
                    if (!batteryOk) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text(stringResource(R.string.open_settings)) }
                },
            )
            SettingRow(stringResource(R.string.test_notification), stringResource(R.string.test_notification_desc), onClick = {
                NotificationHelper.showDailySummary(context, context.getString(R.string.test_notification_title), context.getString(R.string.test_notification_body))
            })
        }
        SettingsGroup(stringResource(R.string.group_notification_types)) {
            SwitchSetting(stringResource(R.string.notifications_master), stringResource(R.string.notifications_master_desc), s.notificationsEnabled) { v -> vm.update { it.copy(notificationsEnabled = v) } }
            SwitchSetting(stringResource(R.string.notif_tasks), stringResource(R.string.notif_tasks_desc), s.taskReminders, enabled) { v -> vm.update { it.copy(taskReminders = v) } }
            SwitchSetting(stringResource(R.string.notif_habits), stringResource(R.string.notif_habits_desc), s.habitReminders, enabled) { v -> vm.update { it.copy(habitReminders = v) } }
            SwitchSetting(stringResource(R.string.notif_goals), stringResource(R.string.notif_goals_desc), s.goalReminders, enabled) { v -> vm.update { it.copy(goalReminders = v) } }
            ChoiceSetting(stringResource(R.string.goal_days_before), stringResource(R.string.goal_days_before_desc), s.goalDeadlineDaysBefore, listOf(0, 1, 2, 3, 5, 7, 14),
                { if (it == 0) stringResource(R.string.same_day) else fmt.number(it) + " " + stringResource(R.string.unit_day) }, enabled = enabled && s.goalReminders) { v ->
                vm.update { it.copy(goalDeadlineDaysBefore = v) }
            }
            SwitchSetting(stringResource(R.string.notif_backup), stringResource(R.string.notif_backup_desc), s.backupNotifications, enabled) { v -> vm.update { it.copy(backupNotifications = v) } }
            SwitchSetting(stringResource(R.string.daily_summary), stringResource(R.string.daily_summary_desc), s.dailySummary, enabled) { v -> vm.update { it.copy(dailySummary = v) } }
            TimeSetting(stringResource(R.string.daily_summary_time), null, s.dailySummaryTime, enabled = enabled && s.dailySummary) { v -> if (v != null) vm.update { it.copy(dailySummaryTime = v) } }
        }
        SettingsGroup(stringResource(R.string.group_repeat_quiet)) {
            ChoiceSetting(stringResource(R.string.snooze_duration), stringResource(R.string.snooze_duration_desc), s.snoozeMinutes, listOf(5, 10, 15, 30, 60),
                { fmt.duration(it.toLong()) }, enabled = enabled) { v -> vm.update { it.copy(snoozeMinutes = v) } }
            ChoiceSetting(stringResource(R.string.repeat_count), stringResource(R.string.repeat_count_desc), s.reminderRepeatCount, listOf(0, 1, 2, 3, 5),
                { if (it == 0) stringResource(R.string.no_repeat_reminder) else fmt.number(it) + " " + stringResource(R.string.times) }, enabled = enabled) { v ->
                vm.update { it.copy(reminderRepeatCount = v) }
            }
            SwitchSetting(stringResource(R.string.quiet_hours), stringResource(R.string.quiet_hours_desc), s.quietHoursEnabled, enabled) { v -> vm.update { it.copy(quietHoursEnabled = v) } }
            TimeSetting(stringResource(R.string.quiet_start), null, s.quietHoursStart, enabled = enabled && s.quietHoursEnabled) { v -> if (v != null) vm.update { it.copy(quietHoursStart = v) } }
            TimeSetting(stringResource(R.string.quiet_end), null, s.quietHoursEnd, enabled = enabled && s.quietHoursEnabled) { v -> if (v != null) vm.update { it.copy(quietHoursEnd = v) } }
        }
        SettingsGroup(stringResource(R.string.group_sound)) {
            Text(stringResource(R.string.sound_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            listOf(
                NotificationHelper.CHANNEL_TASKS to R.string.channel_tasks, NotificationHelper.CHANNEL_HABITS to R.string.channel_habits,
                NotificationHelper.CHANNEL_GOALS to R.string.channel_goals, NotificationHelper.CHANNEL_SUMMARY to R.string.channel_summary,
                NotificationHelper.CHANNEL_BACKUP to R.string.channel_backup,
            ).forEach { (id, label) ->
                SettingRow(stringResource(label), stringResource(R.string.channel_settings_desc), onClick = { openChannelSettings(context, id) })
            }
        }
    }
}

private fun openAppNotificationSettings(context: Context) {
    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    context.startActivity(i)
}

private fun openChannelSettings(context: Context, channel: String) {
    val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    context.startActivity(i)
}

// ================================================================================================
// Notes & goals
// ================================================================================================
@Composable
fun SettingsNotesGoalsScreen() {
    val vm = settingsVm()
    val s by vm.settings.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    SettingsPage(stringResource(R.string.settings_notes_goals)) {
        SettingsGroup(stringResource(R.string.group_notes)) {
            SwitchSetting(stringResource(R.string.autosave), stringResource(R.string.autosave_desc), s.noteAutosave) { v -> vm.update { it.copy(noteAutosave = v) } }
            ChoiceSetting(stringResource(R.string.autosave_delay), stringResource(R.string.autosave_delay_desc), s.noteAutosaveDelayMs, listOf(500L, 800L, 1500L, 3000L),
                { stringResource(R.string.seconds_value, fmt.digits(String.format(java.util.Locale.ROOT, "%.1f", it / 1000.0))) }, enabled = s.noteAutosave) { v ->
                vm.update { it.copy(noteAutosaveDelayMs = v) }
            }
            ChoiceSetting(stringResource(R.string.note_sort), stringResource(R.string.note_sort_desc), s.noteSort, NoteSort.entries, { sortLabel(it) }) { v -> vm.update { it.copy(noteSort = v) } }
            ChoiceSetting(stringResource(R.string.note_layout), null, s.noteLayout, ListLayout.entries, {
                stringResource(if (it == ListLayout.GRID) R.string.layout_grid else R.string.layout_list)
            }) { v -> vm.update { it.copy(noteLayout = v) } }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.default_note_color), style = MaterialTheme.typography.bodyLarge)
                Text(noteColorName(s.defaultNoteColor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NoteColorPicker(s.defaultNoteColor, { col -> vm.update { it.copy(defaultNoteColor = col) } }, Modifier.padding(top = 6.dp))
            }
            SettingRow(stringResource(R.string.categories), stringResource(R.string.categories_desc), Icons.Default.Category, onClick = { nav.navigate(Route.Categories) })
        }
        SettingsGroup(stringResource(R.string.group_goals)) {
            ChoiceSetting(stringResource(R.string.goal_layout), stringResource(R.string.goal_layout_desc), s.goalLayout, ListLayout.entries, {
                stringResource(if (it == ListLayout.GRID) R.string.layout_cards else R.string.layout_list)
            }) { v -> vm.update { it.copy(goalLayout = v) } }
            ChoiceSetting(stringResource(R.string.progress_style), stringResource(R.string.progress_style_desc), s.goalProgressStyle, ProgressStyle.entries, {
                stringResource(if (it == ProgressStyle.PERCENT) R.string.progress_percent else R.string.progress_fraction)
            }) { v -> vm.update { it.copy(goalProgressStyle = v) } }
            SwitchSetting(stringResource(R.string.show_completed_goals), stringResource(R.string.show_completed_goals_desc), s.showCompletedGoals) { v -> vm.update { it.copy(showCompletedGoals = v) } }
        }
    }
}

// ================================================================================================
// Widgets
// ================================================================================================
private data class WidgetType(val cls: Class<*>, val title: Int, val desc: Int)

private val WIDGET_TYPES = listOf(
    WidgetType(CalendarWidgetProvider::class.java, R.string.widget_calendar_name, R.string.widget_calendar_desc),
    WidgetType(TodayWidgetProvider::class.java, R.string.widget_today_name, R.string.widget_today_desc),
    WidgetType(TasksWidgetProvider::class.java, R.string.widget_tasks_name, R.string.widget_tasks_desc),
    WidgetType(QuickNoteWidgetProvider::class.java, R.string.widget_note_name, R.string.widget_note_desc),
    WidgetType(GoalWidgetProvider::class.java, R.string.widget_goal_name, R.string.widget_goal_desc),
    WidgetType(ClockWidgetProvider::class.java, R.string.widget_clock_name, R.string.widget_clock_desc),
    WidgetType(HabitsWidgetProvider::class.java, R.string.widget_habits_name, R.string.widget_habits_desc),
    WidgetType(CountdownWidgetProvider::class.java, R.string.widget_countdown_name, R.string.widget_countdown_desc),
)

@Composable
fun SettingsWidgetsScreen() {
    val context = LocalContext.current
    val c = LocalContainer.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    val placed = remember(refresh) { c.widgets.placedWidgets() }
    val manager = AppWidgetManager.getInstance(context)
    val canPin = manager.isRequestPinAppWidgetSupported
    SettingsPage(stringResource(R.string.settings_widgets)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.widget_guide_title), style = MaterialTheme.typography.titleSmall)
                }
                Text(stringResource(R.string.widget_guide_steps), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsGroup(stringResource(R.string.widget_types)) {
            WIDGET_TYPES.forEach { w ->
                SettingRow(stringResource(w.title), stringResource(w.desc), Icons.Default.Widgets, trailing = {
                    if (canPin) IconButton(onClick = {
                        manager.requestPinAppWidget(ComponentName(context, w.cls), null, null)
                    }) { Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, stringResource(R.string.add_to_home)) }
                })
            }
        }
        SettingsGroup(stringResource(R.string.placed_widgets)) {
            if (placed.isEmpty()) Text(stringResource(R.string.no_placed_widgets), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            placed.forEach { (cls, ids) ->
                val type = WIDGET_TYPES.firstOrNull { it.cls == cls } ?: return@forEach
                ids.forEach { id ->
                    SettingRow(stringResource(type.title), stringResource(R.string.widget_instance, id.toString()), Icons.Default.Edit, onClick = {
                        context.startActivity(Intent(context, WidgetConfigActivity::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    })
                }
            }
        }
    }
}

// ================================================================================================
// Advanced
// ================================================================================================
class AdvancedViewModel(c: AppContainer) : SettingsViewModel(c) {
    fun exportSettings(uri: Uri, context: Context) = launchSafe {
        val json = SettingsCodec.toJson(c.settings.current(), c.settings.currentDashboard())
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } }
        message(UiMessage.Text(R.string.msg_settings_exported))
    }

    fun importSettings(uri: Uri, context: Context) = launchSafe {
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } ?: ""
        val result = try {
            SettingsCodec.fromJson(text)
        } catch (e: IllegalArgumentException) {
            message(UiMessage.Text(R.string.err_settings_file))
            return@launchSafe
        }
        c.settings.replaceAll(result.settings)
        result.dashboard?.let { d -> c.settings.updateDashboard { d } }
        message(UiMessage.Text(R.string.msg_settings_imported))
    }

    fun clearCache(context: Context) = launchSafe {
        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        message(UiMessage.Text(R.string.msg_cache_cleared))
    }
}

@Composable
fun SettingsAdvancedScreen() {
    val c = LocalContainer.current
    val vm: AdvancedViewModel = viewModel { AdvancedViewModel(c) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fmt = LocalFormatter.current
    CollectMessages(vm.messages)
    var errorCount by remember { mutableIntStateOf(ErrorReporter.entries(context).size) }
    var showLog by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportSettings(uri, context) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importSettings(uri, context) }
    SettingsPage(stringResource(R.string.settings_advanced)) {
        SettingsGroup(stringResource(R.string.group_version)) {
            SettingRow(stringResource(R.string.app_version), fmt.digits(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") • " + BuildConfig.BUILD_TYPE))
            SettingRow(stringResource(R.string.db_version), fmt.number(YadinoDatabase.VERSION))
            SettingRow(stringResource(R.string.android_version), fmt.digits(Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"))
            SettingRow(stringResource(R.string.tech_info), stringResource(R.string.tech_info_desc))
        }
        SettingsGroup(stringResource(R.string.group_errors)) {
            SwitchSetting(stringResource(R.string.error_log), stringResource(R.string.error_log_desc), s.errorLogEnabled) { v -> vm.update { it.copy(errorLogEnabled = v) } }
            SettingRow(stringResource(R.string.error_log_view), stringResource(R.string.error_log_count, fmt.number(errorCount)), Icons.Default.BugReport, onClick = { showLog = true })
            SettingRow(stringResource(R.string.error_log_share), stringResource(R.string.error_log_share_desc), Icons.Default.Share, enabled = errorCount > 0, onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ErrorReporter.exportText(context)) }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
            })
            SettingRow(stringResource(R.string.error_log_clear), null, Icons.Default.Delete, enabled = errorCount > 0, onClick = { confirm = "log" })
        }
        SettingsGroup(stringResource(R.string.group_settings_file)) {
            SettingRow(stringResource(R.string.export_settings), stringResource(R.string.export_settings_desc), Icons.Default.FileUpload, onClick = { exportLauncher.launch("yadino-settings.json") })
            SettingRow(stringResource(R.string.import_settings), stringResource(R.string.import_settings_desc), Icons.Default.FileDownload, onClick = { confirm = "import" })
            SettingRow(stringResource(R.string.reset_settings), stringResource(R.string.reset_settings_desc), Icons.Default.RestartAlt, onClick = { confirm = "reset" })
        }
        SettingsGroup(stringResource(R.string.group_storage)) {
            SettingRow(stringResource(R.string.clear_cache), stringResource(R.string.clear_cache_desc), Icons.Default.CleaningServices, onClick = { confirm = "cache" })
        }
    }
    when (confirm) {
        "log" -> ConfirmDialog(stringResource(R.string.error_log_clear), stringResource(R.string.error_log_clear_confirm), stringResource(R.string.delete),
            onConfirm = { ErrorReporter.clear(context); errorCount = 0 }, onDismiss = { confirm = null }, destructive = true)
        "import" -> ConfirmDialog(stringResource(R.string.import_settings), stringResource(R.string.import_settings_confirm), stringResource(R.string.choose_file),
            onConfirm = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, onDismiss = { confirm = null })
        "reset" -> ConfirmDialog(stringResource(R.string.reset_settings), stringResource(R.string.reset_settings_confirm), stringResource(R.string.reset),
            onConfirm = { c.appScope.launch { c.settings.resetToDefaults() } }, onDismiss = { confirm = null })
        "cache" -> ConfirmDialog(stringResource(R.string.clear_cache), stringResource(R.string.clear_cache_confirm), stringResource(R.string.clear),
            onConfirm = { vm.clearCache(context) }, onDismiss = { confirm = null })
    }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.error_log_view)) },
            text = {
                val text = remember { ErrorReporter.entries(context).take(5).joinToString("\n\n") { it.readText() }.ifBlank { context.getString(R.string.error_log_empty) } }
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

// ================================================================================================
// Categories
// ================================================================================================
class CategoriesViewModel(c: AppContainer) : SettingsViewModel(c) {
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun save(cat: Category) = launchSafe { c.categories.save(cat); message(UiMessage.Text(R.string.msg_saved)) }
    fun delete(cat: Category) = launchSafe { c.categories.delete(cat.id) }
}

@Composable
fun CategoriesScreen() {
    val c = LocalContainer.current
    val vm: CategoriesViewModel = viewModel { CategoriesViewModel(c) }
    val cats by vm.categories.collectAsStateWithLifecycle()
    val dark = LocalIsDark.current
    CollectMessages(vm.messages)
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    SettingsPage(stringResource(R.string.categories)) {
        Text(stringResource(R.string.categories_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup(stringResource(R.string.categories)) {
            cats.forEach { cat ->
                SettingRow(cat.name, categoryKindLabel(cat.kind) + if (cat.isDefault) " • " + stringResource(R.string.default_label) else "", onClick = { editing = cat }, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(cat.color.accent(dark), 14.dp)
                        IconButton(onClick = { deleting = cat }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                })
            }
        }
        OutlinedButton(onClick = {
            val now = c.clock.now()
            editing = Category(Ids.newId(), "", NoteColor.GREEN, CategoryKind.GENERAL, false, cats.size, now, now)
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_category)) }
    }
    editing?.let { cat ->
        var name by remember(cat.id) { mutableStateOf(cat.name) }
        var color by remember(cat.id) { mutableStateOf(cat.color) }
        var kind by remember(cat.id) { mutableStateOf(cat.kind) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (cat.name.isEmpty()) R.string.new_category else R.string.edit_category)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    SingleChoiceSegmentedButtonRow {
                        CategoryKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, CategoryKind.entries.size)) { Text(categoryKindLabel(k), maxLines = 1) }
                        }
                    }
                    NoteColorPicker(color, { color = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.save(cat.copy(name = name, color = color, kind = kind)); editing = null }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { cat ->
        ConfirmDialog(stringResource(R.string.delete_category), stringResource(R.string.delete_category_message, cat.name), stringResource(R.string.delete),
            onConfirm = { vm.delete(cat) }, onDismiss = { deleting = null }, destructive = true)
    }
}

@Composable
fun categoryKindLabel(k: CategoryKind) = stringResource(
    when (k) {
        CategoryKind.GENERAL -> R.string.kind_general
        CategoryKind.TASK -> R.string.tab_tasks
        CategoryKind.NOTE -> R.string.tab_notes
        CategoryKind.GOAL -> R.string.goals
    },
)

// ================================================================================================
// About
// ================================================================================================
@Composable
fun AboutScreen() {
    val fmt = LocalFormatter.current
    SettingsPage(stringResource(R.string.about)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
            Text(stringResource(R.string.app_full_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.version_value, fmt.digits(BuildConfig.VERSION_NAME)), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
        SettingsGroup(stringResource(R.string.privacy)) {
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        SettingsGroup(stringResource(R.string.licenses)) {
            Text(stringResource(R.string.licenses_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        HorizontalDivider()
    }
}

@Suppress("unused")
private val unusedPalette = Icons.Default.Palette

@Suppress("unused")
private fun unusedAlarm(context: Context) = context.getSystemService(AlarmManager::class.java)

@Suppress("unused")
private val unusedWidgetUpdater = WidgetUpdater::class
