package ir.yadino.domain.goals

import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class GoalPace { NO_DEADLINE, AHEAD, ON_TRACK, BEHIND, OVERDUE, COMPLETED }

data class GoalProgress(
    /** 0..100 */
    val percent: Int,
    val milestonesDone: Int,
    val milestonesTotal: Int,
    val tasksDone: Int,
    val tasksTotal: Int,
    /** Average completion rate (0..1) of linked habits, null if none. */
    val habitsRate: Double?,
    val daysRemaining: Long?,
    val pace: GoalPace,
) {
    val unitsDone: Int get() = milestonesDone + tasksDone
    val unitsTotal: Int get() = milestonesTotal + tasksTotal
}

/**
 * Goal progress = completed units / total units, where units are milestones and linked
 * (non cancelled) tasks. Linked habits contribute one unit each, weighted by their recent
 * completion rate. Goals without any unit use their manual progress value.
 */
object GoalProgressCalculator {

    fun calculate(
        goal: Goal,
        milestones: List<Milestone>,
        linkedTasks: List<Task>,
        habitRates: List<Double>,
        today: LocalDate,
    ): GoalProgress {
        val ms = milestones.filter { it.goalId == goal.id }
        val tasks = linkedTasks.filter { it.goalId == goal.id && it.status != TaskStatus.CANCELLED && it.isCompletable }
        val msDone = ms.count { it.isDone }
        val tDone = tasks.count { it.isDone }
        val habitsRate = if (habitRates.isEmpty()) null else habitRates.average().coerceIn(0.0, 1.0)
        val units = ms.size + tasks.size + habitRates.size
        val percent = when {
            goal.status == GoalStatus.COMPLETED -> 100
            units == 0 -> (goal.manualProgress ?: 0).coerceIn(0, 100)
            else -> (((msDone + tDone) + habitRates.sumOf { it.coerceIn(0.0, 1.0) }) * 100.0 / units).roundToInt().coerceIn(0, 100)
        }
        val remaining = goal.deadline?.let { ChronoUnit.DAYS.between(today, it) }
        val pace = when {
            goal.status == GoalStatus.COMPLETED || percent >= 100 -> GoalPace.COMPLETED
            goal.deadline == null -> GoalPace.NO_DEADLINE
            remaining != null && remaining < 0 -> GoalPace.OVERDUE
            else -> {
                val total = ChronoUnit.DAYS.between(goal.startDate, goal.deadline).coerceAtLeast(1)
                val elapsed = ChronoUnit.DAYS.between(goal.startDate, today).coerceIn(0, total)
                val expected = elapsed * 100.0 / total
                when {
                    percent >= expected + 10 -> GoalPace.AHEAD
                    percent + 10 >= expected -> GoalPace.ON_TRACK
                    else -> GoalPace.BEHIND
                }
            }
        }
        return GoalProgress(percent, msDone, ms.size, tDone, tasks.size, habitsRate, remaining, pace)
    }
}
