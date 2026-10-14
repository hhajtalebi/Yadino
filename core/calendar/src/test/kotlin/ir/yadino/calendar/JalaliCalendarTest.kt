package ir.yadino.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class JalaliCalendarTest {

    @Test
    fun knownConversions() {
        val cases = mapOf(
            "2026-10-14" to "1405/07/22",
            "2026-03-21" to "1405/01/01",
            "2026-03-20" to "1404/12/29",
            "2025-03-21" to "1404/01/01",
            "2025-03-20" to "1403/12/30",
            "2024-03-20" to "1403/01/01",
            "2024-03-19" to "1402/12/29",
            "2021-03-20" to "1399/12/30",
            "1979-02-11" to "1357/11/22",
            "1970-01-01" to "1348/10/11",
            "2000-01-01" to "1378/10/11",
        )
        for ((g, j) in cases) {
            val date = JalaliDate.from(LocalDate.parse(g))
            assertEquals("gregorian $g", j, date.toString())
            assertEquals("jalali $j", LocalDate.parse(g), JalaliDate.parse(j)!!.toLocalDate())
        }
    }

    @Test
    fun matchesReferenceVectors() {
        val stream = javaClass.classLoader!!.getResourceAsStream("jalali_vectors.csv")!!
        var count = 0
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val (g, j) = line.split(',')
                val gregorian = LocalDate.parse(g)
                assertEquals(line, j, gregorian.toJalali().toString())
                assertEquals(line, gregorian, JalaliDate.parse(j)!!.toLocalDate())
                count++
            }
        }
        assertTrue("vectors loaded", count > 4000)
    }

    @Test
    fun leapYearsMatchReference() {
        val expected = javaClass.classLoader!!.getResourceAsStream("jalali_leap_years.txt")!!
            .bufferedReader().readText().trim().split(',').map { it.toInt() }.toSet()
        for (year in 1300 until 1500) {
            assertEquals("year $year", year in expected, JalaliCalendar.isLeapYear(year))
        }
    }

    @Test
    fun recentLeapYears() {
        listOf(1395, 1399, 1403, 1408, 1412, 1416, 1420).forEach {
            assertTrue("$it should be leap", JalaliCalendar.isLeapYear(it))
        }
        listOf(1400, 1401, 1402, 1404, 1405, 1406, 1407, 1409).forEach {
            assertFalse("$it should not be leap", JalaliCalendar.isLeapYear(it))
        }
    }

    @Test
    fun monthLengths() {
        for (m in 1..6) assertEquals(31, JalaliCalendar.monthLength(1405, m))
        for (m in 7..11) assertEquals(30, JalaliCalendar.monthLength(1405, m))
        assertEquals(29, JalaliCalendar.monthLength(1405, 12))
        assertEquals(30, JalaliCalendar.monthLength(1403, 12))
        assertEquals(365, JalaliCalendar.yearLength(1405))
        assertEquals(366, JalaliCalendar.yearLength(1403))
    }

    @Test
    fun everyDayRoundTripsAndIsContiguous() {
        val start = LocalDate.of(1800, 1, 1).toEpochDay()
        val end = LocalDate.of(2300, 12, 31).toEpochDay()
        var previous = JalaliDate.ofEpochDay(start - 1)
        for (epochDay in start..end) {
            val date = JalaliDate.ofEpochDay(epochDay)
            assertEquals(epochDay, JalaliCalendar.toEpochDay(date.year, date.month, date.day))
            // contiguity: either next day in month, or first day of next month/year
            if (date.day == 1) {
                assertEquals(previous.lengthOfMonth(), previous.day)
                if (date.month == 1) {
                    assertEquals(12, previous.month); assertEquals(previous.year + 1, date.year)
                } else {
                    assertEquals(previous.month + 1, date.month); assertEquals(previous.year, date.year)
                }
            } else {
                assertEquals(previous.day + 1, date.day)
                assertEquals(previous.month, date.month)
            }
            previous = date
        }
    }

    @Test
    fun yearAndMonthBoundaries() {
        // end of a leap year
        val esfand30 = JalaliDate.of(1403, 12, 30)
        assertEquals(JalaliDate.of(1404, 1, 1), esfand30.plusDays(1))
        // end of a common year
        val esfand29 = JalaliDate.of(1404, 12, 29)
        assertEquals(JalaliDate.of(1405, 1, 1), esfand29.plusDays(1))
        // first half -> second half
        assertEquals(JalaliDate.of(1405, 7, 1), JalaliDate.of(1405, 6, 31).plusDays(1))
        assertEquals(JalaliDate.of(1405, 6, 31), JalaliDate.of(1405, 7, 1).minusDays(1))
    }

    @Test
    fun invalidDatesAreRejected() {
        assertNull(JalaliDate.ofOrNull(1404, 12, 30))
        assertNull(JalaliDate.ofOrNull(1405, 7, 31))
        assertNull(JalaliDate.ofOrNull(1405, 13, 1))
        assertNull(JalaliDate.ofOrNull(1405, 0, 1))
        assertNull(JalaliDate.ofOrNull(1405, 1, 0))
        assertNull(JalaliDate.parse("1405/7"))
        assertNull(JalaliDate.parse("abc"))
        try {
            JalaliDate.of(1404, 12, 30)
            fail("expected exception")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun parsesPersianDigits() {
        assertEquals(JalaliDate.of(1405, 7, 22), JalaliDate.parse("۱۴۰۵/۰۷/۲۲"))
        assertEquals(JalaliDate.of(1405, 7, 22), JalaliDate.parse("1405-7-22"))
    }

    @Test
    fun weekdays() {
        assertEquals(DayOfWeek.WEDNESDAY, JalaliDate.of(1405, 7, 22).dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, JalaliDate.of(1405, 1, 1).dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, JalaliDate.of(1404, 1, 1).dayOfWeek)
        assertEquals(DayOfWeek.THURSDAY, JalaliDate.ofEpochDay(0).dayOfWeek)
    }

    @Test
    fun dayOfYear() {
        assertEquals(1, JalaliDate.of(1405, 1, 1).dayOfYear)
        assertEquals(187, JalaliDate.of(1405, 7, 1).dayOfYear)
        assertEquals(365, JalaliDate.of(1405, 12, 29).dayOfYear)
        assertEquals(366, JalaliDate.of(1403, 12, 30).dayOfYear)
    }
}
