package ir.yadino.calendar

/** A date in the (tabular) lunar Hijri calendar. */
data class HijriDate(val year: Int, val month: Int, val day: Int)

/**
 * Arithmetic (tabular) Islamic calendar using the civil epoch and the common 30-year
 * leap cycle (2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29).
 *
 * The official Iranian lunar calendar is based on crescent sighting and can differ from
 * the tabular calendar by about one day. Callers must therefore treat results as
 * approximate and allow the user to apply a correction ([adjustmentDays]).
 */
object HijriCalendar {
    private const val EPOCH_JDN = 1_948_440L

    fun isLeapYear(year: Int): Boolean = Math.floorMod(14 + 11 * year, 30) < 11

    fun monthLength(year: Int, month: Int): Int {
        require(month in 1..12)
        return if (month % 2 == 1 || (month == 12 && isLeapYear(year))) 30 else 29
    }

    /** Julian day number of a tabular Hijri date. */
    fun toJdn(year: Int, month: Int, day: Int): Long =
        day + (59L * (month - 1) + 1) / 2 + (year - 1) * 354L + Math.floorDiv(3L + 11L * year, 30L) + EPOCH_JDN - 1

    fun fromJdn(jdn: Long): HijriDate {
        var year = Math.floorDiv(30L * (jdn - EPOCH_JDN) + 10646L, 10631L).toInt()
        if (jdn < toJdn(year, 1, 1)) year--
        if (jdn >= toJdn(year + 1, 1, 1)) year++
        var month = 1
        while (month < 12 && jdn >= toJdn(year, month + 1, 1)) month++
        val day = (jdn - toJdn(year, month, 1) + 1).toInt()
        return HijriDate(year, month, day)
    }

    /** Converts an ISO epoch day to a Hijri date applying [adjustmentDays] correction. */
    fun fromEpochDay(epochDay: Long, adjustmentDays: Int = 0): HijriDate =
        fromJdn(JalaliCalendar.julianDayNumber(epochDay) - adjustmentDays)

    /** Converts a Hijri date to an ISO epoch day applying [adjustmentDays] correction. */
    fun toEpochDay(date: HijriDate, adjustmentDays: Int = 0): Long =
        toJdn(date.year, date.month, date.day) - JalaliCalendar.julianDayNumber(0) + adjustmentDays
}
