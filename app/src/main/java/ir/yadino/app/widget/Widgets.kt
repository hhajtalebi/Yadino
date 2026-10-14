package ir.yadino.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.core.Formatter
import ir.yadino.app.data.settings.WidgetConfig
import ir.yadino.app.notifications.runAsync
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.quicknote.QuickNoteActivity
import ir.yadino.calendar.CalendarFormatter
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.IranianOccasions
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.goals.GoalProgressCalculator
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.planning.TaskListFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private fun AppSettings.isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.FRIDAY

// =============================================================================================
// 1. Jalali calendar widget (month grid with markers)
// =============================================================================================
class CalendarWidgetProvider : YadinoWidgetProvider() {
    private class Ids(private val context: Context) {
        private fun id(name: String): Int = context.resources.getIdentifier(name, "id", context.packageName)
        val cells = IntArray(42) { id("cal_cell_$it") }
        val days = IntArray(42) { id("cal_day_$it") }
        val dots = IntArray(42) { id("cal_dot_$it") }
        val heads = IntArray(7) { id("cal_head_$it") }
    }

    companion object {
        @Volatile
        private var ids: Ids? = null
        private fun ids(context: Context): Ids = ids ?: Ids(context.applicationContext).also { ids = it }
    }

    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val ids = ids(context)
        val CELL_IDS = ids.cells
        val DAY_IDS = ids.days
        val DOT_IDS = ids.dots
        val HEAD_IDS = ids.heads
        val c = app.container
        val settings = c.settings.awaitLoaded()
        val config = c.widgetConfigs.get(widgetId)
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val today = c.clock.today()
        val size = widgetSize(options, mediumMinWidth = 220, largeMinHeight = 200)

        if (size == WidgetSize.SMALL) {
            val v = RemoteViews(context.packageName, R.layout.widget_calendar_small)
            v.applyBackground(p)
            val j = JalaliDate.from(today)
            v.text(R.id.w_day, fmt.number(j.day), if (IranianOccasions.isHoliday(j, settings.lunarAdjustment) || settings.isWeekend(today)) p.holiday else p.accent, 40f, s, p)
            v.text(R.id.w_weekday, PersianNames.weekday(today.dayOfWeek), p.text, 14f, s, p)
            v.text(R.id.w_month, fmt.monthYear(j), p.secondary, 12f, s, p)
            val occ = IranianOccasions.occasionsOf(j, settings.occasionDisplay, settings.lunarAdjustment).firstOrNull()
            v.text(R.id.w_occasion, occ?.title ?: if (config.showGregorian) fmt.gregorian(today) else "", p.secondary, 11f, s, p)
            v.setOnClickPendingIntent(R.id.widget_root, openApp(context, Route.Calendar(today.toEpochDay()), widgetId * 100))
            return v
        }

        val v = RemoteViews(context.packageName, R.layout.widget_calendar_month)
        v.applyBackground(p)
        val month = JalaliDate.from(today).firstDayOfMonth().plusMonths(config.monthOffset.toLong())
        v.text(R.id.cal_title, fmt.monthYear(month), p.text, 15f, s, p)
        val gregLabel = if (config.showGregorian) {
            val a = month.toLocalDate()
            val b = month.lastDayOfMonth().toLocalDate()
            PersianNames.gregorianMonth(a.monthValue) + (if (a.monthValue != b.monthValue) " – " + PersianNames.gregorianMonth(b.monthValue) else "") +
                " " + fmt.number(b.year)
        } else ""
        v.text(R.id.cal_subtitle, gregLabel, p.secondary, 11f, s, p)
        v.color(R.id.cal_prev, "setColorFilter", p.text, p)
        v.color(R.id.cal_next, "setColorFilter", p.text, p)
        v.setOnClickPendingIntent(R.id.cal_prev, widgetAction(context, WidgetActionReceiver.ACTION_CAL_PREV, widgetId, widgetId * 100 + 90))
        v.setOnClickPendingIntent(R.id.cal_next, widgetAction(context, WidgetActionReceiver.ACTION_CAL_NEXT, widgetId, widgetId * 100 + 91))
        v.setOnClickPendingIntent(R.id.cal_title, widgetAction(context, WidgetActionReceiver.ACTION_CAL_TODAY, widgetId, widgetId * 100 + 92))

