package ir.yadino.app.ui.goals

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.data.repository.ValidationException
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.TaskRow
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.common.todayFlow
import ir.yadino.app.ui.common.validationText
import ir.yadino.app.ui.components.BarChart
import ir.yadino.app.ui.components.CategoryPicker
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.EmptyState
import ir.yadino.app.ui.components.JalaliDatePickerDialog
import ir.yadino.app.ui.components.NoteColorPicker
import ir.yadino.app.ui.components.Pill
import ir.yadino.app.ui.components.PrioritySelector
import ir.yadino.app.ui.components.ProgressRing
import ir.yadino.app.ui.components.SectionCard
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.tasks.FieldRow
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.goals.GoalPace
import ir.yadino.domain.goals.GoalProgress
import ir.yadino.domain.goals.GoalProgressCalculator
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.habits.HabitSummary
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.GoalTerm
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.ListLayout
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.ProgressStyle
import ir.yadino.domain.model.Task
import ir.yadino.domain.validation.ValidationError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class GoalFilter { ACTIVE, PAUSED, COMPLETED, ARCHIVED }

data class GoalItem(val goal: Goal, val progress: GoalProgress)
data class HabitItem(val habit: Habit, val summary: HabitSummary)

data class GoalsState(
    val goals: List<GoalItem> = emptyList(),
    val habits: List<HabitItem> = emptyList(),
    val filter: GoalFilter = GoalFilter.ACTIVE,
    val showArchivedHabits: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
)

private data class Raw(val goals: List<Goal>, val milestones: List<Milestone>, val tasks: List<Task>, val habits: List<Habit>, val logs: List<ir.yadino.domain.model.HabitLog>)

class GoalsViewModel(c: AppContainer) : YadinoViewModel(c) {
    private val filter = MutableStateFlow(GoalFilter.ACTIVE)
    private val archivedHabits = MutableStateFlow(false)
    private val raw = combine(c.goals.goals, c.goals.milestones, c.tasks.tasks, c.habits.habits, c.habits.logsSince(c.clock.today().minusDays(400))) { g, m, t, h, l ->
        Raw(g, m, t, h, l)
    }

    val state: StateFlow<GoalsState> = combine(raw, todayFlow(c.clock), filter, archivedHabits, c.settings.settings) { r, today, f, ah, settings ->
        val items = r.goals.map { g ->
            val rates = r.habits.filter { it.goalId == g.id && !it.isArchived }.map { HabitStats.summarize(it, r.logs, today, settings.firstDayOfWeek).rate30 }
            GoalItem(g, GoalProgressCalculator.calculate(g, r.milestones, r.tasks, rates, today))
        }.filter { i ->
            when (f) {
                GoalFilter.ACTIVE -> !i.goal.isArchived && i.goal.status == GoalStatus.ACTIVE
                GoalFilter.PAUSED -> !i.goal.isArchived && i.goal.status == GoalStatus.PAUSED
                GoalFilter.COMPLETED -> !i.goal.isArchived && i.goal.status == GoalStatus.COMPLETED && settings.showCompletedGoals
                GoalFilter.ARCHIVED -> i.goal.isArchived
            }
        }.sortedWith(compareByDescending<GoalItem> { it.goal.priority.level }.thenBy { it.goal.deadline ?: LocalDate.MAX })
        val habits = r.habits.filter { it.isArchived == ah }.map { HabitItem(it, HabitStats.summarize(it, r.logs, today, settings.firstDayOfWeek)) }
        GoalsState(items, habits, f, ah, today, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsState())

    fun setFilter(f: GoalFilter) { filter.value = f }
    fun setShowArchivedHabits(v: Boolean) { archivedHabits.value = v }
    fun checkIn(h: Habit) = launchSafe { c.habits.checkIn(h, c.clock.today()) }
}

@Composable
fun paceLabel(p: GoalPace): String = stringResource(
    when (p) {
        GoalPace.NO_DEADLINE -> R.string.pace_no_deadline
        GoalPace.AHEAD -> R.string.pace_ahead
        GoalPace.ON_TRACK -> R.string.pace_on_track
        GoalPace.BEHIND -> R.string.pace_behind
        GoalPace.OVERDUE -> R.string.pace_overdue
        GoalPace.COMPLETED -> R.string.pace_completed
    },
)

@Composable
fun GoalsScreen(route: Route.Goals) {
    val c = LocalContainer.current
    val vm: GoalsViewModel = viewModel { GoalsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    var tab by rememberSaveable { mutableStateOf(if (route.habitsTab) 1 else 0) }
    CollectMessages(vm.messages)
    YadinoScaffold(
        topBar = { YadinoTopBar(stringResource(R.string.tab_goals)) },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (tab == 0) nav.navigate(Route.GoalEditor()) else nav.navigate(Route.HabitEditor()) }) {
                Icon(Icons.Default.Add, stringResource(if (tab == 0) R.string.new_goal else R.string.new_habit))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.goals)) }, icon = { Icon(Icons.Default.Flag, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.habits)) }, icon = { Icon(Icons.Default.Repeat, null) })
            }
            if (tab == 0) GoalsList(state, vm, settings.goalLayout) else HabitsList(state, vm)
        }
    }
}

