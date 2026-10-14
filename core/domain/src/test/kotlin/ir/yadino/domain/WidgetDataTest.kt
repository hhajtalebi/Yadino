package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.habit
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.logs
import ir.yadino.domain.TestData.task
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.planning.CountdownTarget
import ir.yadino.domain.planning.Countdowns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** Data behind the habits and countdown home-screen widgets. */
class WidgetDataTest {
    private val today = j(1405, 7, 22) // Wednesday 14 October 2026

    private fun goal(id: String, title: String, deadline: java.time.LocalDate?, status: GoalStatus = GoalStatus.ACTIVE, archived: Boolean = false) =
        Goal(id = id, title = title, startDate = j(1405, 1, 1), deadline = deadline, status = status, isArchived = archived, createdAt = NOW, updatedAt = NOW)

    @Test
    fun habitsDueTodayArePendingFirstAndSkipUnscheduledOrArchived() {
        val read = habit().copy(title = "مطالعه", sortOrder = 2)
        val water = habit(target = 8).copy(title = "آب", sortOrder = 1)
        val walk = habit().copy(title = "پیاده‌روی", sortOrder = 0)
        val fridayOnly = habit(HabitSchedule.SpecificDays(setOf(DayOfWeek.FRIDAY))).copy(title = "جمعه")
        val archived = habit().copy(title = "بایگانی", isArchived = true)
        val future = habit(start = today.plusDays(1)).copy(title = "آینده")
        val allLogs = logs(walk, today) + logs(water, today, count = 3)
        val due = HabitStats.dueToday(listOf(read, water, walk, fridayOnly, archived, future), allLogs, today, DayOfWeek.SATURDAY)
        assertEquals(listOf("آب", "مطالعه", "پیاده‌روی"), due.map { it.first.title })
        assertEquals(3, due[0].second.doneToday)
        assertFalse(due[0].second.isDoneToday)
        assertTrue(due[2].second.isDoneToday)
    }

    @Test
    fun countdownSelectionRoundTrips() {
        assertEquals("goal:g1", Countdowns.encode(CountdownTarget.Kind.GOAL, "g1"))
        assertEquals("task:t1", Countdowns.encode(CountdownTarget.Kind.TASK, "t1"))
        assertTrue(Countdowns.isAutomatic(""))
        assertFalse(Countdowns.isAutomatic("goal:g1"))
    }

    @Test
    fun automaticCountdownPicksTheNearestUpcomingDeadline() {
        val goals = listOf(
            goal("g-far", "هدف دور", today.plusDays(40)),
            goal("g-past", "هدف گذشته", today.minusDays(1)),
            goal("g-done", "هدف تمام‌شده", today.plusDays(1), GoalStatus.COMPLETED),
            goal("g-archived", "هدف بایگانی", today.plusDays(1), archived = true),
            goal("g-none", "بدون موعد", null),
        )
        val tasks = listOf(
            task("کار باز", id = "t-open") { copy(dueDate = today.plusDays(3)) },
            task("کار انجام‌شده", id = "t-done") { copy(dueDate = today.plusDays(2), status = TaskStatus.DONE) },
            task("کار بدون موعد", id = "t-none") { copy(date = today) },
        )
        val target = Countdowns.resolve("", goals, tasks, today)!!
        assertEquals(CountdownTarget(CountdownTarget.Kind.TASK, "t-open", "کار باز", today.plusDays(3)), target)
        assertEquals(3, target.daysLeft(today))
        assertEquals(listOf("t-open", "g-far"), Countdowns.candidates(goals, tasks, today).map { it.id })
        assertEquals(listOf("g-past", "t-open", "g-far"), Countdowns.candidates(goals, tasks, today, includePast = true).map { it.id })
    }

    @Test
    fun deadlinesTodayWhoseTimeHasPassedAreSkipped() {
        val tasks = listOf(
            task("صبح", id = "morning") { copy(dueDate = today, dueTime = LocalTime.of(9, 0)) },
            task("عصر", id = "evening") { copy(dueDate = today, dueTime = LocalTime.of(18, 0)) },
            task("بی‌ساعت", id = "allday") { copy(dueDate = today) },
        )
        assertEquals("morning", Countdowns.nearest(emptyList(), tasks, today, LocalTime.of(8, 0))!!.id)
        assertEquals("evening", Countdowns.nearest(emptyList(), tasks, today, LocalTime.of(12, 0))!!.id)
        assertEquals("allday", Countdowns.nearest(emptyList(), tasks, today, LocalTime.of(19, 0))!!.id)
        assertEquals(0, Countdowns.nearest(emptyList(), tasks, today)!!.daysLeft(today))
    }

    @Test
    fun aChosenTargetIsFollowedUntilItDisappears() {
        val goals = listOf(goal("g1", "پایان‌نامه", today.minusDays(5)), goal("g2", "تمام‌شده", today.plusDays(9), GoalStatus.COMPLETED))
        val tasks = listOf(task("کار", id = "t1") { copy(dueDate = today.plusDays(1), status = TaskStatus.DONE) })
        val overdue = Countdowns.resolve("goal:g1", goals, tasks, today)!!
        assertEquals(-5, overdue.daysLeft(today))
        assertTrue(Countdowns.resolve("goal:g2", goals, tasks, today)!!.finished)
        assertTrue(Countdowns.resolve("task:t1", goals, tasks, today)!!.finished)
        assertNull(Countdowns.resolve("goal:deleted", goals, tasks, today))
        assertNull(Countdowns.resolve("task:t1", goals, emptyList(), today))
        val archived = listOf(goal("g1", "بایگانی", today, archived = true))
        assertNull(Countdowns.resolve("goal:g1", archived, tasks, today))
        assertNull(Countdowns.resolve("goal:g3", listOf(goal("g3", "بدون موعد", null)), tasks, today))
    }
}
