package ir.yadino.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class FormattingAndGridTest {

    private val date = JalaliDate.of(1405, 7, 22)

    @Test
    fun digits() {
        assertEquals("۱۴۰۵/۰۷/۲۲", Digits.toPersian("1405/07/22"))
        assertEquals("1405/07/22", Digits.toLatin("۱۴۰۵/۰۷/۲۲"))
        assertEquals("123", Digits.toLatin("١٢٣"))
        assertEquals("۰۷", Digits.format(7, NumeralStyle.PERSIAN, 2))
        assertEquals("-05", Digits.format(-5, NumeralStyle.LATIN, 2))
    }

    @Test
    fun presetStyles() {
        assertEquals("چهارشنبه ۲۲ مهر ۱۴۰۵", CalendarFormatter.formatJalali(date, DateFormatStyle.FULL, NumeralStyle.PERSIAN))
        assertEquals("۲۲ مهر ۱۴۰۵", CalendarFormatter.formatJalali(date, DateFormatStyle.LONG, NumeralStyle.PERSIAN))
        assertEquals("چهارشنبه ۲۲ مهر", CalendarFormatter.formatJalali(date, DateFormatStyle.MEDIUM, NumeralStyle.PERSIAN))
        assertEquals("1405/07/22", CalendarFormatter.formatJalali(date, DateFormatStyle.NUMERIC, NumeralStyle.LATIN))
        assertEquals("05/7/22", CalendarFormatter.formatJalali(date, DateFormatStyle.NUMERIC_SHORT, NumeralStyle.LATIN))
    }

    @Test
    fun customPatternsAndLiterals() {
        assertEquals("روز ۲۲ از مهر", CalendarFormatter.formatJalali(date, "'روز' d 'از' MMMM", NumeralStyle.PERSIAN))
        assertEquals("ch 22", CalendarFormatter.formatJalali(date, "'ch' dd", NumeralStyle.LATIN))
        assertEquals("چ", CalendarFormatter.formatJalali(date, "E", NumeralStyle.PERSIAN))
        assertEquals("it's", CalendarFormatter.formatJalali(date, "'it''s'", NumeralStyle.LATIN))
    }

    @Test
    fun gregorianFormatting() {
        assertEquals(
            "۱۴ اکتبر ۲۰۲۶",
            CalendarFormatter.formatGregorian(LocalDate.of(2026, 10, 14), "d MMMM yyyy", NumeralStyle.PERSIAN),
        )
    }

    @Test
    fun timeFormatting() {
        assertEquals("۰۸:۰۵", TimeFormatter.format(8, 5, true, NumeralStyle.PERSIAN))
        assertEquals("8:05 ق.ظ", TimeFormatter.format(8, 5, false, NumeralStyle.LATIN))
        assertEquals("12:00 ب.ظ", TimeFormatter.format(12, 0, false, NumeralStyle.LATIN))
        assertEquals("12:30 ق.ظ", TimeFormatter.format(0, 30, false, NumeralStyle.LATIN))
        assertEquals("۱ ساعت و ۳۰ دقیقه", TimeFormatter.formatDuration(90, NumeralStyle.PERSIAN))
        assertEquals("0 دقیقه", TimeFormatter.formatDuration(0, NumeralStyle.LATIN))
        assertEquals("2 روز و 1 ساعت", TimeFormatter.formatDuration(49 * 60, NumeralStyle.LATIN))
    }

    @Test
    fun monthGridStartingSaturday() {
        val grid = WeekMath.monthGrid(date, DayOfWeek.SATURDAY)
        assertEquals(42, grid.size)
        assertEquals(DayOfWeek.SATURDAY, grid.first().date.dayOfWeek)
        val inMonth = grid.filter { it.inMonth }
        assertEquals(30, inMonth.size)
        assertEquals(JalaliDate.of(1405, 7, 1), inMonth.first().date)
        assertEquals(JalaliDate.of(1405, 7, 30), inMonth.last().date)
        // 1 Mehr 1405 is a Wednesday: Sat..Tue are leading days (4 cells)
        assertEquals(4, grid.indexOfFirst { it.inMonth })
        // contiguous
        grid.zipWithNext().forEach { (a, b) -> assertEquals(1, a.date.daysUntil(b.date)) }
    }

    @Test
    fun monthGridStartingMonday() {
        val grid = WeekMath.monthGrid(date, DayOfWeek.MONDAY)
        assertEquals(DayOfWeek.MONDAY, grid.first().date.dayOfWeek)
        assertEquals(2, grid.indexOfFirst { it.inMonth })
    }

    @Test
    fun weeks() {
        assertEquals(JalaliDate.of(1405, 7, 18), WeekMath.startOfWeek(date, DayOfWeek.SATURDAY))
        assertEquals(JalaliDate.of(1405, 7, 24), WeekMath.endOfWeek(date, DayOfWeek.SATURDAY))
        assertEquals(1, WeekMath.weekOfYear(JalaliDate.of(1405, 1, 1), DayOfWeek.SATURDAY))
        assertEquals(2, WeekMath.weekOfYear(JalaliDate.of(1405, 1, 8), DayOfWeek.SATURDAY))
        assertEquals(1, WeekMath.weekOfYear(JalaliDate.of(1405, 1, 7), DayOfWeek.SATURDAY))
        assertEquals(5, WeekMath.rowsNeeded(date, DayOfWeek.SATURDAY))
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), WeekMath.orderedDays(DayOfWeek.SATURDAY).take(2))
    }

    @Test
    fun occasions() {
        val nowruz = IranianOccasions.occasionsOf(JalaliDate.of(1405, 1, 1))
        assertTrue(nowruz.any { it.isHoliday && !it.isApproximate })
        assertTrue(IranianOccasions.isHoliday(JalaliDate.of(1405, 11, 22)))
        assertTrue(IranianOccasions.occasionsOf(JalaliDate.of(1405, 7, 22), OccasionDisplay.NONE).isEmpty())
        val yalda = IranianOccasions.occasionsOf(JalaliDate.of(1405, 9, 30), OccasionDisplay.HOLIDAYS_ONLY)
        assertTrue(yalda.isEmpty())
        val yaldaAll = IranianOccasions.occasionsOf(JalaliDate.of(1405, 9, 30), OccasionDisplay.ALL)
        assertEquals("شب یلدا", yaldaAll.single().title)
    }

    @Test
    fun lunarOccasionsAreApproximateAndAdjustable() {
        // Tabular calendar: 13 Rajab 1448 = 2 Dey 1405
        val day = JalaliDate.of(1405, 10, 2)
        val list = IranianOccasions.occasionsOf(day)
        assertTrue(list.any { it.calendar == OccasionCalendar.LUNAR && it.isApproximate && it.isHoliday })
        // with +1 correction the occasion moves one day later
        assertFalse(IranianOccasions.occasionsOf(day, lunarAdjustment = 1).any { it.calendar == OccasionCalendar.LUNAR })
        assertTrue(IranianOccasions.occasionsOf(day.plusDays(1), lunarAdjustment = 1).any { it.calendar == OccasionCalendar.LUNAR })
    }

    @Test
    fun chaharshanbeSuri() {
        // last Wednesday of 1404 is 27 Esfand (2026-03-18), so the eve is Tuesday 26 Esfand
        val eve = JalaliDate.of(1404, 12, 26)
        assertTrue(IranianOccasions.occasionsOf(eve).any { it.title == "چهارشنبه‌سوری" })
        assertFalse(IranianOccasions.occasionsOf(eve.minusDays(7)).any { it.title == "چهارشنبه‌سوری" })
    }

    @Test
    fun hijriRoundTrip() {
        for (epochDay in LocalDate.of(1990, 1, 1).toEpochDay()..LocalDate.of(2040, 1, 1).toEpochDay() step 3) {
            val h = HijriCalendar.fromEpochDay(epochDay)
            assertEquals(epochDay, HijriCalendar.toEpochDay(h))
            assertTrue(h.day in 1..HijriCalendar.monthLength(h.year, h.month))
        }
        assertEquals(HijriDate(1448, 1, 9), HijriCalendar.fromEpochDay(LocalDate.of(2026, 6, 25).toEpochDay()))
    }
}
