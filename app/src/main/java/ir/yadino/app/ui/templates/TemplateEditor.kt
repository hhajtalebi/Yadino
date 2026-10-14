package ir.yadino.app.ui.templates

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import ir.yadino.app.ui.components.PrioritySelector
import ir.yadino.app.ui.components.TagEditor
import ir.yadino.app.ui.components.TimePickerDialog
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.components.priorityColor
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.tasks.FieldRow
import ir.yadino.app.ui.tasks.GoalPicker
import ir.yadino.app.ui.tasks.ReminderPicker
import ir.yadino.app.ui.tasks.kindLabel
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.planning.PlanTemplates
import ir.yadino.domain.validation.ValidationError
import ir.yadino.domain.validation.Validators
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

data class TemplateEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val template: PlanTemplate? = null,
    val items: List<TemplateItem> = emptyList(),
    val errors: List<ValidationError> = emptyList(),
)

class TemplateEditorViewModel(c: AppContainer, private val route: Route.TemplateEditor) : YadinoViewModel(c) {
    private val _state = MutableStateFlow(TemplateEditorState())
    val state: StateFlow<TemplateEditorState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val goals: StateFlow<List<Goal>> = c.goals.goals.map { l -> l.filter { !it.isArchived && it.status != GoalStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var initial: Pair<PlanTemplate?, List<TemplateItem>>? = null

    init {
        viewModelScope.launch {
            val existing = route.templateId?.let { c.templates.get(it) }
            if (route.templateId != null && existing == null) {
                message(UiMessage.Text(R.string.item_not_found))
                closed.tryEmit(Unit)
                return@launch
            }
            val state = if (existing != null) {
                TemplateEditorState(true, false, existing, c.templates.getItems(existing.id))
            } else {
                val now = c.clock.now()
                TemplateEditorState(true, true, PlanTemplate(Ids.newId(), "", createdAt = now, updatedAt = now))
            }
            _state.value = state
            initial = state.template to state.items
        }
    }

    fun updateTemplate(transform: (PlanTemplate) -> PlanTemplate) {
        val s = _state.value
        val t = s.template ?: return
        _state.value = s.copy(template = transform(t), errors = emptyList())
    }

    /** Switches between a day and a week template, moving the items accordingly. */
    fun setWeek(week: Boolean, firstDayOfWeek: DayOfWeek) {
        val s = _state.value
        val t = s.template ?: return
        if (t.isWeek == week) return
        _state.value = s.copy(
            template = t.copy(spanDays = if (week) PlanTemplate.WEEK else PlanTemplate.DAY),
            items = PlanTemplates.changeSpan(s.items, week, firstDayOfWeek),
            errors = emptyList(),
        )
    }

    /** A new, unsaved item on [dayOffset] with the default reminder from the settings. */
    fun newItem(dayOffset: Int): TemplateItem {
        val now = c.clock.now()
        val s = _state.value
        return TemplateItem(
            id = Ids.newId(), templateId = s.template?.id ?: "", title = "", dayOffset = dayOffset,
            reminderOffsetMinutes = c.settings.currentOrDefault().defaultReminderOffsetMinutes,
            sortOrder = (s.items.maxOfOrNull { it.sortOrder } ?: -1) + 1, createdAt = now, updatedAt = now,
        )
    }

    fun putItem(item: TemplateItem) {
        val s = _state.value
        val items = if (s.items.any { it.id == item.id }) s.items.map { if (it.id == item.id) item else it } else s.items + item
        _state.value = s.copy(items = items, errors = emptyList())
    }

    fun removeItem(id: String) {
        val s = _state.value
        _state.value = s.copy(items = s.items.filter { it.id != id }, errors = emptyList())
    }

    val isDirty: Boolean
        get() {
            val i = initial ?: return false
            val s = _state.value
            return s.template != i.first || s.items != i.second
        }

    fun save() = launchSafe(onError = { e -> if (e is ValidationException) _state.value = _state.value.copy(errors = e.errors) }) {
        val s = _state.value
        val t = s.template ?: return@launchSafe
        val saved = c.templates.save(t, s.items.sortedWith(PlanTemplates.storedOrder))
        initial = s.template to s.items
        message(UiMessage.Text(R.string.msg_template_saved, listOf(saved.name)))
        closed.tryEmit(Unit)
    }

    fun delete() = launchSafe {
        val s = _state.value
        val t = s.template ?: return@launchSafe
        if (!s.isNew) c.templates.delete(t.id)
        initial = s.template to s.items
        closed.tryEmit(Unit)
    }
}

private val NAME_ERRORS = setOf(ValidationError.NAME_REQUIRED, ValidationError.NAME_TOO_LONG, ValidationError.NAME_DUPLICATE)

@Composable
fun TemplateEditorScreen(route: Route.TemplateEditor) {
    val c = LocalContainer.current
    val vm: TemplateEditorViewModel = viewModel { TemplateEditorViewModel(c, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    CollectMessages(vm.messages)
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmToDay by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TemplateItem?>(null) }
    BackHandler(enabled = vm.isDirty) { confirmDiscard = true }

    val template = state.template
    YadinoScaffold(topBar = {
        YadinoTopBar(
            stringResource(if (state.isNew) R.string.template_new else R.string.template_edit),
            onBack = { if (vm.isDirty) confirmDiscard = true else nav.back() },
        ) {
            if (!state.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            TextButton(onClick = vm::save, enabled = template != null) { Text(stringResource(R.string.save)) }
        }
    }) { padding ->
        if (template == null) return@YadinoScaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val nameError = state.errors.firstOrNull { it in NAME_ERRORS }
            OutlinedTextField(
                value = template.name, onValueChange = { v -> vm.updateTemplate { it.copy(name = v) } },
                label = { Text(stringResource(R.string.template_name)) },
                placeholder = { Text(stringResource(R.string.template_name_hint)) },
                singleLine = true, isError = nameError != null,
                supportingText = if (nameError != null) { { Text(validationText(nameError)) } } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = template.description, onValueChange = { v -> vm.updateTemplate { it.copy(description = v) } },
                label = { Text(stringResource(R.string.description)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.template_span), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !template.isWeek, shape = SegmentedButtonDefaults.itemShape(0, 2),
                    onClick = { if (template.isWeek) { if (state.items.isEmpty()) vm.setWeek(false, settings.firstDayOfWeek) else confirmToDay = true } },
                ) { Text(stringResource(R.string.template_span_day)) }
                SegmentedButton(
                    selected = template.isWeek, shape = SegmentedButtonDefaults.itemShape(1, 2),
                    onClick = { vm.setWeek(true, settings.firstDayOfWeek) },
                ) { Text(stringResource(R.string.template_span_week)) }
            }
            if (template.isWeek) {
                Text(stringResource(R.string.template_week_note, PersianNames.weekday(settings.firstDayOfWeek)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Text(stringResource(R.string.template_items), style = MaterialTheme.typography.titleMedium)
            state.errors.filter { it !in NAME_ERRORS }.forEach { Text(validationText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val order = PlanTemplates.itemOrder(template, settings.firstDayOfWeek)
            if (template.isWeek) {
                WeekMath.orderedDays(settings.firstDayOfWeek).forEach { day ->
                    val offset = PlanTemplates.offsetOf(day)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(PersianNames.weekday(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editing = vm.newItem(offset) }) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.template_add_item))
                        }
                    }
                    state.items.filter { Math.floorMod(it.anchorOffset, 7) == offset }.sortedWith(order).forEach { item ->
                        TemplateItemRow(item, onClick = { editing = item }, onRemove = { vm.removeItem(item.id) })
                    }
                }
            } else {
                if (state.items.isEmpty()) {
                    Text(stringResource(R.string.template_no_items), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.items.sortedWith(order).forEach { item -> TemplateItemRow(item, onClick = { editing = item }, onRemove = { vm.removeItem(item.id) }) }
                OutlinedButton(onClick = { editing = vm.newItem(0) }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.template_add_item))
                }
            }
            Spacer(Modifier.size(80.dp))
        }
    }

    editing?.let { item ->
        if (template != null) {
            TemplateItemDialog(
                template, item, isNew = state.items.none { it.id == item.id }, categories, goals,
                onDismiss = { editing = null },
                onDone = { vm.putItem(it); editing = null },
            )
        }
    }
    if (confirmToDay) {
        ConfirmDialog(stringResource(R.string.template_to_day_title), stringResource(R.string.template_to_day_message), stringResource(R.string.confirm),
            onConfirm = { vm.setWeek(false, settings.firstDayOfWeek) }, onDismiss = { confirmToDay = false })
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_changes_message), stringResource(R.string.discard),
            onConfirm = { nav.back() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.template_delete_title), stringResource(R.string.template_delete_message, template?.name ?: ""),
            stringResource(R.string.delete), onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

/** Deadline of an item as text ("همان روز، ۱۷:۰۰"), or null without one. */
@Composable
private fun dueText(item: TemplateItem): String? {
    val fmt = LocalFormatter.current
    val due = item.dueOffset ?: return null
    val days = item.dayOffset?.let { due - it } ?: 0
    val text = if (days <= 0) stringResource(R.string.template_due_same_day) else stringResource(R.string.template_due_after_days, fmt.number(days))
    return text + (item.dueTime?.let { "، " + fmt.time(it) } ?: "")
}

@Composable
private fun TemplateItemRow(item: TemplateItem, onClick: () -> Unit, onRemove: () -> Unit) {
    val fmt = LocalFormatter.current
    val details = buildList {
        add(if (item.dayOffset == null) stringResource(R.string.deadline) else fmt.timeRange(item.startTime, item.endTime) ?: stringResource(R.string.all_day))
        dueText(item)?.let { add(stringResource(R.string.template_due_label, it)) }
        item.reminderOffsetMinutes?.let { add(stringResource(R.string.reminder) + ": " + fmt.minutesOffset(it)) }
        if (item.subtasks.isNotEmpty()) add(stringResource(R.string.template_item_subtasks, fmt.number(item.subtasks.size)))
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon(item.kind), null, tint = priorityColor(item.priority))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.template_remove_item)) }
    }
}

/** Moves an item to another day of a week template; a deadline keeps its distance. */
private fun TemplateItem.movedTo(offset: Int): TemplateItem {
    val day = dayOffset ?: return copy(dueOffset = offset)
    return copy(dayOffset = offset, dueOffset = dueOffset?.let { it + offset - day })
}

/** Removes the deadline; a deadline-only item is then planned on its day instead. */
private fun TemplateItem.withoutDeadline(): TemplateItem =
    if (dayOffset == null) copy(dayOffset = dueOffset, dueOffset = null, dueTime = null) else copy(dueOffset = null, dueTime = null)

/** Sets the deadline [days] after the planned day (a deadline-only item gets planned for longer ones). */
private fun TemplateItem.withDeadlineAfter(days: Int): TemplateItem {
    val day = dayOffset
    return when {
        day != null -> copy(dueOffset = day + days)
        days == 0 -> this
        else -> copy(dayOffset = dueOffset, dueOffset = (dueOffset ?: 0) + days)
    }
}

private val DEADLINE_CHOICES = listOf(0, 1, 2, 3, 7)

/** Full screen editor of one template item. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TemplateItemDialog(
    template: PlanTemplate,
    initial: TemplateItem,
    isNew: Boolean,
    categories: List<Category>,
    goals: List<Goal>,
    onDismiss: () -> Unit,
    onDone: (TemplateItem) -> Unit,
) {
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    var item by remember(initial.id) { mutableStateOf(initial) }
    var errors by remember { mutableStateOf(emptyList<ValidationError>()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var deadlineMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (item != initial) confirmDiscard = true else onDismiss() }
    fun update(transform: (TemplateItem) -> TemplateItem) {
        item = transform(item)
        errors = emptyList()
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(
                    title = { Text(stringResource(if (isNew) R.string.template_item_new else R.string.template_item_edit)) },
                    navigationIcon = { IconButton(onClick = dismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                    actions = {
                        TextButton(onClick = {
                            val cleaned = item.copy(title = item.title.trim(), subtasks = PlanTemplates.cleanSubtasks(item.subtasks))
                            val e = Validators.templateItem(cleaned, template.spanDays)
                            if (e.isEmpty()) onDone(cleaned) else errors = e
                        }) { Text(stringResource(R.string.confirm)) }
                    },
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TaskKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(
                                selected = item.kind == k, onClick = { update { it.copy(kind = k) } },
                                shape = SegmentedButtonDefaults.itemShape(i, TaskKind.entries.size),
                            ) { Text(kindLabel(k)) }
                        }
                    }
                    val titleError = errors.firstOrNull { it == ValidationError.TITLE_REQUIRED || it == ValidationError.TITLE_TOO_LONG }
                    OutlinedTextField(
                        value = item.title, onValueChange = { v -> update { it.copy(title = v) } },
                        label = { Text(stringResource(R.string.title)) }, maxLines = 3, isError = titleError != null,
                        supportingText = if (titleError != null) { { Text(validationText(titleError)) } } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (template.isWeek) {
                        Text(stringResource(R.string.template_item_day), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WeekMath.orderedDays(settings.firstDayOfWeek).forEach { d ->
                                val offset = PlanTemplates.offsetOf(d)
                                FilterChip(item.anchorOffset == offset, { update { it.movedTo(offset) } }, { Text(PersianNames.weekday(d)) })
                            }
                        }
                    }
                    if (item.dayOffset != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldRow(Icons.Default.Schedule, stringResource(R.string.start_time), item.startTime?.let { fmt.time(it) } ?: stringResource(R.string.all_day),
                                onClick = { picker = "start" }, modifier = Modifier.weight(1f),
                                onClear = item.startTime?.let { { update { t -> t.copy(startTime = null, endTime = null) } } })
                            if (item.startTime != null) {
                                FieldRow(Icons.Default.Schedule, stringResource(R.string.end_time), item.endTime?.let { fmt.time(it) } ?: "—",
                                    onClick = { picker = "end" }, modifier = Modifier.weight(1f),
                                    onClear = item.endTime?.let { { update { t -> t.copy(endTime = null) } } })
                            }
                        }
                    } else {
                        Text(stringResource(R.string.template_deadline_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { update { it.copy(dayOffset = it.dueOffset) } }) { Text(stringResource(R.string.template_plan_on_day)) }
                    }
                    errors.filter { it != ValidationError.TITLE_REQUIRED && it != ValidationError.TITLE_TOO_LONG }.forEach {
                        Text(validationText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.kind != TaskKind.EVENT) {
                        Text(stringResource(R.string.priority), style = MaterialTheme.typography.labelLarge)
                        PrioritySelector(item.priority) { p -> update { it.copy(priority = p) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CategoryPicker(categories, item.categoryId, { id -> update { it.copy(categoryId = id) } })
                        GoalPicker(goals, item.goalId) { id -> update { it.copy(goalId = id) } }
                    }
                    HorizontalDivider()
                    if (item.kind != TaskKind.EVENT || item.dueOffset != null) {
                        Box {
                            FieldRow(Icons.Default.HourglassBottom, stringResource(R.string.deadline), dueText(item) ?: stringResource(R.string.no_deadline),
                                onClick = { deadlineMenu = true },
                                onClear = item.dueOffset?.let { { update { t -> t.withoutDeadline() } } })
                            DropdownMenu(expanded = deadlineMenu, onDismissRequest = { deadlineMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.no_deadline)) }, onClick = { update { it.withoutDeadline() }; deadlineMenu = false })
                                DEADLINE_CHOICES.forEach { d ->
                                    DropdownMenuItem(
                                        text = { Text(if (d == 0) stringResource(R.string.template_due_same_day) else stringResource(R.string.template_due_after_days, fmt.number(d))) },
                                        onClick = { update { it.withDeadlineAfter(d) }; deadlineMenu = false },
                                    )
                                }
                            }
                        }
                        if (item.dueOffset != null) {
                            TextButton(onClick = { picker = "dueTime" }) {
                                Text(item.dueTime?.let { stringResource(R.string.deadline_time_value, fmt.time(it)) } ?: stringResource(R.string.add_deadline_time))
                            }
                        }
                    }
                    ReminderPicker(item.reminderOffsetMinutes, enabled = true) { v -> update { it.copy(reminderOffsetMinutes = v) } }
                    HorizontalDivider()
                    if (item.kind != TaskKind.EVENT) SubtaskTitlesEditor(item.subtasks) { list -> update { it.copy(subtasks = list) } }
                    OutlinedTextField(
                        value = item.description, onValueChange = { v -> update { it.copy(description = v) } },
                        label = { Text(stringResource(R.string.description)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    TagEditor(item.tags, { tags -> update { it.copy(tags = tags) } })
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }

    when (picker) {
        "start" -> TimePickerDialog(item.startTime ?: settings.defaultTaskTime ?: LocalTime.of(9, 0), { picker = null }, { tm ->
            update { t ->
                val end = t.endTime?.let { e -> if (t.startTime != null) tm.plusMinutes(java.time.Duration.between(t.startTime, e).toMinutes()) else e }
                t.copy(startTime = tm, endTime = end?.takeIf { it.isAfter(tm) })
            }
        }, onClear = { update { it.copy(startTime = null, endTime = null) } })
        "end" -> TimePickerDialog(item.endTime ?: (item.startTime ?: LocalTime.of(9, 0)).plusMinutes(settings.defaultTaskDurationMinutes.toLong()),
            { picker = null }, { tm -> update { it.copy(endTime = tm) } }, onClear = { update { it.copy(endTime = null) } })
        "dueTime" -> TimePickerDialog(item.dueTime ?: LocalTime.of(17, 0), { picker = null }, { tm -> update { it.copy(dueTime = tm) } },
            onClear = { update { it.copy(dueTime = null) } })
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_changes_message), stringResource(R.string.discard),
            onConfirm = onDismiss, onDismiss = { confirmDiscard = false }, destructive = true)
    }
}

@Composable
private fun SubtaskTitlesEditor(titles: List<String>, onChange: (List<String>) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    fun add() {
        val v = input.trim()
        if (v.isNotEmpty()) onChange(titles + v)
        input = ""
    }
    Column {
        Text(stringResource(R.string.subtasks), style = MaterialTheme.typography.titleSmall)
        titles.forEachIndexed { index, title ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = title, onValueChange = { v -> onChange(titles.toMutableList().also { it[index] = v }) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(titles.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
                }
                IconButton(onClick = { onChange(titles.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, placeholder = { Text(stringResource(R.string.add_subtask)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { IconButton(onClick = { add() }) { Icon(Icons.Default.Add, stringResource(R.string.add)) } },
        )
    }
}
