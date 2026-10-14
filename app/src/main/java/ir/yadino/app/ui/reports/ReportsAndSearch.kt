package ir.yadino.app.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.TaskRow
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.common.todayFlow
import ir.yadino.app.ui.components.BarChart
import ir.yadino.app.ui.components.ProgressRing
import ir.yadino.app.ui.components.SectionCard
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.components.priorityColor
import ir.yadino.app.ui.components.priorityLabel
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.PersianNames
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Task
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.reports.PeriodReport
import ir.yadino.domain.reports.ReportCalculator
import ir.yadino.domain.reports.ReportPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class ReportsState(val report: PeriodReport? = null, val period: ReportPeriod = ReportPeriod.WEEK, val anchor: LocalDate = LocalDate.now(),
                        val categories: Map<String, Category> = emptyMap(), val today: LocalDate = LocalDate.now())

private data class ReportInputs(val tasks: List<Task>, val completions: List<ir.yadino.domain.model.TaskCompletion>, val habits: List<Habit>,
                                val logs: List<ir.yadino.domain.model.HabitLog>, val notes: List<Note>)

class ReportsViewModel(c: AppContainer) : YadinoViewModel(c) {
    val period = MutableStateFlow(ReportPeriod.WEEK)
    val anchor = MutableStateFlow(c.clock.today())
    private val inputs = combine(c.tasks.tasks, c.tasks.completions, c.habits.habits, c.habits.logsSince(c.clock.today().minusDays(800)), c.notes.notes) { t, co, h, l, n ->
        ReportInputs(t, co, h, l, n)
    }
    private val goalsFlow = combine(c.goals.goals, c.goals.milestones, c.categories.categories) { g, m, cat -> Triple(g, m, cat) }

