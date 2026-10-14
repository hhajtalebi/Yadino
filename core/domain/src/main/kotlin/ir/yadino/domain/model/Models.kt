package ir.yadino.domain.model

import ir.yadino.domain.notes.Checklist
import ir.yadino.domain.recurrence.RecurrenceRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/*
 * Domain models.
 *
 * Conventions (shared with the database and the backup format):
 *  - identifiers are random UUID strings, so records can be merged across devices and a
 *    future two-way sync can be added without key collisions;
 *  - calendar dates are [LocalDate] (persisted as ISO epoch day) and wall-clock times are
 *    [LocalTime] (persisted as minute of day). They are "floating" local values: a task
 *    planned for 10:00 stays at 10:00 when the time zone changes;
 *  - instants (creation, modification, completion) are [Instant] (persisted as UTC epoch
 *    milliseconds);
 *  - every record carries createdAt/updatedAt for conflict resolution.
 */

enum class Priority(val level: Int) {
    LOW(0), MEDIUM(1), HIGH(2), URGENT(3);

    companion object {
        fun fromLevel(level: Int): Priority = entries.firstOrNull { it.level == level } ?: MEDIUM
    }
}

enum class TaskStatus { TODO, IN_PROGRESS, DONE, CANCELLED }

/** Kind of a planner item. Events have no completion state in the UI. */
enum class TaskKind { TASK, EVENT, REMINDER }

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val kind: TaskKind = TaskKind.TASK,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.TODO,
    val categoryId: String? = null,
    val tags: List<String> = emptyList(),
    /** Planned day. Null means "no date" (inbox). */
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    /** Deadline. */
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    /** Minutes before the anchor time; null = no reminder. */
    val reminderOffsetMinutes: Int? = null,
    val recurrence: RecurrenceRule? = null,
    /** Anchor of the recurrence series (the first occurrence). */
    val recurrenceStart: LocalDate? = null,
    val goalId: String? = null,
    val sourceNoteId: String? = null,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isDone: Boolean get() = status == TaskStatus.DONE
    val isOpen: Boolean get() = status == TaskStatus.TODO || status == TaskStatus.IN_PROGRESS
    val isRecurring: Boolean get() = recurrence != null
    val isCompletable: Boolean get() = kind != TaskKind.EVENT
}

data class Subtask(
    val id: String,
    val taskId: String,
    val title: String,
    val isDone: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A completed occurrence of a (usually recurring) task, used for history and reports. */
data class TaskCompletion(
    val id: String,
    val taskId: String,
    val occurrenceDate: LocalDate,
    val completedAt: Instant,
)

/** Palette keys for notes; the UI maps them to light/dark colors. */
enum class NoteColor { DEFAULT, CREAM, GREEN, GOLD, ROSE, SKY, LAVENDER, SAND }

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val color: NoteColor = NoteColor.DEFAULT,
    val categoryId: String? = null,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val goalId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Nothing typed yet; a lone empty checklist item counts as blank too. */
    val isBlank: Boolean get() = title.isBlank() && Checklist.plainText(content).isBlank()
}

enum class GoalTerm { SHORT, LONG }

enum class GoalStatus { ACTIVE, PAUSED, COMPLETED }

data class Goal(
    val id: String,
    val title: String,
    val description: String = "",
    val term: GoalTerm = GoalTerm.SHORT,
    val priority: Priority = Priority.MEDIUM,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val isArchived: Boolean = false,
    val startDate: LocalDate,
    val deadline: LocalDate? = null,
    val color: NoteColor = NoteColor.GREEN,
    val categoryId: String? = null,
    /** Manual progress (0..100), used only when the goal has no milestones or linked tasks. */
    val manualProgress: Int? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Milestone(
    val id: String,
    val goalId: String,
    val title: String,
    val isDone: Boolean = false,
    val dueDate: LocalDate? = null,
    val sortOrder: Int = 0,
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** When a habit is expected to be performed. */
sealed interface HabitSchedule {
    data object Daily : HabitSchedule
    data class SpecificDays(val days: Set<DayOfWeek>) : HabitSchedule {
        init {
            require(days.isNotEmpty()) { "at least one day required" }
        }
    }
    data class TimesPerWeek(val times: Int) : HabitSchedule {
        init {
            require(times in 1..7) { "times per week must be 1..7" }
        }
    }
}

data class Habit(
    val id: String,
    val title: String,
    val description: String = "",
    val color: NoteColor = NoteColor.GREEN,
    val icon: String = "check",
    val schedule: HabitSchedule = HabitSchedule.Daily,
    /** Required repetitions per day (e.g. 8 glasses of water). */
    val targetPerDay: Int = 1,
    val reminderTime: LocalTime? = null,
    val goalId: String? = null,
    val startDate: LocalDate,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class HabitLog(
    val id: String,
    val habitId: String,
    val date: LocalDate,
    val count: Int,
    val updatedAt: Instant,
)

enum class CategoryKind { GENERAL, TASK, NOTE, GOAL }

data class Category(
    val id: String,
    val name: String,
    val color: NoteColor = NoteColor.GREEN,
    val kind: CategoryKind = CategoryKind.GENERAL,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Entity types, used by the deletion log (tombstones for future sync) and the backup. */
enum class EntityType { CATEGORY, TASK, SUBTASK, TASK_COMPLETION, NOTE, GOAL, MILESTONE, HABIT, HABIT_LOG, TEMPLATE, TEMPLATE_ITEM }

/** Tombstone of a deleted record (kept so that a future sync can propagate deletions). */
data class Deletion(val entityType: EntityType, val entityId: String, val deletedAt: Instant)
