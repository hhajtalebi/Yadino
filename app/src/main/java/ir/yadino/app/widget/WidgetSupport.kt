package ir.yadino.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import ir.yadino.app.MainActivity
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.settings.WidgetConfig
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.app.ui.navigation.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Size buckets derived from the current widget dimensions. */
enum class WidgetSize { SMALL, MEDIUM, LARGE }

internal fun widgetSize(options: Bundle?, mediumMinWidth: Int = 200, largeMinHeight: Int = 220): WidgetSize {
    if (options == null) return WidgetSize.MEDIUM
    val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
    val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110))
    return when {
        w < mediumMinWidth && h < 180 -> WidgetSize.SMALL
        w < 160 -> WidgetSize.SMALL
        h >= largeMinHeight -> WidgetSize.LARGE
        else -> WidgetSize.MEDIUM
    }
}

/** Resolved colours for a widget instance (both day and night variants are kept). */
class WidgetPalette(
    val dark: Boolean,
    val followSystem: Boolean,
    val bg: Int, val text: Int, val secondary: Int, val accent: Int, val holiday: Int,
    val alpha: Int,
    private val lightSet: IntArray,
    private val darkSet: IntArray,
) {
    /**
     * For a colour produced by this palette returns its (day, night) pair so the launcher
     * can switch automatically with the system theme (Android 12+). Colours derived with an
     * alpha component (e.g. faded days of adjacent months) are mapped as well.
     */
    fun dayNight(color: Int): Pair<Int, Int>? {
        val current = if (dark) darkSet else lightSet
        val rgb = color and 0x00FFFFFF
        val a = color ushr 24
        val i = current.indexOfFirst { (it and 0x00FFFFFF) == rgb }
        if (i < 0) return null
        fun withAlpha(c: Int) = (c and 0x00FFFFFF) or (a shl 24)
        return withAlpha(lightSet[i]) to withAlpha(darkSet[i])
    }
}

internal fun palette(context: Context, config: WidgetConfig): WidgetPalette {
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = when (config.theme) {
        "DARK" -> true
        "LIGHT" -> false
        else -> systemDark
    }
    fun c(id: Int) = ContextCompat.getColor(context, id)
    fun accentFor(d: Boolean) = when (config.accent) {
        "GOLD" -> c(R.color.widget_gold)
        "BLUE" -> if (d) 0xFF8CC8FF.toInt() else 0xFF1D5C8C.toInt()
        "ROSE" -> if (d) 0xFFF3A6B5.toInt() else 0xFF9C3550.toInt()
        else -> c(if (d) R.color.widget_accent_dark else R.color.widget_accent_light)
    }
    // order: bg, text, secondary, accent, holiday
    val lightSet = intArrayOf(c(R.color.widget_bg_light), c(R.color.widget_text_light), c(R.color.widget_text_secondary_light), accentFor(false), c(R.color.widget_holiday))
    val darkSet = intArrayOf(c(R.color.widget_bg_dark), c(R.color.widget_text_dark), c(R.color.widget_text_secondary_dark), accentFor(true), 0xFFF08A80.toInt())
    val set = if (dark) darkSet else lightSet
    return WidgetPalette(
        dark = dark,
        followSystem = config.theme == "SYSTEM",
        bg = set[0], text = set[1], secondary = set[2], accent = set[3], holiday = set[4],
        alpha = (config.opacity.coerceIn(0, 100) * 255 / 100),
        lightSet = lightSet, darkSet = darkSet,
    )
}

/** Applies a colour through [method]; follows the system theme automatically on Android 12+. */
internal fun RemoteViews.color(id: Int, method: String, color: Int, p: WidgetPalette) {
    val pair = if (p.followSystem && Build.VERSION.SDK_INT >= 31) p.dayNight(color) else null
    if (pair != null && Build.VERSION.SDK_INT >= 31) setColorInt(id, method, pair.first, pair.second)
    else setInt(id, method, color)
}

internal fun RemoteViews.applyBackground(p: WidgetPalette) {
    color(R.id.widget_bg, "setColorFilter", p.bg, p)
    setInt(R.id.widget_bg, "setImageAlpha", p.alpha)
}

internal fun RemoteViews.text(id: Int, value: CharSequence?, color: Int, sizeSp: Float, scale: Float, p: WidgetPalette? = null) {
    setTextViewText(id, value ?: "")
    if (p != null) color(id, "setTextColor", color, p) else setTextColor(id, color)
    setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sizeSp * scale)
}

/** PendingIntent that opens the app at [route]. */
internal fun openApp(context: Context, route: Route, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(NotificationHelper.EXTRA_ROUTE, route.encode())
        action = "ir.yadino.app.WIDGET.$requestCode"
    }
    return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

internal fun widgetAction(context: Context, action: String, widgetId: Int, requestCode: Int, extra: String? = null): PendingIntent {
    val intent = Intent(context, WidgetActionReceiver::class.java).apply {
        this.action = action
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        if (extra != null) putExtra(WidgetActionReceiver.EXTRA_VALUE, extra)
    }
    return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Base provider: renders asynchronously (database access) using goAsync and the app
 * scope. Widgets are event driven: they are refreshed when data or settings change and
 * at local midnight — there is no polling service.
 */
abstract class YadinoWidgetProvider : AppWidgetProvider() {

    /** Builds the views of one widget instance. Runs on a background thread. */
    abstract suspend fun render(context: Context, app: YadinoApplication, widgetId: Int, options: Bundle?): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        renderAsync(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val app = context.applicationContext as YadinoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.container.widgetConfigs.delete(appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val app = context.applicationContext as YadinoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                renderAll(context, app, ids)
            } finally {
                pending.finish()
            }
        }
    }

    suspend fun renderAll(context: Context, app: YadinoApplication, ids: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            try {
                val views = render(context, app, id, manager.getAppWidgetOptions(id))
                manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                ErrorReporter.log(context, e, "widget-${javaClass.simpleName}")
            }
        }
    }
}

/** Debounced refresh of every widget after data or settings changes. */
class WidgetUpdater(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null

    @Synchronized
    fun requestUpdate() {
        job?.cancel()
        job = scope.launch {
            delay(400)
            updateNow()
        }
    }

    suspend fun updateNow() {
        val app = context.applicationContext as YadinoApplication
        val manager = AppWidgetManager.getInstance(context)
        for (cls in ALL_PROVIDERS) {
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) continue
            val provider = cls.getDeclaredConstructor().newInstance()
            provider.renderAll(context, app, ids)
            @Suppress("DEPRECATION") // RemoteViewsService collections are still supported on all API levels
            if (provider is ListWidgetProvider) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }
    }

    /** Ids of all placed widgets grouped by provider class (for the widgets settings page). */
    fun placedWidgets(): Map<Class<out YadinoWidgetProvider>, IntArray> {
        val manager = AppWidgetManager.getInstance(context)
        return ALL_PROVIDERS.associateWith { manager.getAppWidgetIds(ComponentName(context, it)) }.filterValues { it.isNotEmpty() }
    }

    companion object {
        val ALL_PROVIDERS: List<Class<out YadinoWidgetProvider>> = listOf(
            CalendarWidgetProvider::class.java,
            TodayWidgetProvider::class.java,
            TasksWidgetProvider::class.java,
            QuickNoteWidgetProvider::class.java,
            GoalWidgetProvider::class.java,
            ClockWidgetProvider::class.java,
            HabitsWidgetProvider::class.java,
            CountdownWidgetProvider::class.java,
        )
    }
}

/** Marker for providers that use a collection (ListView) and need data-changed notifications. */
abstract class ListWidgetProvider : YadinoWidgetProvider()
