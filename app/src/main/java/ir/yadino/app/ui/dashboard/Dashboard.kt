package ir.yadino.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Upcoming
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.TaskRow
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.common.todayFlow
import ir.yadino.app.ui.components.ProgressRing
import ir.yadino.app.ui.components.SectionCard
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.theme.HolidayRed
import ir.yadino.app.ui.theme.HolidayRedDark
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.app.ui.theme.container
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.IranianOccasions
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.Occasion
import ir.yadino.domain.goals.GoalProgress
import ir.yadino.domain.goals.GoalProgressCalculator
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.habits.HabitSummary
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.DashboardConfig
import ir.yadino.domain.model.DashboardSection
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.ProgressStyle
import ir.yadino.domain.model.ShortcutAction
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.planning.PlannedItem
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.planning.TaskListFilter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DashboardState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val config: DashboardConfig = DashboardConfig.DEFAULT,
    val todayPlan: List<PlannedItem> = emptyList(),
    val overdue: List<Task> = emptyList(),
    val important: List<Task> = emptyList(),
    val upcoming: List<Task> = emptyList(),
    val habits: List<Pair<Habit, HabitSummary>> = emptyList(),
    val goals: List<Pair<Goal, GoalProgress>> = emptyList(),
    val pinnedNotes: List<Note> = emptyList(),
    val recentNotes: List<Note> = emptyList(),
    val categories: Map<String, Category> = emptyMap(),
    val subtasks: Map<String, Pair<Int, Int>> = emptyMap(),
    val occasions: List<Occasion> = emptyList(),
    val draftCount: Int = 0,
)

private data class TaskData(val tasks: List<Task>, val subtasks: Map<String, Pair<Int, Int>>, val categories: List<Category>)
private data class GoalData(val goals: List<Goal>, val milestones: List<ir.yadino.domain.model.Milestone>)
private data class HabitData(val habits: List<Habit>, val logs: List<ir.yadino.domain.model.HabitLog>)

class DashboardViewModel(c: AppContainer) : YadinoViewModel(c) {
    private val today = todayFlow(c.clock)

    private val taskData = combine(c.tasks.tasks, c.tasks.subtaskProgress, c.categories.categories) { t, s, cat -> TaskData(t, s, cat) }
    private val goalData = combine(c.goals.goals, c.goals.milestones) { g, m -> GoalData(g, m) }
    private val habitData = combine(c.habits.habits, c.habits.logsSince(c.clock.today().minusDays(120))) { h, l -> HabitData(h, l) }
    private val settingsFlow = combine(c.settings.settings, c.settings.dashboard, c.notes.draftCount) { s, d, n -> Triple(s, d, n) }

