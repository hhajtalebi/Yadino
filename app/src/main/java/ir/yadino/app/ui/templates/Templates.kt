package ir.yadino.app.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.EmptyState
import ir.yadino.app.ui.components.JalaliDatePickerDialog
import ir.yadino.app.ui.components.Pill
import ir.yadino.app.ui.components.ScreenPadding
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.calendar.PersianNames
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.planning.PlanTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** A template with its items, for the list. */
data class TemplateSummary(val template: PlanTemplate, val items: List<TemplateItem>)

class TemplatesViewModel(c: AppContainer) : YadinoViewModel(c) {

    /** null while loading. */
    val summaries: StateFlow<List<TemplateSummary>?> = combine(c.templates.templates, c.templates.items) { templates, items ->
        val byTemplate = items.groupBy { it.templateId }
        templates.map { TemplateSummary(it, byTemplate[it.id].orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(template: PlanTemplate) = launchSafe {
        val removed = c.templates.delete(template.id) ?: return@launchSafe
        message(UiMessage.WithUndo(R.string.msg_template_deleted, undo = {
            launchSafe { c.templates.restore(removed.first, removed.second) }
        }, args = listOf(template.name)))
    }

    fun duplicate(template: PlanTemplate, nameFor: (Int) -> String) = launchSafe {
        val copy = c.templates.duplicate(template.id, nameFor) ?: return@launchSafe
        message(UiMessage.Text(R.string.msg_template_duplicated, listOf(copy.name)))
    }

    fun apply(template: PlanTemplate, date: LocalDate, skipDuplicates: Boolean) = launchSafe {
        val result = c.templates.apply(template, date, skipDuplicates)
        message(appliedMessage(template, result, ::formatNumber) { ids -> launchSafe { c.tasks.deleteMany(ids) } })
    }
}

/** Snackbar after applying a template; undo deletes the created tasks. */
internal fun appliedMessage(
    template: PlanTemplate,
    result: PlanTemplates.Applied,
    number: (Int) -> String,
    undo: (List<String>) -> Unit,
): UiMessage {
    val ids = result.tasks.map { it.id }
    return when {
        ids.isEmpty() -> UiMessage.Text(R.string.msg_template_nothing_applied)
        result.skipped > 0 -> UiMessage.WithUndo(R.string.msg_template_applied_skipped, undo = { undo(ids) },
            args = listOf(number(ids.size), number(result.skipped)))
        else -> UiMessage.WithUndo(R.string.msg_template_applied, undo = { undo(ids) }, args = listOf(number(ids.size), template.name))
    }
}

@Composable
fun TemplatesScreen() {
    val c = LocalContainer.current
    val vm: TemplatesViewModel = viewModel { TemplatesViewModel(c) }
    val list by vm.summaries.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val context = LocalContext.current
    CollectMessages(vm.messages)
    var applying by remember { mutableStateOf<PlanTemplate?>(null) }
    var deleting by remember { mutableStateOf<PlanTemplate?>(null) }

    YadinoScaffold(
        topBar = { YadinoTopBar(stringResource(R.string.templates_title), onBack = { nav.back() }) },
        floatingActionButton = {
            if (!list.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Route.TemplateEditor()) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.template_new)) },
                )
            }
        },
    ) { padding ->
        val summaries = list
        when {
            summaries == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            summaries.isEmpty() -> EmptyState(
                Icons.Default.ViewAgenda, stringResource(R.string.templates_empty_title), stringResource(R.string.templates_empty_message),
                Modifier.padding(padding),
            ) {
                Button(onClick = { nav.navigate(Route.TemplateEditor()) }) { Text(stringResource(R.string.template_new)) }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = ScreenPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(summaries, key = { it.template.id }) { s ->
                    TemplateCard(
                        s,
                        onOpen = { nav.navigate(Route.TemplateEditor(s.template.id)) },
                        onApply = { applying = s.template },
                        onDuplicate = {
                            vm.duplicate(s.template) { i ->
                                if (i == 1) context.getString(R.string.template_copy_name, s.template.name)
                                else context.getString(R.string.template_copy_name_n, s.template.name, fmt.number(i))
                            }
                        },
                        onDelete = { deleting = s.template },
                    )
                }
            }
        }
    }

    applying?.let { t ->
        ApplyTemplateDialog(t, c.clock.today(), onDismiss = { applying = null }, onApply = { date, skip -> vm.apply(t, date, skip) })
    }
    deleting?.let { t ->
        ConfirmDialog(
            stringResource(R.string.template_delete_title), stringResource(R.string.template_delete_message, t.name), stringResource(R.string.delete),
            onConfirm = { vm.delete(t) }, onDismiss = { deleting = null }, destructive = true,
        )
    }
}

