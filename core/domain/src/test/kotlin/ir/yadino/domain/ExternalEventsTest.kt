package ir.yadino.domain

import ir.yadino.domain.model.ExternalEvent
import ir.yadino.domain.planning.ExternalEvents
import ir.yadino.domain.planning.ExternalEvents.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Device calendar events on Yadino's days: all-day dates, midnight, time zones and daylight saving. */
class ExternalEventsTest {
    private val tehran = ZoneId.of("Asia/Tehran")
    private val day = LocalDate.of(2026, 10, 14) // 22 Mehr 1405

    private fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = tehran): Instant =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant()

    private fun utcMidnight(date: LocalDate): Instant = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()

    private fun event(title: String, start: Instant, end: Instant, allDay: Boolean = false, id: Long = title.hashCode().toLong()) =
        ExternalEvent(eventId = id, calendarId = 1, title = title, start = start, end = end, allDay = allDay, color = 0xFF3366CC.toInt())

    @Test
    fun allDayEventsKeepTheirDateInEveryTimeZone() {
        val birthday = event("تولد", utcMidnight(day), utcMidnight(day.plusDays(1)), allDay = true)
        for (zone in listOf(tehran, ZoneId.of("America/Los_Angeles"), ZoneId.of("Pacific/Auckland"))) {
            assertEquals(day to day, ExternalEvents.days(birthday, zone))
            assertEquals(Slot.AllDay, ExternalEvents.slot(birthday, day, zone))
            assertNull(ExternalEvents.slot(birthday, day.plusDays(1), zone))
            assertNull(ExternalEvents.slot(birthday, day.minusDays(1), zone))
        }
        val trip = event("سفر", utcMidnight(day), utcMidnight(day.plusDays(3)), allDay = true)
        assertEquals(day to day.plusDays(2), ExternalEvents.days(trip, tehran))
    }

    @Test
    fun timedEventsUseWallClockMinutes() {
        val meeting = event("جلسه", at(day, 9), at(day, 10, 30))
        assertEquals(Slot.Timed(540, 630), ExternalEvents.slot(meeting, day, tehran))
        // the same instant seen from another zone falls on other hours
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(Slot.Timed(7 * 60 + 30, 9 * 60), ExternalEvents.slot(meeting, day, berlin))
    }

    @Test
    fun eventsAcrossMidnightAreSplitAndMidnightEndsStayOnTheirDay() {
        val night = event("شیفت شب", at(day, 22), at(day.plusDays(1), 2))
        assertEquals(day to day.plusDays(1), ExternalEvents.days(night, tehran))
        assertEquals(Slot.Timed(22 * 60, 1440), ExternalEvents.slot(night, day, tehran))
        assertEquals(Slot.Timed(0, 120), ExternalEvents.slot(night, day.plusDays(1), tehran))

        val untilMidnight = event("تا نیمه‌شب", at(day, 23), at(day.plusDays(1), 0))
        assertEquals(day to day, ExternalEvents.days(untilMidnight, tehran))
        assertEquals(Slot.Timed(23 * 60, 1440), ExternalEvents.slot(untilMidnight, day, tehran))
        assertNull(ExternalEvents.slot(untilMidnight, day.plusDays(1), tehran))
    }

    @Test
    fun timedEventsCoveringWholeDaysShowAsAllDayThere() {
        val conference = event("همایش", at(day, 10), at(day.plusDays(2), 16))
        assertEquals(Slot.Timed(600, 1440), ExternalEvents.slot(conference, day, tehran))
        assertEquals(Slot.AllDay, ExternalEvents.slot(conference, day.plusDays(1), tehran))
        assertEquals(Slot.Timed(0, 960), ExternalEvents.slot(conference, day.plusDays(2), tehran))
        val wholeDay = event("کل روز", at(day, 0), at(day.plusDays(1), 0))
        assertEquals(Slot.AllDay, ExternalEvents.slot(wholeDay, day, tehran))
    }

    @Test
    fun zeroLengthAndBrokenEventsStayOnOneDay() {
        val moment = event("لحظه", at(day, 12), at(day, 12))
        assertEquals(day to day, ExternalEvents.days(moment, tehran))
        assertEquals(Slot.Timed(720, 720), ExternalEvents.slot(moment, day, tehran))
        val broken = event("خراب", at(day, 12), at(day, 11)) // end before start: treated as a moment
        assertEquals(Slot.Timed(720, 720), ExternalEvents.slot(broken, day, tehran))
        val brokenAllDay = event("خراب تمام‌روز", utcMidnight(day), utcMidnight(day), allDay = true)
        assertEquals(day to day, ExternalEvents.days(brokenAllDay, tehran))
    }

    @Test
    fun daylightSavingDaysUseTheClockOnTheWall() {
        val berlin = ZoneId.of("Europe/Berlin")
        val springForward = LocalDate.of(2026, 3, 29) // 02:00 -> 03:00
        val e = event("صبح زود", at(springForward, 1, 30, berlin), at(springForward, 3, 30, berlin))
        assertEquals(Slot.Timed(90, 210), ExternalEvents.slot(e, springForward, berlin))
        val wholeShortDay = event("روز کوتاه", springForward.atStartOfDay(berlin).toInstant(), springForward.plusDays(1).atStartOfDay(berlin).toInstant())
        assertEquals(Slot.AllDay, ExternalEvents.slot(wholeShortDay, springForward, berlin))
        assertEquals(springForward to springForward, ExternalEvents.days(wholeShortDay, berlin))
    }

    @Test
    fun byDayClipsToTheRangeAndOrdersAllDayFirst() {
        val long = event("دوره آموزشی", utcMidnight(day.minusDays(30)), utcMidnight(day.plusDays(30)), allDay = true)
        val late = event("ب: عصر", at(day, 18), at(day, 19))
        val early = event("الف: صبح", at(day, 8), at(day, 9))
        val sameTime = event("آ: صبح", at(day, 8), at(day, 8, 30))
        val outside = event("بیرون از بازه", at(day.plusDays(10), 8), at(day.plusDays(10), 9))
        val map = ExternalEvents.byDay(listOf(late, outside, long, early, sameTime), day, day.plusDays(1), tehran)
        assertEquals(setOf(day, day.plusDays(1)), map.keys)
        assertEquals(listOf("دوره آموزشی", "آ: صبح", "الف: صبح", "ب: عصر"), map.getValue(day).map { it.event.title })
        assertTrue(map.getValue(day).first().isAllDay)
        assertEquals(listOf("دوره آموزشی"), map.getValue(day.plusDays(1)).map { it.event.title })
        // occurrences of a recurring event are told apart by their start
        val weekly1 = event("کلاس", at(day, 17), at(day, 18), id = 7)
        val weekly2 = weekly1.copy(start = at(day.plusDays(7), 17), end = at(day.plusDays(7), 18))
        assertTrue(weekly1.key != weekly2.key)
    }
}
