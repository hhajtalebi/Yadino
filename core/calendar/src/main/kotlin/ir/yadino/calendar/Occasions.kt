package ir.yadino.calendar

import java.time.DayOfWeek

/** Source calendar of an occasion. */
enum class OccasionCalendar { SOLAR, LUNAR }

/**
 * A calendar occasion.
 *
 * @property isHoliday official public holiday in Iran.
 * @property isApproximate true for lunar occasions whose exact day depends on crescent
 * sighting; the UI must label these as approximate.
 */
data class Occasion(
    val title: String,
    val isHoliday: Boolean,
    val calendar: OccasionCalendar,
    val isApproximate: Boolean,
)

/** How occasions are displayed (user setting). */
enum class OccasionDisplay {
    NONE,
    HOLIDAYS_ONLY,
    ALL,
}

/**
 * Iranian occasions.
 *
 * Solar (Jalali) occasions are fixed and exact. Lunar occasions are computed with the
 * tabular Hijri calendar and are flagged as approximate; the user can shift them with a
 * correction of a few days in settings.
 */
object IranianOccasions {

    private data class Fixed(val month: Int, val day: Int, val title: String, val holiday: Boolean)

    private val solar = listOf(
        Fixed(1, 1, "جشن نوروز، آغاز سال نو", true),
        Fixed(1, 2, "عید نوروز", true),
        Fixed(1, 3, "عید نوروز", true),
        Fixed(1, 4, "عید نوروز", true),
        Fixed(1, 12, "روز جمهوری اسلامی ایران", true),
        Fixed(1, 13, "روز طبیعت", true),
        Fixed(2, 1, "روز بزرگداشت سعدی", false),
        Fixed(2, 10, "روز ملی خلیج فارس", false),
        Fixed(2, 12, "روز معلم", false),
        Fixed(2, 25, "روز بزرگداشت فردوسی و پاسداشت زبان فارسی", false),
        Fixed(2, 28, "روز بزرگداشت خیام", false),
        Fixed(3, 14, "رحلت امام خمینی (ره)", true),
        Fixed(3, 15, "قیام ۱۵ خرداد", true),
        Fixed(6, 1, "روز بزرگداشت ابوعلی سینا و روز پزشک", false),
        Fixed(7, 8, "روز بزرگداشت مولوی", false),
        Fixed(7, 20, "روز بزرگداشت حافظ", false),
        Fixed(9, 16, "روز دانشجو", false),
        Fixed(9, 30, "شب یلدا", false),
        Fixed(11, 22, "پیروزی انقلاب اسلامی", true),
        Fixed(12, 5, "روز بزرگداشت خواجه نصیرالدین طوسی و روز مهندس", false),
        Fixed(12, 29, "روز ملی شدن صنعت نفت ایران", true),
    )

    /** Lunar occasions; day 0 means "last day of the month". */
    private val lunar = listOf(
        Fixed(1, 9, "تاسوعای حسینی", true),
        Fixed(1, 10, "عاشورای حسینی", true),
        Fixed(2, 20, "اربعین حسینی", true),
        Fixed(2, 28, "رحلت پیامبر اکرم (ص) و شهادت امام حسن مجتبی (ع)", true),
        Fixed(2, 0, "شهادت امام رضا (ع)", true),
        Fixed(3, 8, "شهادت امام حسن عسکری (ع)", true),
        Fixed(3, 17, "میلاد پیامبر اکرم (ص) و امام جعفر صادق (ع)", true),
        Fixed(6, 3, "شهادت حضرت فاطمه زهرا (س)", true),
        Fixed(6, 20, "ولادت حضرت فاطمه زهرا (س) و روز مادر", false),
        Fixed(7, 13, "ولادت امام علی (ع) و روز پدر", true),
        Fixed(7, 27, "مبعث پیامبر اکرم (ص)", true),
        Fixed(8, 15, "ولادت حضرت قائم (عج)", true),
        Fixed(9, 21, "شهادت حضرت علی (ع)", true),
        Fixed(10, 1, "عید سعید فطر", true),
        Fixed(10, 2, "تعطیل به مناسبت عید سعید فطر", true),
        Fixed(10, 25, "شهادت امام جعفر صادق (ع)", true),
        Fixed(12, 10, "عید سعید قربان", true),
        Fixed(12, 18, "عید سعید غدیر خم", true),
    )

    /**
     * Occasions of [date] filtered by [display]. [lunarAdjustment] shifts lunar dates by
     * the given number of days (positive = later).
     */
    fun occasionsOf(
        date: JalaliDate,
        display: OccasionDisplay = OccasionDisplay.ALL,
        lunarAdjustment: Int = 0,
    ): List<Occasion> {
        if (display == OccasionDisplay.NONE) return emptyList()
        val result = ArrayList<Occasion>(2)
        for (f in solar) {
            if (f.month == date.month && f.day == date.day) {
                result += Occasion(f.title, f.holiday, OccasionCalendar.SOLAR, isApproximate = false)
            }
        }
        if (isChaharshanbeSuri(date)) {
            result += Occasion("چهارشنبه‌سوری", false, OccasionCalendar.SOLAR, isApproximate = false)
        }
        val hijri = HijriCalendar.fromEpochDay(date.epochDay, lunarAdjustment)
        val lastDay = HijriCalendar.monthLength(hijri.year, hijri.month)
        for (f in lunar) {
            val targetDay = if (f.day == 0) lastDay else f.day
            if (f.month == hijri.month && targetDay == hijri.day) {
                result += Occasion(f.title, f.holiday, OccasionCalendar.LUNAR, isApproximate = true)
            }
        }
        return if (display == OccasionDisplay.HOLIDAYS_ONLY) result.filter { it.isHoliday } else result
    }

    /** True if the date is an official holiday according to the occasion tables. */
    fun isHoliday(date: JalaliDate, lunarAdjustment: Int = 0): Boolean =
        occasionsOf(date, OccasionDisplay.HOLIDAYS_ONLY, lunarAdjustment).isNotEmpty()

    /** Eve of the last Wednesday of the year (the Tuesday evening). */
    private fun isChaharshanbeSuri(date: JalaliDate): Boolean {
        if (date.month != 12 || date.dayOfWeek != DayOfWeek.TUESDAY) return false
        val nextWednesday = date.plusDays(1)
        // It is the last Wednesday of the year when the following Wednesday is in the new year.
        return nextWednesday.year == date.year && nextWednesday.plusDays(7).year != date.year
    }
}
