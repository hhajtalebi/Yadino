package ir.yadino.domain.planning

import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.RecurringCompletionMode
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.recurrence.RecurrenceEnd
import ir.yadino.domain.recurrence.RecurrenceEngine
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Task lists shown in the tasks screen. */
enum class TaskListFilter { TODAY, UPCOMING, OVERDUE, NO_DATE, DONE, ALL, ARCHIVED }

/** An item placed on a specific day (real task or a projected future occurrence). */
data class PlannedItem(
    val task: Task,
    val date: LocalDate,
    /** True for future occurrences of a recurring task that are computed, not stored. */
    val isProjected: Boolean,
) {
    val sortKey: Int get() = task.startTime?.let { it.hour * 60 + it.minute } ?: -1
}

object TaskFilters {

    /** The effective day of a task: planned date, otherwise its deadline. */
    fun effectiveDate(task: Task): LocalDate? = task.date ?: task.dueDate

    fun isOverdue(task: Task, today: LocalDate, now: LocalTime? = null): Boolean {
        if (!task.isOpen || !task.isCompletable || task.isArchived) return false
        val due = task.dueDate
        if (due != null) {
            if (due.isBefore(today)) return true
            if (due == today && now != null && task.dueTime != null && task.dueTime.isBefore(now)) return true
            return false
        }
        val d = task.date ?: return false
        return d.isBefore(today)
    }

    /** Applies a list filter. The result is sorted for display. */
    fun filter(tasks: List<Task>, filter: TaskListFilter, today: LocalDate, behavior: OverdueBehavior, now: LocalTime? = null): List<Task> {
        val list = when (filter) {
            TaskListFilter.TODAY -> tasks.filter { t ->
                !t.isArchived && (
                    (effectiveDate(t) == today && t.status != TaskStatus.CANCELLED) ||
                        (behavior == OverdueBehavior.SHOW_IN_TODAY && isOverdue(t, today, now))
                    )
            }
            TaskListFilter.UPCOMING -> tasks.filter { t -> !t.isArchived && t.isOpen && (effectiveDate(t)?.isAfter(today) == true) }
            TaskListFilter.OVERDUE -> tasks.filter { isOverdue(it, today, now) }
            TaskListFilter.NO_DATE -> tasks.filter { !it.isArchived && it.isOpen && it.date == null && it.dueDate == null }
            TaskListFilter.DONE -> tasks.filter { !it.isArchived && it.status == TaskStatus.DONE }
            TaskListFilter.ALL -> tasks.filter { !it.isArchived }
            TaskListFilter.ARCHIVED -> tasks.filter { it.isArchived }
        }
        return when (filter) {
            TaskListFilter.DONE -> list.sortedByDescending { it.completedAt ?: it.updatedAt }
            else -> sortForPlan(list)
        }
    }

    /**
     * True when the user can put [a] and [b] in any order relative to each other. [sortForPlan]
     * always keeps open tasks before finished ones and earlier dates first, so manual
     * reordering (drag and drop, move up/down) only happens inside such a group.
     */
    fun canReorderTogether(a: Task, b: Task): Boolean = a.isOpen == b.isOpen && effectiveDate(a) == effectiveDate(b)

    /** Sort used by daily plans: open first, then date, explicit order, time, priority. */
    fun sortForPlan(tasks: List<Task>): List<Task> = tasks.sortedWith(
        compareBy<Task> { if (it.isOpen) 0 else 1 }
            .thenBy { effectiveDate(it)?.toEpochDay() ?: Long.MAX_VALUE }
            .thenBy { it.sortOrder }
            .thenBy { it.startTime?.toSecondOfDay() ?: Int.MAX_VALUE }
            .thenByDescending { it.priority.level }
            .thenBy { it.createdAt },
    )

    /** Open, high priority tasks for the dashboard. */
    fun important(tasks: List<Task>, today: LocalDate): List<Task> = tasks
        .filter { !it.isArchived && it.isOpen && it.isCompletable && it.priority.level >= Priority.HIGH.level }
        .sortedWith(compareByDescending<Task> { it.priority.level }.thenBy { effectiveDate(it)?.toEpochDay() ?: Long.MAX_VALUE })

    fun search(tasks: List<Task>, query: String): List<Task> {
        val q = query.trim()
        if (q.isEmpty()) return tasks
        return tasks.filter { t ->
            t.title.contains(q, ignoreCase = true) || t.description.contains(q, ignoreCase = true) ||
                t.tags.any { it.contains(q, ignoreCase = true) }
        }
    }

