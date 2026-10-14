package ir.yadino.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ir.yadino.app.LocaleHelper
import ir.yadino.app.MainActivity
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.data.settings.WidgetConfig
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.YadinoTheme
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.notes.Checklist
import ir.yadino.domain.planning.CountdownTarget
import ir.yadino.domain.planning.Countdowns
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Per-instance widget configuration: theme, background opacity, text size, accent colour
 * and the content shown (task filter, note or goal). Several instances of the same widget
 * can have different settings.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as YadinoApplication
        val c = app.container
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        val providerClass = info?.provider?.className ?: ""
        setContent {
            val loaded by c.settings.state.collectAsStateWithLifecycle()
            var config by remember { mutableStateOf<WidgetConfig?>(null) }
            var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
            var goals by remember { mutableStateOf<List<Goal>>(emptyList()) }
            var countdowns by remember { mutableStateOf<List<CountdownTarget>>(emptyList()) }
            val today = remember { c.clock.today() }
            LaunchedEffect(Unit) {
                val cfg = c.widgetConfigs.get(widgetId)
                config = cfg
                notes = c.notes.getRecent(30).filter { !it.isArchived }
                val allGoals = c.goals.getAll()
                goals = allGoals.filter { !it.isArchived && it.status != GoalStatus.COMPLETED }
                if (providerClass == CountdownWidgetProvider::class.java.name) {
                    val allTasks = c.tasks.getAll()
                    val upcoming = Countdowns.candidates(allGoals, allTasks, today).take(40)
                    // Keep the current choice selectable even if it is finished or overdue.
                    val current = cfg.content.takeIf { !Countdowns.isAutomatic(it) }?.let { Countdowns.resolve(it, allGoals, allTasks, today) }
                    countdowns = if (current != null && upcoming.none { it.kind == current.kind && it.id == current.id }) listOf(current) + upcoming else upcoming
                }
            }
            YadinoTheme(loaded ?: AppSettings()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val cfg = config ?: return@Surface
                    WidgetConfigContent(providerClass, cfg, notes, goals, countdowns, today, onChange = { config = it }, onSave = {
                        lifecycleScope.launch {
                            c.widgetConfigs.save(widgetId, cfg)
                            c.widgets.updateNow()
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        }
                    }, onCancel = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
private fun WidgetConfigContent(
    providerClass: String,
    cfg: WidgetConfig,
    notes: List<Note>,
    goals: List<Goal>,
    countdowns: List<CountdownTarget>,
    today: LocalDate,
    onChange: (WidgetConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val fmt = LocalFormatter.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.widget_settings_title), style = MaterialTheme.typography.headlineSmall)
        // live preview of the colour choices
        val dark = cfg.theme == "DARK"
        Card(colors = CardDefaults.cardColors(containerColor = (if (dark) Color(0xFF16231D) else Color(0xFFFFFBF2)).copy(alpha = cfg.opacity / 100f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.widget_preview), color = if (dark) Color(0xFFEEF2EE) else Color(0xFF1B1F1C),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * cfg.textScale))
                Text(stringResource(R.string.widget_preview_body), color = accentColor(cfg.accent, dark),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize * cfg.textScale))
            }
        }
        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SYSTEM" to R.string.theme_system, "LIGHT" to R.string.theme_light, "DARK" to R.string.theme_dark).forEach { (k, l) ->
                FilterChip(cfg.theme == k, { onChange(cfg.copy(theme = k)) }, { Text(stringResource(l)) })
            }
        }
        Text(stringResource(R.string.widget_accent), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("GREEN" to R.string.color_green, "GOLD" to R.string.color_gold, "BLUE" to R.string.color_sky, "ROSE" to R.string.color_rose).forEach { (k, l) ->
                FilterChip(cfg.accent == k, { onChange(cfg.copy(accent = k)) }, { Text(stringResource(l)) },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(accentColor(k, false))) })
            }
        }
        Text(stringResource(R.string.widget_opacity, fmt.percent(cfg.opacity)), style = MaterialTheme.typography.titleSmall)
        Slider(cfg.opacity.toFloat(), { onChange(cfg.copy(opacity = it.toInt())) }, valueRange = 0f..100f, steps = 19)
        Text(stringResource(R.string.widget_text_size, fmt.percent((cfg.textScale * 100).toInt())), style = MaterialTheme.typography.titleSmall)
        Slider(cfg.textScale, { onChange(cfg.copy(textScale = Math.round(it * 20) / 20f)) }, valueRange = 0.8f..1.4f, steps = 11)
        when (providerClass) {
            TasksWidgetProvider::class.java.name -> {
                Text(stringResource(R.string.widget_content_filter), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("TODAY" to R.string.filter_today, "UPCOMING" to R.string.filter_upcoming, "OVERDUE" to R.string.filter_overdue,
                        "IMPORTANT" to R.string.widget_filter_important, "ALL" to R.string.widget_filter_all_open).forEach { (k, l) ->
                        FilterChip(cfg.content.ifBlank { "TODAY" } == k, { onChange(cfg.copy(content = k)) }, { Text(stringResource(l)) })
                    }
                }
            }
            QuickNoteWidgetProvider::class.java.name -> {
                Text(stringResource(R.string.widget_content_note), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(cfg.content.isBlank(), { onChange(cfg.copy(content = "")) }, { Text(stringResource(R.string.widget_latest_note)) })
                    notes.forEach { n ->
                        FilterChip(cfg.content == n.id, { onChange(cfg.copy(content = n.id)) }, { Text(NoteFilters.displayTitle(n, 30).ifBlank { stringResource(R.string.untitled_note) }) })
                    }
                }
            }
            GoalWidgetProvider::class.java.name -> {
                Text(stringResource(R.string.widget_content_goal), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(cfg.content.isBlank(), { onChange(cfg.copy(content = "")) }, { Text(stringResource(R.string.widget_top_goal)) })
                    goals.forEach { g -> FilterChip(cfg.content == g.id, { onChange(cfg.copy(content = g.id)) }, { Text(g.title) }) }
                }
            }
            CountdownWidgetProvider::class.java.name -> {
                Text(stringResource(R.string.widget_content_countdown), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(Countdowns.isAutomatic(cfg.content), { onChange(cfg.copy(content = "")) }, { Text(stringResource(R.string.widget_countdown_nearest)) })
                    countdowns.forEach { t ->
                        val key = Countdowns.encode(t.kind, t.id)
                        FilterChip(
                            cfg.content == key, { onChange(cfg.copy(content = key)) },
                            { Text(t.title + " • " + fmt.relativeDay(t.date, today), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(if (t.kind == CountdownTarget.Kind.GOAL) Icons.Default.Flag else Icons.Default.TaskAlt, null, Modifier.size(16.dp))
                            },
                        )
                    }
                }
                if (countdowns.isEmpty()) {
                    Text(stringResource(R.string.widget_countdown_no_candidates), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (providerClass == CalendarWidgetProvider::class.java.name || providerClass == ClockWidgetProvider::class.java.name) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_gregorian), modifier = Modifier.weight(1f))
                Switch(cfg.showGregorian, { onChange(cfg.copy(showGregorian = it)) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onSave) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun accentColor(key: String, dark: Boolean): Color = when (key) {
    "GOLD" -> Color(0xFFC9A227)
    "BLUE" -> if (dark) Color(0xFF8CC8FF) else Color(0xFF1D5C8C)
    "ROSE" -> if (dark) Color(0xFFF3A6B5) else Color(0xFF9C3550)
    else -> if (dark) Color(0xFF8FD3B0) else Color(0xFF1F5C45)
}

/**
 * Invisible trampoline for clicks on widget list items: toggles a task or checks in a habit
 * (persisted to the database), or opens the item in the app, then finishes immediately.
 */
class WidgetItemActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TASK = "task"
        const val EXTRA_HABIT = "habit"
        const val EXTRA_NOTE = "note"
        const val EXTRA_ITEM = "item"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHECKED = "checked"
        const val KIND_TOGGLE = "toggle"
        const val KIND_OPEN = "open"
        const val KIND_HABIT_CHECK_IN = "habit_check_in"
        const val KIND_HABIT_OPEN = "habit_open"
        const val KIND_NOTE_ITEM = "note_item"
        const val KIND_NOTE_OPEN = "note_open"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent?.getStringExtra(EXTRA_TASK)
        val habitId = intent?.getStringExtra(EXTRA_HABIT)
        val noteId = intent?.getStringExtra(EXTRA_NOTE)
        val kind = intent?.getStringExtra(EXTRA_KIND)
        val app = application as YadinoApplication
        val c = app.container
        when {
            taskId != null && kind == KIND_TOGGLE -> app.appScope.launch {
                c.tasks.get(taskId)?.let { c.tasks.setCompleted(it, !it.isDone) }
            }
            taskId != null -> open(Route.TaskEditor(taskId = taskId))
            // Same rule as the dashboard: toggles single check-ins, counts up multi-count habits.
            habitId != null && kind == KIND_HABIT_CHECK_IN -> app.appScope.launch {
                c.habits.get(habitId)?.takeIf { !it.isArchived }?.let { c.habits.checkIn(it, c.clock.today()) }
            }
            habitId != null -> open(Route.HabitDetail(habitId))
            // A checklist item of the quick-note widget: written to the note right away.
            noteId != null && kind == KIND_NOTE_ITEM -> {
                val index = intent.getIntExtra(EXTRA_ITEM, -1)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val wasChecked = intent.getBooleanExtra(EXTRA_CHECKED, false)
                app.appScope.launch {
                    val note = c.notes.get(noteId) ?: return@launch
                    val content = Checklist.toggleShown(note.content, index, text, wasChecked)
                    if (content != null) c.notes.save(note.copy(content = content)) else c.widgets.requestUpdate()
                }
            }
            noteId != null -> open(Route.NoteEditor(noteId))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun open(route: Route) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationHelper.EXTRA_ROUTE, route.encode())
        })
    }
}