@Composable
private fun TemplateCard(s: TemplateSummary, onOpen: () -> Unit, onApply: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    val t = s.template
    var menu by remember { mutableStateOf(false) }
    val ordered = remember(s.items, settings.firstDayOfWeek) { s.items.sortedWith(PlanTemplates.itemOrder(t, settings.firstDayOfWeek)) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewAgenda, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(if (t.isWeek) R.string.template_kind_week else R.string.template_kind_day))
                        Text(stringResource(R.string.n_items, fmt.number(s.items.size)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (t.description.isNotBlank()) {
                Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
            }
            Column(Modifier.padding(top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ordered.take(PREVIEW_ROWS).forEach { item -> TemplateItemLine(t, item) }
                if (ordered.size > PREVIEW_ROWS) {
                    Text(stringResource(R.string.template_more_items, fmt.number(ordered.size - PREVIEW_ROWS)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onApply) {
                    Icon(Icons.Default.PostAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.template_apply))
                }
            }
        }
    }
}

private const val PREVIEW_ROWS = 4

/** "شنبه ۰۹:۰۰  کار عمیق" — one compact line of a template item. */
@Composable
private fun TemplateItemLine(template: PlanTemplate, item: TemplateItem) {
    val fmt = LocalFormatter.current
    val day = if (template.isWeek) PersianNames.weekday(PlanTemplates.weekdayOf(item.anchorOffset)) + " " else ""
    val time = item.startTime?.let { fmt.time(it) } ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(kindIcon(item.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        if (day.isNotEmpty() || time.isNotEmpty()) {
            Text((day + time).trim(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun kindIcon(kind: TaskKind): ImageVector = when (kind) {
    TaskKind.TASK -> Icons.Default.TaskAlt
    TaskKind.EVENT -> Icons.Default.Event
    TaskKind.REMINDER -> Icons.Default.Notifications
}

/**
 * Confirms applying [template]: shows where every item lands on the chosen day (or week),
 * marks items that already exist there and lets the user skip them.
 */
@Composable
fun ApplyTemplateDialog(
    template: PlanTemplate,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onApply: (date: LocalDate, skipDuplicates: Boolean) -> Unit,
) {
    val c = LocalContainer.current
    val fmt = LocalFormatter.current
    val settings = LocalAppSettings.current
    var epochDay by rememberSaveable { mutableStateOf(initialDate.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    var picking by remember { mutableStateOf(false) }
    var skip by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf<List<PlanTemplates.PreviewItem>?>(null) }
    LaunchedEffect(template.id, epochDay, settings.firstDayOfWeek) {
        preview = null
        preview = try {
            c.templates.preview(template, date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorReporter.log(c.app, e, "templatePreview")
            emptyList()
        }
    }
    val start = PlanTemplates.startFor(template, date, settings.firstDayOfWeek)
    val rows = preview
    val toCreate = rows?.count { !(skip && it.isDuplicate) } ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_apply_title, template.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (template.isWeek) stringResource(R.string.template_apply_week, fmt.date(start), fmt.date(start.plusDays(6)))
                        else stringResource(R.string.template_apply_day, fmt.weekday(date) + " " + fmt.date(date)),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.template_change_date)) }
                }
                HorizontalDivider()
                if (rows == null) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows, key = { it.item.id }) { row -> PreviewRow(template, row, dimmed = skip && row.isDuplicate) }
                    }
                    val duplicates = rows.count { it.isDuplicate }
                    if (duplicates > 0) {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { skip = !skip },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = skip, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.template_duplicates_skip, fmt.number(duplicates)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(date, skip); onDismiss() }, enabled = rows != null && toCreate > 0) {
                Text(if (rows != null && toCreate == 0) stringResource(R.string.template_apply_none) else stringResource(R.string.template_apply_confirm, fmt.number(toCreate)))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (picking) {
        JalaliDatePickerDialog(
            initial = date, today = c.clock.today(), onDismiss = { picking = false },
            onPick = { d -> if (d != null) epochDay = d.toEpochDay() },
            title = stringResource(if (template.isWeek) R.string.template_pick_week_title else R.string.template_pick_date_title),
        )
    }
}

@Composable
private fun PreviewRow(template: PlanTemplate, row: PlanTemplates.PreviewItem, dimmed: Boolean) {
    val fmt = LocalFormatter.current
    val item = row.item
    Row(Modifier.fillMaxWidth().alpha(if (dimmed) 0.45f else 1f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(76.dp)) {
            if (template.isWeek) Text(fmt.weekday(row.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                when {
                    row.placement.date == null -> stringResource(R.string.deadline)
                    else -> fmt.timeRange(item.startTime, item.endTime) ?: stringResource(R.string.all_day)
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(kindIcon(item.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (row.isDuplicate) Pill(stringResource(R.string.template_duplicate_badge))
    }
}

/** Picks one of the stored templates (used by the calendar). */
@Composable
fun TemplatePickerDialog(
    templates: List<PlanTemplate>,
    onPick: (PlanTemplate) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_pick_title)) },
        text = {
            if (templates.isEmpty()) {
                Text(stringResource(R.string.template_pick_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(templates, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(t); onDismiss() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ViewAgenda, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Pill(stringResource(if (t.isWeek) R.string.template_kind_week else R.string.template_kind_day))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onManage(); onDismiss() }) { Text(stringResource(R.string.template_manage)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
