package ir.yadino.domain.model

import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.NumeralStyle
import ir.yadino.calendar.OccasionDisplay
import java.time.DayOfWeek
import java.time.LocalTime

enum class StartScreen { DASHBOARD, CALENDAR, TASKS, NOTES, GOALS }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Palette { YADINO, OCEAN, ROSE, AMETHYST, DYNAMIC }
enum class AnimationLevel { FULL, REDUCED, OFF }
enum class CalendarView { MONTH, WEEK, DAY }

/** What happens when an occurrence of a recurring task is completed. */
enum class RecurringCompletionMode {
    /** The task moves to its next occurrence; history is kept in the completion log. */
    MOVE_TO_NEXT,

    /** A completed copy is kept in the done list and the task moves to the next occurrence. */
    KEEP_COMPLETED_COPY,
}

/** How unfinished tasks from previous days are handled. */
enum class OverdueBehavior {
    /** Show them in the "today" plan and on the dashboard (default). */
    SHOW_IN_TODAY,

    /** Show them only in the "overdue" list. */
    SEPARATE_LIST,

    /** Automatically move them to today at the start of each day. */
    MOVE_TO_TODAY,
}

enum class NoteSort { UPDATED_DESC, CREATED_DESC, TITLE_ASC, COLOR }
enum class ListLayout { GRID, LIST }
enum class ProgressStyle { PERCENT, FRACTION }
enum class BackupReminderFrequency { OFF, WEEKLY, MONTHLY }
enum class AutoBackupFrequency { OFF, DAILY, WEEKLY }

/**
 * All user settings with their defaults. Every field is consumed somewhere in the app;
 * see docs/ARCHITECTURE.md (settings table) for where each one takes effect.
 */
data class AppSettings(
    // --- general -------------------------------------------------------------------
    val startScreen: StartScreen = StartScreen.DASHBOARD,
    val dateFormat: DateFormatStyle = DateFormatStyle.FULL,
    val use24Hour: Boolean = true,
    /** Null follows the device time zone (including DST changes). */
    val timeZoneId: String? = null,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.SATURDAY,
    val numerals: NumeralStyle = NumeralStyle.PERSIAN,
    /** Text size multiplier (0.85..1.40). */
    val fontScale: Float = 1.0f,
    /** Overall UI scale multiplier (0.85..1.20). */
    val displayScale: Float = 1.0f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.YADINO,
    val animationLevel: AnimationLevel = AnimationLevel.FULL,
    val showGregorian: Boolean = true,
    val showWeekNumbers: Boolean = false,
    // --- calendar & planning ---------------------------------------------------------
    val defaultCalendarView: CalendarView = CalendarView.MONTH,
    val workDayStart: LocalTime = LocalTime.of(8, 0),
    val workDayEnd: LocalTime = LocalTime.of(17, 0),
    val workDays: Set<DayOfWeek> = DEFAULT_WORK_DAYS,
    /** Default start time for new timed tasks; null creates all-day tasks by default. */
    val defaultTaskTime: LocalTime? = LocalTime.of(9, 0),
    val defaultTaskDurationMinutes: Int = 60,
    /** Default reminder offset for new tasks; null = no reminder by default. */
    val defaultReminderOffsetMinutes: Int? = 15,
    /** Time of day used for reminders of tasks without a time. */
    val allDayReminderTime: LocalTime = LocalTime.of(9, 0),
    val recurringCompletion: RecurringCompletionMode = RecurringCompletionMode.MOVE_TO_NEXT,
    val showFutureOccurrences: Boolean = true,
    val overdueBehavior: OverdueBehavior = OverdueBehavior.SHOW_IN_TODAY,
    val occasionDisplay: OccasionDisplay = OccasionDisplay.ALL,
    /** Correction applied to approximate lunar occasions (-2..2 days). */
    val lunarAdjustment: Int = 0,
    // --- notifications -------------------------------------------------------------------
    val notificationsEnabled: Boolean = true,
    val taskReminders: Boolean = true,
    val habitReminders: Boolean = true,
    val goalReminders: Boolean = true,
    val backupNotifications: Boolean = true,
    val dailySummary: Boolean = false,
    val dailySummaryTime: LocalTime = LocalTime.of(8, 0),
    val goalDeadlineDaysBefore: Int = 3,
    val snoozeMinutes: Int = 10,
    /** Repeat an unanswered reminder this many times (every [snoozeMinutes]). */
    val reminderRepeatCount: Int = 0,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: LocalTime = LocalTime.of(23, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(7, 0),
    // --- notes & goals ----------------------------------------------------------------------
    val noteAutosave: Boolean = true,
    val noteAutosaveDelayMs: Long = 800,
    val defaultNoteColor: NoteColor = NoteColor.DEFAULT,
    val noteSort: NoteSort = NoteSort.UPDATED_DESC,
    val noteLayout: ListLayout = ListLayout.GRID,
    val goalLayout: ListLayout = ListLayout.LIST,
    val goalProgressStyle: ProgressStyle = ProgressStyle.PERCENT,
    val showCompletedGoals: Boolean = true,
    // --- data & backup ----------------------------------------------------------------------
    val backupReminder: BackupReminderFrequency = BackupReminderFrequency.MONTHLY,
    val autoDriveBackup: AutoBackupFrequency = AutoBackupFrequency.OFF,
    val driveBackupUnmeteredOnly: Boolean = true,
    val driveBackupsToKeep: Int = 10,
    // --- advanced -----------------------------------------------------------------------------
    val errorLogEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT_WORK_DAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        )
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.40f
        const val MIN_DISPLAY_SCALE = 0.85f
        const val MAX_DISPLAY_SCALE = 1.20f
    }
}

