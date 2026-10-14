package ir.yadino.domain.habits

import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.HabitSchedule
import java.time.DayOfWeek
import java.time.LocalDate

/** Aggregated statistics of a habit. */
data class HabitSummary(
    val currentStreak: Int,
    val bestStreak: Int,
    /** Unit of the streak values: days for daily/specific-day habits, weeks otherwise. */
    val streakInWeeks: Boolean,
    val doneToday: Int,
    val target: Int,
    val isScheduledToday: Boolean,
    /** Completion rate (0..1) of the last 30 days. */
    val rate30: Double,
    /** For TimesPerWeek: completed days in the current week. */
    val weekProgress: Int,
    val weekTarget: Int,
) {
    val isDoneToday: Boolean get() = doneToday >= target
}

/**
 * Streak and completion statistics. Today's pending check-in never breaks a streak: the
 * current streak counts back from today if it is done, otherwise from the previous
 * scheduled day.
 */
object HabitStats {

    fun isScheduled(habit: Habit, date: LocalDate): Boolean {
        if (date.isBefore(habit.startDate)) return false
        return when (val s = habit.schedule) {
            HabitSchedule.Daily -> true
            is HabitSchedule.SpecificDays -> date.dayOfWeek in s.days
            is HabitSchedule.TimesPerWeek -> true
        }
    }

    /**
     * Habits to check in today (home-screen widget): scheduled and not archived; the ones not
     * yet done first, then in the user's order.
     */
    fun dueToday(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, firstDayOfWeek: DayOfWeek): List<Pair<Habit, HabitSummary>> =
        habits.filter { !it.isArchived && isScheduled(it, today) }
            .map { it to summarize(it, logs, today, firstDayOfWeek) }
            .sortedWith(compareBy<Pair<Habit, HabitSummary>> { it.second.isDoneToday }.thenBy { it.first.sortOrder }.thenBy { it.first.title })

    private fun completedOn(habit: Habit, counts: Map<LocalDate, Int>, date: LocalDate) =
        (counts[date] ?: 0) >= habit.targetPerDay

    fun summarize(habit: Habit, logs: List<HabitLog>, today: LocalDate, firstDayOfWeek: DayOfWeek): HabitSummary {
        val counts = logs.filter { it.habitId == habit.id }.associate { it.date to it.count }
        return when (val s = habit.schedule) {
            is HabitSchedule.TimesPerWeek -> {
                val weekStart = WeekMath.startOfWeek(JalaliDate.from(today), firstDayOfWeek).toLocalDate()
                val weekDone = (0..6).count { completedOn(habit, counts, weekStart.plusDays(it.toLong())) }
                HabitSummary(
                    currentStreak = weeklyStreak(habit, counts, today, firstDayOfWeek, s.times),
                    bestStreak = bestWeeklyStreak(habit, counts, today, firstDayOfWeek, s.times),
                    streakInWeeks = true,
                    doneToday = counts[today] ?: 0,
                    target = habit.targetPerDay,
                    isScheduledToday = isScheduled(habit, today),
                    rate30 = weeklyRate(habit, counts, today, firstDayOfWeek, s.times),
                    weekProgress = weekDone,
                    weekTarget = s.times,
                )
            }
            else -> HabitSummary(
                currentStreak = dailyStreak(habit, counts, today),
                bestStreak = bestDailyStreak(habit, counts, today),
                streakInWeeks = false,
                doneToday = counts[today] ?: 0,
                target = habit.targetPerDay,
                isScheduledToday = isScheduled(habit, today),
                rate30 = completionRate(habit, counts, today.minusDays(29), today),
                weekProgress = 0,
                weekTarget = 0,
            )
        }
    }

    internal fun dailyStreak(habit: Habit, counts: Map<LocalDate, Int>, today: LocalDate): Int {
        var day = today
        if (!completedOn(habit, counts, day)) day = day.minusDays(1)
        var streak = 0
        var guard = 0
        while (!day.isBefore(habit.startDate) && guard < 5000) {
            guard++
            if (isScheduled(habit, day)) {
                if (completedOn(habit, counts, day)) streak++ else break
            }
            day = day.minusDays(1)
        }
        return streak
    }

