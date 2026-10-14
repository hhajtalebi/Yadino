package ir.yadino.domain.reports

import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.planning.TaskFilters
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

enum class ReportPeriod { DAY, WEEK, MONTH }

data class DayStat(
    val date: LocalDate,
    val planned: Int,
    val completed: Int,
    val habitsDone: Int,
    val habitsScheduled: Int,
)

data class PeriodReport(
    val period: ReportPeriod,
    val start: LocalDate,
    val end: LocalDate,
    /** Task occurrences planned in the period. */
    val planned: Int,
    /** Planned occurrences that were completed. */
    val completedOnPlan: Int,
    /** Completions performed during the period (by completion time). */
    val completed: Int,
    val completionRate: Double,
    /** Open tasks that were overdue at the end of the period (or today). */
    val overdue: Int,
    val tasksCreated: Int,
    val notesCreated: Int,
    val habitRate: Double?,
    val habitCheckIns: Int,
    val goalsCompleted: Int,
    val milestonesCompleted: Int,
    val focusMinutes: Long,
    val completedByPriority: Map<Priority, Int>,
    val completedByCategory: Map<String?, Int>,
    val days: List<DayStat>,
) {
    val bestDay: DayStat? get() = days.filter { it.completed > 0 }.maxByOrNull { it.completed }
}

object ReportCalculator {

    /** Inclusive range of a period around [anchor] (weeks use [firstDayOfWeek]; months are Jalali months). */
    fun range(period: ReportPeriod, anchor: LocalDate, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> = when (period) {
        ReportPeriod.DAY -> anchor to anchor
        ReportPeriod.WEEK -> {
            val j = JalaliDate.from(anchor)
            WeekMath.startOfWeek(j, firstDayOfWeek).toLocalDate() to WeekMath.endOfWeek(j, firstDayOfWeek).toLocalDate()
        }
        ReportPeriod.MONTH -> {
            val j = JalaliDate.from(anchor)
            j.firstDayOfMonth().toLocalDate() to j.lastDayOfMonth().toLocalDate()
        }
    }

    fun build(
        period: ReportPeriod,
        anchor: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
        tasks: List<Task>,
        completions: List<TaskCompletion>,
        habits: List<Habit>,
        habitLogs: List<HabitLog>,
        notes: List<Note>,
        goals: List<Goal>,
        milestones: List<Milestone>,
    ): PeriodReport {
        val (start, end) = range(period, anchor, firstDayOfWeek)
        fun inRange(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
        val taskById = tasks.associateBy { it.id }

        val completionsOnPlan = completions.filter { inRange(it.occurrenceDate) }
        val completionsInPeriod = completions.filter { inRange(it.completedAt.atZone(zone).toLocalDate()) }
        val openPlanned = tasks.filter { t ->
            !t.isArchived && t.isOpen && t.isCompletable && TaskFilters.effectiveDate(t)?.let(::inRange) == true
        }
        val planned = completionsOnPlan.size + openPlanned.size
        val evaluationDay = if (end.isBefore(today)) end.plusDays(1) else today
        val overdue = tasks.count { t ->
            val d = TaskFilters.effectiveDate(t)
            t.isOpen && t.isCompletable && !t.isArchived && d != null && d.isBefore(evaluationDay) && !d.isBefore(start.minusYears(5))
        }

        val byPriority = LinkedHashMap<Priority, Int>()
        val byCategory = LinkedHashMap<String?, Int>()
        var focus = 0L
        for (c in completionsInPeriod) {
            val t = taskById[c.taskId] ?: continue
            byPriority[t.priority] = (byPriority[t.priority] ?: 0) + 1
            byCategory[t.categoryId] = (byCategory[t.categoryId] ?: 0) + 1
            if (t.startTime != null && t.endTime != null && t.endTime.isAfter(t.startTime)) {
                focus += Duration.between(t.startTime, t.endTime).toMinutes()
            }
        }

        val activeHabits = habits.filter { !it.isArchived }
        val counts = habitLogs.groupBy { it.habitId }.mapValues { (_, l) -> l.associate { it.date to it.count } }
        var habitScheduledTotal = 0
        var habitDoneTotal = 0
        val days = ArrayList<DayStat>()
        var day = start
        while (!day.isAfter(end)) {
            val d = day
            val dayPlanned = completionsOnPlan.count { it.occurrenceDate == d } + openPlanned.count { TaskFilters.effectiveDate(it) == d }
            val dayCompleted = completionsInPeriod.count { it.completedAt.atZone(zone).toLocalDate() == d }
            var hs = 0
            var hd = 0
            if (!d.isAfter(today)) {
                for (h in activeHabits) {
                    if (HabitStats.isScheduled(h, d)) {
                        hs++
                        if ((counts[h.id]?.get(d) ?: 0) >= h.targetPerDay) hd++
                    }
                }
            }
            habitScheduledTotal += hs
            habitDoneTotal += hd
            days += DayStat(d, dayPlanned, dayCompleted, hd, hs)
            day = day.plusDays(1)
        }

        return PeriodReport(
            period = period,
            start = start,
            end = end,
            planned = planned,
            completedOnPlan = completionsOnPlan.size,
            completed = completionsInPeriod.size,
            completionRate = if (planned == 0) 0.0 else completionsOnPlan.size.toDouble() / planned,
            overdue = overdue,
            tasksCreated = tasks.count { inRange(it.createdAt.atZone(zone).toLocalDate()) },
            notesCreated = notes.count { inRange(it.createdAt.atZone(zone).toLocalDate()) },
            habitRate = if (habitScheduledTotal == 0) null else habitDoneTotal.toDouble() / habitScheduledTotal,
            habitCheckIns = habitLogs.count { inRange(it.date) && it.count > 0 },
            goalsCompleted = goals.count { g -> g.completedAt?.let { inRange(it.atZone(zone).toLocalDate()) } == true },
            milestonesCompleted = milestones.count { m -> m.completedAt?.let { inRange(it.atZone(zone).toLocalDate()) } == true },
            focusMinutes = focus,
            completedByPriority = byPriority,
            completedByCategory = byCategory,
            days = days,
        )
    }
}
