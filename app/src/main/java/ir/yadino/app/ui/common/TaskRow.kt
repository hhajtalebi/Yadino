package ir.yadino.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.yadino.app.R
import ir.yadino.app.ui.components.ColorDot
import ir.yadino.app.ui.components.priorityColor
import ir.yadino.app.ui.components.priorityLabel
import ir.yadino.app.ui.theme.HolidayRed
import ir.yadino.app.ui.theme.HolidayRedDark
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.planning.TaskFilters
import java.time.LocalDate

/** A task/event/reminder row used across the app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    today: LocalDate,
    onToggle: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    category: Category? = null,
    subtasks: Pair<Int, Int>? = null,
    showDate: Boolean = true,
    projectedDate: LocalDate? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val overdue = projectedDate == null && TaskFilters.isOverdue(task, today)
    val red = if (dark) HolidayRedDark else HolidayRed
    val done = task.status == TaskStatus.DONE && projectedDate == null
    val stateText = stringResource(if (done) R.string.state_done else R.string.state_open)
    Row(
        modifier = modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = false) { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (task.kind == TaskKind.EVENT || onToggle == null) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (task.kind == TaskKind.EVENT) Icons.Default.Event else Icons.Default.Alarm,
                    contentDescription = stringResource(if (task.kind == TaskKind.EVENT) R.string.kind_event else R.string.kind_task),
                    tint = category?.color?.accent(dark) ?: MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            val label = stringResource(R.string.toggle_done_for, task.title)
            Checkbox(
                checked = done,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = priorityColor(task.priority)),
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val date = projectedDate ?: TaskFilters.effectiveDate(task)
            val parts = buildList {
                if (showDate && date != null) add(fmt.relativeDay(date, today))
                fmt.timeRange(task.startTime, task.endTime)?.let { add(it) }
                if (task.dueDate != null && task.date != null) add(stringResource(R.string.deadline_short, fmt.shortDate(task.dueDate!!)))
                subtasks?.let { (d, t) -> if (t > 0) add(fmt.number(d) + "/" + fmt.number(t)) }
                if (overdue) add(stringResource(R.string.overdue_short))
                if (projectedDate != null) add(stringResource(R.string.projected_occurrence))
                if (task.status == TaskStatus.IN_PROGRESS) add(stringResource(R.string.status_in_progress))
            }
            if (parts.isNotEmpty() || category != null || task.priority.level >= Priority.HIGH.level || task.isRecurring || task.reminderOffsetMinutes != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (category != null) ColorDot(category.color.accent(dark), 8.dp)
                    if (task.priority.level >= Priority.HIGH.level) {
                        Icon(Icons.Default.Flag, priorityLabel(task.priority), tint = priorityColor(task.priority), modifier = Modifier.size(14.dp))
                    }
                    if (task.isRecurring) Icon(Icons.Default.Repeat, stringResource(R.string.recurring), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.reminderOffsetMinutes != null) Icon(Icons.Default.NotificationsActive, stringResource(R.string.has_reminder), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        parts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) red else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}
