package ir.yadino.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
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
import ir.yadino.app.ui.navigation.Route
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.habits.HabitSummary
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Habit
import ir.yadino.domain.planning.CountdownTarget
import ir.yadino.domain.planning.Countdowns
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

// =============================================================================================
// 7. Today's habits widget (collection; check-ins are written to the database)
// =============================================================================================
class HabitsWidgetProvider : ListWidgetProvider() {
    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val c = app.container
        val settings = c.settings.awaitLoaded()
        val config = c.widgetConfigs.get(widgetId)
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val today = c.clock.today()
        val items = HabitWidgetData.load(app, today, settings)
        val done = items.count { it.second.isDoneToday }
        val title = context.getString(R.string.widget_habits_name)
        val open = openApp(context, Route.Goals(habitsTab = true), widgetId * 100 + 1)

        if (widgetSize(options) == WidgetSize.SMALL) {
            val v = RemoteViews(context.packageName, R.layout.widget_list_small)
            v.applyBackground(p)
            v.text(R.id.w_count, fmt.number(items.size - done), p.accent, 34f, s, p)
            v.text(R.id.w_title, title, p.text, 13f, s, p)
            val next = items.firstOrNull { !it.second.isDoneToday }?.first?.title
            val status = next ?: context.getString(if (items.isEmpty()) R.string.widget_habits_none else R.string.widget_habits_all_done)
            v.text(R.id.w_next, status, p.secondary, 12f, s, p)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        val v = RemoteViews(context.packageName, R.layout.widget_list)
        v.applyBackground(p)
        v.text(R.id.w_title, title, p.text, 15f, s, p)
        val subtitle = if (items.isEmpty()) fmt.date(today, DateFormatStyle.MEDIUM)
        else context.getString(R.string.checklist_progress, fmt.number(done), fmt.number(items.size))
        v.text(R.id.w_subtitle, subtitle, p.secondary, 12f, s, p)
        v.text(R.id.w_empty, context.getString(R.string.widget_habits_none), p.secondary, 13f, s, p)
        v.color(R.id.w_add, "setColorFilter", p.accent, p)
        v.setContentDescription(R.id.w_add, context.getString(R.string.new_habit))
        v.setOnClickPendingIntent(R.id.w_add, openApp(context, Route.HabitEditor(), widgetId * 100 + 2))
        v.setOnClickPendingIntent(R.id.w_header, open)
        val serviceIntent = Intent(context, HabitListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("yadino-widget://habits/$widgetId")
        }
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.widget_list, serviceIntent)
        v.setEmptyView(R.id.widget_list, R.id.w_empty)
        // Item clicks go through the trampoline activity (check in / open), as for tasks.
        val template = Intent(context, WidgetItemActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        v.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(context, widgetId * 100 + 3, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        )
        return v
    }
}

/** Shared data loading for the habits widget (same history window as the dashboard). */
object HabitWidgetData {
    suspend fun load(app: YadinoApplication, today: LocalDate, settings: AppSettings): List<Pair<Habit, HabitSummary>> {
        val c = app.container
        return HabitStats.dueToday(c.habits.getAll(), c.habits.getLogsSince(today.minusDays(120)), today, settings.firstDayOfWeek).take(50)
    }
}

class HabitListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        HabitListFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private class HabitListFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<Pair<Habit, HabitSummary>> = emptyList()
    private var settings = AppSettings()
    private var config = WidgetConfig()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val app = context.applicationContext as YadinoApplication
        runBlocking {
            settings = app.container.settings.awaitLoaded()
            config = app.container.widgetConfigs.get(widgetId)
            items = HabitWidgetData.load(app, app.container.clock.today(), settings)
        }
    }

    override fun onDestroy() = Unit
    override fun getCount(): Int = items.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.first?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_list_item)
        val (habit, summary) = items.getOrNull(position) ?: return v
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        v.text(R.id.item_title, habit.title, if (summary.isDoneToday) p.secondary else p.text, 14f, s, p)
        val meta = buildList {
            if (summary.target > 1) add(fmt.number(summary.doneToday) + "/" + fmt.number(summary.target))
            if (summary.streakInWeeks) {
                add(context.getString(R.string.streak_weeks, fmt.number(summary.currentStreak)) + " • " +
                    fmt.number(summary.weekProgress) + "/" + fmt.number(summary.weekTarget))
            } else if (summary.currentStreak > 0) {
                add(context.getString(R.string.streak_days, fmt.number(summary.currentStreak)))
            }
            if (!summary.isDoneToday) habit.reminderTime?.let { add(fmt.time(it)) }
        }.joinToString(" • ")
        v.text(R.id.item_meta, meta, p.secondary, 12f, s, p)
        v.setViewVisibility(R.id.item_meta, if (meta.isEmpty()) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.item_check, View.VISIBLE)
        v.setImageViewResource(R.id.item_check, if (summary.isDoneToday) R.drawable.ic_widget_checked else R.drawable.ic_widget_unchecked)
        v.color(R.id.item_check, "setColorFilter", p.accent, p)
        v.setContentDescription(R.id.item_check, context.getString(R.string.check_in_for, habit.title))
        v.setOnClickFillInIntent(R.id.item_check, Intent().apply {
            putExtra(WidgetItemActivity.EXTRA_KIND, WidgetItemActivity.KIND_HABIT_CHECK_IN)
            putExtra(WidgetItemActivity.EXTRA_HABIT, habit.id)
        })
        v.setOnClickFillInIntent(R.id.item_body, Intent().apply {
            putExtra(WidgetItemActivity.EXTRA_KIND, WidgetItemActivity.KIND_HABIT_OPEN)
            putExtra(WidgetItemActivity.EXTRA_HABIT, habit.id)
        })
        return v
    }
}

