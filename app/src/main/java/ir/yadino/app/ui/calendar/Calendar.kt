package ir.yadino.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.core.Formatter
import ir.yadino.app.data.repository.ValidationException
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.TaskRow
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.common.todayFlow
import ir.yadino.app.ui.common.validationText
import ir.yadino.app.ui.components.JalaliDatePickerDialog
import ir.yadino.app.ui.components.MonthYearPickerDialog
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.templates.ApplyTemplateDialog
import ir.yadino.app.ui.templates.TemplatePickerDialog
import ir.yadino.app.ui.templates.appliedMessage
import ir.yadino.app.ui.theme.HolidayRed
import ir.yadino.app.ui.theme.HolidayRedDark
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.IranianOccasions
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.model.CalendarView
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Task
import ir.yadino.domain.planning.DayLayout
import ir.yadino.domain.planning.PlannedItem
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.validation.ValidationError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

data class CalendarData(
    val tasks: List<Task> = emptyList(),
    val categories: Map<String, Category> = emptyMap(),
    val goals: List<Goal> = emptyList(),
    val subtasks: Map<String, Pair<Int, Int>> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
)

class CalendarViewModel(c: AppContainer, initialDate: LocalDate?) : YadinoViewModel(c) {
    val selected = MutableStateFlow(initialDate ?: c.clock.today())
    val view = MutableStateFlow(c.settings.currentOrDefault().defaultCalendarView)