    internal fun bestDailyStreak(habit: Habit, counts: Map<LocalDate, Int>, today: LocalDate): Int {
        var best = 0
        var run = 0
        var day = habit.startDate
        val first = counts.keys.minOrNull()?.let { maxOf(it, habit.startDate) } ?: return 0
        day = first
        while (!day.isAfter(today)) {
            if (isScheduled(habit, day)) {
                if (completedOn(habit, counts, day)) {
                    run++
                    if (run > best) best = run
                } else if (day != today) {
                    run = 0
                }
            }
            day = day.plusDays(1)
        }
        return best
    }

    /** Completion rate of scheduled days in [from]..[to] (0..1). Days before start are ignored. */
    fun completionRate(habit: Habit, counts: Map<LocalDate, Int>, from: LocalDate, to: LocalDate): Double {
        var scheduled = 0
        var done = 0
        var day = maxOf(from, habit.startDate)
        while (!day.isAfter(to)) {
            if (isScheduled(habit, day)) {
                scheduled++
                if (completedOn(habit, counts, day)) done++
            }
            day = day.plusDays(1)
        }
        return if (scheduled == 0) 0.0 else done.toDouble() / scheduled
    }

    private fun weekDone(habit: Habit, counts: Map<LocalDate, Int>, weekStart: LocalDate): Int =
        (0..6).count { completedOn(habit, counts, weekStart.plusDays(it.toLong())) }

    private fun weekStartOf(date: LocalDate, firstDayOfWeek: DayOfWeek) =
        WeekMath.startOfWeek(JalaliDate.from(date), firstDayOfWeek).toLocalDate()

    internal fun weeklyStreak(habit: Habit, counts: Map<LocalDate, Int>, today: LocalDate, firstDayOfWeek: DayOfWeek, times: Int): Int {
        var week = weekStartOf(today, firstDayOfWeek)
        if (weekDone(habit, counts, week) < times) week = week.minusWeeks(1)
        var streak = 0
        val startWeek = weekStartOf(habit.startDate, firstDayOfWeek)
        while (!week.isBefore(startWeek) && streak < 1000) {
            if (weekDone(habit, counts, week) >= times) streak++ else break
            week = week.minusWeeks(1)
        }
        return streak
    }

    internal fun bestWeeklyStreak(habit: Habit, counts: Map<LocalDate, Int>, today: LocalDate, firstDayOfWeek: DayOfWeek, times: Int): Int {
        val first = counts.keys.minOrNull() ?: return 0
        var week = weekStartOf(maxOf(first, habit.startDate), firstDayOfWeek)
        val last = weekStartOf(today, firstDayOfWeek)
        var best = 0
        var run = 0
        while (!week.isAfter(last)) {
            if (weekDone(habit, counts, week) >= times) {
                run++
                if (run > best) best = run
            } else if (week != last) run = 0
            week = week.plusWeeks(1)
        }
        return best
    }

    private fun weeklyRate(habit: Habit, counts: Map<LocalDate, Int>, today: LocalDate, firstDayOfWeek: DayOfWeek, times: Int): Double {
        // last 4 complete weeks + current week progress
        val current = weekStartOf(today, firstDayOfWeek)
        val startWeek = weekStartOf(habit.startDate, firstDayOfWeek)
        var total = 0.0
        var weeks = 0
        for (i in 0..4) {
            val w = current.minusWeeks(i.toLong())
            if (w.isBefore(startWeek)) break
            total += minOf(1.0, weekDone(habit, counts, w).toDouble() / times)
            weeks++
        }
        return if (weeks == 0) 0.0 else total / weeks
    }

    /** Daily completion map for a heat-map view: date -> ratio (0..1), null if not scheduled. */
    fun history(habit: Habit, logs: List<HabitLog>, from: LocalDate, to: LocalDate): Map<LocalDate, Double?> {
        val counts = logs.filter { it.habitId == habit.id }.associate { it.date to it.count }
        val out = LinkedHashMap<LocalDate, Double?>()
        var day = from
        while (!day.isAfter(to)) {
            out[day] = if (isScheduled(habit, day)) minOf(1.0, (counts[day] ?: 0).toDouble() / habit.targetPerDay) else null
            day = day.plusDays(1)
        }
        return out
    }
}