// =============================================================================================
// 8. Deadline countdown widget
// =============================================================================================
class CountdownWidgetProvider : YadinoWidgetProvider() {
    override suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews {
        val c = app.container
        val config = c.widgetConfigs.get(widgetId)
        val settings = c.settings.awaitLoaded()
        val fmt = Formatter(settings)
        val p = palette(context, config)
        val s = config.textScale
        val today = c.clock.today()
        val automatic = Countdowns.isAutomatic(config.content)
        val target = Countdowns.resolve(config.content, c.goals.getAll(), c.tasks.getAll(), today, c.clock.nowTime())
        val small = widgetSize(options) == WidgetSize.SMALL
        val v = RemoteViews(context.packageName, if (small) R.layout.widget_countdown_small else R.layout.widget_countdown)
        v.applyBackground(p)

        if (target == null) {
            v.text(R.id.w_days, "—", p.secondary, if (small) 30f else 36f, s, p)
            v.text(R.id.w_unit, "", p.secondary, 12f, s, p)
            val message = if (automatic) R.string.widget_countdown_empty else R.string.widget_countdown_missing
            v.text(R.id.w_title, context.getString(message), p.text, if (small) 12f else 14f, s, p)
            if (!small) {
                v.text(R.id.w_kind, "", p.accent, 12f, s, p)
                val hint = if (automatic) R.string.widget_countdown_empty_hint else R.string.widget_countdown_missing_hint
                v.text(R.id.w_date, context.getString(hint), p.secondary, 12f, s, p)
            }
            // A deleted target is fixed by choosing again; otherwise open the goals to set deadlines.
            val click = if (automatic) openApp(context, Route.Goals(), widgetId * 100 + 1) else reconfigure(context, widgetId)
            v.setOnClickPendingIntent(R.id.widget_root, click)
            return v
        }

        val days = target.daysLeft(today)
        val dueToday = days == 0L && !target.finished
        val big: String
        val unit: String
        val color: Int
        when {
            target.finished -> {
                big = "✓"
                unit = context.getString(R.string.widget_countdown_finished)
                color = p.accent
            }
            days > 0 -> {
                big = fmt.number(days)
                unit = context.getString(R.string.widget_countdown_days_left)
                color = p.accent
            }
            days == 0L -> {
                big = context.getString(R.string.today)
                unit = target.time?.let { fmt.time(it) } ?: context.getString(R.string.widget_countdown_due_today)
                color = p.holiday
            }
            else -> {
                big = fmt.number(-days)
                unit = context.getString(R.string.widget_countdown_days_passed)
                color = p.holiday
            }
        }
        val bigSize = when {
            dueToday -> if (small) 20f else 26f
            small -> 30f
            else -> 40f
        }
        v.text(R.id.w_days, big, color, bigSize, s, p)
        v.text(R.id.w_unit, unit, p.secondary, 12f, s, p)
        v.text(R.id.w_title, target.title, p.text, if (small) 12f else 15f, s, p)
        if (!small) {
            val kind = context.getString(if (target.kind == CountdownTarget.Kind.GOAL) R.string.widget_countdown_goal else R.string.widget_countdown_task)
            v.text(R.id.w_kind, if (automatic) context.getString(R.string.widget_countdown_nearest) + " • " + kind else kind, p.accent, 12f, s, p)
            val date = fmt.date(target.date, DateFormatStyle.FULL) + (target.time?.let { " • " + fmt.time(it) } ?: "")
            v.text(R.id.w_date, date, p.secondary, 12f, s, p)
        }
        val route = if (target.kind == CountdownTarget.Kind.GOAL) Route.GoalDetail(target.id) else Route.TaskEditor(taskId = target.id)
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, route, widgetId * 100 + 1))
        return v
    }

    /** Opens the configuration of this widget instance (choose another target). */
    private fun reconfigure(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = Uri.parse("yadino-widget://config/$widgetId")
        }
        return PendingIntent.getActivity(context, widgetId * 100 + 9, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
