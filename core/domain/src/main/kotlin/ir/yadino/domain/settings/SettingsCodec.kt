package ir.yadino.domain.settings

import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.NumeralStyle
import ir.yadino.calendar.OccasionDisplay
import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonObject
import ir.yadino.domain.json.JsonString
import ir.yadino.domain.model.AnimationLevel
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.AutoBackupFrequency
import ir.yadino.domain.model.BackupReminderFrequency
import ir.yadino.domain.model.CalendarView
import ir.yadino.domain.model.DashboardConfig
import ir.yadino.domain.model.DashboardSection
import ir.yadino.domain.model.DashboardSectionConfig
import ir.yadino.domain.model.ListLayout
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.NoteSort
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.Palette
import ir.yadino.domain.model.ProgressStyle
import ir.yadino.domain.model.RecurringCompletionMode
import ir.yadino.domain.model.ShortcutAction
import ir.yadino.domain.model.StartScreen
import ir.yadino.domain.model.ThemeMode
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * Converts [AppSettings] to and from a flat string map. The same representation is used
 * by the DataStore, the settings export file and the backup. Decoding is lenient: unknown
 * keys are ignored and invalid values fall back to defaults, so a settings file written
 * by a newer or older version never breaks the app.
 */
object SettingsCodec {

    const val FORMAT = "yadino-settings"
    const val VERSION = 1

    private class Field(
        val key: String,
        val get: (AppSettings) -> String?,
        val set: (AppSettings, String) -> AppSettings,
    )

    private inline fun <reified E : Enum<E>> enumOf(value: String): E? =
        enumValues<E>().firstOrNull { it.name == value }

    private fun time(value: String): LocalTime? = try {
        val m = value.toInt()
        if (m in 0 until 24 * 60) LocalTime.of(m / 60, m % 60) else null
    } catch (e: NumberFormatException) {
        null
    }

    private fun LocalTime.enc(): String = (hour * 60 + minute).toString()

    private fun bool(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun days(value: String): Set<DayOfWeek>? {
        if (value.isBlank()) return emptySet()
        return value.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { v -> v in 1..7 }?.let(DayOfWeek::of) }.toSet()
    }

    private val fields: List<Field> = listOf(
        Field("general.startScreen", { it.startScreen.name }) { s, v -> enumOf<StartScreen>(v)?.let { s.copy(startScreen = it) } ?: s },
        Field("general.dateFormat", { it.dateFormat.name }) { s, v -> enumOf<DateFormatStyle>(v)?.let { s.copy(dateFormat = it) } ?: s },
        Field("general.use24Hour", { it.use24Hour.toString() }) { s, v -> bool(v)?.let { s.copy(use24Hour = it) } ?: s },
        Field("general.timeZoneId", { it.timeZoneId ?: "" }) { s, v ->
            when {
                v.isBlank() -> s.copy(timeZoneId = null)
                runCatching { ZoneId.of(v) }.isSuccess -> s.copy(timeZoneId = v)
                else -> s
            }
        },
        Field("general.firstDayOfWeek", { it.firstDayOfWeek.value.toString() }) { s, v ->
            v.toIntOrNull()?.takeIf { it in 1..7 }?.let { s.copy(firstDayOfWeek = DayOfWeek.of(it)) } ?: s
        },
        Field("general.numerals", { it.numerals.name }) { s, v -> enumOf<NumeralStyle>(v)?.let { s.copy(numerals = it) } ?: s },
        Field("general.fontScale", { it.fontScale.toString() }) { s, v ->
            v.toFloatOrNull()?.let { s.copy(fontScale = it.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)) } ?: s
        },
        Field("general.displayScale", { it.displayScale.toString() }) { s, v ->
            v.toFloatOrNull()?.let { s.copy(displayScale = it.coerceIn(AppSettings.MIN_DISPLAY_SCALE, AppSettings.MAX_DISPLAY_SCALE)) } ?: s
        },
        Field("general.themeMode", { it.themeMode.name }) { s, v -> enumOf<ThemeMode>(v)?.let { s.copy(themeMode = it) } ?: s },
        Field("general.palette", { it.palette.name }) { s, v -> enumOf<Palette>(v)?.let { s.copy(palette = it) } ?: s },
        Field("general.animationLevel", { it.animationLevel.name }) { s, v -> enumOf<AnimationLevel>(v)?.let { s.copy(animationLevel = it) } ?: s },
        Field("general.showGregorian", { it.showGregorian.toString() }) { s, v -> bool(v)?.let { s.copy(showGregorian = it) } ?: s },
        Field("general.showWeekNumbers", { it.showWeekNumbers.toString() }) { s, v -> bool(v)?.let { s.copy(showWeekNumbers = it) } ?: s },

