package ir.yadino.app.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.data.repository.ValidationException
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.common.validationText
import ir.yadino.app.ui.components.CategoryPicker
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.JalaliDatePickerDialog
import ir.yadino.app.ui.components.PrioritySelector
import ir.yadino.app.ui.components.TagEditor
import ir.yadino.app.ui.components.TimePickerDialog
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.notes.Checklist
import ir.yadino.domain.recurrence.Frequency
import ir.yadino.domain.recurrence.RecurrenceEnd
import ir.yadino.domain.recurrence.RecurrenceEngine
import ir.yadino.domain.recurrence.RecurrenceRule
import ir.yadino.domain.validation.ValidationError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class TaskEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val task: Task? = null,
    val subtasks: List<Subtask> = emptyList(),
    val errors: List<ValidationError> = emptyList(),
    val sourceNoteTitle: String? = null,
)

class TaskEditorViewModel(c: AppContainer, private val route: Route.TaskEditor) : YadinoViewModel(c) {
    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val goals: StateFlow<List<Goal>> = c.goals.goals.map { l -> l.filter { !it.isArchived && it.status != GoalStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var initial: Pair<Task?, List<Subtask>>? = null

    init {
        viewModelScope.launch {
            val settings = c.settings.awaitLoaded()
            val existing = route.taskId?.let { c.tasks.get(it) }
            if (route.taskId != null && existing == null) {
                message(UiMessage.Text(R.string.item_not_found))
                closed.tryEmit(Unit)
                return@launch
            }
            if (existing != null) {
                val subs = c.tasks.getSubtasks(existing.id)
                val noteTitle = existing.sourceNoteId?.let { c.notes.get(it) }?.let { NoteFilters.displayTitle(it) }
                _state.value = TaskEditorState(true, false, existing, subs, sourceNoteTitle = noteTitle)
                initial = existing to subs
            } else {
                val now = c.clock.now()
                val date = route.date?.let { LocalDate.ofEpochDay(it) }
                val kind = TaskKind.entries.firstOrNull { it.name == route.kind } ?: TaskKind.TASK
                val note = route.fromNoteId?.let { c.notes.get(it) }
                // A checklist in the note becomes subtasks; the remaining text becomes the description.
                val fromNote = note?.let { Checklist.split(it.content) }
                val start = if (date != null && (kind != TaskKind.TASK || settings.defaultTaskTime != null)) settings.defaultTaskTime ?: LocalTime.of(9, 0) else null
                val end = if (kind == TaskKind.EVENT && start != null) start.plusMinutes(settings.defaultTaskDurationMinutes.toLong()).takeIf { it.isAfter(start) } else null
                val task = Task(
                    id = Ids.newId(), title = note?.let { NoteFilters.displayTitle(it, 200) } ?: "", description = fromNote?.text ?: "",
                    kind = kind, date = date, startTime = start, endTime = end,
                    reminderOffsetMinutes = if (date != null) settings.defaultReminderOffsetMinutes else null,
                    goalId = route.goalId ?: note?.goalId, sourceNoteId = note?.id, categoryId = note?.categoryId, tags = note?.tags ?: emptyList(),
                    createdAt = now, updatedAt = now,
                )
                val subtasks = fromNote?.items.orEmpty().mapIndexed { i, item -> Subtask(Ids.newId(), task.id, item.text, item.checked, i, now, now) }
                _state.value = TaskEditorState(true, true, task, subtasks, sourceNoteTitle = note?.let { NoteFilters.displayTitle(it) })
                initial = task to subtasks
            }
        }
    }

    fun update(transform: (Task) -> Task) {
        val cur = _state.value
        val t = cur.task ?: return
        _state.value = cur.copy(task = transform(t), errors = emptyList())
    }

    fun updateSubtasks(transform: (List<Subtask>) -> List<Subtask>) {
        _state.value = _state.value.copy(subtasks = transform(_state.value.subtasks))
    }

    fun addSubtask(title: String) {
        if (title.isBlank()) return
        val now = c.clock.now()
        val t = _state.value.task ?: return
        updateSubtasks { it + Subtask(Ids.newId(), t.id, title.trim(), false, it.size, now, now) }
    }

    val isDirty: Boolean
        get() {
            val i = initial ?: return false
            val s = _state.value
            return s.task != i.first || s.subtasks != i.second
        }

    fun save() = launchSafe(onError = { e -> if (e is ValidationException) _state.value = _state.value.copy(errors = e.errors) }) {
        val s = _state.value
        val t = s.task ?: return@launchSafe
        c.tasks.save(t, s.subtasks)
        initial = s.task to s.subtasks
        message(UiMessage.Text(R.string.msg_saved))
        closed.tryEmit(Unit)
    }

    fun delete() = launchSafe {
        val t = _state.value.task ?: return@launchSafe
        if (!_state.value.isNew) c.tasks.delete(t.id)
        closed.tryEmit(Unit)
    }

    fun duplicateAndOpen(onOpen: (String) -> Unit) = launchSafe {
        val t = _state.value.task ?: return@launchSafe
        val copy = c.tasks.duplicate(t)
        onOpen(copy.id)
    }
}

@Composable
fun kindLabel(k: TaskKind): String = stringResource(
    when (k) {
        TaskKind.TASK -> R.string.kind_task
        TaskKind.EVENT -> R.string.kind_event
        TaskKind.REMINDER -> R.string.kind_reminder
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(route: Route.TaskEditor) {
    val c = LocalContainer.current
    val vm: TaskEditorViewModel = viewModel { TaskEditorViewModel(c, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    val today = c.clock.today()
    CollectMessages(vm.messages)
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = vm.isDirty) { confirmDiscard = true }

    val task = state.task
    YadinoScaffold(topBar = {
        YadinoTopBar(
            stringResource(if (state.isNew) R.string.new_item else R.string.edit_item, task?.let { kindLabel(it.kind) } ?: ""),
            onBack = { if (vm.isDirty) confirmDiscard = true else nav.back() },
        ) {
            if (!state.isNew) {
                IconButton(onClick = { vm.duplicateAndOpen { nav.replace(Route.TaskEditor(taskId = it)) } }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
            TextButton(onClick = vm::save, enabled = task != null) { Text(stringResource(R.string.save)) }
        }
    }) { padding ->
        if (task == null) return@YadinoScaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TaskKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = task.kind == k, onClick = { vm.update { it.copy(kind = k) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TaskKind.entries.size),
                    ) { Text(kindLabel(k)) }
                }
            }
            OutlinedTextField(
                value = task.title, onValueChange = { v -> vm.update { it.copy(title = v) } },
                label = { Text(stringResource(R.string.title)) }, singleLine = false, maxLines = 3,
                isError = state.errors.any { it == ValidationError.TITLE_REQUIRED || it == ValidationError.TITLE_TOO_LONG },
                supportingText = {
                    state.errors.firstOrNull { it == ValidationError.TITLE_REQUIRED || it == ValidationError.TITLE_TOO_LONG }?.let { Text(validationText(it)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = task.description, onValueChange = { v -> vm.update { it.copy(description = v) } },
                label = { Text(stringResource(R.string.description)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            state.sourceNoteTitle?.let {
                AssistChip(onClick = { task.sourceNoteId?.let { id -> nav.navigate(Route.NoteEditor(id)) } },
                    label = { Text(stringResource(R.string.from_note, it)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.StickyNote2, null, Modifier.size(18.dp)) })
            }
            if (task.kind != TaskKind.EVENT) {
                Text(stringResource(R.string.priority), style = MaterialTheme.typography.labelLarge)
                PrioritySelector(task.priority) { p -> vm.update { it.copy(priority = p) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CategoryPicker(categories, task.categoryId, { id -> vm.update { it.copy(categoryId = id) } })
                GoalPicker(goals, task.goalId) { id -> vm.update { it.copy(goalId = id) } }
                if (!state.isNew && task.kind != TaskKind.EVENT) StatusPicker(task.status) { s -> vm.update { it.copy(status = s) } }
            }
            HorizontalDivider()
            FieldRow(Icons.Default.CalendarToday, stringResource(R.string.date), task.date?.let { fmt.date(it) } ?: stringResource(R.string.no_date),
                onClick = { dialog = "date" }, onClear = task.date?.let { { vm.update { t -> t.copy(date = null, startTime = null, endTime = null, recurrence = null) } } })
            if (task.date != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldRow(Icons.Default.Schedule, stringResource(R.string.start_time), task.startTime?.let { fmt.time(it) } ?: stringResource(R.string.all_day),
                        onClick = { dialog = "start" }, modifier = Modifier.weight(1f),
                        onClear = task.startTime?.let { { vm.update { t -> t.copy(startTime = null, endTime = null) } } })
                    if (task.startTime != null) {
                        FieldRow(Icons.Default.Schedule, stringResource(R.string.end_time), task.endTime?.let { fmt.time(it) } ?: "—",
                            onClick = { dialog = "end" }, modifier = Modifier.weight(1f),
                            onClear = task.endTime?.let { { vm.update { t -> t.copy(endTime = null) } } })
                    }
                }
                if (state.errors.contains(ValidationError.END_BEFORE_START)) Text(validationText(ValidationError.END_BEFORE_START), color = MaterialTheme.colorScheme.error)
            }
            if (task.kind != TaskKind.EVENT) {
                FieldRow(Icons.Default.HourglassBottom, stringResource(R.string.deadline),
                    task.dueDate?.let { fmt.date(it) + (task.dueTime?.let { t -> "، " + fmt.time(t) } ?: "") } ?: stringResource(R.string.no_deadline),
                    onClick = { dialog = "due" }, onClear = task.dueDate?.let { { vm.update { t -> t.copy(dueDate = null, dueTime = null) } } })
                if (task.dueDate != null) {
                    TextButton(onClick = { dialog = "dueTime" }) {
                        Text(task.dueTime?.let { stringResource(R.string.deadline_time_value, fmt.time(it)) } ?: stringResource(R.string.add_deadline_time))
                    }
                }
                if (state.errors.contains(ValidationError.DUE_BEFORE_DATE)) Text(validationText(ValidationError.DUE_BEFORE_DATE), color = MaterialTheme.colorScheme.error)
            }
            ReminderPicker(task.reminderOffsetMinutes, enabled = task.date != null || task.dueDate != null) { v -> vm.update { it.copy(reminderOffsetMinutes = v) } }
            if (state.errors.contains(ValidationError.REMINDER_WITHOUT_DATE)) Text(validationText(ValidationError.REMINDER_WITHOUT_DATE), color = MaterialTheme.colorScheme.error)
            RecurrenceEditor(task.recurrence, task.date ?: task.dueDate, enabled = task.date != null || task.dueDate != null) { r ->
                vm.update { it.copy(recurrence = r, recurrenceStart = if (r == null) null else it.recurrenceStart) }
            }
            state.errors.filter { it == ValidationError.RECURRENCE_UNTIL_BEFORE_START || it == ValidationError.RECURRENCE_WITHOUT_DATE }.forEach {
                Text(validationText(it), color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            TagEditor(task.tags, { tags -> vm.update { it.copy(tags = tags) } })
            if (task.kind != TaskKind.EVENT) {
                SubtaskEditor(state.subtasks, vm)
            }
            Spacer(Modifier.size(80.dp))
        }
    }

    when (dialog) {
        "date" -> JalaliDatePickerDialog(task?.date, today, { dialog = null }, { d ->
            vm.update { t -> if (d == null) t.copy(date = null, startTime = null, endTime = null) else t.copy(date = d) }
        }, allowClear = true)
        "due" -> JalaliDatePickerDialog(task?.dueDate ?: task?.date, today, { dialog = null }, { d -> vm.update { t -> t.copy(dueDate = d, dueTime = if (d == null) null else t.dueTime) } },
            allowClear = true, title = stringResource(R.string.deadline))
        "start" -> TimePickerDialog(task?.startTime ?: settings.defaultTaskTime ?: LocalTime.of(9, 0), { dialog = null }, { tm ->
            vm.update { t ->
                val end = t.endTime?.let { e -> if (t.startTime != null) tm.plusMinutes(java.time.Duration.between(t.startTime, e).toMinutes()) else e }
                t.copy(startTime = tm, endTime = end?.takeIf { it.isAfter(tm) })
            }
        }, onClear = { vm.update { it.copy(startTime = null, endTime = null) } })
        "end" -> TimePickerDialog(task?.endTime ?: (task?.startTime ?: LocalTime.of(9, 0)).plusMinutes(settings.defaultTaskDurationMinutes.toLong()),
            { dialog = null }, { tm -> vm.update { it.copy(endTime = tm) } }, onClear = { vm.update { it.copy(endTime = null) } })
        "dueTime" -> TimePickerDialog(task?.dueTime ?: LocalTime.of(17, 0), { dialog = null }, { tm -> vm.update { it.copy(dueTime = tm) } },
            onClear = { vm.update { it.copy(dueTime = null) } })
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_changes_message), stringResource(R.string.discard),
            onConfirm = { nav.back() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.delete_task), stringResource(R.string.delete_task_message, task?.title ?: ""), stringResource(R.string.delete),
            onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

@Composable
fun FieldRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, onClear: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onClear != null) IconButton(onClick = onClear) { Icon(Icons.Default.Close, stringResource(R.string.clear)) }
    }
}

@Composable
fun GoalPicker(goals: List<Goal>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sel = goals.firstOrNull { it.id == selected }
    androidx.compose.foundation.layout.Box {
        AssistChip(onClick = { open = true }, label = { Text(sel?.title ?: stringResource(R.string.no_goal)) },
            leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(18.dp)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.no_goal)) }, onClick = { onSelect(null); open = false })
            goals.forEach { g -> DropdownMenuItem(text = { Text(g.title) }, onClick = { onSelect(g.id); open = false }) }
        }
    }
}

@Composable
private fun StatusPicker(selected: TaskStatus, onSelect: (TaskStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        AssistChip(onClick = { open = true }, label = { Text(statusLabel(selected)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TaskStatus.entries.forEach { s -> DropdownMenuItem(text = { Text(statusLabel(s)) }, onClick = { onSelect(s); open = false }) }
        }
    }
}

@Composable
fun statusLabel(s: TaskStatus): String = stringResource(
    when (s) {
        TaskStatus.TODO -> R.string.status_todo
        TaskStatus.IN_PROGRESS -> R.string.status_in_progress
        TaskStatus.DONE -> R.string.status_done
        TaskStatus.CANCELLED -> R.string.status_cancelled
    },
)

private val REMINDER_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 24 * 60, 2 * 24 * 60)

@Composable
fun ReminderPicker(value: Int?, enabled: Boolean, onChange: (Int?) -> Unit) {
    val fmt = LocalFormatter.current
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        FieldRow(
            Icons.Default.Notifications, stringResource(R.string.reminder),
            if (!enabled) stringResource(R.string.reminder_needs_date) else value?.let { fmt.minutesOffset(it) } ?: stringResource(R.string.no_reminder),
            onClick = { if (enabled) open = true },
            onClear = value?.let { { onChange(null) } },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.no_reminder)) }, onClick = { onChange(null); open = false })
            REMINDER_OPTIONS.forEach { m -> DropdownMenuItem(text = { Text(fmt.minutesOffset(m)) }, onClick = { onChange(m); open = false }) }
            DropdownMenuItem(text = { Text(stringResource(R.string.custom)) }, onClick = { open = false; custom = true })
        }
    }
    if (custom) {
        var text by remember { mutableStateOf(value?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(stringResource(R.string.custom_reminder)) },
            text = {
                OutlinedTextField(text, { text = it.filter { ch -> ch.isDigit() || ch in '۰'..'۹' } }, label = { Text(stringResource(R.string.minutes_before)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    ir.yadino.calendar.Digits.toLatin(text).toIntOrNull()?.let { onChange(it.coerceIn(0, 7 * 24 * 60)) }
                    custom = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Recurrence editor: presets + interval + weekdays + end condition (Jalali based). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurrenceEditor(rule: RecurrenceRule?, start: LocalDate?, enabled: Boolean, onChange: (RecurrenceRule?) -> Unit) {
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    var expanded by rememberSaveable { mutableStateOf(rule != null) }
    var untilPicker by remember { mutableStateOf(false) }
    Column {
        FieldRow(
            Icons.Default.Repeat, stringResource(R.string.repeat),
            if (!enabled) stringResource(R.string.repeat_needs_date) else rule?.let { RecurrenceEngine.describe(it, start, fmt.numerals) } ?: stringResource(R.string.no_repeat),
            onClick = { if (enabled) expanded = !expanded },
            onClear = rule?.let { { onChange(null); expanded = false } },
        )
        if (enabled && expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(rule == null, { onChange(null) }, { Text(stringResource(R.string.no_repeat)) })
                FilterChip(rule?.frequency == Frequency.DAILY, { onChange(RecurrenceRule.daily(rule?.interval ?: 1).copy(end = rule?.end ?: RecurrenceEnd.Never)) }, { Text(stringResource(R.string.daily)) })
                FilterChip(rule?.frequency == Frequency.WEEKLY, {
                    onChange(RecurrenceRule(Frequency.WEEKLY, rule?.interval ?: 1, rule?.weekDays?.takeIf { rule.frequency == Frequency.WEEKLY } ?: emptySet(), rule?.end ?: RecurrenceEnd.Never))
                }, { Text(stringResource(R.string.weekly)) })
                FilterChip(rule?.frequency == Frequency.MONTHLY, { onChange(RecurrenceRule(Frequency.MONTHLY, rule?.interval ?: 1, end = rule?.end ?: RecurrenceEnd.Never)) }, { Text(stringResource(R.string.monthly)) })
                FilterChip(rule?.frequency == Frequency.YEARLY, { onChange(RecurrenceRule(Frequency.YEARLY, rule?.interval ?: 1, end = rule?.end ?: RecurrenceEnd.Never)) }, { Text(stringResource(R.string.yearly)) })
                AssistChip(onClick = { onChange(RecurrenceRule(Frequency.WEEKLY, 1, settings.workDays, rule?.end ?: RecurrenceEnd.Never)) }, label = { Text(stringResource(R.string.workdays_preset)) })
            }
            if (rule != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.every_n), modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = { if (rule.interval > 1) onChange(rule.copy(interval = rule.interval - 1)) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.decrease)) }
                    Text(fmt.number(rule.interval), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { if (rule.interval < 99) onChange(rule.copy(interval = rule.interval + 1)) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.increase)) }
                    Text(stringResource(when (rule.frequency) {
                        Frequency.DAILY -> R.string.unit_day
                        Frequency.WEEKLY -> R.string.unit_week
                        Frequency.MONTHLY -> R.string.unit_month
                        Frequency.YEARLY -> R.string.unit_year
                    }))
                }
                if (rule.frequency == Frequency.WEEKLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WeekMath.orderedDays(settings.firstDayOfWeek).forEach { d ->
                            val days = rule.weekDays.ifEmpty { start?.let { setOf(it.dayOfWeek) } ?: emptySet() }
                            FilterChip(d in days, {
                                val next = if (d in days) days - d else days + d
                                if (next.isNotEmpty()) onChange(rule.copy(weekDays = next))
                            }, { Text(PersianNames.weekday(d)) })
                        }
                    }
                }
                Text(stringResource(R.string.repeat_end), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(rule.end == RecurrenceEnd.Never, { onChange(rule.copy(end = RecurrenceEnd.Never)) }, { Text(stringResource(R.string.end_never)) })
                    FilterChip(rule.end is RecurrenceEnd.Until, { untilPicker = true }, {
                        Text((rule.end as? RecurrenceEnd.Until)?.let { stringResource(R.string.end_until_value, fmt.date(it.date)) } ?: stringResource(R.string.end_until))
                    })
                    FilterChip(rule.end is RecurrenceEnd.Count, {
                        onChange(rule.copy(end = RecurrenceEnd.Count((rule.end as? RecurrenceEnd.Count)?.count ?: 10)))
                    }, { Text(stringResource(R.string.end_count)) })
                }
                (rule.end as? RecurrenceEnd.Count)?.let { cnt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (cnt.count > 1) onChange(rule.copy(end = RecurrenceEnd.Count(cnt.count - 1))) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.decrease)) }
                        Text(stringResource(R.string.times_count, fmt.number(cnt.count)))
                        IconButton(onClick = { onChange(rule.copy(end = RecurrenceEnd.Count(cnt.count + 1))) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.increase)) }
                    }
                }
            }
        }
    }
    if (untilPicker && rule != null) {
        JalaliDatePickerDialog((rule.end as? RecurrenceEnd.Until)?.date ?: start?.plusMonths(1), start ?: LocalDate.now(), { untilPicker = false },
            { d -> if (d != null) onChange(rule.copy(end = RecurrenceEnd.Until(d))) }, title = stringResource(R.string.end_until))
    }
}

@Composable
private fun SubtaskEditor(subtasks: List<Subtask>, vm: TaskEditorViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Column {
        Text(stringResource(R.string.subtasks), style = MaterialTheme.typography.titleSmall)
        subtasks.forEachIndexed { index, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.isDone, onCheckedChange = { v -> vm.updateSubtasks { l -> l.map { if (it.id == s.id) it.copy(isDone = v) else it } } })
                OutlinedTextField(
                    value = s.title, onValueChange = { v -> vm.updateSubtasks { l -> l.map { if (it.id == s.id) it.copy(title = v) else it } } },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    vm.updateSubtasks { l -> if (index > 0) l.toMutableList().apply { add(index - 1, removeAt(index)) } else l }
                }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up)) }
                IconButton(onClick = { vm.updateSubtasks { l -> l.filter { it.id != s.id } } }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, placeholder = { Text(stringResource(R.string.add_subtask)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.addSubtask(input); input = "" }),
            trailingIcon = { IconButton(onClick = { vm.addSubtask(input); input = "" }) { Icon(Icons.Default.Add, stringResource(R.string.add)) } },
        )
    }
}
