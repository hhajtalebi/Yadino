package ir.yadino.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.notifications.ReminderIds
import ir.yadino.app.notifications.ReminderReceiver
import ir.yadino.app.widget.CalendarWidgetProvider
import ir.yadino.app.widget.ClockWidgetProvider
import ir.yadino.app.widget.CountdownWidgetProvider
import ir.yadino.app.widget.GoalWidgetProvider
import ir.yadino.app.widget.HabitsWidgetProvider
import ir.yadino.app.widget.QuickNoteWidgetProvider
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime

/** Widgets render and inflate; reminders are scheduled and cancelled with AlarmManager. */
@RunWith(AndroidJUnit4::class)
class PlatformIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = context as YadinoApplication

    @Test
    fun widgetLayoutsRenderAndInflateInAllSizes() = runBlocking {
        val sizes = listOf(
            Bundle().apply { putInt("appWidgetMinWidth", 120); putInt("appWidgetMaxHeight", 120) },
            Bundle().apply { putInt("appWidgetMinWidth", 300); putInt("appWidgetMaxHeight", 150) },
            Bundle().apply { putInt("appWidgetMinWidth", 300); putInt("appWidgetMaxHeight", 400) },
        )
        val providers = listOf(CalendarWidgetProvider(), ClockWidgetProvider(), GoalWidgetProvider(), QuickNoteWidgetProvider(), CountdownWidgetProvider())
        for (provider in providers) {
            for (options in sizes) {
                val views = provider.render(context, app, 4242, options)
                assertNotNull(views.apply(context, FrameLayout(context)))
            }
        }
        // Collection widgets bind their list only inside a real AppWidgetHostView:
        // render every size, inflate the small (list-free) layout.
        val habits = HabitsWidgetProvider()
        for (options in sizes) assertNotNull(habits.render(context, app, 4243, options))
        assertNotNull(habits.render(context, app, 4243, sizes[0]).apply(context, FrameLayout(context)))
    }

    @Test
    fun taskReminderIsScheduledAndCancelled() = runBlocking {
        val c = app.container
        val now = Instant.now()
        val task = Task(
            id = Ids.newId(), title = "یادآوری آزمایشی", date = c.clock.today().plusDays(1), startTime = LocalTime.NOON,
            reminderOffsetMinutes = 10, createdAt = now, updatedAt = now,
        )
        c.reminders.scheduleTask(task, c.settings.awaitLoaded())
        assertNotNull(pending(task.id))
        c.reminders.cancelTask(task.id)
        assertNull(pending(task.id))
    }

    private fun pending(id: String): PendingIntent? = PendingIntent.getBroadcast(
        context, ReminderIds.taskAlarm(id),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_TASK).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )
}