        Field("calendar.defaultView", { it.defaultCalendarView.name }) { s, v -> enumOf<CalendarView>(v)?.let { s.copy(defaultCalendarView = it) } ?: s },
        Field("calendar.workDayStart", { it.workDayStart.enc() }) { s, v -> time(v)?.let { s.copy(workDayStart = it) } ?: s },
        Field("calendar.workDayEnd", { it.workDayEnd.enc() }) { s, v -> time(v)?.let { s.copy(workDayEnd = it) } ?: s },
        Field("calendar.workDays", { it.workDays.sortedBy { d -> d.value }.joinToString(",") { d -> d.value.toString() } }) { s, v ->
            days(v)?.let { s.copy(workDays = it) } ?: s
        },
        Field("calendar.defaultTaskTime", { it.defaultTaskTime?.enc() ?: "" }) { s, v ->
            if (v.isBlank()) s.copy(defaultTaskTime = null) else time(v)?.let { s.copy(defaultTaskTime = it) } ?: s
        },
        Field("calendar.defaultTaskDuration", { it.defaultTaskDurationMinutes.toString() }) { s, v ->
            v.toIntOrNull()?.let { s.copy(defaultTaskDurationMinutes = it.coerceIn(5, 24 * 60)) } ?: s
        },
        Field("calendar.defaultReminderOffset", { it.defaultReminderOffsetMinutes?.toString() ?: "" }) { s, v ->
            if (v.isBlank()) s.copy(defaultReminderOffsetMinutes = null)
            else v.toIntOrNull()?.let { s.copy(defaultReminderOffsetMinutes = it.coerceIn(0, 7 * 24 * 60)) } ?: s
        },
        Field("calendar.allDayReminderTime", { it.allDayReminderTime.enc() }) { s, v -> time(v)?.let { s.copy(allDayReminderTime = it) } ?: s },
        Field("calendar.recurringCompletion", { it.recurringCompletion.name }) { s, v ->
            enumOf<RecurringCompletionMode>(v)?.let { s.copy(recurringCompletion = it) } ?: s
        },
        Field("calendar.showFutureOccurrences", { it.showFutureOccurrences.toString() }) { s, v -> bool(v)?.let { s.copy(showFutureOccurrences = it) } ?: s },
        Field("calendar.overdueBehavior", { it.overdueBehavior.name }) { s, v -> enumOf<OverdueBehavior>(v)?.let { s.copy(overdueBehavior = it) } ?: s },
        Field("calendar.occasionDisplay", { it.occasionDisplay.name }) { s, v -> enumOf<OccasionDisplay>(v)?.let { s.copy(occasionDisplay = it) } ?: s },
        Field("calendar.lunarAdjustment", { it.lunarAdjustment.toString() }) { s, v ->
            v.toIntOrNull()?.let { s.copy(lunarAdjustment = it.coerceIn(-2, 2)) } ?: s
        },

