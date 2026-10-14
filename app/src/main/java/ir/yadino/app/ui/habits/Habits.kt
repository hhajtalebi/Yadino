package ir.yadino.app.ui.habits

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.data.repository.ValidationException
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.common.todayFlow
import ir.yadino.app.ui.common.validationText
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.JalaliDatePickerDialog
import ir.yadino.app.ui.components.NoteColorPicker
import ir.yadino.app.ui.components.SectionCard
import ir.yadino.app.ui.components.TimePickerDialog
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.goals.scheduleText
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.tasks.FieldRow
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.habits.HabitSummary
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Ids
import ir.yadino.domain.validation.ValidationError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class HabitEditorViewModel(c: AppContainer, private val route: Route.HabitEditor) : YadinoViewModel(c) {
    private val _habit = MutableStateFlow<Habit?>(null)
    val habit: StateFlow<Habit?> = _habit.asStateFlow()
    val errors = MutableStateFlow<List<ValidationError>>(emptyList())
    val isNew = route.habitId == null
    val goals: StateFlow<List<Goal>> = c.goals.goals.map { l -> l.filter { !it.isArchived } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var initial: Habit? = null

    init {
        viewModelScope.launch {
            val now = c.clock.now()
            val h = route.habitId?.let { c.habits.get(it) }
                ?: Habit(Ids.newId(), "", startDate = c.clock.today(), goalId = route.goalId, createdAt = now, updatedAt = now)
            _habit.value = h
            initial = h
        }
    }

    val isDirty get() = initial != null && initial != _habit.value
    fun update(t: (Habit) -> Habit) { _habit.value?.let { _habit.value = t(it); errors.value = emptyList() } }
    fun save() = launchSafe(onError = { if (it is ValidationException) errors.value = it.errors }) {
        val h = _habit.value ?: return@launchSafe
        c.habits.save(h)
        initial = h
        closed.tryEmit(Unit)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitEditorScreen(route: Route.HabitEditor) {
    val c = LocalContainer.current
    val vm: HabitEditorViewModel = viewModel { HabitEditorViewModel(c, route) }
    val habit by vm.habit.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    CollectMessages(vm.messages)
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var goalMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.isDirty) { confirmDiscard = true }
    val h = habit
    YadinoScaffold(topBar = {
        YadinoTopBar(stringResource(if (vm.isNew) R.string.new_habit else R.string.edit_habit), onBack = { if (vm.isDirty) confirmDiscard = true else nav.back() }) {
            TextButton(onClick = vm::save) { Text(stringResource(R.string.save)) }
        }
    }) { padding ->
        if (h == null) return@YadinoScaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(h.title, { v -> vm.update { it.copy(title = v) } }, label = { Text(stringResource(R.string.habit_title)) }, modifier = Modifier.fillMaxWidth(),
                isError = ValidationError.TITLE_REQUIRED in errors,
                supportingText = { errors.firstOrNull { it == ValidationError.TITLE_REQUIRED || it == ValidationError.TITLE_TOO_LONG }?.let { Text(validationText(it)) } })
            OutlinedTextField(h.description, { v -> vm.update { it.copy(description = v) } }, label = { Text(stringResource(R.string.description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.schedule), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(h.schedule == HabitSchedule.Daily, { vm.update { it.copy(schedule = HabitSchedule.Daily) } }, { Text(stringResource(R.string.schedule_daily)) })
                FilterChip(h.schedule is HabitSchedule.SpecificDays, {
                    vm.update { it.copy(schedule = HabitSchedule.SpecificDays(settings.workDays.ifEmpty { setOf(DayOfWeek.SATURDAY) })) }
                }, { Text(stringResource(R.string.schedule_specific_days)) })
                FilterChip(h.schedule is HabitSchedule.TimesPerWeek, { vm.update { it.copy(schedule = HabitSchedule.TimesPerWeek(3)) } }, { Text(stringResource(R.string.schedule_weekly_count)) })
            }
            when (val s = h.schedule) {
                is HabitSchedule.SpecificDays -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WeekMath.orderedDays(settings.firstDayOfWeek).forEach { d ->
                        FilterChip(d in s.days, {
                            val next = if (d in s.days) s.days - d else s.days + d
                            if (next.isNotEmpty()) vm.update { it.copy(schedule = HabitSchedule.SpecificDays(next)) }
                        }, { Text(PersianNames.weekday(d)) })
                    }
                }
                is HabitSchedule.TimesPerWeek -> Stepper(stringResource(R.string.times_per_week_label), s.times, 1..7) { v -> vm.update { it.copy(schedule = HabitSchedule.TimesPerWeek(v)) } }
                HabitSchedule.Daily -> Unit
            }
            Stepper(stringResource(R.string.target_per_day), h.targetPerDay, 1..100) { v -> vm.update { it.copy(targetPerDay = v) } }
            FieldRow(Icons.Default.Notifications, stringResource(R.string.habit_reminder), h.reminderTime?.let { fmt.time(it) } ?: stringResource(R.string.no_reminder),
                onClick = { dialog = "time" }, onClear = h.reminderTime?.let { { vm.update { it.copy(reminderTime = null) } } })
            FieldRow(Icons.Default.CalendarToday, stringResource(R.string.start_date), fmt.date(h.startDate), onClick = { dialog = "start" })
            Box {
                AssistChip(onClick = { goalMenu = true }, label = { Text(goals.firstOrNull { it.id == h.goalId }?.title ?: stringResource(R.string.link_to_goal)) },
                    leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(18.dp)) })
                DropdownMenu(expanded = goalMenu, onDismissRequest = { goalMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.no_goal)) }, onClick = { vm.update { it.copy(goalId = null) }; goalMenu = false })
                    goals.forEach { g -> DropdownMenuItem(text = { Text(g.title) }, onClick = { vm.update { it.copy(goalId = g.id) }; goalMenu = false }) }
                }
            }
            Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
            NoteColorPicker(h.color, { col -> vm.update { it.copy(color = col) } })
            Spacer(Modifier.size(80.dp))
        }
    }
    when (dialog) {
        "time" -> TimePickerDialog(h?.reminderTime ?: LocalTime.of(20, 0), { dialog = null }, { t -> vm.update { it.copy(reminderTime = t) } },
            onClear = { vm.update { it.copy(reminderTime = null) } })
        "start" -> JalaliDatePickerDialog(h?.startDate, c.clock.today(), { dialog = null }, { d -> if (d != null) vm.update { it.copy(startDate = d) } })
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_changes_message), stringResource(R.string.discard),
            onConfirm = { nav.back() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val fmt = LocalFormatter.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { if (value > range.first) onChange(value - 1) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.decrease)) }
        Text(fmt.number(value), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { if (value < range.last) onChange(value + 1) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.increase)) }
    }
}