        val days = WeekMath.orderedDays(settings.firstDayOfWeek)
        days.forEachIndexed { i, d ->
            v.text(HEAD_IDS[i], PersianNames.weekdayShort(d), if (d == DayOfWeek.FRIDAY) p.holiday else p.secondary, 11f, s, p)
        }
        val grid = WeekMath.monthGrid(month, settings.firstDayOfWeek)
        val from = grid.first().date.toLocalDate()
        val to = grid.last().date.toLocalDate()
        val tasks = c.tasks.getForRange(from, to)
        val plan = TaskFilters.planForRange(tasks, from, to, settings.showFutureOccurrences, settings.firstDayOfWeek)
        val rows = WeekMath.rowsNeeded(month, settings.firstDayOfWeek)
        grid.forEachIndexed { i, cell ->
            val date = cell.date.toLocalDate()
            val row = i / 7
            if (row >= rows && size != WidgetSize.LARGE) {
                v.setViewVisibility(CELL_IDS[i], View.GONE)
                return@forEachIndexed
            }
            v.setViewVisibility(CELL_IDS[i], View.VISIBLE)
            val holiday = settings.isWeekend(date) || IranianOccasions.isHoliday(cell.date, settings.lunarAdjustment)
            val base = if (holiday) p.holiday else p.text
            val color = if (cell.inMonth) base else (base and 0x00FFFFFF) or 0x55000000
            v.text(DAY_IDS[i], fmt.number(cell.date.day), color, 13f, s, p)
            v.setInt(CELL_IDS[i], "setBackgroundResource", if (date == today) R.drawable.widget_today_bg else 0)
            val items = plan[date].orEmpty()
            val hasDeadline = tasks.any { it.dueDate == date && it.isOpen }
            v.setTextViewText(DOT_IDS[i], if (items.isNotEmpty() || hasDeadline) "•" else " ")
            v.color(DOT_IDS[i], "setTextColor", if (items.any { it.task.priority.level >= 2 } || hasDeadline) p.holiday else p.accent, p)
            v.setOnClickPendingIntent(CELL_IDS[i], openApp(context, Route.Calendar(date.toEpochDay()), widgetId * 100 + i))
        }
        return v
    }
}

// =============================================================================================
// 2 & 3. Today's schedule and task list widgets (collections)
// =============================================================================================
abstract class TaskCollectionWidget : ListWidgetProvider() {
    abstract val defaultFilter: String
    abstract fun title(context: Context, filter: String): String

    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val c = app.container
        val settings = c.settings.awaitLoaded()
        val config = c.widgetConfigs.get(widgetId)
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val today = c.clock.today()
        val filter = config.content.ifBlank { defaultFilter }
        val items = TaskWidgetData.load(app, filter, today, settings)
        val size = widgetSize(options)
        val open = openApp(context, if (filter == "TODAY") Route.Dashboard else Route.Tasks(filter), widgetId * 100 + 1)
        val add = openApp(context, Route.TaskEditor(date = if (filter == "TODAY") today.toEpochDay() else null), widgetId * 100 + 2)

        if (size == WidgetSize.SMALL) {
            val v = RemoteViews(context.packageName, R.layout.widget_list_small)
            v.applyBackground(p)
            val open2 = items.count { it.isOpen && it.isCompletable }
            v.text(R.id.w_count, fmt.number(open2), p.accent, 34f, s, p)
            v.text(R.id.w_title, title(context, filter), p.text, 13f, s, p)
            val next = items.firstOrNull { it.isOpen }
            v.text(R.id.w_next, next?.let { t -> (t.startTime?.let { fmt.time(it) + " • " } ?: "") + t.title } ?: context.getString(R.string.widget_all_done), p.secondary, 12f, s, p)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        val v = RemoteViews(context.packageName, R.layout.widget_list)
        v.applyBackground(p)
        v.text(R.id.w_title, title(context, filter), p.text, 15f, s, p)
        v.text(R.id.w_subtitle, fmt.date(today, DateFormatStyle.MEDIUM), p.secondary, 11f, s, p)
        v.text(R.id.w_empty, context.getString(R.string.widget_empty_tasks), p.secondary, 13f, s, p)
        v.color(R.id.w_add, "setColorFilter", p.accent, p)
        v.setOnClickPendingIntent(R.id.w_add, add)
        v.setOnClickPendingIntent(R.id.w_header, open)
        val serviceIntent = Intent(context, TaskListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("yadino-widget://list/$widgetId/$filter")
        }
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.widget_list, serviceIntent)
        v.setEmptyView(R.id.widget_list, R.id.w_empty)
        // Item clicks go through a tiny trampoline activity (toggle / open)
        val template = Intent(context, WidgetItemActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        v.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(context, widgetId * 100 + 3, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        )
        return v
    }
}

