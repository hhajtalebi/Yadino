package ir.yadino.calendar

import java.time.DayOfWeek

/** Persian names used by the calendar. */
object PersianNames {

    val jalaliMonths: List<String> = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    /** Gregorian month names as they are written in Persian. */
    val gregorianMonths: List<String> = listOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر",
    )

    val hijriMonths: List<String> = listOf(
        "محرم", "صفر", "ربیع‌الاول", "ربیع‌الثانی", "جمادی‌الاول", "جمادی‌الثانی",
        "رجب", "شعبان", "رمضان", "شوال", "ذی‌القعده", "ذی‌الحجه",
    )

    val seasons: List<String> = listOf("بهار", "تابستان", "پاییز", "زمستان")

    fun jalaliMonth(month: Int): String = jalaliMonths[month - 1]

    fun gregorianMonth(month: Int): String = gregorianMonths[month - 1]

    fun hijriMonth(month: Int): String = hijriMonths[month - 1]

    /** Season name for a Jalali month. */
    fun season(month: Int): String = seasons[(month - 1) / 3]

    fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.SATURDAY -> "شنبه"
        DayOfWeek.SUNDAY -> "یکشنبه"
        DayOfWeek.MONDAY -> "دوشنبه"
        DayOfWeek.TUESDAY -> "سه‌شنبه"
        DayOfWeek.WEDNESDAY -> "چهارشنبه"
        DayOfWeek.THURSDAY -> "پنجشنبه"
        DayOfWeek.FRIDAY -> "جمعه"
    }

    /** One-letter abbreviations commonly used in Persian calendars. */
    fun weekdayShort(day: DayOfWeek): String = when (day) {
        DayOfWeek.SATURDAY -> "ش"
        DayOfWeek.SUNDAY -> "ی"
        DayOfWeek.MONDAY -> "د"
        DayOfWeek.TUESDAY -> "س"
        DayOfWeek.WEDNESDAY -> "چ"
        DayOfWeek.THURSDAY -> "پ"
        DayOfWeek.FRIDAY -> "ج"
    }
}