// =============================================================================================
// Habit detail: streaks, completion rate and a 12-week heat map (tap a day to correct it)
// =============================================================================================
data class HabitDetailState(val habit: Habit? = null, val logs: List<HabitLog> = emptyList(), val summary: HabitSummary? = null, val today: LocalDate = LocalDate.now(), val loaded: Boolean = false)

class HabitDetailViewModel(c: AppContainer, private val habitId: String) : YadinoViewModel(c) {
    val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val state: StateFlow<HabitDetailState> = combine(c.habits.observe(habitId), c.habits.observeLogs(habitId), todayFlow(c.clock), c.settings.settings) { h, logs, today, s ->
        HabitDetailState(h, logs, h?.let { HabitStats.summarize(it, logs, today, s.firstDayOfWeek) }, today, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HabitDetailState())

    fun toggleDay(date: LocalDate) = launchSafe {
        val h = state.value.habit ?: return@launchSafe
        val current = state.value.logs.firstOrNull { it.date == date }?.count ?: 0
        c.habits.setCount(h, date, if (current >= h.targetPerDay) 0 else h.targetPerDay)
    }
    fun checkInToday() = launchSafe { state.value.habit?.let { c.habits.checkIn(it, c.clock.today()) } }
    fun setArchived(v: Boolean) = launchSafe { state.value.habit?.let { c.habits.setArchived(it, v) } }
    fun delete() = launchSafe { c.habits.delete(habitId); closed.tryEmit(Unit) }
}

@Composable
fun HabitDetailScreen(route: Route.HabitDetail) {
    val c = LocalContainer.current
    val vm: HabitDetailViewModel = viewModel { HabitDetailViewModel(c, route.habitId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val settings = LocalAppSettings.current
    CollectMessages(vm.messages)
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val h = state.habit
    YadinoScaffold(topBar = {
        YadinoTopBar(h?.title ?: "", onBack = { nav.back() }) {
            if (h != null) {
                IconButton(onClick = { nav.navigate(Route.HabitEditor(h.id)) }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (h.isArchived) R.string.unarchive else R.string.archive)) },
                            leadingIcon = { Icon(if (h.isArchived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                            onClick = { menu = false; vm.setArchived(!h.isArchived) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
    }) { padding ->
        val s = state.summary
        if (h == null || s == null) return@YadinoScaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(stringResource(R.string.current_streak), fmt.number(s.currentStreak) + " " + stringResource(if (s.streakInWeeks) R.string.unit_week else R.string.unit_day), Modifier.weight(1f))
                StatCard(stringResource(R.string.best_streak), fmt.number(s.bestStreak) + " " + stringResource(if (s.streakInWeeks) R.string.unit_week else R.string.unit_day), Modifier.weight(1f))
                StatCard(stringResource(R.string.rate_30_short), fmt.percent((s.rate30 * 100).toInt()), Modifier.weight(1f))
            }
            Text(scheduleText(h.schedule) + if (h.targetPerDay > 1) " • " + stringResource(R.string.target_value, fmt.number(h.targetPerDay)) else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (h.description.isNotBlank()) Text(h.description)
            TextButton(onClick = vm::checkInToday, enabled = s.isScheduledToday && !h.isArchived) {
                Icon(Icons.Default.LocalFireDepartment, null)
                Spacer(Modifier.width(6.dp))
                Text(if (s.isDoneToday) stringResource(R.string.done_today_undo) else stringResource(R.string.check_in_today) +
                    if (h.targetPerDay > 1) " (" + fmt.number(s.doneToday) + "/" + fmt.number(h.targetPerDay) + ")" else "")
            }
            SectionCard(stringResource(R.string.last_12_weeks)) {
                Text(stringResource(R.string.heatmap_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val start = WeekMath.startOfWeek(JalaliDate.from(state.today.minusWeeks(11)), settings.firstDayOfWeek).toLocalDate()
                val history = HabitStats.history(h, state.logs, start, start.plusWeeks(12).minusDays(1))
                val accent = h.color.accent(dark)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        WeekMath.orderedDays(settings.firstDayOfWeek).forEach { d ->
                            Text(PersianNames.weekdayShort(d), style = MaterialTheme.typography.labelSmall, modifier = Modifier.size(18.dp), textAlign = TextAlign.Center)
                        }
                    }
                    for (w in 0 until 12) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (d in 0 until 7) {
                                val date = start.plusDays((w * 7 + d).toLong())
                                val ratio = history[date]
                                val future = date.isAfter(state.today)
                                val desc = fmt.date(date, DateFormatStyle.FULL) + "، " + when {
                                    ratio == null -> stringResource(R.string.not_scheduled)
                                    ratio >= 1.0 -> stringResource(R.string.state_done)
                                    else -> stringResource(R.string.state_open)
                                }
                                Box(
                                    Modifier.fillMaxWidth().size(18.dp).clip(MaterialTheme.shapes.extraSmall)
                                        .background(
                                            when {
                                                future || ratio == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                ratio <= 0.0 -> MaterialTheme.colorScheme.surfaceVariant
                                                else -> accent.copy(alpha = (0.35f + 0.65f * ratio.toFloat()).coerceAtMost(1f))
                                            },
                                        )
                                        .then(if (date == state.today) Modifier.border(1.dp, MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.extraSmall) else Modifier)
                                        .clickable(enabled = !future && ratio != null && !h.isArchived) { vm.toggleDay(date) }
                                        .semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.delete_habit), stringResource(R.string.delete_habit_message), stringResource(R.string.delete),
            onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
