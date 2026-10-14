package ir.yadino.app.ui.calendar

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.yadino.app.R
import ir.yadino.app.core.Formatter
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.domain.model.ExternalEvent
import ir.yadino.domain.planning.ExternalEvents
import java.time.LocalTime

/** Wall-clock minute of the day as a time; the end of the day (1440) is shown as midnight. */
private fun timeOf(minute: Int): LocalTime = if (minute >= ExternalEvents.DAY_MINUTES) LocalTime.MIDNIGHT else LocalTime.of(minute / 60, minute % 60)

/** "تمام روز" or the time range of a device event on one day. */
@Composable
internal fun deviceEventTime(onDay: ExternalEvents.OnDay, fmt: Formatter = LocalFormatter.current): String =
    when (val slot = onDay.slot) {
        ExternalEvents.Slot.AllDay -> stringResource(R.string.all_day)
        is ExternalEvents.Slot.Timed -> fmt.timeRange(timeOf(slot.start), timeOf(slot.end).takeIf { slot.end > slot.start }) ?: ""
    }

/** Opens an event of the device calendar in the calendar app (read-only in Yadino). */
internal fun openDeviceEvent(context: Context, event: ExternalEvent) {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilli())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.device_event_open_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.device_event_open_failed, Toast.LENGTH_SHORT).show()
    }
}

/** A device calendar event in the agenda, the week view and the all-day row. */
@Composable
internal fun DeviceEventRow(onDay: ExternalEvents.OnDay, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time = deviceEventTime(onDay)
    val description = stringResource(R.string.a11y_device_event, onDay.event.title, time)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.device_event_open)) { openDeviceEvent(context, onDay.event) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(onDay.event.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(onDay.event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Event, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Marker of a day with device calendar events in the month grid: a ring in the event's colour. */
@Composable
internal fun DeviceEventMarker(color: Int) {
    Box(Modifier.size(5.dp).border(1.dp, Color(color), CircleShape))
}

/**
 * A timed device calendar event on the day timeline: read-only (no dragging), a stripe in the
 * calendar's colour on a light background; tapping opens it in the calendar app.
 */
@Composable
internal fun DeviceEventBlock(onDay: ExternalEvents.OnDay, start: Int, end: Int, hourHeight: Dp, x: Dp, width: Dp) {
    val context = LocalContext.current
    val color = Color(onDay.event.color)
    val shape = MaterialTheme.shapes.small
    val time = deviceEventTime(onDay)
    val description = stringResource(R.string.a11y_device_event, onDay.event.title, time)
    Row(
        Modifier
            .offset(x = x, y = hourHeight * (start / 60f))
            .width(width - 2.dp)
            .height(hourHeight * ((end - start) / 60f))
            .clip(shape)
            .background(lerp(MaterialTheme.colorScheme.surface, color, 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .clickable(onClickLabel = stringResource(R.string.device_event_open)) { openDeviceEvent(context, onDay.event) }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(onDay.event.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (end - start >= 40) {
                Text(time + " · " + stringResource(R.string.device_events), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
