package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.task
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.OverdueBehavior
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.RecurringCompletionMode
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.planning.PlanCopier
import ir.yadino.domain.planning.QuietHours
import ir.yadino.domain.planning.ReminderCalculator
import ir.yadino.domain.planning.TaskCompletionLogic
import ir.yadino.domain.planning.TaskFilters
import ir.yadino.domain.planning.TaskListFilter
import ir.yadino.domain.recurrence.Frequency
import ir.yadino.domain.recurrence.RecurrenceEnd
import ir.yadino.domain.recurrence.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class PlanningTest {
    private val today = j(1405, 7, 22)
    private var seq = 0
    private val ids = { "new-${++seq}" }

    @Test
    fun listFilters() {
        val todayTask = task("امروز", today)
        val overdue = task("عقب", today.minusDays(2))
        val upcoming = task("آینده", today.plusDays(3))
        val done = task("انجام", today) { copy(status = TaskStatus.DONE, completedAt = NOW) }
        val noDate = task("بی‌تاریخ")
        val archived = task("بایگانی", today) { copy(isArchived = true) }
        val deadlinePassed = task("مهلت") { copy(dueDate = today.minusDays(1)) }
        val all = listOf(todayTask, overdue, upcoming, done, noDate, archived, deadlinePassed)

        val todayList = TaskFilters.filter(all, TaskListFilter.TODAY, today, OverdueBehavior.SHOW_IN_TODAY)
        assertEquals(setOf(todayTask, overdue, done, deadlinePassed), todayList.toSet())
        assertTrue("open tasks are listed before done ones", todayList.last() == done)
        val separate = TaskFilters.filter(all, TaskListFilter.TODAY, today, OverdueBehavior.SEPARATE_LIST)
        assertEquals(setOf(todayTask, done), separate.toSet())
        assertEquals(setOf(overdue, deadlinePassed), TaskFilters.filter(all, TaskListFilter.OVERDUE, today, OverdueBehavior.SEPARATE_LIST).toSet())
        assertEquals(listOf(upcoming), TaskFilters.filter(all, TaskListFilter.UPCOMING, today, OverdueBehavior.SEPARATE_LIST))
        assertEquals(listOf(noDate), TaskFilters.filter(all, TaskListFilter.NO_DATE, today, OverdueBehavior.SEPARATE_LIST))
        assertEquals(listOf(done), TaskFilters.filter(all, TaskListFilter.DONE, today, OverdueBehavior.SEPARATE_LIST))
        assertEquals(listOf(archived), TaskFilters.filter(all, TaskListFilter.ARCHIVED, today, OverdueBehavior.SEPARATE_LIST))
    }

    @Test
    fun eventsAreNeverOverdue() {
        val event = task("جلسه", today.minusDays(1)) { copy(kind = TaskKind.EVENT) }
        assertFalse(TaskFilters.isOverdue(event, today))
    }

    @Test
    fun dueTimeOverdueToday() {
        val t = task("مهلت ساعتی") { copy(dueDate = today, dueTime = LocalTime.of(10, 0)) }
        assertTrue(TaskFilters.isOverdue(t, today, LocalTime.of(11, 0)))
        assertFalse(TaskFilters.isOverdue(t, today, LocalTime.of(9, 0)))
    }

    @Test
    fun importantAndSearch() {
        val a = task("گزارش مالی", today) { copy(priority = Priority.URGENT) }
        val b = task("خرید", today) { copy(priority = Priority.HIGH, tags = listOf("خانه")) }
        val c = task("مطالعه", today)
        assertEquals(listOf(a, b), TaskFilters.important(listOf(c, b, a), today))
        assertEquals(listOf(b), TaskFilters.search(listOf(a, b, c), "خانه"))
        assertEquals(listOf(a), TaskFilters.search(listOf(a, b, c), "مالی"))
    }

    @Test
    fun completingSimpleTask() {
        val t = task("ساده", today)
        val r = TaskCompletionLogic.complete(t, NOW, RecurringCompletionMode.MOVE_TO_NEXT, DayOfWeek.SATURDAY, ids)
        assertEquals(TaskStatus.DONE, r.updatedTask.status)
        assertEquals(NOW, r.updatedTask.completedAt)
        assertEquals(today, r.completion.occurrenceDate)
        assertNull(r.completedCopy)
    }

    @Test
    fun completingRecurringTaskMovesToNextOccurrence() {
        val t = task("هفتگی", today) { copy(recurrence = RecurrenceRule.weekly(), dueDate = today.plusDays(1)) }
        val r = TaskCompletionLogic.complete(t, NOW, RecurringCompletionMode.MOVE_TO_NEXT, DayOfWeek.SATURDAY, ids)
        assertEquals(today.plusWeeks(1), r.updatedTask.date)
        assertEquals(today.plusWeeks(1).plusDays(1), r.updatedTask.dueDate)
        assertEquals(TaskStatus.TODO, r.updatedTask.status)
        assertEquals(today, r.updatedTask.recurrenceStart)
        assertEquals(t.id, r.updatedTask.id)
        assertTrue(r.resetSubtasks)
        assertNull(r.completedCopy)
    }

    @Test
    fun keepCompletedCopyMode() {
        val t = task("روزانه", today) { copy(recurrence = RecurrenceRule.daily()) }
        val r = TaskCompletionLogic.complete(t, NOW, RecurringCompletionMode.KEEP_COMPLETED_COPY, DayOfWeek.SATURDAY, ids)
        val copy = r.completedCopy!!
        assertEquals(TaskStatus.DONE, copy.status)
        assertEquals(today, copy.date)
        assertNull(copy.recurrence)
        assertEquals(today.plusDays(1), r.updatedTask.date)
    }

    @Test
    fun monthlySeriesKeepsAnchorDay() {
        var t = task("قبض", j(1405, 6, 31)) { copy(recurrence = RecurrenceRule.monthly()) }
        val dates = mutableListOf<java.time.LocalDate>()
        repeat(7) {
            t = TaskCompletionLogic.complete(t, NOW, RecurringCompletionMode.MOVE_TO_NEXT, DayOfWeek.SATURDAY, ids).updatedTask
            dates += t.date!!
        }
        assertEquals(listOf(j(1405, 7, 30), j(1405, 8, 30), j(1405, 9, 30), j(1405, 10, 30), j(1405, 11, 30), j(1405, 12, 29), j(1406, 1, 31)), dates)
    }

    @Test
    fun seriesEndMarksTaskDone() {
        val t = task("سه بار", today) { copy(recurrence = RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(2))) }
        val first = TaskCompletionLogic.complete(t, NOW, RecurringCompletionMode.MOVE_TO_NEXT, DayOfWeek.SATURDAY, ids).updatedTask
        assertEquals(TaskStatus.TODO, first.status)
        assertEquals(1, TaskCompletionLogic.remainingOccurrences(first, DayOfWeek.SATURDAY))
        val second = TaskCompletionLogic.complete(first, NOW, RecurringCompletionMode.MOVE_TO_NEXT, DayOfWeek.SATURDAY, ids).updatedTask
        assertEquals(TaskStatus.DONE, second.status)
    }

    @Test
    fun planForRangeProjectsRecurringOccurrences() {
        val weekly = task("ورزش", today) { copy(recurrence = RecurrenceRule.weekly()) }
        val single = task("تک", today.plusDays(2))
        val plan = TaskFilters.planForRange(listOf(weekly, single), today, today.plusDays(20), true, DayOfWeek.SATURDAY)
        assertEquals(false, plan[today]!!.single().isProjected)
        assertTrue(plan[today.plusDays(7)]!!.single().isProjected)
        assertTrue(plan[today.plusDays(14)]!!.single().isProjected)
        assertEquals(single, plan[today.plusDays(2)]!!.single().task)
        val noProjection = TaskFilters.planForRange(listOf(weekly), today, today.plusDays(20), false, DayOfWeek.SATURDAY)
        assertEquals(1, noProjection.size)
    }

    @Test
    fun copyDayPlan() {
        val a = task("الف", today) { copy(status = TaskStatus.DONE, startTime = LocalTime.of(9, 0)) }
        val b = task("ب", today.plusDays(1))
        val sub = Subtask("s1", a.id, "زیرکار", isDone = true, createdAt = NOW, updatedAt = NOW)
        val copied = PlanCopier.copy(listOf(a, b), listOf(sub), today, today, today.plusDays(7), NOW, ids)
        val t = copied.tasks.single()
        assertEquals(today.plusDays(7), t.date)
        assertEquals(TaskStatus.TODO, t.status)
        assertEquals(LocalTime.of(9, 0), t.startTime)
        val s = copied.subtasks.single()
        assertEquals(t.id, s.taskId)
        assertFalse(s.isDone)
    }

    @Test
    fun reminderTriggers() {
        val zone = ZoneId.of("Asia/Tehran")
        val settings = AppSettings()
        val now = LocalDateTime.of(today, LocalTime.of(7, 0)).atZone(zone).toInstant()
        val timed = task("جلسه", today) { copy(startTime = LocalTime.of(10, 0), reminderOffsetMinutes = 15) }
        assertEquals(LocalDateTime.of(today, LocalTime.of(9, 45)).atZone(zone).toInstant(), ReminderCalculator.triggerAt(timed, settings, zone, now))
        val allDay = task("روزانه", today) { copy(reminderOffsetMinutes = 0) }
        assertEquals(LocalDateTime.of(today, settings.allDayReminderTime).atZone(zone).toInstant(), ReminderCalculator.triggerAt(allDay, settings, zone, now))
        val past = task("گذشته", today) { copy(startTime = LocalTime.of(6, 0), reminderOffsetMinutes = 0) }
        assertNull(ReminderCalculator.triggerAt(past, settings, zone, now))
        val done = timed.copy(status = TaskStatus.DONE)
        assertNull(ReminderCalculator.triggerAt(done, settings, zone, now))
        val noReminder = timed.copy(reminderOffsetMinutes = null)
        assertNull(ReminderCalculator.triggerAt(noReminder, settings, zone, now))
    }

    @Test
    fun reminderRespectsQuietHours() {
        val zone = ZoneId.of("Asia/Tehran")
        val settings = AppSettings(quietHoursEnabled = true, quietHoursStart = LocalTime.of(22, 0), quietHoursEnd = LocalTime.of(7, 30))
        val now = LocalDateTime.of(today, LocalTime.of(12, 0)).atZone(zone).toInstant()
        val late = task("دیروقت", today) { copy(startTime = LocalTime.of(23, 30), reminderOffsetMinutes = 0) }
        assertEquals(LocalDateTime.of(today.plusDays(1), LocalTime.of(7, 30)).atZone(zone).toInstant(), ReminderCalculator.triggerAt(late, settings, zone, now))
    }

    @Test
    fun quietHoursRanges() {
        assertTrue(QuietHours.contains(LocalTime.of(23, 0), LocalTime.of(22, 0), LocalTime.of(7, 0)))
        assertTrue(QuietHours.contains(LocalTime.of(3, 0), LocalTime.of(22, 0), LocalTime.of(7, 0)))
        assertFalse(QuietHours.contains(LocalTime.of(7, 0), LocalTime.of(22, 0), LocalTime.of(7, 0)))
        assertTrue(QuietHours.contains(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)))
        assertFalse(QuietHours.contains(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(12, 0)))
        val early = LocalDateTime.of(today, LocalTime.of(2, 0))
        assertEquals(LocalDateTime.of(today, LocalTime.of(7, 0)), QuietHours.adjust(early, LocalTime.of(22, 0), LocalTime.of(7, 0)))
    }

    @Test
    fun reminderAcrossDstGap() {
        // Europe/Berlin springs forward on 2026-03-29 at 02:00 -> 03:00.
        val zone = ZoneId.of("Europe/Berlin")
        val day = java.time.LocalDate.of(2026, 3, 29)
        val t = task("DST", day) { copy(startTime = LocalTime.of(2, 30), reminderOffsetMinutes = 0) }
        val now = Instant.parse("2026-03-28T12:00:00Z")
        val trigger = ReminderCalculator.triggerAt(t, AppSettings(), zone, now)
        assertNotNull(trigger)
        // 02:30 does not exist; java.time moves it forward to 03:30 local (01:30 UTC)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), trigger)
    }

    @Test
    fun habitReminderSkipsUnscheduledDays() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = LocalDateTime.of(today, LocalTime.of(21, 0)).atZone(zone).toInstant() // Wednesday evening
        val next = ReminderCalculator.nextHabitTrigger(LocalTime.of(20, 0), { it.dayOfWeek == DayOfWeek.SATURDAY }, AppSettings(), zone, now)
        assertEquals(LocalDateTime.of(today.plusDays(3), LocalTime.of(20, 0)).atZone(zone).toInstant(), next)
    }

    @Test
    fun manualReorderStaysInsideGroupsAndSurvivesSorting() {
        val today = j(1405, 7, 22)
        val overdue = task("دیروز", j(1405, 7, 21))
        val a = task("الف", today) { copy(sortOrder = 1) }
        val b = task("ب", today) { copy(sortOrder = 2, startTime = LocalTime.of(8, 0)) }
        val c = task("پ", today) { copy(sortOrder = 3) }
        val done = task("انجام‌شده", today) { copy(status = TaskStatus.DONE, completedAt = NOW) }
        assertTrue(TaskFilters.canReorderTogether(a, c))
        assertFalse("other date", TaskFilters.canReorderTogether(a, overdue))
        assertFalse("finished tasks stay below open ones", TaskFilters.canReorderTogether(a, done))

        val shown = TaskFilters.sortForPlan(listOf(done, c, b, a, overdue))
        assertEquals(listOf(overdue, a, b, c, done).map { it.id }, shown.map { it.id })
        // drag "پ" to the top of today's group and persist the order like TaskRepository.reorder
        val dragged = shown.toMutableList().apply { add(1, removeAt(3)) }
        val persisted = dragged.mapIndexed { i, t -> t.copy(sortOrder = i + 1) }
        assertEquals(dragged.map { it.id }, TaskFilters.sortForPlan(persisted.shuffled(java.util.Random(3))).map { it.id })
    }
}