    val state: StateFlow<DashboardState> = combine(today, taskData, goalData, habitData, combine(settingsFlow, c.notes.notes) { a, b -> a to b }) { day, td, gd, hd, sn ->
        val (settingsTriple, notes) = sn
        val (settings, config, drafts) = settingsTriple
        build(day, td, gd, hd, settings, config, drafts, notes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    private fun build(
        day: LocalDate, td: TaskData, gd: GoalData, hd: HabitData, settings: AppSettings, config: DashboardConfig, drafts: Int, notes: List<Note>,
    ): DashboardState {
        val plan = TaskFilters.planForRange(td.tasks, day, day, settings.showFutureOccurrences, settings.firstDayOfWeek)[day].orEmpty()
        val overdue = TaskFilters.filter(td.tasks, TaskListFilter.OVERDUE, day, settings.overdueBehavior)
        val important = TaskFilters.important(td.tasks, day)
        val upcoming = TaskFilters.filter(td.tasks, TaskListFilter.UPCOMING, day, settings.overdueBehavior)
            .filter { TaskFilters.effectiveDate(it)?.let { d -> !d.isAfter(day.plusDays(7)) } == true }
        val habits = hd.habits.filter { !it.isArchived }.map { it to HabitStats.summarize(it, hd.logs, day, settings.firstDayOfWeek) }
            .sortedWith(compareBy<Pair<Habit, HabitSummary>> { !it.second.isScheduledToday }.thenBy { it.second.isDoneToday })
        val goals = gd.goals.filter { !it.isArchived && it.status == GoalStatus.ACTIVE }.map { g ->
            val rates = hd.habits.filter { it.goalId == g.id && !it.isArchived }.map { h -> HabitStats.summarize(h, hd.logs, day, settings.firstDayOfWeek).rate30 }
            g to GoalProgressCalculator.calculate(g, gd.milestones, td.tasks, rates, day)
        }.sortedWith(compareByDescending<Pair<Goal, GoalProgress>> { it.first.priority.level }.thenBy { it.first.deadline ?: LocalDate.MAX })
        val active = notes.filter { !it.isArchived }
        val occasions = IranianOccasions.occasionsOf(JalaliDate.from(day), settings.occasionDisplay, settings.lunarAdjustment)
        val todayPlan = if (settings.overdueBehavior == OverdueBehavior.SHOW_IN_TODAY) {
            plan + overdue.filter { o -> plan.none { it.task.id == o.id } }.map { PlannedItem(it, TaskFilters.effectiveDate(it) ?: day, false) }
        } else plan
        return DashboardState(
            loaded = true, today = day, config = config.normalized(),
            todayPlan = todayPlan.sortedWith(compareBy<PlannedItem> { !it.task.isOpen }.thenBy { it.sortKey }.thenBy { it.task.sortOrder }),
            overdue = overdue, important = important, upcoming = upcoming, habits = habits, goals = goals,
            pinnedNotes = active.filter { it.isPinned }.sortedByDescending { it.updatedAt },
            recentNotes = active.sortedByDescending { it.updatedAt },
            categories = td.categories.associateBy { it.id }, subtasks = td.subtasks, occasions = occasions, draftCount = drafts,
        )
    }

    fun toggle(task: Task) = launchSafe { c.tasks.setCompleted(task, !task.isDone) }

    fun checkIn(habit: Habit) = launchSafe { c.habits.checkIn(habit, c.clock.today()) }

    fun quickAdd(title: String) = launchSafe {
        if (title.isBlank()) return@launchSafe
        c.tasks.quickAdd(title, c.clock.today())
        message(UiMessage.Text(R.string.msg_task_added))
    }

    fun quickNote(title: String, content: String) = launchSafe {
        if (c.notes.create(title, content, c.settings.current().defaultNoteColor) != null) message(UiMessage.Text(R.string.msg_note_saved))
    }

    fun moveToToday(task: Task) = launchSafe { c.tasks.moveToDate(task, c.clock.today()) }

    fun moveAllOverdue() = launchSafe {
        val n = c.tasks.moveOverdueToToday()
        message(UiMessage.Text(R.string.msg_moved_count, listOf(n.toString())))
    }

    fun backupReminderShortcut() = Unit

    fun updateConfig(transform: (DashboardConfig) -> DashboardConfig) = viewModelScope.launch { c.settings.updateDashboard(transform) }
    fun resetConfig() = viewModelScope.launch { c.settings.resetDashboard() }
}

@Composable
fun dashboardViewModel(): DashboardViewModel {
    val c = LocalContainer.current
    return viewModel { DashboardViewModel(c) }
}

@Composable
fun DashboardScreen() {
    val vm = dashboardViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    CollectMessages(vm.messages)
    var addMenu by remember { mutableStateOf(false) }
    var quickNote by rememberSaveable { mutableStateOf(false) }

    YadinoScaffold(
        topBar = {
            YadinoTopBar(stringResource(R.string.app_name)) {
                IconButton(onClick = { nav.navigate(Route.Search) }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                IconButton(onClick = { nav.navigate(Route.Reports) }) { Icon(Icons.AutoMirrored.Filled.ShowChart, stringResource(R.string.reports)) }
                IconButton(onClick = { nav.navigate(Route.DashboardCustomize) }) { Icon(Icons.Default.DashboardCustomize, stringResource(R.string.customize_dashboard)) }
                IconButton(onClick = { nav.navigate(Route.Settings) }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            }
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.add)) },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_task)) }, leadingIcon = { Icon(Icons.Default.Task, null) },
                        onClick = { addMenu = false; nav.navigate(Route.TaskEditor(date = state.today.toEpochDay())) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_event)) }, leadingIcon = { Icon(Icons.Default.Event, null) },
                        onClick = { addMenu = false; nav.navigate(Route.TaskEditor(date = state.today.toEpochDay(), kind = TaskKind.EVENT.name)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.quick_note)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.StickyNote2, null) },
                        onClick = { addMenu = false; quickNote = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_habit)) }, leadingIcon = { Icon(Icons.Default.Repeat, null) },
                        onClick = { addMenu = false; nav.navigate(Route.HabitEditor()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_goal)) }, leadingIcon = { Icon(Icons.Default.Flag, null) },
                        onClick = { addMenu = false; nav.navigate(Route.GoalEditor()) })
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { DateHeader(state) }
            if (state.draftCount > 0) {
                item(key = "drafts") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Restore, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drafts_found), modifier = Modifier.weight(1f))
                            TextButton(onClick = { nav.navigate(Route.Notes) }) { Text(stringResource(R.string.view)) }
                        }
                    }
                }
            }
            state.config.sections.filter { it.visible }.forEach { sc ->
                item(key = sc.section.name) {
                    when (sc.section) {
                        DashboardSection.SHORTCUTS -> Shortcuts(state.config.shortcuts, onQuickNote = { quickNote = true }, today = state.today)
                        DashboardSection.TODAY_PLAN -> TodayPlan(state, sc.itemCount, vm)
                        DashboardSection.OVERDUE -> if (state.overdue.isNotEmpty()) Overdue(state, sc.itemCount, vm)
                        DashboardSection.IMPORTANT_TASKS -> if (state.important.isNotEmpty()) TaskSection(
                            stringResource(R.string.section_important), Icons.Default.Flag, state.important.take(sc.itemCount), state, vm,
                        ) { nav.navigate(Route.Tasks("IMPORTANT")) }
                        DashboardSection.UPCOMING -> if (state.upcoming.isNotEmpty()) TaskSection(
                            stringResource(R.string.section_upcoming), Icons.Default.Upcoming, state.upcoming.take(sc.itemCount), state, vm,
                        ) { nav.navigate(Route.Tasks("UPCOMING")) }
                        DashboardSection.HABITS -> Habits(state, sc.itemCount, vm)
                        DashboardSection.GOALS -> Goals(state, sc.itemCount, settings)
                        DashboardSection.PINNED_NOTES -> if (state.pinnedNotes.isNotEmpty()) NotesRow(stringResource(R.string.section_pinned_notes), Icons.Default.PushPin, state.pinnedNotes.take(sc.itemCount))
                        DashboardSection.RECENT_NOTES -> NotesRow(stringResource(R.string.section_recent_notes), Icons.AutoMirrored.Filled.StickyNote2, state.recentNotes.take(sc.itemCount), showEmpty = true)
                    }
                }
            }
        }
    }
    if (quickNote) QuickNoteSheet(onDismiss = { quickNote = false }, onSave = { t, b -> vm.quickNote(t, b); quickNote = false })
}

