package ir.yadino.app.core

import ir.yadino.calendar.CalendarFormatter
import ir.yadino.calendar.DateFormatStyle
import ir.yadino.calendar.Digits
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.NumeralStyle
import ir.yadino.calendar.PersianNames
import ir.yadino.calendar.TimeFormatter
import ir.yadino.calendar.WeekMath
import ir.yadino.domain.model.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Source of "now" and the effective time zone (device zone unless overridden in settings). */
class AppClock(private val settingsProvider: () -> AppSettings) {

    fun zone(): ZoneId = zoneFor(settingsProvider())

    fun now(): Instant = Instant.now()

    fun today(): LocalDate = LocalDate.now(zone())

    fun nowTime(): LocalTime = LocalTime.now(zone())

    companion object {
        fun zoneFor(settings: AppSettings): ZoneId =
            settings.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
}

/**
 * All user-visible date, time and number formatting. Uses the Jalali calendar module and
 * honours the date format, numeral system and 12/24 hour settings.
 */
class Formatter(val settings: AppSettings) {
    val numerals: NumeralStyle get() = settings.numerals

    fun number(value: Int): String = Digits.format(value, numerals)
    fun number(value: Long): String = Digits.format(value, numerals)
    fun digits(text: String): String = Digits.apply(text, numerals)

    fun percent(value: Int): String = number(value) + "٪"

    fun date(date: LocalDate, style: DateFormatStyle = settings.dateFormat): String =
        CalendarFormatter.formatJalali(JalaliDate.from(date), style, numerals)

    fun shortDate(date: LocalDate): String = CalendarFormatter.formatJalali(JalaliDate.from(date), "d MMMM", numerals)

    fun dayAndMonth(date: LocalDate): String = shortDate(date)

    fun monthYear(date: JalaliDate): String = PersianNames.jalaliMonth(date.month) + " " + number(date.year)

    fun gregorian(date: LocalDate): String = CalendarFormatter.formatGregorian(date, "d MMMM yyyy", numerals)

    fun weekday(date: LocalDate): String = PersianNames.weekday(date.dayOfWeek)

    fun time(time: LocalTime): String = TimeFormatter.format(time, settings.use24Hour, numerals)

    fun timeRange(start: LocalTime?, end: LocalTime?): String? = when {
        start != null && end != null -> time(start) + " تا " + time(end)
        start != null -> time(start)
        else -> null
    }

    fun dateTime(instant: Instant, zone: ZoneId): String {
        val zdt = instant.atZone(zone)
        return date(zdt.toLocalDate(), DateFormatStyle.LONG) + "، " + time(zdt.toLocalTime())
    }

    fun duration(minutes: Long): String = TimeFormatter.formatDuration(minutes, numerals)

    fun weekNumber(date: LocalDate): String =
        "هفته " + number(WeekMath.weekOfYear(JalaliDate.from(date), settings.firstDayOfWeek))

    /** «امروز»، «فردا»، «دیروز» or a short date, used in lists. */
    fun relativeDay(date: LocalDate, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, date)
        return when (diff) {
            0L -> "امروز"
            1L -> "فردا"
            2L -> "پس‌فردا"
            -1L -> "دیروز"
            in 3L..6L -> weekday(date)
            else -> if (JalaliDate.from(date).year == JalaliDate.from(today).year) shortDate(date)
            else date(date, DateFormatStyle.NUMERIC)
        }
    }

    fun daysLeft(days: Long): String = when {
        days == 0L -> "امروز"
        days == 1L -> "فردا"
        days > 0 -> number(days) + " روز مانده"
        else -> number(-days) + " روز گذشته"
    }

    fun minutesOffset(minutes: Int): String = when {
        minutes == 0 -> "سر وقت"
        minutes % (24 * 60) == 0 -> number(minutes / (24 * 60)) + " روز قبل"
        minutes % 60 == 0 -> number(minutes / 60) + " ساعت قبل"
        else -> number(minutes) + " دقیقه قبل"
    }
}
