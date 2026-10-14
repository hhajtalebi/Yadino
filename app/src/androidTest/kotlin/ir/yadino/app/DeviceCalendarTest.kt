package ir.yadino.app

import android.database.MatrixCursor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.data.device.DeviceCalendarPrefs
import ir.yadino.app.data.device.DeviceCalendarSource
import ir.yadino.app.data.settings.AppStateStore
import ir.yadino.app.ui.calendar.DayTimeline
import ir.yadino.app.ui.theme.YadinoTheme
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.ExternalEvent
import ir.yadino.domain.model.Task
import ir.yadino.domain.planning.ExternalEvents
import ir.yadino.domain.planning.PlannedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Device calendar events: reading provider rows, the device-only choices, and read-only blocks on the timeline. */
@RunWith(AndroidJUnit4::class)
class DeviceCalendarTest {
    @get:Rule
    val compose = createComposeRule()

    private val day = LocalDate.of(2026, 10, 14)
    private val zone = ZoneId.of("Asia/Tehran")

    @Test
    fun providerRowsBecomeEventsAndCalendars() {
        val rows = MatrixCursor(DeviceCalendarSource.EVENT_PROJECTION)
        val start = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        rows.addRow(arrayOf<Any?>(11L, "  جلسه  ", start, start + 3_600_000L, 0, 3L, 0xFF112233.toInt()))
        rows.addRow(arrayOf<Any?>(12L, "", start, null, 1, 4L, null)) // no title, no end, no colour, all-day
        val events = DeviceCalendarSource.readEvents(rows, untitled = "(بدون عنوان)")
        assertEquals(2, events.size)
        assertEquals(ExternalEvent(11L, 3L, "جلسه", Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 3_600_000L), false, 0xFF112233.toInt()), events[0])
        assertEquals("(بدون عنوان)", events[1].title)
        assertEquals(events[1].start, events[1].end)
        assertTrue(events[1].allDay)
        assertEquals(DeviceCalendarSource.DEFAULT_COLOR, events[1].color)

        val calendars = MatrixCursor(DeviceCalendarSource.CALENDAR_PROJECTION)
        calendars.addRow(arrayOf<Any?>(3L, "کار", "me@example.com", 0xFF0000FF.toInt()))
        calendars.addRow(arrayOf<Any?>(4L, null, "family@example.com", null))
        val list = DeviceCalendarSource.readCalendars(calendars)
        assertEquals(listOf("کار", "family@example.com"), list.map { it.name })
        assertEquals(DeviceCalendarSource.DEFAULT_COLOR, list[1].color)
    }

    @Test
    fun choicesStayOnThisDevice() = runBlocking {
        val store = AppStateStore(ApplicationProvider.getApplicationContext())
        store.setDeviceCalendarsEnabled(true)
        store.setDeviceCalendarHidden(4L, true)
        store.setDeviceCalendarHidden(5L, true)
        store.setDeviceCalendarHidden(5L, false)
        assertEquals(DeviceCalendarPrefs(enabled = true, hidden = setOf(4L)), store.deviceCalendars.first())
        store.setDeviceCalendarHidden(4L, false)
        store.setDeviceCalendarsEnabled(false)
        assertEquals(DeviceCalendarPrefs(), store.deviceCalendars.first())
    }

    @Test
    fun timelineShowsDeviceEventsReadOnlyNextToTasks() {
        val created = Instant.parse("2026-10-14T08:00:00Z")
        val task = Task(id = "t", title = "تمرین", date = day, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0), createdAt = created, updatedAt = created)
        val meeting = ExternalEvent(21L, 3L, "جلسه هفتگی", day.atTime(9, 30).atZone(zone).toInstant(), day.atTime(10, 30).atZone(zone).toInstant(), false, 0xFF3366CC.toInt())
        val onDay = ExternalEvents.OnDay(meeting, ExternalEvents.slot(meeting, day, zone)!!)
        compose.setContent {
            YadinoTheme(AppSettings()) {
                DayTimeline(
                    listOf(PlannedItem(task, day, false)), emptyMap(), day, isToday = false, now = LocalTime.NOON, scroll = rememberScrollState(),
                    onAddAt = {}, onOpen = {}, onRetime = { _, _, _ -> }, external = listOf(onDay),
                )
            }
        }
        val node = compose.onNodeWithContentDescription("رویداد تقویم گوشی: جلسه هفتگی", substring = true).performScrollTo().fetchSemanticsNode()
        assertNull("device events cannot be moved from Yadino", node.config.getOrNull(SemanticsActions.CustomActions))
        val taskNode = compose.onNodeWithText("تمرین").performScrollTo().fetchSemanticsNode()
        assertFalse(taskNode.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty())
        // the two overlap in time, so they share the width
        assertTrue(node.boundsInRoot.right <= taskNode.boundsInRoot.left + 1f || taskNode.boundsInRoot.right <= node.boundsInRoot.left + 1f)
    }
}
