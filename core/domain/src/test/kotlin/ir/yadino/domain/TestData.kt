package ir.yadino.domain

import ir.yadino.calendar.JalaliDate
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Task
import java.time.Instant
import java.time.LocalDate

object TestData {
    val NOW: Instant = Instant.parse("2026-10-14T08:00:00Z")

    fun j(y: Int, m: Int, d: Int): LocalDate = JalaliDate.of(y, m, d).toLocalDate()

    private var counter = 0
    fun id(prefix: String = "id"): String = "$prefix-${++counter}"

    fun task(
        title: String = "کار",
        date: LocalDate? = null,
        id: String = id("task"),
        build: Task.() -> Task = { this },
    ): Task = Task(id = id, title = title, date = date, createdAt = NOW, updatedAt = NOW).build()

    fun habit(schedule: HabitSchedule = HabitSchedule.Daily, start: LocalDate = j(1405, 1, 1), target: Int = 1) =
        Habit(id = id("habit"), title = "عادت", schedule = schedule, targetPerDay = target, startDate = start, createdAt = NOW, updatedAt = NOW)

    fun logs(habit: Habit, vararg days: LocalDate, count: Int = 1) =
        days.map { HabitLog(id("log"), habit.id, it, count, NOW) }
}