    /**
     * Expands tasks into planned items for each day in [from]..[to]. Recurring tasks are
     * shown on their stored date and, when [projectRecurring] is true, on their future
     * occurrences (marked as projected).
     */
    fun planForRange(
        tasks: List<Task>,
        from: LocalDate,
        to: LocalDate,
        projectRecurring: Boolean,
        firstDayOfWeek: DayOfWeek,
    ): Map<LocalDate, List<PlannedItem>> {
        val result = HashMap<LocalDate, MutableList<PlannedItem>>()
        for (task in tasks) {
            if (task.isArchived || task.status == TaskStatus.CANCELLED) continue
            val d = effectiveDate(task) ?: continue
            if (!d.isBefore(from) && !d.isAfter(to)) {
                result.getOrPut(d) { ArrayList() } += PlannedItem(task, d, isProjected = false)
            }
            val rule = task.recurrence
            if (projectRecurring && rule != null && task.isOpen) {
                val anchor = task.recurrenceStart ?: d
                val start = maxOf(from, d.plusDays(1))
                if (!start.isAfter(to)) {
                    for (occ in RecurrenceEngine.occurrencesBetween(rule, anchor, start, to, firstDayOfWeek, limit = 400)) {
                        result.getOrPut(occ) { ArrayList() } += PlannedItem(task, occ, isProjected = true)
                    }
                }
            }
        }
        return result.mapValues { (_, items) ->
            items.sortedWith(compareBy<PlannedItem> { it.sortKey }.thenBy { it.task.sortOrder }.thenByDescending { it.task.priority.level })
        }
    }
}

/** Result of completing a task. */
data class CompletionResult(
    /** The updated task (moved to the next occurrence or marked done). */
    val updatedTask: Task,
    /** Completion log entry. */
    val completion: TaskCompletion,
    /** Completed copy kept in history ([RecurringCompletionMode.KEEP_COMPLETED_COPY]). */
    val completedCopy: Task?,
    /** Subtasks of the updated task after the operation (reset for the next occurrence). */
    val resetSubtasks: Boolean,
)

object TaskCompletionLogic {

    /**
     * Completes [task]. Non recurring tasks become DONE. Recurring tasks move to their next
     * occurrence (if any) according to [mode]; when the series has ended the task is DONE.
     */
    fun complete(
        task: Task,
        now: Instant,
        mode: RecurringCompletionMode,
        firstDayOfWeek: DayOfWeek,
        newId: () -> String,
    ): CompletionResult {
        val occurrence = task.date ?: task.dueDate ?: now.atZone(ZoneId.systemDefault()).toLocalDate()
        val completion = TaskCompletion(newId(), task.id, occurrence, now)
        val rule = task.recurrence
        if (rule == null) {
            return CompletionResult(task.copy(status = TaskStatus.DONE, completedAt = now, updatedAt = now), completion, null, false)
        }
        val anchor = task.recurrenceStart ?: occurrence
        val next = RecurrenceEngine.nextOccurrence(rule, anchor, occurrence, firstDayOfWeek)
        if (next == null) {
            return CompletionResult(task.copy(status = TaskStatus.DONE, completedAt = now, updatedAt = now), completion, null, false)
        }
        val shift = ChronoUnit.DAYS.between(occurrence, next)
        val moved = task.copy(
            date = task.date?.plusDays(shift),
            dueDate = task.dueDate?.plusDays(shift),
            status = TaskStatus.TODO,
            completedAt = null,
            recurrenceStart = anchor,
            updatedAt = now,
        )
        val copy = if (mode == RecurringCompletionMode.KEEP_COMPLETED_COPY) {
            task.copy(
                id = newId(), recurrence = null, recurrenceStart = null, status = TaskStatus.DONE,
                completedAt = now, reminderOffsetMinutes = null, createdAt = now, updatedAt = now,
            )
        } else null
        return CompletionResult(moved, completion, copy, resetSubtasks = true)
    }

    /** Remaining occurrences of a COUNT based series after [occurrence] (for display). */
    fun remainingOccurrences(task: Task, firstDayOfWeek: DayOfWeek): Int? {
        val rule = task.recurrence ?: return null
        val end = rule.end as? RecurrenceEnd.Count ?: return null
        val anchor = task.recurrenceStart ?: task.date ?: return null
        val current = task.date ?: return null
        return (end.count - RecurrenceEngine.countUpTo(rule, anchor, current, firstDayOfWeek) + 1).coerceAtLeast(0)
    }
}

/** Copies the plan of a day or a week to another date. */
object PlanCopier {

    data class Copied(val tasks: List<Task>, val subtasks: List<Subtask>)