@Composable
private fun GoalsList(state: GoalsState, vm: GoalsViewModel, layout: ListLayout) {
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val settings = LocalAppSettings.current
    Column {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(GoalFilter.entries) { f ->
                FilterChip(state.filter == f, { vm.setFilter(f) }, {
                    Text(stringResource(when (f) {
                        GoalFilter.ACTIVE -> R.string.goal_filter_active
                        GoalFilter.PAUSED -> R.string.goal_filter_paused
                        GoalFilter.COMPLETED -> R.string.goal_filter_completed
                        GoalFilter.ARCHIVED -> R.string.goal_filter_archived
                    }))
                })
            }
        }
        if (state.loaded && state.goals.isEmpty()) {
            EmptyState(Icons.Default.Flag, stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_message))
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.goals, key = { it.goal.id }) { item ->
                val g = item.goal
                val p = item.progress
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Route.GoalDetail(g.id)) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (layout == ListLayout.GRID) {
                            ProgressRing(p.percent, 64.dp, 6.dp, g.color.accent(dark),
                                label = if (settings.goalProgressStyle == ProgressStyle.FRACTION && p.unitsTotal > 0) fmt.number(p.unitsDone) + "/" + fmt.number(p.unitsTotal) else null)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Pill(stringResource(if (g.term == GoalTerm.SHORT) R.string.term_short else R.string.term_long))
                            }
                            if (layout == ListLayout.LIST) {
                                LinearProgressIndicator(progress = { p.percent / 100f }, color = g.color.accent(dark), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            }
                            val meta = buildList {
                                add(if (settings.goalProgressStyle == ProgressStyle.FRACTION && p.unitsTotal > 0) fmt.number(p.unitsDone) + "/" + fmt.number(p.unitsTotal) else fmt.percent(p.percent))
                                p.daysRemaining?.let { add(fmt.daysLeft(it)) }
                            }
                            Text(meta.joinToString(" • ") + " • " + paceLabel(p.pace), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitsList(state: GoalsState, vm: GoalsViewModel) {
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    Column {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!state.showArchivedHabits, { vm.setShowArchivedHabits(false) }, { Text(stringResource(R.string.habits_active)) })
            FilterChip(state.showArchivedHabits, { vm.setShowArchivedHabits(true) }, { Text(stringResource(R.string.habits_archived)) })
        }
        if (state.loaded && state.habits.isEmpty()) {
            EmptyState(Icons.Default.Repeat, stringResource(R.string.habits_empty_title), stringResource(R.string.habits_empty_message))
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.habits, key = { it.habit.id }) { (h, s) ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Route.HabitDetail(h.id)) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (h.targetPerDay <= 1) {
                            Checkbox(s.isDoneToday, { vm.checkIn(h) }, enabled = s.isScheduledToday && !h.isArchived)
                        } else {
                            OutlinedButton(onClick = { vm.checkIn(h) }, enabled = s.isScheduledToday && !h.isArchived) {
                                Text(fmt.number(s.doneToday) + "/" + fmt.number(s.target))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.titleMedium)
                            Text(scheduleText(h.schedule) + " • " + stringResource(R.string.rate_30, fmt.percent((s.rate30 * 100).toInt())),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LocalFireDepartment, null, tint = if (s.currentStreak > 0) h.color.accent(dark) else MaterialTheme.colorScheme.outline)
                            Text(fmt.number(s.currentStreak), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun scheduleText(s: HabitSchedule): String {
    val fmt = LocalFormatter.current
    return when (s) {
        HabitSchedule.Daily -> stringResource(R.string.schedule_daily)
        is HabitSchedule.SpecificDays -> WeekMath.orderedDays(LocalAppSettings.current.firstDayOfWeek).filter { it in s.days }
            .joinToString("، ") { ir.yadino.calendar.PersianNames.weekday(it) }
        is HabitSchedule.TimesPerWeek -> stringResource(R.string.schedule_times_per_week, fmt.number(s.times))
    }
}

// =============================================================================================
// Goal detail
// =============================================================================================
data class GoalDetailState(
    val goal: Goal? = null,
    val milestones: List<Milestone> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val habits: List<HabitItem> = emptyList(),
    val notes: List<Note> = emptyList(),
    val progress: GoalProgress? = null,
    val weekly: List<Int> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val unlinkedTasks: List<Task> = emptyList(),
    val unlinkedHabits: List<Habit> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
)

class GoalDetailViewModel(c: AppContainer, private val goalId: String) : YadinoViewModel(c) {
    val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val raw = combine(c.goals.observe(goalId), c.goals.observeMilestones(goalId), c.tasks.tasks, c.habits.habits, c.habits.logsSince(c.clock.today().minusDays(120))) { g, m, t, h, l ->
        GoalRaw(g, m, t, h, l)
    }

    private data class GoalRaw(val goal: Goal?, val ms: List<Milestone>, val tasks: List<Task>, val habits: List<Habit>, val logs: List<ir.yadino.domain.model.HabitLog>)

    val state: StateFlow<GoalDetailState> = combine(raw, c.notes.notes, c.tasks.completions, todayFlow(c.clock), c.settings.settings) { r, notes, completions, today, settings ->
        val g = r.goal ?: return@combine GoalDetailState(loaded = true)
        val linkedTasks = r.tasks.filter { it.goalId == g.id }
        val linkedHabits = r.habits.filter { it.goalId == g.id }.map { HabitItem(it, HabitStats.summarize(it, r.logs, today, settings.firstDayOfWeek)) }
        val progress = GoalProgressCalculator.calculate(g, r.ms, linkedTasks, linkedHabits.filter { !it.habit.isArchived }.map { it.summary.rate30 }, today)
        // completed units per week over the last 8 weeks
        val zone = c.clock.zone()
        val taskIds = linkedTasks.map { it.id }.toSet()
        val weekStart = WeekMath.startOfWeek(JalaliDate.from(today), settings.firstDayOfWeek).toLocalDate()
        val weeks = (7 downTo 0).map { weekStart.minusWeeks(it.toLong()) }
        val weekly = weeks.map { ws ->
            val we = ws.plusDays(6)
            completions.count { it.taskId in taskIds && it.completedAt.atZone(zone).toLocalDate().let { d -> !d.isBefore(ws) && !d.isAfter(we) } } +
                r.ms.count { m -> m.completedAt?.atZone(zone)?.toLocalDate()?.let { d -> !d.isBefore(ws) && !d.isAfter(we) } == true }
        }
        val fmt = ir.yadino.app.core.Formatter(settings)
        GoalDetailState(
            goal = g, milestones = r.ms, tasks = ir.yadino.domain.planning.TaskFilters.sortForPlan(linkedTasks), habits = linkedHabits,
            notes = notes.filter { it.goalId == g.id }, progress = progress, weekly = weekly, weekLabels = weeks.map { fmt.number(JalaliDate.from(it).day) + "/" + fmt.number(JalaliDate.from(it).month) },
            unlinkedTasks = r.tasks.filter { it.goalId == null && it.isOpen && !it.isArchived && it.isCompletable },
            unlinkedHabits = r.habits.filter { it.goalId == null && !it.isArchived },
            today = today, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalDetailState())

    fun toggleMilestone(m: Milestone) = launchSafe { c.goals.toggleMilestone(m) }
    fun addMilestone(title: String) = launchSafe { c.goals.addMilestone(goalId, title) }
    fun toggleTask(t: Task) = launchSafe { c.tasks.setCompleted(t, !t.isDone) }
    fun setStatus(status: GoalStatus) = launchSafe {
        state.value.goal?.let { c.goals.setStatus(it, status) }
        if (status == GoalStatus.COMPLETED) message(UiMessage.Text(R.string.msg_goal_completed))
    }
    fun setArchived(v: Boolean) = launchSafe { state.value.goal?.let { c.goals.setArchived(it, v) } }
    fun delete() = launchSafe { c.goals.delete(goalId); closed.tryEmit(Unit) }
    fun linkTasks(ids: List<String>) = launchSafe { c.tasks.linkToGoal(ids, goalId) }
    fun unlinkTask(t: Task) = launchSafe { c.tasks.linkToGoal(listOf(t.id), null) }
    fun linkHabits(ids: List<String>) = launchSafe { c.habits.linkToGoal(ids, goalId) }
}

@Composable
fun GoalDetailScreen(route: Route.GoalDetail) {
    val c = LocalContainer.current
    val vm: GoalDetailViewModel = viewModel { GoalDetailViewModel(c, route.goalId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val settings = LocalAppSettings.current
    CollectMessages(vm.messages)
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf<String?>(null) }
    var milestoneInput by rememberSaveable { mutableStateOf("") }

    val g = state.goal
    YadinoScaffold(topBar = {
        YadinoTopBar(g?.title ?: "", onBack = { nav.back() }) {
            if (g != null) {
                IconButton(onClick = { nav.navigate(Route.GoalEditor(g.id)) }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (g.status == GoalStatus.ACTIVE) DropdownMenuItem(text = { Text(stringResource(R.string.pause_goal)) }, leadingIcon = { Icon(Icons.Default.Pause, null) },
                            onClick = { menu = false; vm.setStatus(GoalStatus.PAUSED) })
                        if (g.status != GoalStatus.ACTIVE) DropdownMenuItem(text = { Text(stringResource(R.string.resume_goal)) }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = { menu = false; vm.setStatus(GoalStatus.ACTIVE) })
                        if (g.status != GoalStatus.COMPLETED) DropdownMenuItem(text = { Text(stringResource(R.string.complete_goal)) }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                            onClick = { menu = false; vm.setStatus(GoalStatus.COMPLETED) })
                        DropdownMenuItem(text = { Text(stringResource(if (g.isArchived) R.string.unarchive else R.string.archive)) },
                            leadingIcon = { Icon(if (g.isArchived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                            onClick = { menu = false; vm.setArchived(!g.isArchived) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
    }) { padding ->
        if (g == null) {
            if (state.loaded) EmptyState(Icons.Default.Flag, stringResource(R.string.goal_not_found), "")
            return@YadinoScaffold
        }
        val p = state.progress!!
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(p.percent, 96.dp, 9.dp, g.color.accent(dark),
                            label = if (settings.goalProgressStyle == ProgressStyle.FRACTION && p.unitsTotal > 0) fmt.number(p.unitsDone) + "/" + fmt.number(p.unitsTotal) else null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(stringResource(if (g.term == GoalTerm.SHORT) R.string.term_short else R.string.term_long))
                                Pill(goalStatusLabel(g.status))
                            }
                            Text(paceLabel(p.pace), style = MaterialTheme.typography.titleSmall)
                            g.deadline?.let { Text(stringResource(R.string.deadline_value, fmt.date(it)) + " • " + fmt.daysLeft(p.daysRemaining ?: 0), style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.started_on, fmt.date(g.startDate)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (g.description.isNotBlank()) Text(g.description, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                SectionCard(stringResource(R.string.milestones) + " (" + fmt.number(p.milestonesDone) + "/" + fmt.number(p.milestonesTotal) + ")") {
                    state.milestones.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.toggleMilestone(m) }) {
                            Checkbox(m.isDone, { vm.toggleMilestone(m) })
                            Column(Modifier.weight(1f)) {
                                Text(m.title)
                                m.dueDate?.let { Text(fmt.date(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    OutlinedTextField(milestoneInput, { milestoneInput = it }, placeholder = { Text(stringResource(R.string.add_milestone)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.addMilestone(milestoneInput); milestoneInput = "" }),
                        trailingIcon = { IconButton(onClick = { vm.addMilestone(milestoneInput); milestoneInput = "" }) { Icon(Icons.Default.Add, stringResource(R.string.add)) } })
                }
            }
            item {
                SectionCard(stringResource(R.string.linked_tasks) + " (" + fmt.number(p.tasksDone) + "/" + fmt.number(p.tasksTotal) + ")",
                    actionLabel = stringResource(R.string.link_existing), onAction = { linkDialog = "tasks" }) {
                    state.tasks.forEach { t ->
                        TaskRow(t, state.today, { vm.toggleTask(t) }, { nav.navigate(Route.TaskEditor(taskId = t.id)) },
                            trailing = { IconButton(onClick = { vm.unlinkTask(t) }) { Icon(Icons.Default.Close, stringResource(R.string.unlink)) } })
                    }
                    TextButton(onClick = { nav.navigate(Route.TaskEditor(goalId = g.id, date = state.today.toEpochDay())) }) {
                        Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_task_for_goal))
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.linked_habits), actionLabel = stringResource(R.string.link_existing), onAction = { linkDialog = "habits" }) {
                    if (state.habits.isEmpty()) Text(stringResource(R.string.no_linked_habits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.habits.forEach { (h, s) ->
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Route.HabitDetail(h.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Repeat, null, tint = h.color.accent(dark))
                            Spacer(Modifier.width(8.dp))
                            Text(h.title, Modifier.weight(1f))
                            Text(stringResource(R.string.rate_30, fmt.percent((s.rate30 * 100).toInt())), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { nav.navigate(Route.HabitEditor(goalId = g.id)) }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_habit)) }
                }
            }
            item {
                SectionCard(stringResource(R.string.linked_notes), actionLabel = stringResource(R.string.new_note), onAction = { nav.navigate(Route.NoteEditor(goalId = g.id)) }) {
                    if (state.notes.isEmpty()) Text(stringResource(R.string.no_linked_notes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.notes.forEach { n ->
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Route.NoteEditor(n.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.StickyNote2, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(NoteFilters.displayTitle(n).ifBlank { stringResource(R.string.untitled_note) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.goal_weekly_report)) {
                    BarChart(state.weekly, state.weekLabels, highlightIndex = state.weekly.lastIndex)
                    Text(stringResource(R.string.goal_weekly_report_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.delete_goal), stringResource(R.string.delete_goal_message), stringResource(R.string.delete),
            onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
    when (linkDialog) {
        "tasks" -> MultiSelectDialog(stringResource(R.string.link_tasks), state.unlinkedTasks.map { it.id to it.title }, { linkDialog = null }) { vm.linkTasks(it) }
        "habits" -> MultiSelectDialog(stringResource(R.string.link_habits), state.unlinkedHabits.map { it.id to it.title }, { linkDialog = null }) { vm.linkHabits(it) }
    }
}

@Composable
fun goalStatusLabel(s: GoalStatus): String = stringResource(
    when (s) {
        GoalStatus.ACTIVE -> R.string.goal_status_active
        GoalStatus.PAUSED -> R.string.goal_status_paused
        GoalStatus.COMPLETED -> R.string.goal_status_completed
    },
)

@Composable
fun MultiSelectDialog(title: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) Text(stringResource(R.string.nothing_to_link))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(options, key = { it.first }) { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { if (id in selected) selected.remove(id) else selected.add(id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in selected, { if (it) selected.add(id) else selected.remove(id) })
                        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.toList()); onDismiss() }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.link)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// =============================================================================================
// Goal editor
// =============================================================================================
data class GoalEditorState(val goal: Goal? = null, val milestones: List<Milestone> = emptyList(), val isNew: Boolean = true, val errors: List<ValidationError> = emptyList())

class GoalEditorViewModel(c: AppContainer, private val goalId: String?) : YadinoViewModel(c) {
    private val _state = MutableStateFlow(GoalEditorState())
    val state: StateFlow<GoalEditorState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val closed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var initial: GoalEditorState? = null

    init {
        viewModelScope.launch {
            val existing = goalId?.let { c.goals.get(it) }
            val s = if (existing != null) GoalEditorState(existing, c.goals.getMilestones(existing.id), false)
            else {
                val now = c.clock.now()
                GoalEditorState(Goal(Ids.newId(), "", startDate = c.clock.today(), createdAt = now, updatedAt = now), emptyList(), true)
            }
            _state.value = s
            initial = s
        }
    }

    val isDirty: Boolean get() = initial != null && (initial!!.goal != _state.value.goal || initial!!.milestones != _state.value.milestones)

    fun update(t: (Goal) -> Goal) { _state.value.goal?.let { _state.value = _state.value.copy(goal = t(it), errors = emptyList()) } }
    fun updateMilestones(t: (List<Milestone>) -> List<Milestone>) { _state.value = _state.value.copy(milestones = t(_state.value.milestones)) }
    fun addMilestone(title: String) {
        val g = _state.value.goal ?: return
        if (title.isBlank()) return
        val now = c.clock.now()
        updateMilestones { it + Milestone(Ids.newId(), g.id, title.trim(), sortOrder = it.size, createdAt = now, updatedAt = now) }
    }

    fun save() = launchSafe(onError = { if (it is ValidationException) _state.value = _state.value.copy(errors = it.errors) }) {
        val s = _state.value
        val g = s.goal ?: return@launchSafe
        c.goals.save(g, s.milestones)
        initial = s
        closed.tryEmit(g.id)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalEditorScreen(route: Route.GoalEditor) {
    val c = LocalContainer.current
    val vm: GoalEditorViewModel = viewModel { GoalEditorViewModel(c, route.goalId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    CollectMessages(vm.messages)
    LaunchedEffect(vm) {
        vm.closed.collect { id -> if (route.goalId == null) nav.replace(Route.GoalDetail(id)) else nav.back() }
    }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = vm.isDirty) { confirmDiscard = true }
    val g = state.goal
    YadinoScaffold(topBar = {
        YadinoTopBar(stringResource(if (state.isNew) R.string.new_goal else R.string.edit_goal), onBack = { if (vm.isDirty) confirmDiscard = true else nav.back() }) {
            TextButton(onClick = vm::save) { Text(stringResource(R.string.save)) }
        }
    }) { padding ->
        if (g == null) return@YadinoScaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(g.title, { v -> vm.update { it.copy(title = v) } }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth(),
                isError = ValidationError.TITLE_REQUIRED in state.errors,
                supportingText = { state.errors.firstOrNull { it == ValidationError.TITLE_REQUIRED || it == ValidationError.TITLE_TOO_LONG }?.let { Text(validationText(it)) } })
            OutlinedTextField(g.description, { v -> vm.update { it.copy(description = v) } }, label = { Text(stringResource(R.string.description)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                GoalTerm.entries.forEachIndexed { i, t ->
                    SegmentedButton(g.term == t, { vm.update { it.copy(term = t) } }, SegmentedButtonDefaults.itemShape(i, 2)) {
                        Text(stringResource(if (t == GoalTerm.SHORT) R.string.term_short else R.string.term_long))
                    }
                }
            }
            Text(stringResource(R.string.priority), style = MaterialTheme.typography.labelLarge)
            PrioritySelector(g.priority) { p -> vm.update { it.copy(priority = p) } }
            CategoryPicker(categories, g.categoryId, { id -> vm.update { it.copy(categoryId = id) } })
            FieldRow(Icons.Default.Flag, stringResource(R.string.start_date), fmt.date(g.startDate), onClick = { dialog = "start" })
            FieldRow(Icons.Default.Flag, stringResource(R.string.deadline), g.deadline?.let { fmt.date(it) } ?: stringResource(R.string.no_deadline),
                onClick = { dialog = "deadline" }, onClear = g.deadline?.let { { vm.update { it.copy(deadline = null) } } })
            if (ValidationError.DEADLINE_BEFORE_START in state.errors) Text(validationText(ValidationError.DEADLINE_BEFORE_START), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
            NoteColorPicker(g.color, { col -> vm.update { it.copy(color = col) } })
            HorizontalDivider()
            Text(stringResource(R.string.milestones), style = MaterialTheme.typography.titleSmall)
            state.milestones.forEachIndexed { index, m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(m.isDone, { v -> vm.updateMilestones { l -> l.map { if (it.id == m.id) it.copy(isDone = v) else it } } })
                    OutlinedTextField(m.title, { v -> vm.updateMilestones { l -> l.map { if (it.id == m.id) it.copy(title = v) else it } } }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.updateMilestones { l -> if (index > 0) l.toMutableList().apply { add(index - 1, removeAt(index)) } else l } }, enabled = index > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { vm.updateMilestones { l -> l.filter { it.id != m.id } } }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
                }
            }
            OutlinedTextField(input, { input = it }, placeholder = { Text(stringResource(R.string.add_milestone)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { vm.addMilestone(input); input = "" }),
                trailingIcon = { IconButton(onClick = { vm.addMilestone(input); input = "" }) { Icon(Icons.Default.Add, stringResource(R.string.add)) } })
            if (state.milestones.isEmpty()) {
                Text(stringResource(R.string.manual_progress, fmt.percent(g.manualProgress ?: 0)), style = MaterialTheme.typography.labelLarge)
                Slider((g.manualProgress ?: 0).toFloat(), { v -> vm.update { it.copy(manualProgress = v.toInt()) } }, valueRange = 0f..100f, steps = 19)
                Text(stringResource(R.string.manual_progress_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(80.dp))
        }
    }
    when (dialog) {
        "start" -> JalaliDatePickerDialog(g?.startDate, c.clock.today(), { dialog = null }, { d -> if (d != null) vm.update { it.copy(startDate = d) } }, title = stringResource(R.string.start_date))
        "deadline" -> JalaliDatePickerDialog(g?.deadline ?: c.clock.today().plusMonths(1), c.clock.today(), { dialog = null }, { d -> vm.update { it.copy(deadline = d) } },
            allowClear = true, title = stringResource(R.string.deadline))
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_changes_message), stringResource(R.string.discard),
            onConfirm = { nav.back() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
}

@Suppress("unused")
private val unusedLink = Icons.Default.Link