        Field("notifications.enabled", { it.notificationsEnabled.toString() }) { s, v -> bool(v)?.let { s.copy(notificationsEnabled = it) } ?: s },
        Field("notifications.tasks", { it.taskReminders.toString() }) { s, v -> bool(v)?.let { s.copy(taskReminders = it) } ?: s },
        Field("notifications.habits", { it.habitReminders.toString() }) { s, v -> bool(v)?.let { s.copy(habitReminders = it) } ?: s },
        Field("notifications.goals", { it.goalReminders.toString() }) { s, v -> bool(v)?.let { s.copy(goalReminders = it) } ?: s },
        Field("notifications.backup", { it.backupNotifications.toString() }) { s, v -> bool(v)?.let { s.copy(backupNotifications = it) } ?: s },
        Field("notifications.dailySummary", { it.dailySummary.toString() }) { s, v -> bool(v)?.let { s.copy(dailySummary = it) } ?: s },
        Field("notifications.dailySummaryTime", { it.dailySummaryTime.enc() }) { s, v -> time(v)?.let { s.copy(dailySummaryTime = it) } ?: s },
        Field("notifications.goalDaysBefore", { it.goalDeadlineDaysBefore.toString() }) { s, v ->
            v.toIntOrNull()?.let { s.copy(goalDeadlineDaysBefore = it.coerceIn(0, 30)) } ?: s
        },
        Field("notifications.snoozeMinutes", { it.snoozeMinutes.toString() }) { s, v ->
            v.toIntOrNull()?.let { s.copy(snoozeMinutes = it.coerceIn(1, 240)) } ?: s
        },
        Field("notifications.repeatCount", { it.reminderRepeatCount.toString() }) { s, v ->
            v.toIntOrNull()?.let { s.copy(reminderRepeatCount = it.coerceIn(0, 5)) } ?: s
        },
        Field("notifications.quietEnabled", { it.quietHoursEnabled.toString() }) { s, v -> bool(v)?.let { s.copy(quietHoursEnabled = it) } ?: s },
        Field("notifications.quietStart", { it.quietHoursStart.enc() }) { s, v -> time(v)?.let { s.copy(quietHoursStart = it) } ?: s },
        Field("notifications.quietEnd", { it.quietHoursEnd.enc() }) { s, v -> time(v)?.let { s.copy(quietHoursEnd = it) } ?: s },

        Field("notes.autosave", { it.noteAutosave.toString() }) { s, v -> bool(v)?.let { s.copy(noteAutosave = it) } ?: s },
        Field("notes.autosaveDelay", { it.noteAutosaveDelayMs.toString() }) { s, v ->
            v.toLongOrNull()?.let { s.copy(noteAutosaveDelayMs = it.coerceIn(300, 10_000)) } ?: s
        },
        Field("notes.defaultColor", { it.defaultNoteColor.name }) { s, v -> enumOf<NoteColor>(v)?.let { s.copy(defaultNoteColor = it) } ?: s },
        Field("notes.sort", { it.noteSort.name }) { s, v -> enumOf<NoteSort>(v)?.let { s.copy(noteSort = it) } ?: s },
        Field("notes.layout", { it.noteLayout.name }) { s, v -> enumOf<ListLayout>(v)?.let { s.copy(noteLayout = it) } ?: s },
        Field("goals.layout", { it.goalLayout.name }) { s, v -> enumOf<ListLayout>(v)?.let { s.copy(goalLayout = it) } ?: s },
        Field("goals.progressStyle", { it.goalProgressStyle.name }) { s, v -> enumOf<ProgressStyle>(v)?.let { s.copy(goalProgressStyle = it) } ?: s },
        Field("goals.showCompleted", { it.showCompletedGoals.toString() }) { s, v -> bool(v)?.let { s.copy(showCompletedGoals = it) } ?: s },