    val state: StateFlow<ReportsState> = combine(inputs, goalsFlow, combine(period, anchor) { p, a -> p to a }, todayFlow(c.clock), c.settings.settings) { i, gm, pa, today, s ->
        val (goals, milestones, cats) = gm
        val (p, a) = pa
        val report = ReportCalculator.build(p, a, today, c.clock.zone(), s.firstDayOfWeek, i.tasks, i.completions, i.habits, i.logs, i.notes, goals, milestones)
        ReportsState(report, p, a, cats.associateBy { it.id }, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportsState())

    fun shift(delta: Int) {
        anchor.value = when (period.value) {
            ReportPeriod.DAY -> anchor.value.plusDays(delta.toLong())
            ReportPeriod.WEEK -> anchor.value.plusWeeks(delta.toLong())
            ReportPeriod.MONTH -> JalaliDate.from(anchor.value).plusMonths(delta.toLong()).toLocalDate()
        }
    }
}

@Composable
fun ReportsScreen() {
    val c = LocalContainer.current
    val vm: ReportsViewModel = viewModel { ReportsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    YadinoScaffold(topBar = { YadinoTopBar(stringResource(R.string.reports), onBack = { nav.back() }) }) { padding ->
        val r = state.report
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ReportPeriod.entries.forEachIndexed { i, p ->
                        SegmentedButton(state.period == p, { vm.period.value = p }, SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(stringResource(when (p) {
                                ReportPeriod.DAY -> R.string.report_daily
                                ReportPeriod.WEEK -> R.string.report_weekly
                                ReportPeriod.MONTH -> R.string.report_monthly
                            }))
                        }
                    }
                }
                if (r != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.previous)) }
                        Text(
                            when (r.period) {
                                ReportPeriod.DAY -> fmt.date(r.start, DateFormatStyle.FULL)
                                ReportPeriod.WEEK -> fmt.shortDate(r.start) + " – " + fmt.shortDate(r.end)
                                ReportPeriod.MONTH -> fmt.monthYear(JalaliDate.from(r.start))
                            },
                            Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { vm.shift(1) }, enabled = r.end.isBefore(state.today)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.next)) }
                    }
                }
            }
            if (r == null) return@LazyColumn
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing((r.completionRate * 100).toInt(), 88.dp, 8.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.completion_rate), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.planned_vs_done, fmt.number(r.completedOnPlan), fmt.number(r.planned)), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.done_in_period, fmt.number(r.completed)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(stringResource(R.string.metric_overdue), fmt.number(r.overdue), Modifier.weight(1f))
                    Metric(stringResource(R.string.metric_created), fmt.number(r.tasksCreated), Modifier.weight(1f))
                    Metric(stringResource(R.string.metric_notes), fmt.number(r.notesCreated), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(stringResource(R.string.metric_habits), r.habitRate?.let { fmt.percent((it * 100).toInt()) } ?: "—", Modifier.weight(1f))
                    Metric(stringResource(R.string.metric_focus), if (r.focusMinutes > 0) fmt.duration(r.focusMinutes) else "—", Modifier.weight(1f))
                    Metric(stringResource(R.string.metric_goals), fmt.number(r.goalsCompleted) + " / " + fmt.number(r.milestonesCompleted), Modifier.weight(1f))
                }
            }
            if (r.period != ReportPeriod.DAY) {
                item {
                    SectionCard(stringResource(R.string.completed_per_day)) {
                        val values = r.days.map { it.completed }
                        val labels = r.days.map { d ->
                            if (r.period == ReportPeriod.WEEK) PersianNames.weekdayShort(d.date.dayOfWeek) else fmt.number(JalaliDate.from(d.date).day)
                        }
                        BarChart(values, labels, highlightIndex = r.days.indexOfFirst { it.date == state.today }.takeIf { it >= 0 })
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.by_priority)) {
                    val total = r.completedByPriority.values.sum().coerceAtLeast(1)
                    Priority.entries.reversed().forEach { p ->
                        val n = r.completedByPriority[p] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(priorityLabel(p), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { n.toFloat() / total }, color = priorityColor(p), modifier = Modifier.weight(1f))
                            Text(fmt.number(n), Modifier.width(36.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (r.completedByCategory.isNotEmpty()) {
                item {
                    SectionCard(stringResource(R.string.by_category)) {
                        r.completedByCategory.entries.sortedByDescending { it.value }.forEach { (id, n) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(id?.let { state.categories[it]?.name } ?: stringResource(R.string.no_category), Modifier.weight(1f))
                                Text(fmt.number(n))
                            }
                        }
                    }
                }
            }
            r.bestDay?.let { best ->
                item { Text(stringResource(R.string.best_day, fmt.date(best.date, DateFormatStyle.MEDIUM), fmt.number(best.completed)), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// =============================================================================================
// Global search
// =============================================================================================
data class SearchState(val query: String = "", val tasks: List<Task> = emptyList(), val notes: List<Note> = emptyList(),
                       val goals: List<Goal> = emptyList(), val habits: List<Habit> = emptyList(), val today: LocalDate = LocalDate.now())

class SearchViewModel(c: AppContainer) : YadinoViewModel(c) {
    val query = MutableStateFlow("")
    val state: StateFlow<SearchState> = combine(query, c.tasks.tasks, c.notes.notes, c.goals.goals, c.habits.habits) { q, t, n, g, h ->
        if (q.isBlank()) SearchState(q, today = c.clock.today())
        else SearchState(
            q, TaskFilters.search(t, q).take(50), NoteFilters.search(n, q).take(50),
            g.filter { it.title.contains(q, true) || it.description.contains(q, true) }, h.filter { it.title.contains(q, true) }, c.clock.today(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchState())
}

@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val vm: SearchViewModel = viewModel { SearchViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    YadinoScaffold(topBar = {
        YadinoTopBar(stringResource(R.string.search), onBack = { nav.back() })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = state.query, onValueChange = { vm.query.value = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.search_everything)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } },
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                if (state.query.isNotBlank() && state.tasks.isEmpty() && state.notes.isEmpty() && state.goals.isEmpty() && state.habits.isEmpty()) {
                    item { Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (state.tasks.isNotEmpty()) item { Header(stringResource(R.string.tab_tasks)) }
                items(state.tasks, key = { "t" + it.id }) { t -> TaskRow(t, state.today, null, { nav.navigate(Route.TaskEditor(taskId = t.id)) }) }
                if (state.notes.isNotEmpty()) item { Header(stringResource(R.string.tab_notes)) }
                items(state.notes, key = { "n" + it.id }) { n -> ResultRow(Icons.AutoMirrored.Filled.StickyNote2, NoteFilters.displayTitle(n).ifBlank { stringResource(R.string.untitled_note) }, n.content) { nav.navigate(Route.NoteEditor(n.id)) } }
                if (state.goals.isNotEmpty()) item { Header(stringResource(R.string.goals)) }
                items(state.goals, key = { "g" + it.id }) { g -> ResultRow(Icons.Default.Flag, g.title, g.description) { nav.navigate(Route.GoalDetail(g.id)) } }
                if (state.habits.isNotEmpty()) item { Header(stringResource(R.string.habits)) }
                items(state.habits, key = { "h" + it.id }) { h -> ResultRow(Icons.Default.Repeat, h.title, h.description) { nav.navigate(Route.HabitDetail(h.id)) } }
            }
        }
    }
}

@Composable
private fun Header(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@Composable
private fun ResultRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
