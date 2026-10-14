package ir.yadino.domain

import ir.yadino.calendar.NumeralStyle
import ir.yadino.domain.TestData.j
import ir.yadino.domain.recurrence.Frequency
import ir.yadino.domain.recurrence.RecurrenceEnd
import ir.yadino.domain.recurrence.RecurrenceEngine
import ir.yadino.domain.recurrence.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class RecurrenceTest {

    @Test
    fun dailyWithInterval() {
        val rule = RecurrenceRule.daily(3)
        val start = j(1405, 7, 1)
        val occ = RecurrenceEngine.occurrencesBetween(rule, start, start, j(1405, 7, 10))
        assertEquals(listOf(j(1405, 7, 1), j(1405, 7, 4), j(1405, 7, 7), j(1405, 7, 10)), occ)
    }

    @Test
    fun weeklyOnSeveralDays() {
        // 1 Mehr 1405 is a Wednesday
        val rule = RecurrenceRule.weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY))
        val occ = RecurrenceEngine.occurrencesBetween(rule, j(1405, 7, 1), j(1405, 7, 1), j(1405, 7, 14))
        assertEquals(listOf(j(1405, 7, 4), j(1405, 7, 6), j(1405, 7, 11), j(1405, 7, 13)), occ)
    }

    @Test
    fun weeklyEveryTwoWeeksDefaultsToStartWeekday() {
        val rule = RecurrenceRule(Frequency.WEEKLY, interval = 2)
        val occ = RecurrenceEngine.occurrencesBetween(rule, j(1405, 7, 1), j(1405, 7, 1), j(1405, 8, 15))
        assertEquals(listOf(j(1405, 7, 1), j(1405, 7, 15), j(1405, 7, 29), j(1405, 8, 13)), occ)
    }

    @Test
    fun monthlyFollowsJalaliMonthsAndClamps() {
        val rule = RecurrenceRule.monthly()
        val occ = RecurrenceEngine.occurrencesBetween(rule, j(1405, 6, 31), j(1405, 6, 1), j(1406, 1, 31))
        assertEquals(
            listOf(
                j(1405, 6, 31), j(1405, 7, 30), j(1405, 8, 30), j(1405, 9, 30), j(1405, 10, 30),
                j(1405, 11, 30), j(1405, 12, 29), j(1406, 1, 31),
            ),
            occ,
        )
    }

    @Test
    fun yearlyOnLeapDay() {
        val rule = RecurrenceRule.yearly()
        val occ = RecurrenceEngine.occurrencesBetween(rule, j(1403, 12, 30), j(1403, 1, 1), j(1409, 1, 1))
        assertEquals(listOf(j(1403, 12, 30), j(1404, 12, 29), j(1405, 12, 29), j(1406, 12, 29), j(1407, 12, 29), j(1408, 12, 30)), occ)
    }

    @Test
    fun countAndUntilEnds() {
        val counted = RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(3))
        assertEquals(3, RecurrenceEngine.occurrencesBetween(counted, j(1405, 1, 1), j(1405, 1, 1), j(1405, 12, 1)).size)
        assertNull(RecurrenceEngine.nextOccurrence(counted, j(1405, 1, 1), j(1405, 1, 3)))
        val until = RecurrenceRule(Frequency.WEEKLY, end = RecurrenceEnd.Until(j(1405, 1, 20)))
        val occ = RecurrenceEngine.occurrencesBetween(until, j(1405, 1, 1), j(1405, 1, 1), j(1405, 12, 1))
        assertEquals(listOf(j(1405, 1, 1), j(1405, 1, 8), j(1405, 1, 15)), occ)
    }

    @Test
    fun nextAndMembership() {
        val rule = RecurrenceRule.weekly(setOf(DayOfWeek.SATURDAY))
        val start = j(1405, 7, 4) // Saturday
        assertEquals(j(1405, 7, 11), RecurrenceEngine.nextOccurrence(rule, start, start))
        assertEquals(j(1405, 7, 4), RecurrenceEngine.nextOccurrence(rule, start, j(1405, 7, 1)))
        assertTrue(RecurrenceEngine.isOccurrence(rule, start, j(1405, 7, 18)))
        assertFalse(RecurrenceEngine.isOccurrence(rule, start, j(1405, 7, 19)))
        assertFalse(RecurrenceEngine.isOccurrence(rule, start, j(1405, 6, 28)))
        assertEquals(3, RecurrenceEngine.countUpTo(rule, start, j(1405, 7, 18)))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val rules = listOf(
            RecurrenceRule.daily(),
            RecurrenceRule(Frequency.WEEKLY, 2, setOf(DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY), RecurrenceEnd.Count(10)),
            RecurrenceRule(Frequency.MONTHLY, 1, emptySet(), RecurrenceEnd.Until(j(1406, 1, 1))),
            RecurrenceRule.yearly(2),
        )
        for (r in rules) assertEquals(r, RecurrenceRule.decode(r.encode()))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;DAYS=WE,SA;COUNT=10", rules[1].encode())
        assertNull(RecurrenceRule.decode("FREQ=SOMETIMES"))
        assertNull(RecurrenceRule.decode("garbage"))
        assertNull(RecurrenceRule.decode(""))
    }

    @Test
    fun persianDescriptions() {
        assertEquals("هر روز", RecurrenceEngine.describe(RecurrenceRule.daily(), null, NumeralStyle.PERSIAN))
        assertEquals("هر ۲ هفته: شنبه، دوشنبه",
            RecurrenceEngine.describe(RecurrenceRule.weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), 2), null, NumeralStyle.PERSIAN))
        assertEquals("هر ماه، روز ۳۱", RecurrenceEngine.describe(RecurrenceRule.monthly(), j(1405, 6, 31), NumeralStyle.PERSIAN))
        assertEquals("هر روز، ۵ بار", RecurrenceEngine.describe(RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(5)), null, NumeralStyle.PERSIAN))
    }
}
