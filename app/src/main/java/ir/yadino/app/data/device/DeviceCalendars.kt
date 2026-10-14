package ir.yadino.app.data.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import ir.yadino.app.data.settings.AppStateStore
import ir.yadino.domain.model.ExternalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A calendar on the device: one calendar of an account in Android's calendar provider. */
data class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Int)

/**
 * The user's choices for device calendars. They belong to this device (calendar ids differ
 * between devices), so they are kept with the app state, not in the settings export or backups.
 */
data class DeviceCalendarPrefs(val enabled: Boolean = false, val hidden: Set<Long> = emptySet())

/**
 * Reads the calendars and events on the device (CalendarContract). Read-only: nothing is
 * written to the provider. Every call tolerates a missing or revoked permission and a device
 * without a calendar provider — it then returns nothing.
 */
class DeviceCalendarSource(context: Context) {
    private val context = context.applicationContext

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        query(CalendarContract.Calendars.CONTENT_URI, CALENDAR_PROJECTION, null,
            "${CalendarContract.Calendars.ACCOUNT_NAME}, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}", ::readCalendars)
            .orEmpty()
    }

    /** Occurrences that overlap [from]..[to] in calendars that are visible in the device's calendar app. */
    suspend fun events(from: Instant, to: Instant): List<ExternalEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission() || !to.isAfter(from)) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})"
        query(uri, EVENT_PROJECTION, selection, "${CalendarContract.Instances.BEGIN} ASC", ::readEvents).orEmpty()
    }

    /** Emits once at the start and again whenever data in the calendar provider changes. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (_: SecurityException) {
            false
        }
        trySend(Unit)
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun <T> query(uri: Uri, projection: Array<String>, selection: String?, sort: String, read: (Cursor) -> T): T? = try {
        context.contentResolver.query(uri, projection, selection, null, sort)?.use(read)
    } catch (_: SecurityException) {
        null // permission revoked while the app was running
    } catch (_: IllegalArgumentException) {
        null // a provider that does not know a column
    }

    companion object {
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )

        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR,
        )

        /** Rows of [CALENDAR_PROJECTION]; also used by tests with a MatrixCursor. */
        fun readCalendars(c: Cursor): List<DeviceCalendar> {
            val out = ArrayList<DeviceCalendar>(c.count)
            while (c.moveToNext()) {
                out += DeviceCalendar(
                    id = c.getLong(0),
                    name = c.getString(1)?.trim().orEmpty().ifEmpty { c.getString(2).orEmpty() },
                    account = c.getString(2).orEmpty(),
                    color = if (c.isNull(3)) DEFAULT_COLOR else c.getInt(3),
                )
            }
            return out
        }

        /** Rows of [EVENT_PROJECTION]; events without a title get [untitled]. */
        fun readEvents(c: Cursor, untitled: String = ""): List<ExternalEvent> {
            val out = ArrayList<ExternalEvent>(c.count)
            while (c.moveToNext()) {
                val start = c.getLong(2)
                val end = if (c.isNull(3)) start else c.getLong(3)
                out += ExternalEvent(
                    eventId = c.getLong(0),
                    calendarId = c.getLong(5),
                    title = c.getString(1)?.trim()?.ifEmpty { null } ?: untitled,
                    start = Instant.ofEpochMilli(start),
                    end = Instant.ofEpochMilli(end),
                    allDay = !c.isNull(4) && c.getInt(4) != 0,
                    color = if (c.isNull(6)) DEFAULT_COLOR else c.getInt(6),
                )
            }
            return out
        }

        /** Used when the provider has no colour for a calendar or event. */
        const val DEFAULT_COLOR = 0xFF3D6373.toInt()
    }
}

/** Device calendar events for the calendar screens, following the user's choices. */
class DeviceCalendarRepository(private val source: DeviceCalendarSource, private val state: AppStateStore) {

    val prefs: Flow<DeviceCalendarPrefs> = state.deviceCalendars

    fun hasPermission(): Boolean = source.hasPermission()

    suspend fun calendars(): List<DeviceCalendar> = source.calendars()

    suspend fun setEnabled(enabled: Boolean) = state.setDeviceCalendarsEnabled(enabled)

    suspend fun setHidden(calendarId: Long, hidden: Boolean) = state.setDeviceCalendarHidden(calendarId, hidden)

    /**
     * Events of the shown calendars that touch the days [from]..[to] in [zone]; empty while the
     * feature is off or the permission is missing. Queried again when the device calendars
     * change (a sync often changes many rows at once, so changes are gathered for a moment).
     * The query window has a day of margin on both sides because all-day events are stored at
     * midnight UTC, which is another day in some time zones.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun events(from: LocalDate, to: LocalDate, zone: ZoneId, untitled: String): Flow<List<ExternalEvent>> =
        combine(prefs, source.changes().debounce(CHANGE_DELAY_MILLIS)) { p, _ -> p }
            .mapLatest { p ->
                if (!p.enabled || !source.hasPermission()) {
                    emptyList()
                } else {
                    val window = source.events(from.minusDays(1).atStartOfDay(zone).toInstant(), to.plusDays(2).atStartOfDay(zone).toInstant())
                    window.filter { it.calendarId !in p.hidden }.map { if (it.title.isEmpty()) it.copy(title = untitled) else it }
                }
            }
            .distinctUntilChanged()

    private companion object {
        const val CHANGE_DELAY_MILLIS = 300L
    }
}