/** Dashboard sections that can be shown, hidden and reordered. */
enum class DashboardSection(val defaultCount: Int) {
    TODAY_PLAN(6),
    IMPORTANT_TASKS(4),
    OVERDUE(4),
    HABITS(5),
    GOALS(3),
    RECENT_NOTES(4),
    PINNED_NOTES(4),
    SHORTCUTS(0),
    UPCOMING(4),
}

/** Quick actions available as dashboard shortcuts. */
enum class ShortcutAction { NEW_TASK, NEW_NOTE, NEW_EVENT, NEW_HABIT, NEW_GOAL, OPEN_CALENDAR, OPEN_REPORTS, OPEN_SEARCH, BACKUP_NOW }

data class DashboardSectionConfig(val section: DashboardSection, val visible: Boolean, val itemCount: Int)

data class DashboardConfig(
    val sections: List<DashboardSectionConfig>,
    val shortcuts: List<ShortcutAction>,
) {
    companion object {
        val DEFAULT = DashboardConfig(
            sections = listOf(
                DashboardSectionConfig(DashboardSection.SHORTCUTS, true, 0),
                DashboardSectionConfig(DashboardSection.TODAY_PLAN, true, DashboardSection.TODAY_PLAN.defaultCount),
                DashboardSectionConfig(DashboardSection.OVERDUE, true, DashboardSection.OVERDUE.defaultCount),
                DashboardSectionConfig(DashboardSection.IMPORTANT_TASKS, true, DashboardSection.IMPORTANT_TASKS.defaultCount),
                DashboardSectionConfig(DashboardSection.HABITS, true, DashboardSection.HABITS.defaultCount),
                DashboardSectionConfig(DashboardSection.GOALS, true, DashboardSection.GOALS.defaultCount),
                DashboardSectionConfig(DashboardSection.PINNED_NOTES, true, DashboardSection.PINNED_NOTES.defaultCount),
                DashboardSectionConfig(DashboardSection.RECENT_NOTES, true, DashboardSection.RECENT_NOTES.defaultCount),
                DashboardSectionConfig(DashboardSection.UPCOMING, false, DashboardSection.UPCOMING.defaultCount),
            ),
            shortcuts = listOf(ShortcutAction.NEW_TASK, ShortcutAction.NEW_NOTE, ShortcutAction.NEW_EVENT, ShortcutAction.OPEN_CALENDAR),
        )
    }

    /** Ensures every section appears exactly once (new sections added in updates are appended). */
    fun normalized(): DashboardConfig {
        val seen = LinkedHashMap<DashboardSection, DashboardSectionConfig>()
        sections.forEach { if (it.section !in seen) seen[it.section] = it.copy(itemCount = it.itemCount.coerceIn(0, 20)) }
        DEFAULT.sections.forEach { if (it.section !in seen) seen[it.section] = it.copy(visible = false) }
        return copy(sections = seen.values.toList(), shortcuts = shortcuts.distinct().take(8))
    }

    fun move(section: DashboardSection, delta: Int): DashboardConfig {
        val list = sections.toMutableList()
        val index = list.indexOfFirst { it.section == section }
        val target = index + delta
        if (index < 0 || target !in list.indices) return this
        val item = list.removeAt(index)
        list.add(target, item)
        return copy(sections = list)
    }
}