        Field("backup.reminder", { it.backupReminder.name }) { s, v -> enumOf<BackupReminderFrequency>(v)?.let { s.copy(backupReminder = it) } ?: s },
        Field("backup.autoDrive", { it.autoDriveBackup.name }) { s, v -> enumOf<AutoBackupFrequency>(v)?.let { s.copy(autoDriveBackup = it) } ?: s },
        Field("backup.unmeteredOnly", { it.driveBackupUnmeteredOnly.toString() }) { s, v -> bool(v)?.let { s.copy(driveBackupUnmeteredOnly = it) } ?: s },
        Field("backup.keep", { it.driveBackupsToKeep.toString() }) { s, v ->
            v.toIntOrNull()?.let { s.copy(driveBackupsToKeep = it.coerceIn(1, 50)) } ?: s
        },
        Field("advanced.errorLog", { it.errorLogEnabled.toString() }) { s, v -> bool(v)?.let { s.copy(errorLogEnabled = it) } ?: s },
    )

    /** All keys known by this version. */
    val keys: List<String> get() = fields.map { it.key }

    fun encode(settings: AppSettings): Map<String, String> =
        fields.associate { it.key to (it.get(settings) ?: "") }

    fun decode(values: Map<String, String>, base: AppSettings = AppSettings()): AppSettings {
        var s = base
        for (f in fields) {
            val v = values[f.key] ?: continue
            s = f.set(s, v)
        }
        return s
    }

    /** Settings export file (JSON). */
    fun toJson(settings: AppSettings, dashboard: DashboardConfig): String {
        val body = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "settings" to encode(settings),
            "dashboard" to DashboardCodec.encode(dashboard),
        )
        return Json.stringify(Json.toJsonValue(body), pretty = true)
    }

    class ImportResult(val settings: AppSettings, val dashboard: DashboardConfig?, val ignoredKeys: Int)

    /** Parses a settings export file. Throws [IllegalArgumentException] on invalid files. */
    fun fromJson(text: String): ImportResult {
        val root = try {
            Json.parse(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("not a JSON object")
        require(root.string("format") == FORMAT) { "not a Yadino settings file" }
        val version = root.int("version") ?: 0
        require(version in 1..VERSION) { "unsupported settings version $version" }
        val map = root.obj("settings")?.members?.mapNotNull { (k, v) -> (v as? JsonString)?.let { k to it.value } }?.toMap()
            ?: throw IllegalArgumentException("missing settings")
        val known = keys.toSet()
        val dashboard = root.string("dashboard")?.let { DashboardCodec.decode(it) }
        return ImportResult(decode(map), dashboard, map.keys.count { it !in known })
    }
}

/** Compact string encoding of the dashboard layout. */
object DashboardCodec {
    /** Format: `SECTION:1:6|SECTION:0:4;SHORTCUT,SHORTCUT` */
    fun encode(config: DashboardConfig): String =
        config.sections.joinToString("|") { "${it.section.name}:${if (it.visible) 1 else 0}:${it.itemCount}" } +
            ";" + config.shortcuts.joinToString(",") { it.name }

    fun decode(text: String?): DashboardConfig {
        if (text.isNullOrBlank()) return DashboardConfig.DEFAULT
        return try {
            val (sectionsPart, shortcutsPart) = (text.split(';') + "").let { it[0] to it[1] }
            val sections = sectionsPart.split('|').filter { it.isNotBlank() }.mapNotNull { token ->
                val p = token.split(':')
                val section = DashboardSection.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return@mapNotNull null
                DashboardSectionConfig(section, p.getOrNull(1) == "1", p.getOrNull(2)?.toIntOrNull() ?: section.defaultCount)
            }
            if (sections.isEmpty()) return DashboardConfig.DEFAULT
            val shortcuts = shortcutsPart.split(',').mapNotNull { n -> ShortcutAction.entries.firstOrNull { it.name == n.trim() } }
            DashboardConfig(sections, shortcuts).normalized()
        } catch (e: Exception) {
            DashboardConfig.DEFAULT
        }
    }
}