@Composable
private fun DateHeader(state: DashboardState) {
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    val dark = LocalIsDark.current
    val nav = LocalNavigator.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Route.Calendar(state.today.toEpochDay())) },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(fmt.number(JalaliDate.from(state.today).day), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(fmt.date(state.today, settings.dateFormat), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                val sub = buildList {
                    if (settings.showGregorian) add(fmt.gregorian(state.today))
                    if (settings.showWeekNumbers) add(fmt.weekNumber(state.today))
                }
                if (sub.isNotEmpty()) Text(sub.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                state.occasions.forEach { o ->
                    Text(
                        o.title + if (o.isApproximate) " (" + stringResource(R.string.approximate) + ")" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (o.isHoliday) (if (dark) HolidayRedDark else HolidayRed) else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun shortcutLabel(a: ShortcutAction): String = stringResource(
    when (a) {
        ShortcutAction.NEW_TASK -> R.string.new_task
        ShortcutAction.NEW_NOTE -> R.string.quick_note
        ShortcutAction.NEW_EVENT -> R.string.new_event
        ShortcutAction.NEW_HABIT -> R.string.new_habit
        ShortcutAction.NEW_GOAL -> R.string.new_goal
        ShortcutAction.OPEN_CALENDAR -> R.string.tab_calendar
        ShortcutAction.OPEN_REPORTS -> R.string.reports
        ShortcutAction.OPEN_SEARCH -> R.string.search
        ShortcutAction.BACKUP_NOW -> R.string.backup_now
    },
)

private fun shortcutIcon(a: ShortcutAction): ImageVector = when (a) {
    ShortcutAction.NEW_TASK -> Icons.Default.Task
    ShortcutAction.NEW_NOTE -> Icons.AutoMirrored.Filled.StickyNote2
    ShortcutAction.NEW_EVENT -> Icons.Default.Event
    ShortcutAction.NEW_HABIT -> Icons.Default.Repeat
    ShortcutAction.NEW_GOAL -> Icons.Default.Flag
    ShortcutAction.OPEN_CALENDAR -> Icons.Default.CalendarMonth
    ShortcutAction.OPEN_REPORTS -> Icons.AutoMirrored.Filled.ShowChart
    ShortcutAction.OPEN_SEARCH -> Icons.Default.Search
    ShortcutAction.BACKUP_NOW -> Icons.Default.Backup
}

@Composable
private fun Shortcuts(shortcuts: List<ShortcutAction>, onQuickNote: () -> Unit, today: LocalDate) {
    val nav = LocalNavigator.current
    if (shortcuts.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shortcuts) { a ->
            AssistChip(
                onClick = {
                    when (a) {
                        ShortcutAction.NEW_TASK -> nav.navigate(Route.TaskEditor(date = today.toEpochDay()))
                        ShortcutAction.NEW_NOTE -> onQuickNote()
                        ShortcutAction.NEW_EVENT -> nav.navigate(Route.TaskEditor(date = today.toEpochDay(), kind = TaskKind.EVENT.name))
                        ShortcutAction.NEW_HABIT -> nav.navigate(Route.HabitEditor())
                        ShortcutAction.NEW_GOAL -> nav.navigate(Route.GoalEditor())
                        ShortcutAction.OPEN_CALENDAR -> nav.navigate(Route.Calendar())
                        ShortcutAction.OPEN_REPORTS -> nav.navigate(Route.Reports)
                        ShortcutAction.OPEN_SEARCH -> nav.navigate(Route.Search)
                        ShortcutAction.BACKUP_NOW -> nav.navigate(Route.SettingsData)
                    }
                },
                label = { Text(shortcutLabel(a)) },
                leadingIcon = { Icon(shortcutIcon(a), null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun TodayPlan(state: DashboardState, count: Int, vm: DashboardViewModel) {
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    var input by rememberSaveable { mutableStateOf("") }
    val open = state.todayPlan.count { it.task.isOpen && it.task.isCompletable }
    val done = state.todayPlan.count { it.task.isDone }
    SectionCard(
        title = stringResource(R.string.section_today) + if (open + done > 0) " (" + fmt.number(done) + "/" + fmt.number(open + done) + ")" else "",
        icon = Icons.Default.Today,
        actionLabel = stringResource(R.string.see_all),
        onAction = { nav.navigate(Route.Tasks("TODAY")) },
    ) {
        if (open + done > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / (open + done) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        if (state.todayPlan.isEmpty()) {
            Text(stringResource(R.string.today_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        state.todayPlan.take(count).forEach { item ->
            TaskRow(
                task = item.task, today = state.today,
                onToggle = if (item.isProjected) null else ({ vm.toggle(item.task) }),
                onClick = { nav.navigate(Route.TaskEditor(taskId = item.task.id)) },
                category = item.task.categoryId?.let { state.categories[it] },
                subtasks = state.subtasks[item.task.id], showDate = item.date != state.today,
                projectedDate = if (item.isProjected) item.date else null,
            )
        }
        if (state.todayPlan.size > count) {
            Text(stringResource(R.string.and_more, fmt.number(state.todayPlan.size - count)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.navigate(Route.Tasks("TODAY")) }.padding(4.dp))
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            placeholder = { Text(stringResource(R.string.quick_add_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.quickAdd(input); input = "" }),
            trailingIcon = {
                IconButton(onClick = { vm.quickAdd(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
            },
        )
    }
}

@Composable
private fun Overdue(state: DashboardState, count: Int, vm: DashboardViewModel) {
    val nav = LocalNavigator.current
    SectionCard(
        title = stringResource(R.string.section_overdue), icon = Icons.Default.Warning,
        actionLabel = stringResource(R.string.move_all_to_today), onAction = { vm.moveAllOverdue() },
    ) {
        state.overdue.take(count).forEach { t ->
            TaskRow(
                task = t, today = state.today, onToggle = { vm.toggle(t) }, onClick = { nav.navigate(Route.TaskEditor(taskId = t.id)) },
                category = t.categoryId?.let { state.categories[it] }, subtasks = state.subtasks[t.id],
                trailing = { TextButton(onClick = { vm.moveToToday(t) }) { Text(stringResource(R.string.to_today)) } },
            )
        }
    }
}

@Composable
private fun TaskSection(title: String, icon: ImageVector, tasks: List<Task>, state: DashboardState, vm: DashboardViewModel, onAll: () -> Unit) {
    val nav = LocalNavigator.current
    SectionCard(title = title, icon = icon, actionLabel = stringResource(R.string.see_all), onAction = onAll) {
        tasks.forEach { t ->
            TaskRow(
                task = t, today = state.today, onToggle = { vm.toggle(t) }, onClick = { nav.navigate(Route.TaskEditor(taskId = t.id)) },
                category = t.categoryId?.let { state.categories[it] }, subtasks = state.subtasks[t.id],
            )
        }
    }
}

@Composable
private fun Habits(state: DashboardState, count: Int, vm: DashboardViewModel) {
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    SectionCard(
        title = stringResource(R.string.section_habits), icon = Icons.Default.LocalFireDepartment,
        actionLabel = stringResource(R.string.see_all), onAction = { nav.navigate(Route.Goals(habitsTab = true)) },
    ) {
        if (state.habits.isEmpty()) {
            Text(stringResource(R.string.habits_empty_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { nav.navigate(Route.HabitEditor()) }) { Text(stringResource(R.string.new_habit)) }
        }
        state.habits.take(count).forEach { (h, s) ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Route.HabitDetail(h.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = stringResource(R.string.check_in_for, h.title)
                if (h.targetPerDay <= 1) {
                    Checkbox(checked = s.isDoneToday, onCheckedChange = { vm.checkIn(h) }, enabled = s.isScheduledToday,
                        modifier = Modifier.semantics { contentDescription = label })
                } else {
                    OutlinedButton(onClick = { vm.checkIn(h) }, enabled = s.isScheduledToday) {
                        Text(fmt.number(s.doneToday) + "/" + fmt.number(s.target))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (!s.isScheduledToday) stringResource(R.string.habit_not_today)
                        else if (s.streakInWeeks) stringResource(R.string.streak_weeks, fmt.number(s.currentStreak)) + " • " + fmt.number(s.weekProgress) + "/" + fmt.number(s.weekTarget)
                        else stringResource(R.string.streak_days, fmt.number(s.currentStreak)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.currentStreak > 0) Icon(Icons.Default.LocalFireDepartment, null, tint = h.color.accent(dark), modifier = Modifier.size(20.dp))
            }
        }
    }
}


@Composable
private fun Goals(state: DashboardState, count: Int, settings: AppSettings) {
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    SectionCard(
        title = stringResource(R.string.section_goals), icon = Icons.Default.Flag,
        actionLabel = stringResource(R.string.see_all), onAction = { nav.navigate(Route.Goals()) },
    ) {
        if (state.goals.isEmpty()) {
            Text(stringResource(R.string.goals_empty_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { nav.navigate(Route.GoalEditor()) }) { Text(stringResource(R.string.new_goal)) }
        }
        state.goals.take(count).forEach { (g, p) ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Route.GoalDetail(g.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    p.percent, 48.dp, 5.dp, g.color.accent(dark),
                    label = if (settings.goalProgressStyle == ProgressStyle.FRACTION && p.unitsTotal > 0) fmt.number(p.unitsDone) + "/" + fmt.number(p.unitsTotal) else null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p.daysRemaining?.let { Text(fmt.daysLeft(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun NotesRow(title: String, icon: ImageVector, notes: List<Note>, showEmpty: Boolean = false) {
    val nav = LocalNavigator.current
    val dark = LocalIsDark.current
    SectionCard(title = title, icon = icon, actionLabel = stringResource(R.string.see_all), onAction = { nav.navigate(Route.Notes) }) {
        if (notes.isEmpty() && showEmpty) {
            Text(stringResource(R.string.notes_empty_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { n ->
                Card(
                    modifier = Modifier.width(160.dp).height(100.dp).clickable { nav.navigate(Route.NoteEditor(n.id)) },
                    colors = CardDefaults.cardColors(containerColor = n.color.container(dark)),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(NoteFilters.displayTitle(n).ifBlank { stringResource(R.string.untitled_note) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.content, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickNoteSheet(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.quick_note), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.note_body)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { onSave(title, body) }, enabled = title.isNotBlank() || body.isNotBlank()) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

// ================================================================================================
// Customisation
// ================================================================================================
@Composable
private fun sectionLabel(s: DashboardSection): String = stringResource(
    when (s) {
        DashboardSection.TODAY_PLAN -> R.string.section_today
        DashboardSection.IMPORTANT_TASKS -> R.string.section_important
        DashboardSection.OVERDUE -> R.string.section_overdue
        DashboardSection.HABITS -> R.string.section_habits
        DashboardSection.GOALS -> R.string.section_goals
        DashboardSection.RECENT_NOTES -> R.string.section_recent_notes
        DashboardSection.PINNED_NOTES -> R.string.section_pinned_notes
        DashboardSection.SHORTCUTS -> R.string.section_shortcuts
        DashboardSection.UPCOMING -> R.string.section_upcoming
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardCustomizeScreen() {
    val vm = dashboardViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    var confirmReset by remember { mutableStateOf(false) }
    YadinoScaffold(topBar = {
        YadinoTopBar(stringResource(R.string.customize_dashboard), onBack = { nav.back() }) {
            TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.reset)) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.customize_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.config.sections, key = { it.section.name }) { sc ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sectionLabel(sc.section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.updateConfig { it.move(sc.section, -1) } }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up)) }
                            IconButton(onClick = { vm.updateConfig { it.move(sc.section, 1) } }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down)) }
                            Switch(checked = sc.visible, onCheckedChange = { v ->
                                vm.updateConfig { cfg -> cfg.copy(sections = cfg.sections.map { if (it.section == sc.section) it.copy(visible = v) else it }) }
                            })
                        }
                        if (sc.section != DashboardSection.SHORTCUTS && sc.visible) {
                            Text(stringResource(R.string.items_count, fmt.number(sc.itemCount)), style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = sc.itemCount.toFloat(), valueRange = 1f..12f, steps = 10,
                                onValueChange = { v -> vm.updateConfig { cfg -> cfg.copy(sections = cfg.sections.map { if (it.section == sc.section) it.copy(itemCount = v.toInt()) else it }) } },
                            )
                        }
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.shortcuts_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.shortcuts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutAction.entries.forEach { a ->
                        val selected = a in state.config.shortcuts
                        FilterChip(
                            selected = selected,
                            onClick = { vm.updateConfig { cfg -> cfg.copy(shortcuts = if (selected) cfg.shortcuts - a else cfg.shortcuts + a) } },
                            label = { Text(shortcutLabel(a)) },
                            leadingIcon = { Icon(if (selected) Icons.Default.Check else shortcutIcon(a), null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
    }
    if (confirmReset) {
        ir.yadino.app.ui.components.ConfirmDialog(
            title = stringResource(R.string.reset_dashboard), message = stringResource(R.string.reset_dashboard_message),
            confirmText = stringResource(R.string.reset), onConfirm = { vm.resetConfig() }, onDismiss = { confirmReset = false },
        )
    }
}