    val data: StateFlow<CalendarData> = combine(c.tasks.tasks, c.categories.categories, c.goals.goals, c.tasks.subtaskProgress, todayFlow(c.clock)) { t, cats, g, s, d ->
        CalendarData(t, cats.associateBy { it.id }, g.filter { !it.isArchived && it.deadline != null }, s, d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarData(today = c.clock.today()))

    fun select(d: LocalDate) { selected.value = d }
    fun setView(v: CalendarView) { view.value = v }
    fun toggle(task: Task) = launchSafe { c.tasks.setCompleted(task, !task.isDone) }

    fun copyPlan(from: LocalDate, to: LocalDate, target: LocalDate) = launchSafe {
        val n = c.tasks.copyPlan(from, to, target)
        message(UiMessage.Text(if (n == 0) R.string.msg_nothing_to_copy else R.string.msg_copied_count, listOf(formatNumber(n))))
    }

    val templates: StateFlow<List<PlanTemplate>> = c.templates.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun applyTemplate(template: PlanTemplate, date: LocalDate, skipDuplicates: Boolean) = launchSafe {
        val result = c.templates.apply(template, date, skipDuplicates)
        message(appliedMessage(template, result, ::formatNumber) { ids -> launchSafe { c.tasks.deleteMany(ids) } })
    }

    /** State of the "save plan as template" dialog. */
    data class TemplateSave(val week: Boolean, val start: LocalDate, val count: Int? = null, val errors: List<ValidationError> = emptyList())

    val templateSave = MutableStateFlow<TemplateSave?>(null)

    fun startTemplateSave(week: Boolean, start: LocalDate) {
        val request = TemplateSave(week, start)
        templateSave.value = request
        launchSafe(onError = { templateSave.value = null }) {
            val n = c.templates.planSize(start, if (week) PlanTemplate.WEEK else PlanTemplate.DAY)
            if (templateSave.value != request) return@launchSafe
            if (n == 0) {
                templateSave.value = null
                message(UiMessage.Text(R.string.msg_plan_empty_for_template))
            } else {
                templateSave.value = request.copy(count = n)
            }
        }
    }

    fun saveTemplate(name: String) {
        val request = templateSave.value ?: return
        launchSafe(onError = { e -> if (e is ValidationException) templateSave.value = request.copy(errors = e.errors) }) {
            val saved = c.templates.saveFromPlan(request.start, if (request.week) PlanTemplate.WEEK else PlanTemplate.DAY, name)
            templateSave.value = null
            message(UiMessage.Text(if (saved == null) R.string.msg_plan_empty_for_template else R.string.msg_template_saved, listOfNotNull(saved?.name)))
        }
    }

    fun cancelTemplateSave() {
        templateSave.value = null
    }

    /** Changes the time of a task from the day timeline (drag, resize or accessibility action), with undo. */
    fun retime(task: Task, startMinute: Int, endMinute: Int?) = launchSafe {
        val start = LocalTime.of(startMinute / 60, startMinute % 60)
        val end = endMinute?.takeIf { it in (startMinute + 1)..DayLayout.LAST_MINUTE }?.let { LocalTime.of(it / 60, it % 60) }
        if (start == task.startTime && end == task.endTime) return@launchSafe
        c.tasks.save(task.copy(startTime = start, endTime = end))
        val range = Formatter(c.settings.currentOrDefault()).timeRange(start, end) ?: ""
        message(UiMessage.WithUndo(R.string.msg_task_retimed, undo = { launchSafe { c.tasks.save(task) } }, args = listOf(task.title, range)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(route: Route.Calendar) {
    val c = LocalContainer.current
    val vm: CalendarViewModel = viewModel { CalendarViewModel(c, route.epochDay?.let { LocalDate.ofEpochDay(it) }) }
    val data by vm.data.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    val nav = LocalNavigator.current
    CollectMessages(vm.messages)
    var monthPicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var copyMode by rememberSaveable { mutableStateOf<String?>(null) }
    var templatePicker by remember { mutableStateOf(false) }
    var applyTemplate by remember { mutableStateOf<PlanTemplate?>(null) }
    val templates by vm.templates.collectAsStateWithLifecycle()
    val templateSave by vm.templateSave.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val baseMonth = remember { JalaliDate.from(data.today).monthIndex }
    val pager = rememberPagerState(initialPage = 600 + (JalaliDate.from(selected).monthIndex - baseMonth)) { 1201 }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            val month = JalaliDate.ofMonthIndex(baseMonth + page - 600)
            val sel = JalaliDate.from(vm.selected.value)
            if (sel.monthIndex != month.monthIndex) vm.select(month.withDayClamped(sel.day).toLocalDate())
        }
    }
    fun pageOf(d: LocalDate) = 600 + (JalaliDate.from(d).monthIndex - baseMonth)
    fun goTo(d: LocalDate) {
        vm.select(d)
        if (vm.view.value == CalendarView.MONTH) scope.launch { pager.animateScrollToPage(pageOf(d)) }
        else pager.requestScrollToPage(pageOf(d))
    }
    LaunchedEffect(view) {
        if (view == CalendarView.MONTH && pager.currentPage != pageOf(vm.selected.value)) pager.scrollToPage(pageOf(vm.selected.value))
    }

    val selJ = JalaliDate.from(selected)
    YadinoScaffold(
        topBar = {
            YadinoTopBar(
                title = when (view) {
                    CalendarView.DAY -> fmt.date(selected, DateFormatStyle.LONG)
                    else -> fmt.monthYear(selJ)
                },
            ) {
                IconButton(onClick = { goTo(data.today) }) { Icon(Icons.Default.Today, stringResource(R.string.go_to_today)) }
                TextButton(onClick = { monthPicker = true }) { Text(stringResource(R.string.pick_month_short)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.copy_day_plan)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; copyMode = "day" })
                        DropdownMenuItem(text = { Text(stringResource(R.string.copy_week_plan)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; copyMode = "week" })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.calendar_apply_template)) }, leadingIcon = { Icon(Icons.Default.PostAdd, null) },
                            onClick = { menu = false; templatePicker = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.calendar_save_day_template)) }, leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                            onClick = { menu = false; vm.startTemplateSave(false, selected) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.calendar_save_week_template)) }, leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                            onClick = { menu = false; vm.startTemplateSave(true, WeekMath.startOfWeek(selJ, settings.firstDayOfWeek).toLocalDate()) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.templates_title)) }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) },
                            onClick = { menu = false; nav.navigate(Route.Templates) })
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Route.TaskEditor(date = selected.toEpochDay())) }) {
                Icon(Icons.Default.Add, stringResource(R.string.add_for_date))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                CalendarView.entries.forEachIndexed { i, v ->
                    SegmentedButton(selected = view == v, onClick = { vm.setView(v) }, shape = SegmentedButtonDefaults.itemShape(i, CalendarView.entries.size)) {
                        Text(stringResource(when (v) {
                            CalendarView.MONTH -> R.string.view_month
                            CalendarView.WEEK -> R.string.view_week
                            CalendarView.DAY -> R.string.view_day
                        }))
                    }
                }
            }
            when (view) {
                CalendarView.MONTH -> {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), beyondViewportPageCount = 0) { page ->
                        val month = JalaliDate.ofMonthIndex(baseMonth + page - 600)
                        MonthGrid(month, selected, data, onSelect = { vm.select(it) })
                    }
                    HorizontalDivider()
                    DayAgenda(selected, data, vm, Modifier.weight(1f))
                }
                CalendarView.WEEK -> WeekView(selected, data, vm, onShift = { goTo(selected.plusWeeks(it.toLong())) })
                CalendarView.DAY -> DayView(selected, data, vm, onShift = { goTo(selected.plusDays(it.toLong())) })
            }
        }
    }

    if (monthPicker) MonthYearPickerDialog(selJ, { monthPicker = false }) { m -> goTo(m.toLocalDate()) }
    copyMode?.let { mode ->
        val (from, to) = if (mode == "day") selected to selected
        else WeekMath.startOfWeek(selJ, settings.firstDayOfWeek).toLocalDate() to WeekMath.endOfWeek(selJ, settings.firstDayOfWeek).toLocalDate()
        JalaliDatePickerDialog(
            initial = if (mode == "day") selected.plusDays(1) else from.plusWeeks(1), today = data.today,
            onDismiss = { copyMode = null },
            onPick = { target ->
                if (target != null) {
                    val start = if (mode == "day") target else WeekMath.startOfWeek(JalaliDate.from(target), settings.firstDayOfWeek).toLocalDate()
                    vm.copyPlan(from, to, start)
                }
            },
            title = stringResource(if (mode == "day") R.string.copy_day_target else R.string.copy_week_target),
        )
    }
    if (templatePicker) {
        TemplatePickerDialog(templates, onPick = { applyTemplate = it }, onManage = { nav.navigate(Route.Templates) }, onDismiss = { templatePicker = false })
    }
    applyTemplate?.let { t ->
        ApplyTemplateDialog(t, selected, onDismiss = { applyTemplate = null }, onApply = { date, skip ->
            vm.applyTemplate(t, date, skip)
            if (date != selected) goTo(date)
        })
    }
    templateSave?.let { request -> SaveTemplateDialog(request, onSave = vm::saveTemplate, onDismiss = vm::cancelTemplateSave) }
}

