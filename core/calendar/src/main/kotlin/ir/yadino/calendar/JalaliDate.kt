package ir.yadino.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * An immutable date in the Solar Hijri (Jalali) calendar.
 *
 * Instances are always valid. Storage and arithmetic are based on [epochDay] (ISO epoch
 * day) which makes the type trivially interoperable with [LocalDate] and with the
 * database layer where dates are persisted as epoch days.
 */
class JalaliDate private constructor(
    val year: Int,
    val month: Int,
    val day: Int,
    /** ISO epoch day (days since 1970-01-01). */
    val epochDay: Long,
) : Comparable<JalaliDate> {

    /** Day of week as defined by java.time (MONDAY..SUNDAY). */
    val dayOfWeek: DayOfWeek
        get() = DayOfWeek.of(Math.floorMod(epochDay + 3, 7L).toInt() + 1)

    /** 1-based day of the Jalali year. */
    val dayOfYear: Int
        get() = JalaliCalendar.dayOfYearIndex(month, day) + 1

    val isLeapYear: Boolean get() = JalaliCalendar.isLeapYear(year)

    fun lengthOfMonth(): Int = JalaliCalendar.monthLength(year, month)

    fun lengthOfYear(): Int = JalaliCalendar.yearLength(year)

    fun toLocalDate(): LocalDate = LocalDate.ofEpochDay(epochDay)

    fun plusDays(days: Long): JalaliDate = if (days == 0L) this else ofEpochDay(epochDay + days)

    fun minusDays(days: Long): JalaliDate = plusDays(-days)

    fun plusWeeks(weeks: Long): JalaliDate = plusDays(weeks * 7)

    /**
     * Adds months keeping the day of month when possible. When the target month is
     * shorter the result is clamped to its last day (e.g. 31 Shahrivar + 1 month = 30 Mehr).
     */
    fun plusMonths(months: Long): JalaliDate {
        if (months == 0L) return this
        val total = (year.toLong() * 12 + (month - 1)) + months
        val newYear = Math.floorDiv(total, 12L).toInt()
        val newMonth = Math.floorMod(total, 12L).toInt() + 1
        val newDay = minOf(day, JalaliCalendar.monthLength(newYear, newMonth))
        return of(newYear, newMonth, newDay)
    }

    fun minusMonths(months: Long): JalaliDate = plusMonths(-months)

    /** Adds years; Esfand 30th is clamped to Esfand 29th in non-leap years. */
    fun plusYears(years: Long): JalaliDate {
        if (years == 0L) return this
        val newYear = Math.addExact(year.toLong(), years).toInt()
        val newDay = minOf(day, JalaliCalendar.monthLength(newYear, month))
        return of(newYear, month, newDay)
    }

    fun withDay(day: Int): JalaliDate = of(year, month, day)

    fun withDayClamped(day: Int): JalaliDate = of(year, month, day.coerceIn(1, lengthOfMonth()))

    fun firstDayOfMonth(): JalaliDate = if (day == 1) this else of(year, month, 1)

    fun lastDayOfMonth(): JalaliDate = of(year, month, lengthOfMonth())

    fun firstDayOfYear(): JalaliDate = of(year, 1, 1)

    fun lastDayOfYear(): JalaliDate = of(year, 12, JalaliCalendar.monthLength(year, 12))

    fun isBefore(other: JalaliDate): Boolean = epochDay < other.epochDay

    fun isAfter(other: JalaliDate): Boolean = epochDay > other.epochDay

    /** Number of days from this date to [other] (positive if [other] is later). */
    fun daysUntil(other: JalaliDate): Long = other.epochDay - epochDay

    /** Month index usable for month arithmetic and comparisons (year*12 + month-1). */
    val monthIndex: Int get() = year * 12 + (month - 1)

    override fun compareTo(other: JalaliDate): Int = epochDay.compareTo(other.epochDay)

    override fun equals(other: Any?): Boolean = other is JalaliDate && other.epochDay == epochDay

    override fun hashCode(): Int = epochDay.hashCode()

    /** ISO-like representation, e.g. `1403/12/30` (Latin digits). */
    override fun toString(): String =
        "%04d/%02d/%02d".format(year, month, day)

    companion object {
        /** Creates a date, throwing [IllegalArgumentException] for invalid input. */
        fun of(year: Int, month: Int, day: Int): JalaliDate {
            require(JalaliCalendar.isValid(year, month, day)) { "Invalid Jalali date: $year/$month/$day" }
            return JalaliDate(year, month, day, JalaliCalendar.toEpochDay(year, month, day))
        }

        /** Creates a date or returns null for invalid input. */
        fun ofOrNull(year: Int, month: Int, day: Int): JalaliDate? =
            if (JalaliCalendar.isValid(year, month, day)) of(year, month, day) else null

        fun ofEpochDay(epochDay: Long): JalaliDate {
            val parts = JalaliCalendar.fromEpochDay(epochDay)
            return JalaliDate(parts[0], parts[1], parts[2], epochDay)
        }

        fun from(date: LocalDate): JalaliDate = ofEpochDay(date.toEpochDay())

        /** Creates the date of the first day of the month identified by [monthIndex]. */
        fun ofMonthIndex(monthIndex: Int): JalaliDate =
            of(Math.floorDiv(monthIndex, 12), Math.floorMod(monthIndex, 12) + 1, 1)

        /**
         * Parses `yyyy/MM/dd` (or with `-` separators). Persian and Arabic-Indic digits
         * are accepted. Returns null for malformed or non-existent dates.
         */
        fun parse(text: String): JalaliDate? {
            val normalized = Digits.toLatin(text.trim())
            val parts = normalized.split('/', '-', '.')
            if (parts.size != 3) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val d = parts[2].toIntOrNull() ?: return null
            return ofOrNull(y, m, d)
        }
    }
}

/** Convenience conversion. */
fun LocalDate.toJalali(): JalaliDate = JalaliDate.from(this)
