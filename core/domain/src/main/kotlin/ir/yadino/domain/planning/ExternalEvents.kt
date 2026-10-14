package ir.yadino.domain.planning

import ir.yadino.domain.model.ExternalEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Where events of the device calendars appear in Yadino's calendar: on which days, and on
 * each day either in the all-day row or as a block of wall-clock minutes on the timeline.
 * Pure functions on [ExternalEvent]s, so the day boundaries (time zones, daylight saving,
 * events across midnight) are tested without a device.
 */
object ExternalEvents {
    const val DAY_MINUTES = 24 * 60

    /** How an event appears on one day. */
    sealed interface Slot {
        /** The whole day: an all-day event, or a timed event that covers the day completely. */
        data object AllDay : Slot

        /** Wall-clock minutes of the day; [end] is at least [start] and may be 1440 (midnight). */
        data class Timed(val start: Int, val end: Int) : Slot
    }

    /** An event on a particular day. */
    data class OnDay(val event: ExternalEvent, val slot: Slot) {
        val isAllDay: Boolean get() = slot is Slot.AllDay
    }

    /**
     * First and last day (both inclusive) the event covers in [zone]. All-day events keep
     * their calendar dates (UTC midnights, end exclusive) in every time zone; an event that
     * ends exactly at midnight does not reach into the next day.
     */
    fun days(event: ExternalEvent, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val end = latest(event.start, event.end)
        if (event.allDay) {
            val first = event.start.atZone(ZoneOffset.UTC).toLocalDate()
            val last = end.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            return first to if (last.isBefore(first)) first else last
        }
        val first = event.start.atZone(zone).toLocalDate()
        val last = if (end.isAfter(event.start)) end.minusNanos(1).atZone(zone).toLocalDate() else first
        return first to if (last.isBefore(first)) first else last
    }

    /** How [event] appears on [day], or null when it does not touch that day. */
    fun slot(event: ExternalEvent, day: LocalDate, zone: ZoneId): Slot? {
        val (first, last) = days(event, zone)
        if (day.isBefore(first) || day.isAfter(last)) return null
        if (event.allDay) return Slot.AllDay
        val end = latest(event.start, event.end)
        val dayStart = day.atStartOfDay(zone).toInstant()
        val nextDay = day.plusDays(1).atStartOfDay(zone).toInstant()
        if (!event.start.isAfter(dayStart) && !end.isBefore(nextDay)) return Slot.AllDay
        val from = if (event.start.isBefore(dayStart)) 0 else minuteOfDay(event.start, zone)
        val to = if (!end.isBefore(nextDay)) DAY_MINUTES else minuteOfDay(end, zone)
        return Slot.Timed(from, maxOf(from, to))
    }

    /**
     * The events of every day in [from]..[to] (days without events are left out): all-day
     * entries first, then by start time, title and occurrence. Long events are clipped to the
     * range, so the work is bounded by its length.
     */
    fun byDay(events: List<ExternalEvent>, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, List<OnDay>> {
        val out = HashMap<LocalDate, MutableList<OnDay>>()
        for (event in events) {
            val (first, last) = days(event, zone)
            var day = if (first.isBefore(from)) from else first
            val end = if (last.isAfter(to)) to else last
            while (!day.isAfter(end)) {
                slot(event, day, zone)?.let { out.getOrPut(day) { ArrayList() } += OnDay(event, it) }
                day = day.plusDays(1)
            }
        }
        return out.mapValues { (_, list) -> list.sortedWith(ORDER) }
    }

    private val ORDER: Comparator<OnDay> = compareBy<OnDay> { if (it.isAllDay) 0 else 1 }
        .thenBy { (it.slot as? Slot.Timed)?.start ?: 0 }
        .thenBy { it.event.title }
        .thenBy { it.event.key }

    private fun latest(a: Instant, b: Instant): Instant = if (b.isAfter(a)) b else a

    private fun minuteOfDay(instant: Instant, zone: ZoneId): Int = instant.atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
}