class TodayWidgetProvider : TaskCollectionWidget() {
    override val defaultFilter = "TODAY"
    override fun title(context: Context, filter: String) = context.getString(R.string.widget_today_title)
}

class TasksWidgetProvider : TaskCollectionWidget() {
    override val defaultFilter = "TODAY"
    override fun title(context: Context, filter: String) = when (filter) {
        "UPCOMING" -> context.getString(R.string.filter_upcoming)
        "OVERDUE" -> context.getString(R.string.filter_overdue)
        "IMPORTANT" -> context.getString(R.string.widget_filter_important)
        "ALL" -> context.getString(R.string.widget_filter_all_open)
        else -> context.getString(R.string.filter_today)
    }
}

/** Shared data loading for task widgets. */
object TaskWidgetData {
    suspend fun load(app: YadinoApplication, filter: String, today: LocalDate, settings: AppSettings): List<Task> {
        val all = app.container.tasks.getAll()
        val list = when (filter) {
            "UPCOMING" -> TaskFilters.filter(all, TaskListFilter.UPCOMING, today, settings.overdueBehavior)
            "OVERDUE" -> TaskFilters.filter(all, TaskListFilter.OVERDUE, today, settings.overdueBehavior)
            "IMPORTANT" -> TaskFilters.important(all, today)
            "ALL" -> TaskFilters.sortForPlan(all.filter { !it.isArchived && it.isOpen })
            else -> TaskFilters.filter(all, TaskListFilter.TODAY, today, settings.overdueBehavior, app.container.clock.nowTime())
        }
        return list.take(50)
    }
}

class TaskListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TaskListFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private class TaskListFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<Task> = emptyList()
    private var settings = AppSettings()
    private var config = WidgetConfig()
    private var today: LocalDate = LocalDate.now()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val app = context.applicationContext as YadinoApplication
        runBlocking {
            settings = app.container.settings.awaitLoaded()
            config = app.container.widgetConfigs.get(widgetId)
            today = app.container.clock.today()
            val manager = AppWidgetManager.getInstance(context)
            val info = manager.getAppWidgetInfo(widgetId)
            val default = if (info?.provider?.className == TasksWidgetProvider::class.java.name) "TODAY" else "TODAY"
            items = TaskWidgetData.load(app, config.content.ifBlank { default }, today, settings)
        }
    }

    override fun onDestroy() = Unit
    override fun getCount(): Int = items.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews {
        val t = items.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_list_item)
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val v = RemoteViews(context.packageName, R.layout.widget_list_item)
        v.text(R.id.item_title, t.title, if (t.isDone) p.secondary else p.text, 14f, s, p)
        val meta = buildList {
            val d = TaskFilters.effectiveDate(t)
            if (d != null && d != today) add(fmt.relativeDay(d, today))
            fmt.timeRange(t.startTime, t.endTime)?.let { add(it) }
            if (TaskFilters.isOverdue(t, today)) add(context.getString(R.string.overdue_short))
            if (t.kind == TaskKind.EVENT) add(context.getString(R.string.kind_event))
        }.joinToString(" • ")
        v.text(R.id.item_meta, meta, if (TaskFilters.isOverdue(t, today)) p.holiday else p.secondary, 11f, s, p)
        v.setViewVisibility(R.id.item_meta, if (meta.isEmpty()) View.GONE else View.VISIBLE)
        if (t.isCompletable) {
            v.setViewVisibility(R.id.item_check, View.VISIBLE)
            v.setImageViewResource(R.id.item_check, if (t.isDone) R.drawable.ic_widget_checked else R.drawable.ic_widget_unchecked)
            v.color(R.id.item_check, "setColorFilter", if (t.priority.level >= 2) p.holiday else p.accent, p)
            v.setOnClickFillInIntent(R.id.item_check, Intent().apply {
                putExtra(WidgetItemActivity.EXTRA_KIND, WidgetItemActivity.KIND_TOGGLE)
                putExtra(WidgetItemActivity.EXTRA_TASK, t.id)
            })
        } else {
            v.setViewVisibility(R.id.item_check, View.INVISIBLE)
        }
        v.setOnClickFillInIntent(R.id.item_body, Intent().apply {
            putExtra(WidgetItemActivity.EXTRA_KIND, WidgetItemActivity.KIND_OPEN)
            putExtra(WidgetItemActivity.EXTRA_TASK, t.id)
        })
        return v
    }
}

