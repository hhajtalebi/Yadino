package ir.yadino.calendar

import java.time.LocalDate

/**
 * Low level arithmetic of the Solar Hijri (Jalali) calendar.
 *
 * The implementation follows the well known algorithm by Kazimierz M. Borkowski
 * ("The Persian calendar for 3000 years", Earth, Moon and Planets 74, 1996) which
 * reproduces the astronomical calendar that is officially used in Iran.  It relies
 * on a table of "break" years where the 33-year leap cycle changes and is valid for
 * Jalali years [MIN_YEAR, MAX_YEAR].
 *
 * All conversions go through the ISO epoch day (days since 1970-01-01) so that the
 * rest of the application can store dates as a single unambiguous number.
 */
object JalaliCalendar {

    /** First supported Jalali year. */
    const val MIN_YEAR = 1

    /** Last supported Jalali year. */
    const val MAX_YEAR = 3177

    /** Offset between Julian Day Number and ISO epoch day. */
    private const val JDN_EPOCH_OFFSET = 2_440_588L

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    private class YearInfo(
        /** 0 for a leap year, otherwise the number of years since the last leap year. */
        val leap: Int,
        /** Gregorian year in which this Jalali year starts. */
        val gregorianYear: Int,
        /** Day of March on which Farvardin 1st falls. */
        val march: Int,
    )

    private fun yearInfo(jy: Int): YearInfo {
        requireYear(jy)
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += jump / 33 * 8 + (jump % 33) / 4
            jp = jm
        }
        var n = jy - jp
        leapJ += n / 33 * 8 + (n % 33 + 3) / 4
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1
        val leapG = gy / 4 - (gy / 100 + 1) * 3 / 4 - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + (jump + 4) / 33 * 33
        var leap = ((n + 1) % 33 - 1) % 4
        if (leap == -1) leap = 4
        return YearInfo(leap, gy, march)
    }

    private fun requireYear(jy: Int) {
        require(jy in MIN_YEAR..MAX_YEAR) { "Jalali year out of supported range: $jy" }
    }

    /** Returns true when [year] is a leap year (Esfand has 30 days). */
    fun isLeapYear(year: Int): Boolean = yearInfo(year).leap == 0

    /** Number of days in the given Jalali month (1..12). */
    fun monthLength(year: Int, month: Int): Int {
        require(month in 1..12) { "Invalid Jalali month: $month" }
        return when {
            month <= 6 -> 31
            month <= 11 -> 30
            isLeapYear(year) -> 30
            else -> 29
        }
    }

    /** Number of days in the given Jalali year (365 or 366). */
    fun yearLength(year: Int): Int = if (isLeapYear(year)) 366 else 365

    /** Returns true when the triple represents an existing Jalali date. */
    fun isValid(year: Int, month: Int, day: Int): Boolean =
        year in MIN_YEAR..MAX_YEAR && month in 1..12 && day >= 1 && day <= monthLength(year, month)

    /** Epoch day of Farvardin 1st of [year]. */
    fun startOfYearEpochDay(year: Int): Long {
        val info = yearInfo(year)
        return LocalDate.of(info.gregorianYear, 3, info.march).toEpochDay()
    }

    /** Converts a Jalali date to an ISO epoch day. */
    fun toEpochDay(year: Int, month: Int, day: Int): Long {
        require(isValid(year, month, day)) { "Invalid Jalali date: $year/$month/$day" }
        return startOfYearEpochDay(year) + dayOfYearIndex(month, day)
    }

    /** Zero based index of a day inside its Jalali year. */
    internal fun dayOfYearIndex(month: Int, day: Int): Int =
        if (month <= 7) (month - 1) * 31 + day - 1 else 6 * 31 + (month - 7) * 30 + day - 1

    /**
     * Converts an ISO epoch day to a Jalali date. Returns `[year, month, day]`.
     */
    fun fromEpochDay(epochDay: Long): IntArray {
        val gregorian = LocalDate.ofEpochDay(epochDay)
        var jy = gregorian.year - 621
        var start = startOfYearEpochDay(jy)
        if (epochDay < start) {
            jy -= 1
            start = startOfYearEpochDay(jy)
        }
        val k = (epochDay - start).toInt()
        return if (k < 186) {
            intArrayOf(jy, 1 + k / 31, k % 31 + 1)
        } else {
            val r = k - 186
            intArrayOf(jy, 7 + r / 30, r % 30 + 1)
        }
    }

    /** Julian Day Number of an ISO epoch day (useful for interoperability). */
    fun julianDayNumber(epochDay: Long): Long = epochDay + JDN_EPOCH_OFFSET
}