    /**
     * Creates copies of [tasks] whose effective date is inside [sourceStart]..[sourceEnd],
     * shifted so that [sourceStart] maps to [targetStart]. Copies are open, non recurring
     * and keep their times, priority, category and tags.
     */
    fun copy(
        tasks: List<Task>,
        subtasks: List<Subtask>,
        sourceStart: LocalDate,
        sourceEnd: LocalDate,
        targetStart: LocalDate,
        now: Instant,
        newId: () -> String,
    ): Copied {
        val shift = ChronoUnit.DAYS.between(sourceStart, targetStart)
        val bySource = subtasks.groupBy { it.taskId }
        val newTasks = ArrayList<Task>()
        val newSubtasks = ArrayList<Subtask>()
        for (t in tasks) {
            if (t.isArchived || t.status == TaskStatus.CANCELLED) continue
            val d = TaskFilters.effectiveDate(t) ?: continue
            if (d.isBefore(sourceStart) || d.isAfter(sourceEnd)) continue
            val id = newId()
            newTasks += t.copy(
                id = id,
                date = t.date?.plusDays(shift),
                dueDate = t.dueDate?.plusDays(shift),
                status = TaskStatus.TODO,
                completedAt = null,
                recurrence = null,
                recurrenceStart = null,
                createdAt = now,
                updatedAt = now,
            )
            bySource[t.id].orEmpty().forEach { s ->
                newSubtasks += s.copy(id = newId(), taskId = id, isDone = false, createdAt = now, updatedAt = now)
            }
        }
        return Copied(newTasks, newSubtasks)
    }
}

/** Reminder trigger computations (pure; the Android layer schedules the alarms). */
object ReminderCalculator {

    /** Local date-time the reminder of [task] refers to (before the offset), or null. */
    fun anchor(task: Task, settings: AppSettings): LocalDateTime? {
        val date = task.date ?: task.dueDate ?: return null
        val time = when {
            task.date != null && task.startTime != null -> task.startTime
            task.date == null && task.dueTime != null -> task.dueTime
            task.date != null && task.date == task.dueDate && task.dueTime != null && task.startTime == null -> task.dueTime
            else -> settings.allDayReminderTime
        }
        return LocalDateTime.of(date, time)
    }

    /**
     * Instant at which the reminder of [task] should fire in [zone], adjusted for quiet
     * hours. Returns null when the task has no reminder, is not open, or the time passed.
     */
    fun triggerAt(task: Task, settings: AppSettings, zone: ZoneId, now: Instant): Instant? {
        val offset = task.reminderOffsetMinutes ?: return null
        if (!task.isOpen && task.kind != TaskKind.EVENT) return null
        if (task.isArchived || task.status == TaskStatus.CANCELLED || task.status == TaskStatus.DONE) return null
        val anchor = anchor(task, settings) ?: return null
        var local = anchor.minusMinutes(offset.toLong())
        if (settings.quietHoursEnabled) local = QuietHours.adjust(local, settings.quietHoursStart, settings.quietHoursEnd)
        val instant = local.atZone(zone).toInstant()
        return if (instant.isAfter(now)) instant else null
    }

    /** Next trigger of a habit reminder strictly after [now]. */
    fun nextHabitTrigger(
        reminderTime: LocalTime,
        isScheduledOn: (LocalDate) -> Boolean,
        settings: AppSettings,
        zone: ZoneId,
        now: Instant,
    ): Instant? {
        val today = now.atZone(zone).toLocalDate()
        for (i in 0..14) {
            val day = today.plusDays(i.toLong())
            if (!isScheduledOn(day)) continue
            var local = LocalDateTime.of(day, reminderTime)
            if (settings.quietHoursEnabled) local = QuietHours.adjust(local, settings.quietHoursStart, settings.quietHoursEnd)
            val instant = local.atZone(zone).toInstant()
            if (instant.isAfter(now)) return instant
        }
        return null
    }

    /** Next occurrence of a daily time strictly after [now]. */
    fun nextDaily(time: LocalTime, zone: ZoneId, now: Instant): Instant {
        val today = now.atZone(zone).toLocalDate()
        val todayAt = LocalDateTime.of(today, time).atZone(zone).toInstant()
        return if (todayAt.isAfter(now)) todayAt else LocalDateTime.of(today.plusDays(1), time).atZone(zone).toInstant()
    }
}

object QuietHours {

    /** True if [time] is inside the quiet period [start, end) (which may cross midnight). */
    fun contains(time: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> false
        start.isBefore(end) -> !time.isBefore(start) && time.isBefore(end)
        else -> !time.isBefore(start) || time.isBefore(end)
    }

    /** Moves [dateTime] to the end of the quiet period when it falls inside it. */
    fun adjust(dateTime: LocalDateTime, start: LocalTime, end: LocalTime): LocalDateTime {
        val t = dateTime.toLocalTime()
        if (!contains(t, start, end)) return dateTime
        val day = if (start.isBefore(end) || t.isBefore(end)) dateTime.toLocalDate() else dateTime.toLocalDate().plusDays(1)
        return LocalDateTime.of(day, end)
    }
}
