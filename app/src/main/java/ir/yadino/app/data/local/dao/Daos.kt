package ir.yadino.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import ir.yadino.app.data.local.entity.CategoryEntity
import ir.yadino.app.data.local.entity.DeletionEntity
import ir.yadino.app.data.local.entity.GoalEntity
import ir.yadino.app.data.local.entity.HabitEntity
import ir.yadino.app.data.local.entity.HabitLogEntity
import ir.yadino.app.data.local.entity.MilestoneEntity
import ir.yadino.app.data.local.entity.NoteDraftEntity
import ir.yadino.app.data.local.entity.NoteEntity
import ir.yadino.app.data.local.entity.PlanTemplateEntity
import ir.yadino.app.data.local.entity.SubtaskCount
import ir.yadino.app.data.local.entity.SubtaskEntity
import ir.yadino.app.data.local.entity.TaskCompletionEntity
import ir.yadino.app.data.local.entity.TaskEntity
import ir.yadino.app.data.local.entity.TemplateItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE is_archived = 0 AND status IN ('TODO','IN_PROGRESS') AND reminder_offset IS NOT NULL")
    suspend fun getWithReminders(): List<TaskEntity>

    @Query(
        "SELECT * FROM tasks WHERE is_archived = 0 AND status != 'CANCELLED' AND " +
            "((date BETWEEN :from AND :to) OR (date IS NULL AND due_date BETWEEN :from AND :to) OR recurrence IS NOT NULL OR " +
            "(status IN ('TODO','IN_PROGRESS') AND COALESCE(date, due_date) < :from))",
    )
    suspend fun getForRange(from: Long, to: Long): List<TaskEntity>

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM tasks WHERE date = :date")
    suspend fun maxSortOrderOn(date: Long): Int

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tasks WHERE is_archived = 1")
    suspend fun deleteArchived(): Int

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int

    @Query("UPDATE tasks SET is_archived = 1, updated_at = :now WHERE status = 'DONE' AND is_archived = 0 AND completed_at < :before")
    suspend fun archiveCompletedBefore(before: Long, now: Long): Int

    @Query(
        "UPDATE tasks SET date = :today, updated_at = :now WHERE is_archived = 0 AND status IN ('TODO','IN_PROGRESS') " +
            "AND kind != 'EVENT' AND date IS NOT NULL AND date < :today AND recurrence IS NULL",
    )
    suspend fun moveOverdueTo(today: Long, now: Long): Int
}

@Dao
interface SubtaskDao {
    @Query("SELECT * FROM subtasks WHERE task_id = :taskId ORDER BY sort_order, created_at")
    fun observeForTask(taskId: String): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM subtasks WHERE task_id = :taskId ORDER BY sort_order, created_at")
    suspend fun getForTask(taskId: String): List<SubtaskEntity>

    @Query("SELECT task_id, COUNT(*) AS total, SUM(CASE WHEN is_done THEN 1 ELSE 0 END) AS done FROM subtasks GROUP BY task_id")
    fun observeCounts(): Flow<List<SubtaskCount>>

    @Query("SELECT * FROM subtasks")
    suspend fun getAll(): List<SubtaskEntity>

    @Upsert
    suspend fun upsert(subtask: SubtaskEntity)

    @Upsert
    suspend fun upsertAll(subtasks: List<SubtaskEntity>)

    @Query("DELETE FROM subtasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM subtasks WHERE task_id = :taskId AND id NOT IN (:keep)")
    suspend fun deleteForTaskExcept(taskId: String, keep: List<String>)

    @Query("DELETE FROM subtasks WHERE task_id = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("UPDATE subtasks SET is_done = 0, updated_at = :now WHERE task_id = :taskId")
    suspend fun resetForTask(taskId: String, now: Long)

    @Query("DELETE FROM subtasks")
    suspend fun deleteAll()
}

@Dao
interface CompletionDao {
    @Query("SELECT * FROM task_completions")
    fun observeAll(): Flow<List<TaskCompletionEntity>>

    @Query("SELECT * FROM task_completions")
    suspend fun getAll(): List<TaskCompletionEntity>

    @Query("SELECT * FROM task_completions WHERE task_id = :taskId ORDER BY completed_at DESC")
    fun observeForTask(taskId: String): Flow<List<TaskCompletionEntity>>

    @Query("SELECT * FROM task_completions WHERE task_id = :taskId ORDER BY completed_at DESC LIMIT 1")
    suspend fun latestForTask(taskId: String): TaskCompletionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: TaskCompletionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<TaskCompletionEntity>)

    @Query("DELETE FROM task_completions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM task_completions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM task_completions")
    suspend fun count(): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notes WHERE is_archived = 1")
    suspend fun deleteArchived(): Int

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int
}

@Dao
interface NoteDraftDao {
    @Query("SELECT * FROM note_drafts WHERE note_key = :key")
    suspend fun get(key: String): NoteDraftEntity?

