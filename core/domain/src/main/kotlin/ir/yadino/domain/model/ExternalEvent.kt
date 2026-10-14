package ir.yadino.domain.model

import java.time.Instant

/**
 * A read-only event from a calendar on the device: one occurrence from Android's calendar
 * provider (Google Calendar, Samsung Calendar, Exchange…). Yadino only shows these events
 * next to its own tasks; they are never stored in its database, backups or exports.
 *
 * All-day events follow the provider's convention: [start] and [end] are midnights in UTC
 * and [end] is exclusive (a one-day event ends at the next midnight).
 */
data class ExternalEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    /** ARGB colour of the event, or of its calendar. */
    val color: Int,
) {
    /** Unique for each occurrence: a recurring event has one instance per occurrence. */
    val key: String get() = "$eventId@${start.toEpochMilli()}"
}
