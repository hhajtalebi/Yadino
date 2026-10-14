package ir.yadino.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JalaliDateArithmeticTest {

    @Test
    fun plusMonthsClampsToMonthEnd() {
        assertEquals(JalaliDate.of(1405, 7, 30), JalaliDate.of(1405, 6, 31).plusMonths(1))
        assertEquals(JalaliDate.of(1405, 12, 29), JalaliDate.of(1405, 11, 30).plusMonths(1))
        assertEquals(JalaliDate.of(1403, 12, 30), JalaliDate.of(1403, 11, 30).plusMonths(1))
        assertEquals(JalaliDate.of(1406, 1, 31), JalaliDate.of(1405, 12, 29).plusMonths(1).withDayClamped(31))
    }

    @Test
    fun plusMonthsAcrossYears() {
        assertEquals(JalaliDate.of(1406, 2, 15), JalaliDate.of(1405, 11, 15).plusMonths(3))
        assertEquals(JalaliDate.of(1404, 10, 15), JalaliDate.of(1405, 1, 15).minusMonths(3))
        assertEquals(JalaliDate.of(1395, 1, 1), JalaliDate.of(1405, 1, 1).minusMonths(120))
    }

    @Test
    fun plusYearsHandlesLeapDay() {
        assertEquals(JalaliDate.of(1404, 12, 29), JalaliDate.of(1403, 12, 30).plusYears(1))
        assertEquals(JalaliDate.of(1408, 12, 30), JalaliDate.of(1408, 12, 30).plusYears(0))
        assertEquals(JalaliDate.of(1412, 12, 30), JalaliDate.of(1408, 12, 30).plusYears(4))
    }

    @Test
    fun comparisonsAndDistance() {
        val a = JalaliDate.of(1405, 1, 1)
        val b = JalaliDate.of(1406, 1, 1)
        assertTrue(a < b)
        assertTrue(a.isBefore(b))
        assertEquals(365, a.daysUntil(b))
        assertEquals(366, JalaliDate.of(1403, 1, 1).daysUntil(JalaliDate.of(1404, 1, 1)))
    }

    @Test
    fun monthIndexRoundTrip() {
        val date = JalaliDate.of(1405, 7, 22)
        assertEquals(JalaliDate.of(1405, 7, 1), JalaliDate.ofMonthIndex(date.monthIndex))
        assertEquals(JalaliDate.of(1406, 1, 1), JalaliDate.ofMonthIndex(date.monthIndex + 6))
    }

    @Test
    fun firstAndLastDays() {
        val date = JalaliDate.of(1403, 12, 10)
        assertEquals(JalaliDate.of(1403, 12, 1), date.firstDayOfMonth())
        assertEquals(JalaliDate.of(1403, 12, 30), date.lastDayOfMonth())
        assertEquals(JalaliDate.of(1403, 1, 1), date.firstDayOfYear())
        assertEquals(JalaliDate.of(1403, 12, 30), date.lastDayOfYear())
    }
}
