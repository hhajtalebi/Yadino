package ir.yadino.domain.planning

import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Something with a deadline that a countdown can follow. */
data class CountdownTarget(
    val kind: Kind,
    val id: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    /** Completed goal / done task: the countdown shows it as finished. */
    val finished: Boolean = false,
) {
    enum class Kind { GOAL, TASK }

    /** Whole days from [today] to the deadline: 0 = today, negative = passed. */
    fun daysLeft(today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)
}

/**
 * Deadline countdowns (home-screen widget). The selection is stored as a short string:
 * empty = automatic (nearest upcoming deadline), `goal:<id>` or `task:<id>`.
 */
object Countdowns {
    private const val GOAL = "goal:"
    private const val TASK = "task:"

    fun encode(kind: CountdownTarget.Kind, id: String): String = (if (kind == CountdownTarget.Kind.GOAL) GOAL else TASK) + id

    fun of(goal: Goal): CountdownTarget? = goal.deadline?.let {
        CountdownTarget(CountdownTarget.Kind.GOAL, goal.id, goal.title, it, finished = goal.status == GoalStatus.COMPLETED)
    }

    fun of(task: Task): CountdownTarget? = task.dueDate?.let {
        CountdownTarget(CountdownTarget.Kind.TASK, task.id, task.title, it, task.dueTime, finished = task.isDone)
    }

    /** Deadlines that can be picked: active or paused goals and open tasks, soonest first. */
    fun candidates(goals: List<Goal>, tasks: List<Task>, today: LocalDate, includePast: Boolean = false): List<CountdownTarget> {
        val g = goals.filter { !it.isArchived && it.status != GoalStatus.COMPLETED }.mapNotNull { of(it) }
        val t = tasks.filter { !it.isArchived && it.isOpen }.mapNotNull { of(it) }
        return (g + t)
            .filter { includePast || !it.date.isBefore(today) }
            .sortedWith(compareBy<CountdownTarget> { it.date }.thenBy { it.time ?: LocalTime.MAX }.thenBy { it.kind }.thenBy { it.title })
    }

    /** Nearest deadline from today on (today's deadlines whose time has passed are skipped). */
    fun nearest(goals: List<Goal>, tasks: List<Task>, today: LocalDate, now: LocalTime? = null): CountdownTarget? =
        candidates(goals, tasks, today).firstOrNull { c -> !(c.date == today && now != null && c.time != null && c.time.isBefore(now)) }

    /**
     * The target for a stored selection. A specific goal or task is followed even when it is
     * finished or overdue; null means it was deleted, archived or has no deadline any more.
     */
    fun resolve(selection: String, goals: List<Goal>, tasks: List<Task>, today: LocalDate, now: LocalTime? = null): CountdownTarget? = when {
        selection.startsWith(GOAL) -> goals.firstOrNull { it.id == selection.removePrefix(GOAL) && !it.isArchived }?.let { of(it) }
        selection.startsWith(TASK) -> tasks.firstOrNull { it.id == selection.removePrefix(TASK) && !it.isArchived }?.let { of(it) }
        else -> nearest(goals, tasks, today, now)
    }

    fun isAutomatic(selection: String): Boolean = !selection.startsWith(GOAL) && !selection.startsWith(TASK)
}