    @Query("SELECT * FROM note_drafts ORDER BY saved_at DESC")
    suspend fun getAll(): List<NoteDraftEntity>

    @Query("SELECT COUNT(*) FROM note_drafts")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(draft: NoteDraftEntity)

    @Query("DELETE FROM note_drafts WHERE note_key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM note_drafts")
    suspend fun deleteAll()
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY created_at DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals")
    suspend fun getAll(): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE id = :id")
    fun observeById(id: String): Flow<GoalEntity?>

    @Upsert
    suspend fun upsert(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<GoalEntity>)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM goals")
    suspend fun count(): Int
}

@Dao
interface MilestoneDao {
    @Query("SELECT * FROM milestones ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones WHERE goal_id = :goalId ORDER BY sort_order, created_at")
    fun observeForGoal(goalId: String): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones WHERE goal_id = :goalId ORDER BY sort_order, created_at")
    suspend fun getForGoal(goalId: String): List<MilestoneEntity>

    @Query("SELECT * FROM milestones")
    suspend fun getAll(): List<MilestoneEntity>

    @Upsert
    suspend fun upsert(m: MilestoneEntity)

    @Upsert
    suspend fun upsertAll(list: List<MilestoneEntity>)

    @Query("DELETE FROM milestones WHERE goal_id = :goalId AND id NOT IN (:keep)")
    suspend fun deleteForGoalExcept(goalId: String, keep: List<String>)

    @Query("DELETE FROM milestones WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM milestones")
    suspend fun deleteAll()
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: String): HabitEntity?

    @Query("SELECT * FROM habits WHERE id = :id")
    fun observeById(id: String): Flow<HabitEntity?>

    @Upsert
    suspend fun upsert(h: HabitEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<HabitEntity>)

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM habits")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun count(): Int
}

@Dao
interface HabitLogDao {
    @Query("SELECT * FROM habit_logs WHERE date >= :from")
    fun observeSince(from: Long): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId")
    fun observeForHabit(habitId: String): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId AND date = :date")
    suspend fun get(habitId: String, date: Long): HabitLogEntity?

    @Query("SELECT * FROM habit_logs")
    suspend fun getAll(): List<HabitLogEntity>

    @Query("SELECT * FROM habit_logs WHERE date >= :from")
    suspend fun getSince(from: Long): List<HabitLogEntity>

    @Upsert
    suspend fun upsert(log: HabitLogEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<HabitLogEntity>)

    @Query("DELETE FROM habit_logs WHERE habit_id = :habitId AND date = :date")
    suspend fun delete(habitId: String, date: Long)

    @Query("DELETE FROM habit_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM habit_logs")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sort_order, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort_order, name")
    suspend fun getAll(): List<CategoryEntity>

    @Upsert
    suspend fun upsert(c: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(list: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(list: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface DeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(d: DeletionEntity)

    @Query("SELECT * FROM deletions WHERE deleted_at > :since")
    suspend fun getSince(since: Long): List<DeletionEntity>

    @Query("DELETE FROM deletions WHERE deleted_at < :before")
    suspend fun prune(before: Long): Int

    /** Drops tombstones of records that were restored (undo). */
    @Query("DELETE FROM deletions WHERE entity_type = :type AND entity_id IN (:ids)")
    suspend fun forget(type: String, ids: List<String>)

    @Query("DELETE FROM deletions")
    suspend fun deleteAll()
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM plan_templates ORDER BY name COLLATE NOCASE, created_at")
    fun observeTemplates(): Flow<List<PlanTemplateEntity>>

    @Query("SELECT * FROM template_items ORDER BY template_id, sort_order, created_at")
    fun observeItems(): Flow<List<TemplateItemEntity>>

    @Query("SELECT * FROM plan_templates")
    suspend fun getTemplates(): List<PlanTemplateEntity>

    @Query("SELECT * FROM template_items")
    suspend fun getItems(): List<TemplateItemEntity>

    @Query("SELECT * FROM plan_templates WHERE id = :id")
    suspend fun getTemplate(id: String): PlanTemplateEntity?

    @Query("SELECT * FROM template_items WHERE template_id = :templateId ORDER BY sort_order, created_at")
    suspend fun getItemsOf(templateId: String): List<TemplateItemEntity>

    @Upsert
    suspend fun upsertTemplate(template: PlanTemplateEntity)

    @Upsert
    suspend fun upsertItems(items: List<TemplateItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplates(templates: List<PlanTemplateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<TemplateItemEntity>)

    @Query("DELETE FROM template_items WHERE template_id = :templateId AND id NOT IN (:keep)")
    suspend fun deleteItemsExcept(templateId: String, keep: List<String>)

    @Query("DELETE FROM plan_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM plan_templates")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM plan_templates")
    suspend fun count(): Int
}
