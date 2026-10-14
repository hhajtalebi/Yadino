package ir.yadino.app.data.local

import ir.yadino.app.data.local.entity.CategoryEntity
import ir.yadino.app.data.local.entity.GoalEntity
import ir.yadino.app.data.local.entity.HabitEntity
import ir.yadino.app.data.local.entity.HabitLogEntity
import ir.yadino.app.data.local.entity.MilestoneEntity
import ir.yadino.app.data.local.entity.NoteEntity
import ir.yadino.app.data.local.entity.PlanTemplateEntity
import ir.yadino.app.data.local.entity.SubtaskEntity
import ir.yadino.app.data.local.entity.TaskCompletionEntity
import ir.yadino.app.data.local.entity.TaskEntity
import ir.yadino.app.data.local.entity.TemplateItemEntity
import ir.yadino.domain.backup.HabitScheduleCodec
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.CategoryKind
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.GoalTerm
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.recurrence.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Conversions between Room entities and domain models. */

private const val TAG_SEPARATOR = "\n"

internal fun encodeTags(tags: List<String>): String = tags.joinToString(TAG_SEPARATOR)
internal fun decodeTags(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split(TAG_SEPARATOR).filter { it.isNotBlank() }

internal fun LocalDate.toDb(): Long = toEpochDay()
internal fun Long.toDate(): LocalDate = LocalDate.ofEpochDay(this)
internal fun LocalTime.toDb(): Int = hour * 60 + minute
internal fun Int.toTime(): LocalTime = LocalTime.of((this / 60).coerceIn(0, 23), this % 60)
internal fun Instant.toDb(): Long = toEpochMilli()
internal fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

private inline fun <reified E : Enum<E>> enumOr(value: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == value } ?: default

fun TaskEntity.toDomain() = Task(
    id = id, title = title, description = description, kind = enumOr(kind, TaskKind.TASK),
    priority = Priority.fromLevel(priority), status = enumOr(status, TaskStatus.TODO), categoryId = categoryId,
    tags = decodeTags(tags), date = date?.toDate(), startTime = startMinute?.toTime(), endTime = endMinute?.toTime(),
    dueDate = dueDate?.toDate(), dueTime = dueMinute?.toTime(), reminderOffsetMinutes = reminderOffset,
    recurrence = RecurrenceRule.decode(recurrence), recurrenceStart = recurrenceStart?.toDate(), goalId = goalId,
    sourceNoteId = sourceNoteId, sortOrder = sortOrder, isArchived = isArchived, completedAt = completedAt?.toInstant(),
    createdAt = createdAt.toInstant(), updatedAt = updatedAt.toInstant(),
)

fun Task.toEntity() = TaskEntity(
    id = id, title = title.trim(), description = description, kind = kind.name, priority = priority.level, status = status.name,
    categoryId = categoryId, tags = encodeTags(tags), date = date?.toDb(), startMinute = startTime?.toDb(),
    endMinute = endTime?.toDb(), dueDate = dueDate?.toDb(), dueMinute = dueTime?.toDb(), reminderOffset = reminderOffsetMinutes,
    recurrence = recurrence?.encode(), recurrenceStart = recurrenceStart?.toDb(), goalId = goalId, sourceNoteId = sourceNoteId,
    sortOrder = sortOrder, isArchived = isArchived, completedAt = completedAt?.toDb(), createdAt = createdAt.toDb(),
    updatedAt = updatedAt.toDb(),
)

fun SubtaskEntity.toDomain() = Subtask(id, taskId, title, isDone, sortOrder, createdAt.toInstant(), updatedAt.toInstant())
fun Subtask.toEntity() = SubtaskEntity(id, taskId, title.trim(), isDone, sortOrder, createdAt.toDb(), updatedAt.toDb())

fun TaskCompletionEntity.toDomain() = TaskCompletion(id, taskId, occurrenceDate.toDate(), completedAt.toInstant())
fun TaskCompletion.toEntity() = TaskCompletionEntity(id, taskId, occurrenceDate.toDb(), completedAt.toDb())

fun NoteEntity.toDomain() = Note(
    id = id, title = title, content = content, color = enumOr(color, NoteColor.DEFAULT), categoryId = categoryId,
    tags = decodeTags(tags), isPinned = isPinned, isArchived = isArchived, goalId = goalId,
    createdAt = createdAt.toInstant(), updatedAt = updatedAt.toInstant(),
)

fun Note.toEntity() = NoteEntity(
    id = id, title = title, content = content, color = color.name, categoryId = categoryId, tags = encodeTags(tags),
    isPinned = isPinned, isArchived = isArchived, goalId = goalId, createdAt = createdAt.toDb(), updatedAt = updatedAt.toDb(),
)

fun GoalEntity.toDomain() = Goal(
    id = id, title = title, description = description, term = enumOr(term, GoalTerm.SHORT), priority = Priority.fromLevel(priority),
    status = enumOr(status, GoalStatus.ACTIVE), isArchived = isArchived, startDate = startDate.toDate(),
    deadline = deadline?.toDate(), color = enumOr(color, NoteColor.GREEN), categoryId = categoryId,
    manualProgress = manualProgress, completedAt = completedAt?.toInstant(), createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

fun Goal.toEntity() = GoalEntity(
    id = id, title = title.trim(), description = description, term = term.name, priority = priority.level, status = status.name,
    isArchived = isArchived, startDate = startDate.toDb(), deadline = deadline?.toDb(), color = color.name, categoryId = categoryId,
    manualProgress = manualProgress, completedAt = completedAt?.toDb(), createdAt = createdAt.toDb(), updatedAt = updatedAt.toDb(),
)

fun MilestoneEntity.toDomain() = Milestone(
    id, goalId, title, isDone, dueDate?.toDate(), sortOrder, completedAt?.toInstant(), createdAt.toInstant(), updatedAt.toInstant(),
)

fun Milestone.toEntity() = MilestoneEntity(
    id, goalId, title.trim(), isDone, dueDate?.toDb(), sortOrder, completedAt?.toDb(), createdAt.toDb(), updatedAt.toDb(),
)

fun HabitEntity.toDomain() = Habit(
    id = id, title = title, description = description, color = enumOr(color, NoteColor.GREEN), icon = icon,
    schedule = HabitScheduleCodec.decode(scheduleType, scheduleDays, scheduleTimes), targetPerDay = targetPerDay,
    reminderTime = reminderMinute?.toTime(), goalId = goalId, startDate = startDate.toDate(), isArchived = isArchived,
    sortOrder = sortOrder, createdAt = createdAt.toInstant(), updatedAt = updatedAt.toInstant(),
)

fun Habit.toEntity(): HabitEntity {
    val s = HabitScheduleCodec.encode(schedule)
    return HabitEntity(
        id = id, title = title.trim(), description = description, color = color.name, icon = icon, scheduleType = s.type,
        scheduleDays = s.daysMask, scheduleTimes = s.times, targetPerDay = targetPerDay, reminderMinute = reminderTime?.toDb(),
        goalId = goalId, startDate = startDate.toDb(), isArchived = isArchived, sortOrder = sortOrder,
        createdAt = createdAt.toDb(), updatedAt = updatedAt.toDb(),
    )
}

fun HabitLogEntity.toDomain() = HabitLog(id, habitId, date.toDate(), count, updatedAt.toInstant())
fun HabitLog.toEntity() = HabitLogEntity(id, habitId, date.toDb(), count, updatedAt.toDb())

fun CategoryEntity.toDomain() = Category(
    id, name, enumOr(color, NoteColor.GREEN), enumOr(kind, CategoryKind.GENERAL), isDefault, sortOrder,
    createdAt.toInstant(), updatedAt.toInstant(),
)

fun Category.toEntity() = CategoryEntity(id, name.trim(), color.name, kind.name, isDefault, sortOrder, createdAt.toDb(), updatedAt.toDb())

fun PlanTemplateEntity.toDomain() = PlanTemplate(id, name, description, spanDays, createdAt.toInstant(), updatedAt.toInstant())
fun PlanTemplate.toEntity() = PlanTemplateEntity(id, name.trim(), description, spanDays, createdAt.toDb(), updatedAt.toDb())

/** Subtask titles of template items are stored one per line (titles are single-line). */
internal fun encodeLines(lines: List<String>): String = lines.joinToString("\n") { it.replace('\n', ' ').replace('\r', ' ') }
internal fun decodeLines(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }

fun TemplateItemEntity.toDomain() = TemplateItem(
    id = id, templateId = templateId, title = title, description = description, kind = enumOr(kind, TaskKind.TASK),
    priority = Priority.fromLevel(priority), categoryId = categoryId, tags = decodeTags(tags), dayOffset = dayOffset,
    startTime = startMinute?.toTime(), endTime = endMinute?.toTime(), dueOffset = dueOffset, dueTime = dueMinute?.toTime(),
    reminderOffsetMinutes = reminderOffset, goalId = goalId, subtasks = decodeLines(subtasks), sortOrder = sortOrder,
    createdAt = createdAt.toInstant(), updatedAt = updatedAt.toInstant(),
)

fun TemplateItem.toEntity() = TemplateItemEntity(
    id = id, templateId = templateId, title = title.trim(), description = description, kind = kind.name, priority = priority.level,
    categoryId = categoryId, tags = encodeTags(tags), dayOffset = dayOffset, startMinute = startTime?.toDb(), endMinute = endTime?.toDb(),
    dueOffset = dueOffset, dueMinute = dueTime?.toDb(), reminderOffset = reminderOffsetMinutes, goalId = goalId,
    subtasks = encodeLines(subtasks), sortOrder = sortOrder, createdAt = createdAt.toDb(), updatedAt = updatedAt.toDb(),
)