// =============================================================================================
// 4. Quick note widget
// =============================================================================================
class QuickNoteWidgetProvider : YadinoWidgetProvider() {
    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val c = app.container
        val config = c.widgetConfigs.get(widgetId)
        val settings = c.settings.awaitLoaded()
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val note = if (config.content.isNotBlank()) c.notes.get(config.content) else null
        val shown = note?.takeIf { !it.isArchived } ?: c.notes.getRecent(1).firstOrNull()
        val size = widgetSize(options)
        val v = RemoteViews(context.packageName, if (size == WidgetSize.SMALL) R.layout.widget_note_small else R.layout.widget_note)
        v.applyBackground(p)
        val newNote = PendingIntent.getActivity(
            context, widgetId * 100 + 1,
            Intent(context, QuickNoteActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.w_add, newNote)
        v.color(R.id.w_add, "setColorFilter", p.accent, p)
        if (shown == null) {
            v.text(R.id.w_title, context.getString(R.string.widget_note_empty_title), p.text, 15f, s, p)
            if (size != WidgetSize.SMALL) v.text(R.id.w_content, context.getString(R.string.widget_note_empty_body), p.secondary, 13f, s, p)
            v.setOnClickPendingIntent(R.id.w_body, newNote)
        } else {
            v.text(R.id.w_title, NoteFilters.displayTitle(shown).ifBlank { context.getString(R.string.untitled_note) }, p.text, 15f, s, p)
            if (size != WidgetSize.SMALL) {
                v.text(R.id.w_content, shown.content.take(600), p.secondary, 13f, s, p)
                v.text(R.id.w_meta, fmt.relativeDay(shown.updatedAt.atZone(c.clock.zone()).toLocalDate(), c.clock.today()), p.secondary, 10f, s, p)
            }
            v.setOnClickPendingIntent(R.id.w_body, openApp(context, Route.NoteEditor(shown.id), widgetId * 100 + 2))
        }
        return v
    }
}

// =============================================================================================
// 5. Goal progress widget
// =============================================================================================
class GoalWidgetProvider : YadinoWidgetProvider() {
    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val c = app.container
        val config = c.widgetConfigs.get(widgetId)
        val settings = c.settings.awaitLoaded()
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val today = c.clock.today()
        val goals = c.goals.getAll().filter { !it.isArchived && it.status != GoalStatus.COMPLETED }
        val goal = goals.firstOrNull { it.id == config.content }
            ?: goals.sortedWith(compareByDescending<ir.yadino.domain.model.Goal> { it.priority.level }.thenBy { it.deadline ?: LocalDate.MAX }).firstOrNull()
        val size = widgetSize(options)
        val v = RemoteViews(context.packageName, if (size == WidgetSize.SMALL) R.layout.widget_goal_small else R.layout.widget_goal)
        v.applyBackground(p)
        if (goal == null) {
            v.text(R.id.w_title, context.getString(R.string.widget_goal_empty), p.text, 14f, s, p)
            v.text(R.id.w_percent, "", p.accent, 26f, s, p)
            v.setProgressBar(R.id.w_progress, 100, 0, false)
            if (size != WidgetSize.SMALL) {
                v.text(R.id.w_meta, context.getString(R.string.widget_goal_empty_hint), p.secondary, 12f, s, p)
                v.text(R.id.w_milestones, "", p.secondary, 12f, s, p)
            }
            v.setOnClickPendingIntent(R.id.widget_root, openApp(context, Route.GoalEditor(), widgetId * 100 + 1))
            return v
        }
        val milestones = c.goals.getMilestones(goal.id)
        val tasks = c.tasks.getAll().filter { it.goalId == goal.id }
        val habits = c.habits.getAll().filter { it.goalId == goal.id && !it.isArchived }
        val logs = c.habits.getLogsSince(today.minusDays(30))
        val rates = habits.map { h -> ir.yadino.domain.habits.HabitStats.summarize(h, logs, today, settings.firstDayOfWeek).rate30 }
        val progress = GoalProgressCalculator.calculate(goal, milestones, tasks, rates, today)
        v.text(R.id.w_title, goal.title, p.text, 14f, s, p)
        v.text(R.id.w_percent, fmt.percent(progress.percent), p.accent, 26f, s, p)
        v.setProgressBar(R.id.w_progress, 100, progress.percent, false)
        if (size != WidgetSize.SMALL) {
            val meta = buildList {
                progress.daysRemaining?.let { add(fmt.daysLeft(it)) }
                if (progress.unitsTotal > 0) add(context.getString(R.string.widget_goal_units, fmt.number(progress.unitsDone), fmt.number(progress.unitsTotal)))
                if (goal.status == GoalStatus.PAUSED) add(context.getString(R.string.goal_status_paused))
            }.joinToString(" • ")
            v.text(R.id.w_meta, meta, p.secondary, 12f, s, p)
            val next = milestones.filter { !it.isDone }.take(if (size == WidgetSize.LARGE) 4 else 2)
            v.text(R.id.w_milestones, next.joinToString("\n") { "○ " + it.title }, p.text, 12f, s, p)
        }
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, Route.GoalDetail(goal.id), widgetId * 100 + 1))
        return v
    }
}

