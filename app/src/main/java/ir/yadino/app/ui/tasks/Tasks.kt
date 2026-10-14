package ir.yadino.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.EmptyState
import ir.yadino.app.ui.components.JalaliDatePickerDialog
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.planning.TaskListFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** Filters available in the task list (IMPORTANT is a dashboard shortcut). */
enum class TaskTab { TODAY, UPCOMING, OVERDUE, IMPORTANT, NO_DATE, DONE, ALL, ARCHIVED }

data class TasksState(
    val tab: TaskTab = TaskTab.TODAY,
    val query: String = "",
    val categoryId: String? = null,
    val tasks: List<Task> = emptyList(),
    val categories: List<Category> = emptyList(),
    val subtasks: Map<String, Pair<Int, Int>> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
)

class TasksViewModel(c: AppContainer, initial: TaskTab) : YadinoViewModel(c) {
    private val tab = MutableStateFlow(initial)
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val filters = combine(tab, query, category) { t, q, cat -> Triple(t, q, cat) }

    val state: StateFlow<TasksState> = combine(
        c.tasks.tasks, c.categories.categories, c.tasks.subtaskProgress, combine(todayFlow(c.clock), c.settings.settings) { d, s -> d to s }, filters,
    ) { tasks, cats, subs, ds, f ->
        val (today, settings) = ds
        val (t, q, cat) = f
        val base = when (t) {
            TaskTab.TODAY -> TaskFilters.filter(tasks, TaskListFilter.TODAY, today, settings.overdueBehavior, c.clock.nowTime())
            TaskTab.UPCOMING -> TaskFilters.filter(tasks, TaskListFilter.UPCOMING, today, settings.overdueBehavior)
            TaskTab.OVERDUE -> TaskFilters.filter(tasks, TaskListFilter.OVERDUE, today, settings.overdueBehavior, c.clock.nowTime())
            TaskTab.IMPORTANT -> TaskFilters.important(tasks, today)
            TaskTab.NO_DATE -> TaskFilters.filter(tasks, TaskListFilter.NO_DATE, today, settings.overdueBehavior)
            TaskTab.DONE -> TaskFilters.filter(tasks, TaskListFilter.DONE, today, settings.overdueBehavior)
            TaskTab.ALL -> TaskFilters.filter(tasks, TaskListFilter.ALL, today, settings.overdueBehavior)
            TaskTab.ARCHIVED -> TaskFilters.filter(tasks, TaskListFilter.ARCHIVED, today, settings.overdueBehavior)
        }
        val filtered = TaskFilters.search(base, q).filter { cat == null || it.categoryId == cat }
        TasksState(t, q, cat, filtered, cats, subs, today, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksState(tab = initial))

    fun setTab(t: TaskTab) { tab.value = t }
    fun setQuery(q: String) { query.value = q }
    fun setCategory(id: String?) { category.value = id }

    fun toggle(task: Task) = launchSafe { c.tasks.setCompleted(task, !task.isDone) }

    fun setStatus(task: Task, status: TaskStatus) = launchSafe { c.tasks.setStatus(task, status) }

    fun archive(task: Task, archived: Boolean) = launchSafe {
        c.tasks.setArchived(task, archived)
        message(UiMessage.WithUndo(if (archived) R.string.msg_archived else R.string.msg_unarchived, undo = { launchSafe { c.tasks.setArchived(task, !archived) } }))
    }

    fun delete(task: Task) = launchSafe {
        val subs = c.tasks.getSubtasks(task.id)
        c.tasks.delete(task.id)
        message(UiMessage.WithUndo(R.string.msg_deleted, undo = { launchSafe { c.tasks.restore(task, subs) } }))
    }

    fun moveTo(task: Task, date: LocalDate?) = launchSafe { c.tasks.moveToDate(task, date) }

    fun duplicate(task: Task) = launchSafe {
        c.tasks.duplicate(task)
        message(UiMessage.Text(R.string.msg_duplicated))
    }

    fun move(list: List<Task>, index: Int, delta: Int) = launchSafe {
        val target = index + delta
        if (target !in list.indices) return@launchSafe
        val m = list.toMutableList()
        val item = m.removeAt(index)
        m.add(target, item)
        c.tasks.reorder(m)
    }
}

private fun tabFromRoute(f: String?): TaskTab = TaskTab.entries.firstOrNull { it.name == f } ?: TaskTab.TODAY

@Composable
fun tabLabel(t: TaskTab): String = stringResource(
    when (t) {
        TaskTab.TODAY -> R.string.filter_today
        TaskTab.UPCOMING -> R.string.filter_upcoming
        TaskTab.OVERDUE -> R.string.filter_overdue
        TaskTab.IMPORTANT -> R.string.filter_important
        TaskTab.NO_DATE -> R.string.filter_no_date
        TaskTab.DONE -> R.string.filter_done
        TaskTab.ALL -> R.string.filter_all
        TaskTab.ARCHIVED -> R.string.filter_archived
    },
)

@Composable
fun TasksScreen(route: Route.Tasks) {
    val c = LocalContainer.current
    val vm: TasksViewModel = viewModel { TasksViewModel(c, tabFromRoute(route.filter)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    CollectMessages(vm.messages)
    var searching by rememberSaveable { mutableStateOf(false) }
    var reorder by rememberSaveable { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Task?>(null) }
    var pickDateFor by remember { mutableStateOf<Task?>(null) }
    var confirmDelete by remember { mutableStateOf<Task?>(null) }

    YadinoScaffold(
        topBar = {
            if (searching) {
                TextField(
                    value = state.query, onValueChange = vm::setQuery,
                    placeholder = { Text(stringResource(R.string.search_tasks)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { vm.setQuery(""); searching = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp),
                )
            } else {
                YadinoTopBar(stringResource(R.string.tab_tasks)) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                    Box {
                        IconButton(onClick = { categoryMenu = true }) { Icon(Icons.Default.FilterList, stringResource(R.string.filter_category)) }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.all_categories)) }, onClick = { vm.setCategory(null); categoryMenu = false },
                                leadingIcon = { if (state.categoryId == null) Icon(Icons.Default.Check, null) })
                            state.categories.forEach { cat ->
                                DropdownMenuItem(text = { Text(cat.name) }, onClick = { vm.setCategory(cat.id); categoryMenu = false },
                                    leadingIcon = { if (state.categoryId == cat.id) Icon(Icons.Default.Check, null) })
                            }
                        }
                    }
                    if (state.tab == TaskTab.TODAY) {
                        IconButton(onClick = { reorder = !reorder }) {
                            Icon(Icons.Default.SwapVert, stringResource(R.string.reorder), tint = if (reorder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                nav.navigate(Route.TaskEditor(date = if (state.tab == TaskTab.TODAY) state.today.toEpochDay() else null))
            }) { Icon(Icons.Default.Add, stringResource(R.string.new_task)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TaskTab.entries) { t ->
                    FilterChip(selected = state.tab == t, onClick = { vm.setTab(t); if (t != TaskTab.TODAY) reorder = false }, label = { Text(tabLabel(t)) })
                }
            }
            if (state.loaded && state.tasks.isEmpty()) {
                EmptyState(Icons.Default.TaskAlt, stringResource(R.string.tasks_empty_title), stringResource(R.string.tasks_empty_message))
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
                itemsIndexed(state.tasks, key = { _, t -> t.id }) { index, task ->
                    val cat = state.categories.firstOrNull { it.id == task.categoryId }
                    Box {
                        if (reorder) {
                            TaskRow(
                                task = task, today = state.today, onToggle = { vm.toggle(task) }, onClick = {}, category = cat,
                                subtasks = state.subtasks[task.id],
                                trailing = {
                                    Row {
                                        IconButton(onClick = { vm.move(state.tasks, index, -1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up)) }
                                        IconButton(onClick = { vm.move(state.tasks, index, 1) }, enabled = index < state.tasks.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down)) }
                                    }
                                },
                            )
                        } else {
                            SwipeableTask(task, onComplete = { vm.toggle(task) }, onArchive = { vm.archive(task, !task.isArchived) }) {
                                TaskRow(
                                    task = task, today = state.today, onToggle = { vm.toggle(task) },
                                    onClick = { nav.navigate(Route.TaskEditor(taskId = task.id)) }, onLongClick = { menuFor = task },
                                    category = cat, subtasks = state.subtasks[task.id],
                                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                                )
                            }
                        }
                        DropdownMenu(expanded = menuFor?.id == task.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menuFor = null; nav.navigate(Route.TaskEditor(taskId = task.id)) })
                            if (task.isOpen && task.status != TaskStatus.IN_PROGRESS && task.isCompletable) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.mark_in_progress)) }, onClick = { menuFor = null; vm.setStatus(task, TaskStatus.IN_PROGRESS) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.to_today)) }, onClick = { menuFor = null; vm.moveTo(task, state.today) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.to_tomorrow)) }, onClick = { menuFor = null; vm.moveTo(task, state.today.plusDays(1)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_to_date)) }, onClick = { menuFor = null; pickDateFor = task })
                            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menuFor = null; vm.duplicate(task) })
                            DropdownMenuItem(text = { Text(stringResource(if (task.isArchived) R.string.unarchive else R.string.archive)) },
                                onClick = { menuFor = null; vm.archive(task, !task.isArchived) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuFor = null; confirmDelete = task })
                        }
                    }
                }
            }
        }
    }
    pickDateFor?.let { t ->
        JalaliDatePickerDialog(initial = t.date ?: state.today, today = state.today, onDismiss = { pickDateFor = null },
            onPick = { d -> vm.moveTo(t, d) }, allowClear = true)
    }
    confirmDelete?.let { t ->
        ConfirmDialog(
            title = stringResource(R.string.delete_task), message = stringResource(R.string.delete_task_message, t.title),
            confirmText = stringResource(R.string.delete), destructive = true, onConfirm = { vm.delete(t) }, onDismiss = { confirmDelete = null },
        )
    }
}

/** Swipe from the start edge to complete, from the end edge to archive. */
@Composable
fun SwipeableTask(task: Task, onComplete: () -> Unit, onArchive: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { if (task.isCompletable) onComplete(); false }
                SwipeToDismissBoxValue.EndToStart -> { onArchive(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = task.isCompletable,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp), contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(if (toEnd) Icons.Default.Check else Icons.Default.Archive, null)
            }
        },
    ) { content() }
}
