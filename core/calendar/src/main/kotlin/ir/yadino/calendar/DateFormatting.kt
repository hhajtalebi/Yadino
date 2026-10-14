package ir.yadino.calendar

import java.time.LocalDate
import java.time.LocalTime

/** Predefined date display styles selectable in settings. */
enum class DateFormatStyle(val pattern: String) {
    /** سه‌شنبه ۲۲ مهر ۱۴۰۵ */
    FULL("EEEE d MMMM yyyy"),

    /** ۲۲ مهر ۱۴۰۵ */
    LONG("d MMMM yyyy"),

    /** سه‌شنبه ۲۲ مهر */
    MEDIUM("EEEE d MMMM"),

    /** ۱۴۰۵/۰۷/۲۲ */
    NUMERIC("yyyy/MM/dd"),

    /** ۰۵/۷/۲۲ */
    NUMERIC_SHORT("yy/M/d"),
}

/**
 * Minimal, allocation-friendly pattern formatter shared by the Jalali and Gregorian
 * calendars.
 *
 * Supported tokens: `yyyy`, `yy`, `y`, `MMMM`, `MM`, `M`, `dd`, `d`, `EEEE`, `E`.
 * Text inside single quotes is copied literally; `''` produces a single quote.
 */
object CalendarFormatter {

    fun formatJalali(date: JalaliDate, pattern: String, numerals: NumeralStyle): String =
        format(pattern, numerals, date.year, date.month, date.day, date.dayOfWeek.value) {
            PersianNames.jalaliMonth(it)
        }

    fun formatJalali(date: JalaliDate, style: DateFormatStyle, numerals: NumeralStyle): String =
        formatJalali(date, style.pattern, numerals)

    fun formatGregorian(date: LocalDate, pattern: String, numerals: NumeralStyle): String =
        format(pattern, numerals, date.year, date.monthValue, date.dayOfMonth, date.dayOfWeek.value) {
            PersianNames.gregorianMonth(it)
        }

    private inline fun format(
        pattern: String,
        numerals: NumeralStyle,
        year: Int,
        month: Int,
        day: Int,
        isoDayOfWeek: Int,
        monthName: (Int) -> String,
    ): String {
        val out = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                // '' outside a quoted section produces a literal quote
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    out.append('\''); i += 2; continue
                }
                i++ // opening quote
                while (i < pattern.length) {
                    val q = pattern[i]
                    if (q == '\'') {
                        if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                            out.append('\''); i += 2; continue
                        }
                        i++ // closing quote
                        break
                    }
                    out.append(q); i++
                }
                continue
            }
            var run = 1
            while (i + run < pattern.length && pattern[i + run] == c) run++
            when (c) {
                'y' -> out.append(
                    when (run) {
                        2 -> Digits.format(Math.floorMod(year, 100), numerals, 2)
                        1 -> Digits.format(year, numerals)
                        else -> Digits.format(year, numerals, run)
                    },
                )
                'M' -> out.append(
                    when {
                        run >= 3 -> monthName(month)
                        run == 2 -> Digits.format(month, numerals, 2)
                        else -> Digits.format(month, numerals)
                    },
                )
                'd' -> out.append(Digits.format(day, numerals, if (run >= 2) 2 else 1))
                'E' -> {
                    val dow = java.time.DayOfWeek.of(isoDayOfWeek)
                    out.append(if (run >= 4) PersianNames.weekday(dow) else PersianNames.weekdayShort(dow))
                }
                else -> repeat(run) { out.append(c) }
            }
            i += run
        }
        return out.toString()
    }
}

/** Formats clock times in 24 or 12 hour notation using Persian conventions. */
object TimeFormatter {
    const val AM = "ق.ظ"
    const val PM = "ب.ظ"

    fun format(time: LocalTime, use24Hour: Boolean, numerals: NumeralStyle): String =
        format(time.hour, time.minute, use24Hour, numerals)

    fun format(hour: Int, minute: Int, use24Hour: Boolean, numerals: NumeralStyle): String {
        require(hour in 0..23 && minute in 0..59) { "Invalid time $hour:$minute" }
        return if (use24Hour) {
            Digits.format(hour, numerals, 2) + ":" + Digits.format(minute, numerals, 2)
        } else {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            Digits.format(h12, numerals) + ":" + Digits.format(minute, numerals, 2) + " " +
                (if (hour < 12) AM else PM)
        }
    }

    /** Formats a minute-of-day value (0..1439). */
    fun formatMinuteOfDay(minuteOfDay: Int, use24Hour: Boolean, numerals: NumeralStyle): String =
        format(minuteOfDay / 60, minuteOfDay % 60, use24Hour, numerals)

    /** Human readable duration such as «۱ ساعت و ۳۰ دقیقه». */
    fun formatDuration(totalMinutes: Long, numerals: NumeralStyle): String {
        val minutes = kotlin.math.abs(totalMinutes)
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        val parts = buildList {
            if (days > 0) add(Digits.format(days, numerals) + " روز")
            if (hours > 0) add(Digits.format(hours, numerals) + " ساعت")
            if (mins > 0 || isEmpty()) add(Digits.format(mins, numerals) + " دقیقه")
        }
        return parts.joinToString(" و ")
    }
}