// =============================================================================================
// 6. Date & clock widget
// =============================================================================================
class ClockWidgetProvider : YadinoWidgetProvider() {
    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val c = app.container
        val config = c.widgetConfigs.get(widgetId)
        val settings = c.settings.awaitLoaded()
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val today = c.clock.today()
        val j = JalaliDate.from(today)
        val size = widgetSize(options)
        val v = RemoteViews(context.packageName, if (size == WidgetSize.SMALL) R.layout.widget_clock_small else R.layout.widget_clock)
        v.applyBackground(p)
        val pattern = if (settings.use24Hour) "HH:mm" else "h:mm"
        v.setCharSequence(R.id.w_clock, "setFormat24Hour", pattern)
        v.setCharSequence(R.id.w_clock, "setFormat12Hour", pattern)
        settings.timeZoneId?.let { v.setString(R.id.w_clock, "setTimeZone", it) }
        v.color(R.id.w_clock, "setTextColor", p.accent, p)
        v.setTextViewTextSize(R.id.w_clock, android.util.TypedValue.COMPLEX_UNIT_SP, (if (size == WidgetSize.SMALL) 34f else 46f) * s)
        val holiday = IranianOccasions.isHoliday(j, settings.lunarAdjustment) || settings.isWeekend(today)
        v.text(R.id.w_date, CalendarFormatter.formatJalali(j, if (size == WidgetSize.SMALL) DateFormatStyle.MEDIUM else DateFormatStyle.FULL, settings.numerals),
            if (holiday) p.holiday else p.text, if (size == WidgetSize.SMALL) 13f else 16f, s, p)
        if (size != WidgetSize.SMALL) {
            v.text(R.id.w_gregorian, if (config.showGregorian) fmt.gregorian(today) else "", p.secondary, 12f, s, p)
            val occ = IranianOccasions.occasionsOf(j, settings.occasionDisplay, settings.lunarAdjustment)
            v.text(R.id.w_occasion, occ.joinToString(" • ") { o -> o.title + if (o.isApproximate) " (" + context.getString(R.string.approximate) + ")" else "" },
                if (occ.any { it.isHoliday }) p.holiday else p.secondary, 11f, s, p)
        }
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, Route.Dashboard, widgetId * 100 + 1))
        return v
    }
}

// =============================================================================================
// Actions
// =============================================================================================
/** Calendar widget navigation (no activity start needed). */
class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CAL_PREV = "ir.yadino.app.widget.CAL_PREV"
        const val ACTION_CAL_NEXT = "ir.yadino.app.widget.CAL_NEXT"
        const val ACTION_CAL_TODAY = "ir.yadino.app.widget.CAL_TODAY"
        const val EXTRA_VALUE = "value"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val action = intent.action ?: return
        runAsync(context, "widget-action") { app ->
            val store = app.container.widgetConfigs
            val current = store.get(id).monthOffset
            val next = when (action) {
                ACTION_CAL_PREV -> current - 1
                ACTION_CAL_NEXT -> current + 1
                else -> 0
            }
            store.setMonthOffset(id, next.coerceIn(-1200, 1200))
            CalendarWidgetProvider().renderAll(context, app, intArrayOf(id))
        }
    }
}

/** Ids of placed widgets of [cls] (used by the configuration screen). */
fun widgetIds(context: Context, cls: Class<*>): IntArray =
    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))

@Suppress("unused")
private fun daysBetween(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)

@Suppress("unused")
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
