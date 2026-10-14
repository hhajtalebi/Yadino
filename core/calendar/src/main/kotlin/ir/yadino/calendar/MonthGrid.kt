package ir.yadino.calendar

import java.time.DayOfWeek

/** A cell of a month view. */
data class MonthCell(
    val date: JalaliDate,
    /** True when the cell belongs to the displayed month (not a leading/trailing day). */
    val inMonth: Boolean,
)

/** Calendar helpers that depend on the configured first day of the week. */
object WeekMath {

    /** The seven days of the week starting from [firstDay]. */
    fun orderedDays(firstDay: DayOfWeek): List<DayOfWeek> = List(7) { firstDay.plus(it.toLong()) }

    /** Column index (0..6) of [day] when weeks start on [firstDay]. */
    fun column(day: DayOfWeek, firstDay: DayOfWeek): Int = Math.floorMod(day.value - firstDay.value, 7)

    /** First day of the week containing [date]. */
    fun startOfWeek(date: JalaliDate, firstDay: DayOfWeek): JalaliDate =
        date.minusDays(column(date.dayOfWeek, firstDay).toLong())

    fun endOfWeek(date: JalaliDate, firstDay: DayOfWeek): JalaliDate = startOfWeek(date, firstDay).plusDays(6)

    /** The seven dates of the week containing [date]. */
    fun weekDates(date: JalaliDate, firstDay: DayOfWeek): List<JalaliDate> {
        val start = startOfWeek(date, firstDay)
        return List(7) { start.plusDays(it.toLong()) }
    }

    /**
     * Week number inside the Jalali year. Week 1 is the week that contains Farvardin 1st;
     * weeks start on [firstDay].
     */
    fun weekOfYear(date: JalaliDate, firstDay: DayOfWeek): Int {
        val startOfYear = date.firstDayOfYear()
        val offset = column(startOfYear.dayOfWeek, firstDay)
        return (date.dayOfYear - 1 + offset) / 7 + 1
    }

    /**
     * Builds a fixed 6x7 grid for the month of [anyDayInMonth] (42 cells), including
     * leading and trailing days of adjacent months.
     */
    fun monthGrid(anyDayInMonth: JalaliDate, firstDay: DayOfWeek): List<MonthCell> {
        val first = anyDayInMonth.firstDayOfMonth()
        val start = first.minusDays(column(first.dayOfWeek, firstDay).toLong())
        return List(42) { index ->
            val d = start.plusDays(index.toLong())
            MonthCell(d, d.year == first.year && d.month == first.month)
        }
    }

    /** Number of week rows actually needed to display the month (4..6). */
    fun rowsNeeded(anyDayInMonth: JalaliDate, firstDay: DayOfWeek): Int {
        val first = anyDayInMonth.firstDayOfMonth()
        val cells = column(first.dayOfWeek, firstDay) + first.lengthOfMonth()
        return (cells + 6) / 7
    }
}
