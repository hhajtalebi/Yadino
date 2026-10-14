package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.task
import ir.yadino.domain.goals.GoalPace
import ir.yadino.domain.goals.GoalProgressCalculator
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.reports.ReportCalculator
import ir.yadino.domain.reports.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

class HabitsGoalsReportsTest {
    private val today = j(1405, 7, 22) // Wednesday
    private val sat = DayOfWeek.SATURDAY

    @Test
    fun dailyStreakIgnoresPendingToday() {
        val h = TestData.habit()
        val logs = TestData.logs(h, today.minusDays(1), today.minusDays(2), today.minusDays(3), today.minusDays(5))
        val s = HabitStats.summarize(h, logs, today, sat)
        assertEquals(3, s.currentStreak)
        assertFalse(s.isDoneToday)
        val withToday = HabitStats.summarize(h, logs + TestData.logs(h, today), today, sat)
        assertEquals(4, withToday.currentStreak)
        assertEquals(4, withToday.bestStreak)
    }

    @Test
    fun specificDaysSkipUnscheduled() {
        val h = TestData.habit(HabitSchedule.SpecificDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
        // Wed 22, Mon 20, Sat 18, Wed 15
        val logs = TestData.logs(h, j(1405, 7, 22), j(1405, 7, 20), j(1405, 7, 18), j(1405, 7, 15))
        val s = HabitStats.summarize(h, logs, today, sat)
        assertEquals(4, s.currentStreak)
        assertTrue(s.isScheduledToday)
        assertFalse(HabitStats.isScheduled(h, j(1405, 7, 21)))
    }

    @Test
    fun targetPerDayRequiresCount() {
        val h = TestData.habit(target = 3)
        val logs = TestData.logs(h, today.minusDays(1), count = 3) + TestData.logs(h, today.minusDays(2), count = 2)
        assertEquals(1, HabitStats.summarize(h, logs, today, sat).currentStreak)
    }

    @Test
    fun timesPerWeekStreak() {
        val h = TestData.habit(HabitSchedule.TimesPerWeek(2), start = j(1405, 6, 1))
        // week of 18..24 Mehr: one check-in so far (not met yet) ; previous weeks met
        val logs = TestData.logs(h, j(1405, 7, 19), j(1405, 7, 11), j(1405, 7, 13), j(1405, 7, 4), j(1405, 7, 6))
        val s = HabitStats.summarize(h, logs, today, sat)
        assertTrue(s.streakInWeeks)
        assertEquals(2, s.currentStreak)
        assertEquals(1, s.weekProgress)
        assertEquals(2, s.weekTarget)
    }

    @Test
    fun completionRate() {
        val h = TestData.habit(start = today.minusDays(9))
        val logs = TestData.logs(h, *(0 until 5).map { today.minusDays(it.toLong()) }.toTypedArray())
        assertEquals(0.5, HabitStats.summarize(h, logs, today, sat).rate30, 1e-9)
        val hist = HabitStats.history(h, logs, today.minusDays(11), today)
        assertNull(hist[today.minusDays(11)])
        assertEquals(1.0, hist[today]!!, 1e-9)
    }

    private fun goal(status: GoalStatus = GoalStatus.ACTIVE, manual: Int? = null) = Goal(
        id = "g1", title = "هدف", status = status, startDate = j(1405, 7, 1), deadline = j(1405, 9, 30),
        manualProgress = manual, createdAt = NOW, updatedAt = NOW,
    )

    @Test
    fun goalProgressFromMilestonesAndTasks() {
        val g = goal()
        val ms = listOf(
            Milestone("m1", "g1", "یک", isDone = true, createdAt = NOW, updatedAt = NOW),
            Milestone("m2", "g1", "دو", createdAt = NOW, updatedAt = NOW),
        )
        val tasks = listOf(
            task("t1") { copy(goalId = "g1", status = TaskStatus.DONE) },
            task("t2") { copy(goalId = "g1") },
            task("t3") { copy(goalId = "g1", status = TaskStatus.CANCELLED) },
        )
        val p = GoalProgressCalculator.calculate(g, ms, tasks, emptyList(), today)
        assertEquals(50, p.percent)
        assertEquals(1, p.milestonesDone); assertEquals(2, p.milestonesTotal)
        assertEquals(1, p.tasksDone); assertEquals(2, p.tasksTotal)
        assertEquals(68L, p.daysRemaining)
    }

    @Test
    fun goalProgressManualHabitsAndCompleted() {
        assertEquals(30, GoalProgressCalculator.calculate(goal(manual = 30), emptyList(), emptyList(), emptyList(), today).percent)
        assertEquals(75, GoalProgressCalculator.calculate(goal(), emptyList(), emptyList(), listOf(0.5, 1.0), today).percent)
        val done = GoalProgressCalculator.calculate(goal(GoalStatus.COMPLETED), emptyList(), emptyList(), emptyList(), today)
        assertEquals(100, done.percent)
        assertEquals(GoalPace.COMPLETED, done.pace)
    }

    @Test
    fun goalPace() {
        // 21 of 90 days elapsed -> expected ~23%
        assertEquals(GoalPace.BEHIND, GoalProgressCalculator.calculate(goal(manual = 5), emptyList(), emptyList(), emptyList(), today).pace)
        assertEquals(GoalPace.ON_TRACK, GoalProgressCalculator.calculate(goal(manual = 25), emptyList(), emptyList(), emptyList(), today).pace)
        assertEquals(GoalPace.AHEAD, GoalProgressCalculator.calculate(goal(manual = 60), emptyList(), emptyList(), emptyList(), today).pace)
        assertEquals(GoalPace.OVERDUE, GoalProgressCalculator.calculate(goal(manual = 60), emptyList(), emptyList(), emptyList(), j(1405, 10, 5)).pace)
    }

    @Test
    fun weeklyReport() {
        val zone = ZoneId.of("Asia/Tehran")
        val weekStart = j(1405, 7, 18)
        val t1 = task("a", weekStart) { copy(status = TaskStatus.DONE) }
        val t2 = task("b", weekStart.plusDays(1))
        val t3 = task("c", weekStart.minusDays(3)) // overdue before the week
        val completion = TaskCompletion("c1", t1.id, weekStart, weekStart.atTime(10, 0).atZone(zone).toInstant())
        val h = TestData.habit(start = weekStart)
        val logs = TestData.logs(h, weekStart, weekStart.plusDays(1))
        val note = Note("n1", "یادداشت", "", createdAt = weekStart.atTime(9, 0).atZone(zone).toInstant(), updatedAt = NOW)
        val r = ReportCalculator.build(ReportPeriod.WEEK, today, today, zone, sat, listOf(t1, t2, t3), listOf(completion),
            listOf(h), logs, listOf(note), emptyList(), emptyList())
        assertEquals(weekStart, r.start)
        assertEquals(weekStart.plusDays(6), r.end)
        assertEquals(2, r.planned)
        assertEquals(1, r.completedOnPlan)
        assertEquals(1, r.completed)
        assertEquals(0.5, r.completionRate, 1e-9)
        assertEquals(2, r.overdue)
        assertEquals(1, r.notesCreated)
        assertEquals(7, r.days.size)
        // habit scheduled Sat..Wed (5 days up to today), done on 2
        assertEquals(0.4, r.habitRate!!, 1e-9)
        assertEquals(weekStart, r.bestDay!!.date)
    }

    @Test
    fun monthRangeFollowsJalaliMonth() {
        val (s, e) = ReportCalculator.range(ReportPeriod.MONTH, j(1405, 12, 10), sat)
        assertEquals(j(1405, 12, 1), s)
        assertEquals(j(1405, 12, 29), e)
    }
}
