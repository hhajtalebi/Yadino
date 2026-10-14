package ir.yadino.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities.
 *
 * Storage conventions (unambiguous, time-zone independent):
 *  - dates: ISO epoch day (INTEGER, days since 1970-01-01)
 *  - wall-clock times: minute of day (INTEGER 0..1439)
 *  - instants: UTC epoch milliseconds (INTEGER)
 *  - enums: their stable name (TEXT)
 * Jalali representations are produced only by the presentation layer.
 */

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val kind: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "goals",
    indices = [Index("category_id"), Index("status")],
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val term: String,
    val priority: Int,
    val status: String,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "start_date") val startDate: Long,
    val deadline: Long?,
    val color: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "manual_progress") val manualProgress: Int?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "notes",
    indices = [Index("category_id"), Index("goal_id"), Index("updated_at")],
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = GoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val color: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val tags: String,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "goal_id") val goalId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "tasks",
    indices = [Index("date"), Index("due_date"), Index("goal_id"), Index("category_id"), Index("status"), Index("source_note_id")],
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = GoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["source_note_id"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val kind: String,
    val priority: Int,
    val status: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val tags: String,
    val date: Long?,
    @ColumnInfo(name = "start_minute") val startMinute: Int?,
    @ColumnInfo(name = "end_minute") val endMinute: Int?,
    @ColumnInfo(name = "due_date") val dueDate: Long?,
    @ColumnInfo(name = "due_minute") val dueMinute: Int?,
    @ColumnInfo(name = "reminder_offset") val reminderOffset: Int?,
    val recurrence: String?,
    @ColumnInfo(name = "recurrence_start") val recurrenceStart: Long?,
    @ColumnInfo(name = "goal_id") val goalId: String?,
    @ColumnInfo(name = "source_note_id") val sourceNoteId: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "subtasks",
    indices = [Index("task_id")],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SubtaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val title: String,
    @ColumnInfo(name = "is_done") val isDone: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "task_completions",
    indices = [Index("task_id"), Index("occurrence_date"), Index("completed_at")],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TaskCompletionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "occurrence_date") val occurrenceDate: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
)

@Entity(
    tableName = "milestones",
    indices = [Index("goal_id")],
    foreignKeys = [
        ForeignKey(entity = GoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MilestoneEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "goal_id") val goalId: String,
    val title: String,
    @ColumnInfo(name = "is_done") val isDone: Boolean,
    @ColumnInfo(name = "due_date") val dueDate: Long?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "habits",
    indices = [Index("goal_id")],
    foreignKeys = [
        ForeignKey(entity = GoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class HabitEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val color: String,
    val icon: String,
    @ColumnInfo(name = "schedule_type") val scheduleType: String,
    @ColumnInfo(name = "schedule_days") val scheduleDays: Int,
    @ColumnInfo(name = "schedule_times") val scheduleTimes: Int,
    @ColumnInfo(name = "target_per_day") val targetPerDay: Int,
    @ColumnInfo(name = "reminder_minute") val reminderMinute: Int?,
    @ColumnInfo(name = "goal_id") val goalId: String?,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "habit_logs",
    indices = [Index(value = ["habit_id", "date"], unique = true), Index("date")],
    foreignKeys = [
        ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habit_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class HabitLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    val date: Long,
    val count: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Unsaved note editor content, used to recover drafts after an unexpected shutdown. */
@Entity(tableName = "note_drafts")
data class NoteDraftEntity(
    /** Note id, or "new" for a note that has not been saved yet. */
    @PrimaryKey @ColumnInfo(name = "note_key") val noteKey: String,
    val title: String,
    val content: String,
    val color: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val tags: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

/** Tombstones of deleted records (prepared for a future two-way sync). */
@Entity(tableName = "deletions", primaryKeys = ["entity_type", "entity_id"])
data class DeletionEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

/** Projection used for subtask progress badges. */
data class SubtaskCount(
    @ColumnInfo(name = "task_id") val taskId: String,
    val total: Int,
    val done: Int,
)