/** Name dialog for saving the plan of a day or a week as a template. */
@Composable
private fun SaveTemplateDialog(request: CalendarViewModel.TemplateSave, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val fmt = LocalFormatter.current
    val defaultName = if (request.week) stringResource(R.string.save_template_default_week)
    else stringResource(R.string.save_template_default_day, PersianNames.weekday(request.start.dayOfWeek))
    var name by rememberSaveable(request.start, request.week) { mutableStateOf(defaultName) }
    val nameError = request.errors.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (request.week) R.string.calendar_save_week_template else R.string.calendar_save_day_template)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val count = request.count
                if (count == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Text(
                        if (request.week) stringResource(R.string.save_template_count_week, fmt.number(count), fmt.date(request.start), fmt.date(request.start.plusDays(6)))
                        else stringResource(R.string.save_template_count_day, fmt.number(count), fmt.weekday(request.start) + " " + fmt.date(request.start)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.template_name)) },
                    isError = nameError != null,
                    supportingText = if (nameError != null) { { Text(validationText(nameError)) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = request.count != null && name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MonthGrid(month: JalaliDate, selected: LocalDate, data: CalendarData, onSelect: (LocalDate) -> Unit) {
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val red = if (dark) HolidayRedDark else HolidayRed
    val grid = remember(month.monthIndex, settings.firstDayOfWeek) { WeekMath.monthGrid(month, settings.firstDayOfWeek) }
    val rows = WeekMath.rowsNeeded(month, settings.firstDayOfWeek)
    val from = grid.first().date.toLocalDate()
    val to = grid.last().date.toLocalDate()
    val plan = remember(data.tasks, month.monthIndex, settings.showFutureOccurrences, settings.firstDayOfWeek) {
        TaskFilters.planForRange(data.tasks, from, to, settings.showFutureOccurrences, settings.firstDayOfWeek)
    }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (settings.showWeekNumbers) Spacer(Modifier.width(28.dp))
            WeekMath.orderedDays(settings.firstDayOfWeek).forEach { d ->
                Text(PersianNames.weekdayShort(d), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = if (d == DayOfWeek.FRIDAY) red else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (settings.showWeekNumbers) {
                    Text(fmt.number(WeekMath.weekOfYear(grid[r * 7].date, settings.firstDayOfWeek)), Modifier.width(28.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                for (col in 0 until 7) {
                    val cell = grid[r * 7 + col]
                    val date = cell.date.toLocalDate()
                    val items = plan[date].orEmpty()
                    val deadlines = data.tasks.count { it.dueDate == date && it.isOpen && it.date != date } + data.goals.count { it.deadline == date }
                    val holiday = date.dayOfWeek == DayOfWeek.FRIDAY || IranianOccasions.isHoliday(cell.date, settings.lunarAdjustment)
                    val isSel = date == selected
                    val isToday = date == data.today
                    val desc = fmt.date(date, DateFormatStyle.FULL) + if (items.isNotEmpty()) "، " + stringResource(R.string.n_items, fmt.number(items.size)) else ""
                    Column(
                        Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp).clip(MaterialTheme.shapes.small)
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .then(if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.small) else Modifier)
                            .clickable { onSelect(date) }
                            .semantics { contentDescription = desc },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            fmt.number(cell.date.day),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = (if (holiday) red else MaterialTheme.colorScheme.onSurface).copy(alpha = if (cell.inMonth) 1f else 0.35f),
                        )
                        if (settings.showGregorian) {
                            Text(fmt.number(date.dayOfMonth), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (cell.inMonth) 0.8f else 0.3f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                            items.take(3).forEach { item ->
                                val color = item.task.categoryId?.let { data.categories[it] }?.color?.accent(dark) ?: MaterialTheme.colorScheme.primary
                                Box(Modifier.size(5.dp).clip(CircleShape).background(color.copy(alpha = if (item.isProjected) 0.45f else 1f)))
                            }
                            if (deadlines > 0) Box(Modifier.size(5.dp).clip(CircleShape).background(red))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayAgenda(date: LocalDate, data: CalendarData, vm: CalendarViewModel, modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    val nav = LocalNavigator.current
    val dark = LocalIsDark.current
    val items = remember(data.tasks, date, settings.showFutureOccurrences) {
        TaskFilters.planForRange(data.tasks, date, date, settings.showFutureOccurrences, settings.firstDayOfWeek)[date].orEmpty()
    }
    val occasions = IranianOccasions.occasionsOf(JalaliDate.from(date), settings.occasionDisplay, settings.lunarAdjustment)
    val deadlines = data.tasks.filter { it.dueDate == date && it.date != date && it.isOpen }
    val goalDeadlines = data.goals.filter { it.deadline == date }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            Text(fmt.date(date, DateFormatStyle.FULL), style = MaterialTheme.typography.titleMedium)
            if (settings.showGregorian) Text(fmt.gregorian(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            occasions.forEach { o ->
                Text(o.title + if (o.isApproximate) " (" + stringResource(R.string.approximate) + ")" else "", style = MaterialTheme.typography.bodySmall,
                    color = if (o.isHoliday) (if (dark) HolidayRedDark else HolidayRed) else MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(4.dp))
        }
        if (items.isEmpty() && deadlines.isEmpty() && goalDeadlines.isEmpty()) {
            item {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EventBusy, null, tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.day_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(items, key = { it.task.id + it.date }) { item -> AgendaRow(item, data, vm) }
        if (deadlines.isNotEmpty()) {
            item { Text(stringResource(R.string.deadlines_today), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
            items(deadlines, key = { "d" + it.id }) { t ->
                TaskRow(t, data.today, { vm.toggle(t) }, { nav.navigate(Route.TaskEditor(taskId = t.id)) }, category = t.categoryId?.let { data.categories[it] })
            }
        }
        goalDeadlines.forEach { g ->
            item(key = "g" + g.id) {
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Route.GoalDetail(g.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Flag, null, tint = g.color.accent(dark))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.goal_deadline_label, g.title))
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(item: PlannedItem, data: CalendarData, vm: CalendarViewModel) {
    val nav = LocalNavigator.current
    TaskRow(
        task = item.task, today = data.today,
        onToggle = if (item.isProjected) null else ({ vm.toggle(item.task) }),
        onClick = { nav.navigate(Route.TaskEditor(taskId = item.task.id)) },
        category = item.task.categoryId?.let { data.categories[it] }, subtasks = data.subtasks[item.task.id],
        showDate = false, projectedDate = if (item.isProjected) item.date else null,
    )
}

@Composable
private fun WeekView(selected: LocalDate, data: CalendarData, vm: CalendarViewModel, onShift: (Int) -> Unit) {
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val nav = LocalNavigator.current
    val days = WeekMath.weekDates(JalaliDate.from(selected), settings.firstDayOfWeek).map { it.toLocalDate() }
    val plan = remember(data.tasks, days.first(), settings.showFutureOccurrences) {
        TaskFilters.planForRange(data.tasks, days.first(), days.last(), settings.showFutureOccurrences, settings.firstDayOfWeek)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.previous_week)) }
            Text(
                fmt.shortDate(days.first()) + " – " + fmt.shortDate(days.last()) + if (settings.showWeekNumbers) " • " + fmt.weekNumber(days.first()) else "",
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { onShift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.next_week)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days, key = { it.toEpochDay() }) { d ->
                val items = plan[d].orEmpty()
                val holiday = d.dayOfWeek == DayOfWeek.FRIDAY || IranianOccasions.isHoliday(JalaliDate.from(d), settings.lunarAdjustment)
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (d == data.today) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().clickable { vm.select(d); vm.setView(CalendarView.DAY) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt.weekday(d) + " " + fmt.shortDate(d), style = MaterialTheme.typography.titleSmall,
                                color = if (holiday) (if (dark) HolidayRedDark else HolidayRed) else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            IconButton(onClick = { nav.navigate(Route.TaskEditor(date = d.toEpochDay())) }) { Icon(Icons.Default.Add, stringResource(R.string.add_for_date)) }
                        }
                        IranianOccasions.occasionsOf(JalaliDate.from(d), settings.occasionDisplay, settings.lunarAdjustment).forEach { o ->
                            Text(o.title + if (o.isApproximate) " (" + stringResource(R.string.approximate) + ")" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (items.isEmpty()) Text(stringResource(R.string.day_empty_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        items.forEach { AgendaRow(it, data, vm) }
                    }
                }
            }
        }
    }
}

/**
 * Timeline of a single day with work hours shaded; untimed items are listed on top.
 * Overlapping items are placed side by side. A long press drags an item to another time
 * (15-minute steps, duration kept); the handle at its bottom edge changes the end time.
 * Both offer undo; TalkBack users get the same changes as custom actions.
 */
@Composable
private fun DayView(selected: LocalDate, data: CalendarData, vm: CalendarViewModel, onShift: (Int) -> Unit) {
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    val density = LocalDensity.current
    val items = remember(data.tasks, selected, settings.showFutureOccurrences) {
        TaskFilters.planForRange(data.tasks, selected, selected, settings.showFutureOccurrences, settings.firstDayOfWeek)[selected].orEmpty()
    }
    val untimed = items.filter { it.task.startTime == null }
    val timed = items.filter { it.task.startTime != null }
    val hourHeight = 56.dp
    val hourPx = with(density) { hourHeight.toPx() }
    val scroll = rememberScrollState()
    val isToday = selected == data.today
    val now by produceState(c.clock.nowTime(), isToday) {
        while (isToday) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = c.clock.nowTime()
        }
    }
    LaunchedEffect(selected) {
        val firstItem = timed.minOfOrNull { it.task.startTime!!.hour }
        val hour = when {
            isToday -> now.hour - 1
            firstItem != null -> minOf(firstItem, settings.workDayStart.hour)
            else -> settings.workDayStart.hour
        }.coerceIn(0, 20)
        scroll.scrollTo((hourPx * hour).roundToInt())
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.previous_day)) }
            Text(fmt.date(selected, DateFormatStyle.FULL), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { onShift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.next_day)) }
        }
        if (timed.any { !it.isProjected }) {
            Text(stringResource(R.string.day_drag_hint), Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (untimed.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.all_day), style = MaterialTheme.typography.labelLarge)
                untimed.forEach { AgendaRow(it, data, vm) }
            }
            HorizontalDivider()
        }
        DayTimeline(
            timed, data.categories, selected, isToday, now, scroll,
            onAddAt = { minute -> nav.navigate(Route.TaskEditor(date = selected.toEpochDay(), startMinute = minute)) },
            onOpen = { task -> nav.navigate(Route.TaskEditor(taskId = task.id)) },
            onRetime = vm::retime,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * The 24-hour grid of the day view with its items. Separate from the screen so that it can
 * be tested without a database: [onRetime] receives the new start and end minute (end null =
 * no end time) after a drag, a resize or an accessibility action.
 */
@Composable
internal fun DayTimeline(
    timed: List<PlannedItem>,
    categories: Map<String, Category>,
    day: LocalDate,
    isToday: Boolean,
    now: LocalTime,
    scroll: ScrollState,
    onAddAt: (minute: Int) -> Unit,
    onOpen: (Task) -> Unit,
    onRetime: (task: Task, startMinute: Int, endMinute: Int?) -> Unit,
    modifier: Modifier = Modifier,
    hourHeight: Dp = 56.dp,
) {
    val settings = LocalAppSettings.current
    val fmt = LocalFormatter.current
    val hourPx = with(LocalDensity.current) { hourHeight.toPx() }
    val placements = remember(timed) {
        DayLayout.layout(timed.map { item ->
            val start = item.task.startTime!!.toMinuteOfDay()
            val end = item.task.endTime?.toMinuteOfDay()?.takeIf { it > start } ?: minOf(start + DEFAULT_BLOCK_MINUTES, DayLayout.DAY_MINUTES)
            // short items are drawn at least half an hour tall, so they take part in overlaps as such
            DayLayout.Block(item, start, maxOf(end, minOf(start + MIN_BLOCK_MINUTES, DayLayout.DAY_MINUTES)))
        })
    }
    Box(modifier.verticalScroll(scroll).padding(bottom = 96.dp)) {
        Column {
            for (h in 0 until 24) {
                val work = h >= settings.workDayStart.hour && h < settings.workDayEnd.hour.coerceAtLeast(settings.workDayStart.hour + 1) &&
                    day.dayOfWeek in settings.workDays
                val hourText = fmt.time(LocalTime.of(h, 0))
                Row(
                    Modifier.fillMaxWidth().height(hourHeight)
                        .background(if (work) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(onClickLabel = stringResource(R.string.add_at_time, hourText)) { onAddAt(h * 60) },
                ) {
                    Text(hourText, Modifier.width(64.dp).padding(start = 8.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(hourHeight * 24)) {
            val gutter = 68.dp
            val area = maxWidth - gutter - 8.dp
            placements.forEach { p ->
                key(p.key.task.id, p.key.date) {
                    TimelineBlock(p, categories, hourHeight, hourPx, x = gutter + area / p.columns * p.column, width = area / p.columns, onOpen, onRetime)
                }
            }
        }
        if (isToday) {
            val y = hourHeight * (now.hour + now.minute / 60f)
            val color = MaterialTheme.colorScheme.error
            Box(Modifier.offset(y = y - 1.dp).padding(start = 64.dp).fillMaxWidth().height(2.dp).background(color))
            Box(Modifier.offset(x = 60.dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(color))
        }
    }
}

private const val DEFAULT_BLOCK_MINUTES = 45
private const val MIN_BLOCK_MINUTES = 30

private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

/** One item on the day timeline; stored items can be dragged and resized. */
@Composable
private fun TimelineBlock(
    p: DayLayout.Placement<PlannedItem>,
    categories: Map<String, Category>,
    hourHeight: Dp,
    hourPx: Float,
    x: Dp,
    width: Dp,
    onOpen: (Task) -> Unit,
    onRetime: (Task, Int, Int?) -> Unit,
) {
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val haptics = LocalHapticFeedback.current
    val t = p.key.task
    val editable = !p.key.isProjected
    val storedEnd = t.endTime?.toMinuteOfDay()
    var mode by remember { mutableStateOf(DragMode.NONE) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    // the item changed underneath (saved, or edited elsewhere): a running gesture is void
    LaunchedEffect(t, p.start, p.end) {
        mode = DragMode.NONE
        dragPx = 0f
    }
    val deltaMinutes = (dragPx / hourPx * 60).roundToInt()
    val (start, end) = when (mode) {
        DragMode.MOVE -> DayLayout.move(p.start, p.end, deltaMinutes)
        DragMode.RESIZE -> p.start to DayLayout.resize(p.start, p.end, deltaMinutes)
        DragMode.NONE -> p.start to p.end
    }
    val shownEnd = maxOf(end, minOf(start + MIN_BLOCK_MINUTES, DayLayout.DAY_MINUTES))
    val color = t.categoryId?.let { categories[it] }?.color?.accent(dark) ?: MaterialTheme.colorScheme.primary
    val shape = MaterialTheme.shapes.small
    val active = mode != DragMode.NONE

    fun commitMove(newStart: Int, newEnd: Int) {
        if (newStart != p.start) onRetime(t, newStart, if (storedEnd != null) newEnd else null)
    }
    fun commitResize(newEnd: Int) {
        if (newEnd != p.end && newEnd > p.start) onRetime(t, p.start, newEnd)
    }

    val earlier = stringResource(R.string.a11y_move_earlier, fmt.number(DayLayout.STEP))
    val later = stringResource(R.string.a11y_move_later, fmt.number(DayLayout.STEP))
    val longer = stringResource(R.string.a11y_extend, fmt.number(DayLayout.STEP))
    val shorter = stringResource(R.string.a11y_shorten, fmt.number(DayLayout.STEP))
    val timeText = fmt.timeRange(LocalTime.of(start / 60, start % 60), (if (storedEnd != null || mode == DragMode.RESIZE) end else null)
        ?.takeIf { it <= DayLayout.LAST_MINUTE }?.let { LocalTime.of(it / 60, it % 60) }) ?: ""
    Box(
        Modifier
            .offset(x = x, y = hourHeight * (start / 60f))
            .width(width - 2.dp)
            .height(hourHeight * ((shownEnd - start) / 60f))
            .zIndex(if (active) 1f else 0f)
            .shadow(if (active) 6.dp else 0.dp, shape)
            .clip(shape)
            .background(lerp(MaterialTheme.colorScheme.surface, color, if (p.key.isProjected) 0.12f else if (active) 0.35f else 0.22f))
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .then(
                if (editable) {
                    Modifier.pointerInput(t, p.start, p.end) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragPx = 0f
                                mode = DragMode.MOVE
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragPx += amount.y
                            },
                            onDragEnd = {
                                val (newStart, newEnd) = DayLayout.move(p.start, p.end, (dragPx / hourPx * 60).roundToInt())
                                mode = DragMode.NONE
                                dragPx = 0f
                                commitMove(newStart, newEnd)
                            },
                            onDragCancel = {
                                mode = DragMode.NONE
                                dragPx = 0f
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable { onOpen(t) }
            .semantics {
                if (editable) {
                    customActions = listOf(
                        CustomAccessibilityAction(earlier) { DayLayout.move(p.start, p.end, -DayLayout.STEP).let { (s, e) -> commitMove(s, e) }; true },
                        CustomAccessibilityAction(later) { DayLayout.move(p.start, p.end, DayLayout.STEP).let { (s, e) -> commitMove(s, e) }; true },
                        CustomAccessibilityAction(longer) { commitResize(DayLayout.resize(p.start, p.end, DayLayout.STEP)); true },
                        CustomAccessibilityAction(shorter) { commitResize(DayLayout.resize(p.start, p.end, -DayLayout.STEP)); true },
                    )
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(t.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (shownEnd - start >= 40 || active) Text(timeText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (editable) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp)
                    .pointerInput(t, p.start, p.end) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragPx = 0f
                                mode = DragMode.RESIZE
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragPx += amount
                            },
                            onDragEnd = {
                                val newEnd = DayLayout.resize(p.start, p.end, (dragPx / hourPx * 60).roundToInt())
                                mode = DragMode.NONE
                                dragPx = 0f
                                commitResize(newEnd)
                            },
                            onDragCancel = {
                                mode = DragMode.NONE
                                dragPx = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(color.copy(alpha = 0.7f)))
            }
        }
    }
}

private enum class DragMode { NONE, MOVE, RESIZE }
