package ir.yadino.app.data.repository

import androidx.room.withTransaction
import ir.yadino.app.core.AppClock
import ir.yadino.app.data.local.YadinoDatabase
import ir.yadino.app.data.local.entity.DeletionEntity
import ir.yadino.app.data.local.entity.NoteDraftEntity
import ir.yadino.app.data.local.toDb
import ir.yadino.app.data.local.toDomain
import ir.yadino.app.data.local.toEntity
import ir.yadino.app.data.settings.SettingsRepository
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.DefaultCategories
import ir.yadino.domain.model.EntityType
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.planning.PlanCopier
import ir.yadino.domain.planning.TaskCompletionLogic
import ir.yadino.domain.validation.ValidationError
import ir.yadino.domain.validation.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

/** Hooks executed after data changes (reminder scheduling, widget refresh). */
interface DataSideEffects {
    fun onTasksChanged(changed: List<Task>, deletedIds: List<String> = emptyList())
    fun onHabitsChanged(changed: List<Habit>, deletedIds: List<String> = emptyList())
    fun onGoalsChanged(changed: List<Goal>, deletedIds: List<String> = emptyList())
    fun onNotesChanged()
    /** Bulk change (restore, reset): everything must be refreshed. */
    fun onEverythingChanged()
}

/** Thrown when input fails validation; the UI shows the Persian message of each error. */
class ValidationException(val errors: List<ValidationError>) : Exception(errors.joinToString())

private suspend fun YadinoDatabase.logDeletion(type: EntityType, id: String, at: Long) =
    deletionDao().insert(DeletionEntity(type.name, id, at))

class TaskRepository(
    private val db: YadinoDatabase,
    private val clock: AppClock,
    private val settings: SettingsRepository,
    private val effects: DataSideEffects,
) {
    private val taskDao = db.taskDao()
    private val subtaskDao = db.subtaskDao()
    private val completionDao = db.completionDao()

    val tasks: Flow<List<Task>> = taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** taskId -> (done, total) */
    val subtaskProgress: Flow<Map<String, Pair<Int, Int>>> =
        subtaskDao.observeCounts().map { list -> list.associate { it.taskId to (it.done to it.total) } }

    val completions: Flow<List<TaskCompletion>> = completionDao.observeAll().map { l -> l.map { it.toDomain() } }

    fun observe(id: String): Flow<Task?> = taskDao.observeById(id).map { it?.toDomain() }

    fun observeSubtasks(taskId: String): Flow<List<Subtask>> = subtaskDao.observeForTask(taskId).map { l -> l.map { it.toDomain() } }

    fun observeCompletions(taskId: String): Flow<List<TaskCompletion>> =
        completionDao.observeForTask(taskId).map { l -> l.map { it.toDomain() } }

    suspend fun get(id: String): Task? = taskDao.getById(id)?.toDomain()

    suspend fun getSubtasks(taskId: String): List<Subtask> = subtaskDao.getForTask(taskId).map { it.toDomain() }

    suspend fun getAll(): List<Task> = taskDao.getAll().map { it.toDomain() }

    suspend fun getForRange(from: LocalDate, to: LocalDate): List<Task> =
        taskDao.getForRange(from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    /** Validates and stores a task with (optionally) its full subtask list. */
    suspend fun save(task: Task, subtasks: List<Subtask>? = null): Task {
        val now = clock.now()
        var t = task.copy(
            title = task.title.trim(),
            tags = Validators.normalizeTags(task.tags),
            updatedAt = now,
            recurrenceStart = if (task.recurrence != null) task.recurrenceStart ?: task.date ?: task.dueDate else null,
            completedAt = if (task.status == TaskStatus.DONE) task.completedAt ?: now else null,
        )
        val errors = Validators.task(t)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        db.withTransaction {
            val existing = taskDao.getById(t.id)
            if (existing == null && t.date != null && t.sortOrder == 0) {
                t = t.copy(sortOrder = taskDao.maxSortOrderOn(t.date!!.toEpochDay()) + 1)
            }
            taskDao.upsert(t.toEntity())
            if (subtasks != null) {
                val cleaned = subtasks.filter { it.title.isNotBlank() }.mapIndexed { i, s ->
                    s.copy(taskId = t.id, sortOrder = i, updatedAt = now)
                }
                subtaskDao.deleteForTaskExcept(t.id, cleaned.map { it.id })
                subtaskDao.upsertAll(cleaned.map { it.toEntity() })
            }
        }
        effects.onTasksChanged(listOf(t))
        return t
    }

    suspend fun quickAdd(title: String, date: LocalDate?, kind: TaskKind = TaskKind.TASK, goalId: String? = null): Task {
        val now = clock.now()
        val s = settings.current()
        return save(
            Task(
                id = Ids.newId(), title = title, kind = kind, date = date, goalId = goalId,
                reminderOffsetMinutes = if (date != null && kind != TaskKind.TASK) s.defaultReminderOffsetMinutes else null,
                createdAt = now, updatedAt = now,
            ),
        )
    }

    /** Completes (or re-opens) a task, handling recurring series. */
    suspend fun setCompleted(task: Task, completed: Boolean) {
        val now = clock.now()
        val s = settings.current()
        val changed = ArrayList<Task>()
        db.withTransaction {
            if (completed) {
                val r = TaskCompletionLogic.complete(task, now, s.recurringCompletion, s.firstDayOfWeek) { Ids.newId() }
                completionDao.insert(r.completion.toEntity())
                r.completedCopy?.let { copy ->
                    taskDao.upsert(copy.toEntity())
                    val subs = subtaskDao.getForTask(task.id)
                    subtaskDao.upsertAll(subs.map { it.copy(id = Ids.newId(), taskId = copy.id) })
                    changed += copy
                }
                taskDao.upsert(r.updatedTask.toEntity())
                if (r.resetSubtasks) subtaskDao.resetForTask(task.id, now.toEpochMilli())
                changed += r.updatedTask
            } else {
                completionDao.latestForTask(task.id)?.let { completionDao.delete(it.id) }
                val reopened = task.copy(status = TaskStatus.TODO, completedAt = null, updatedAt = now)
                taskDao.upsert(reopened.toEntity())
                changed += reopened
            }
        }
        effects.onTasksChanged(changed)
    }

    suspend fun setStatus(task: Task, status: TaskStatus) {
        if (status == TaskStatus.DONE) return setCompleted(task, true)
        if (task.status == TaskStatus.DONE && status != TaskStatus.DONE) {
            completionDao.latestForTask(task.id)?.let { completionDao.delete(it.id) }
        }
        val updated = task.copy(status = status, completedAt = null, updatedAt = clock.now())
        taskDao.upsert(updated.toEntity())
        effects.onTasksChanged(listOf(updated))
    }

    suspend fun setArchived(task: Task, archived: Boolean) {
        val updated = task.copy(isArchived = archived, updatedAt = clock.now())
        taskDao.upsert(updated.toEntity())
        effects.onTasksChanged(listOf(updated))
    }

    suspend fun delete(id: String) {
        val now = clock.now().toEpochMilli()
        db.withTransaction {
            taskDao.delete(id)
            db.logDeletion(EntityType.TASK, id, now)
        }
        effects.onTasksChanged(emptyList(), listOf(id))
    }

    /** Re-inserts a deleted task (undo). */
    suspend fun restore(task: Task, subtasks: List<Subtask>) {
        db.withTransaction {
            taskDao.upsert(task.toEntity())
            subtaskDao.upsertAll(subtasks.map { it.toEntity() })
        }
        effects.onTasksChanged(listOf(task))
    }

    suspend fun moveToDate(task: Task, date: LocalDate?) {
        val shift = if (task.date != null && date != null) java.time.temporal.ChronoUnit.DAYS.between(task.date, date) else 0
        val updated = task.copy(
            date = date,
            dueDate = if (task.dueDate != null && date != null && task.dueDate!!.isBefore(date)) task.dueDate!!.plusDays(shift) else task.dueDate,
            startTime = if (date == null) null else task.startTime,
            endTime = if (date == null) null else task.endTime,
            recurrenceStart = if (task.recurrence != null && date != null && task.recurrenceStart == task.date) date else task.recurrenceStart,
            sortOrder = if (date != null) taskDao.maxSortOrderOn(date.toEpochDay()) + 1 else 0,
            updatedAt = clock.now(),
        )
        taskDao.upsert(updated.toEntity())
        effects.onTasksChanged(listOf(updated))
    }

    /** Persists a manual order for the tasks of a day plan. */
    suspend fun reorder(orderedTasks: List<Task>) {
        val now = clock.now()
        val updated = orderedTasks.mapIndexed { index, t -> t.copy(sortOrder = index + 1, updatedAt = now) }
        db.withTransaction { taskDao.upsertAll(updated.map { it.toEntity() }) }
        effects.onTasksChanged(updated)
    }

    suspend fun duplicate(task: Task): Task {
        val now = clock.now()
        val copy = task.copy(id = Ids.newId(), status = TaskStatus.TODO, completedAt = null, createdAt = now, updatedAt = now)
        val subs = subtaskDao.getForTask(task.id).map { it.toDomain().copy(id = Ids.newId(), taskId = copy.id, isDone = false) }
        return save(copy, subs)
    }

    /** Copies a day/week plan to another start date. Returns the number of tasks copied. */
    suspend fun copyPlan(sourceStart: LocalDate, sourceEnd: LocalDate, targetStart: LocalDate): Int {
        val tasks = getForRange(sourceStart, sourceEnd)
        val subtasks = tasks.flatMap { getSubtasks(it.id) }
        val copied = PlanCopier.copy(tasks, subtasks, sourceStart, sourceEnd, targetStart, clock.now()) { Ids.newId() }
        db.withTransaction {
            taskDao.upsertAll(copied.tasks.map { it.toEntity() })
            subtaskDao.upsertAll(copied.subtasks.map { it.toEntity() })
        }
        effects.onTasksChanged(copied.tasks)
        return copied.tasks.size
    }

    suspend fun toggleSubtask(subtask: Subtask) {
        subtaskDao.upsert(subtask.copy(isDone = !subtask.isDone, updatedAt = clock.now()).toEntity())
        effects.onTasksChanged(emptyList())
    }

    suspend fun addSubtask(taskId: String, title: String) {
        if (title.isBlank()) return
        val now = clock.now()
        val order = subtaskDao.getForTask(taskId).size
        subtaskDao.upsert(Subtask(Ids.newId(), taskId, title.trim(), false, order, now, now).toEntity())
        effects.onTasksChanged(emptyList())
    }

    suspend fun moveOverdueToToday(): Int {
        val n = taskDao.moveOverdueTo(clock.today().toEpochDay(), clock.now().toEpochMilli())
        if (n > 0) effects.onEverythingChanged()
        return n
    }

    suspend fun linkToGoal(taskIds: List<String>, goalId: String?) {
        val now = clock.now()
        val changed = ArrayList<Task>()
        db.withTransaction {
            for (id in taskIds) {
                val t = taskDao.getById(id)?.toDomain() ?: continue
                val u = t.copy(goalId = goalId, updatedAt = now)
                taskDao.upsert(u.toEntity())
                changed += u
            }
        }
        effects.onTasksChanged(changed)
    }
}

class NoteRepository(
    private val db: YadinoDatabase,
    private val clock: AppClock,
    private val effects: DataSideEffects,
) {
    private val dao = db.noteDao()
    private val drafts = db.noteDraftDao()

    val notes: Flow<List<Note>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    val draftCount: Flow<Int> = drafts.observeCount()

    fun observe(id: String): Flow<Note?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun get(id: String): Note? = dao.getById(id)?.toDomain()

    suspend fun getRecent(limit: Int): List<Note> = dao.getRecent(limit).map { it.toDomain() }

    /** Saves a note. Blank new notes are not stored. Returns the stored note (or null). */
    suspend fun save(note: Note): Note? {
        val errors = Validators.note(note)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        if (note.isBlank && dao.getById(note.id) == null) return null
        val stored = note.copy(tags = Validators.normalizeTags(note.tags), updatedAt = clock.now())
        dao.upsert(stored.toEntity())
        effects.onNotesChanged()
        return stored
    }

    suspend fun create(title: String, content: String, color: NoteColor = NoteColor.DEFAULT, goalId: String? = null): Note? {
        val now = clock.now()
        return save(Note(Ids.newId(), title.trim(), content, color, goalId = goalId, createdAt = now, updatedAt = now))
    }

    suspend fun setPinned(note: Note, pinned: Boolean) {
        save(note.copy(isPinned = pinned))
    }

    suspend fun setArchived(note: Note, archived: Boolean) {
        save(note.copy(isArchived = archived, isPinned = if (archived) false else note.isPinned))
    }

    suspend fun delete(id: String) {
        val now = clock.now().toEpochMilli()
        db.withTransaction {
            dao.delete(id)
            drafts.delete(id)
            db.logDeletion(EntityType.NOTE, id, now)
        }
        effects.onNotesChanged()
    }

    suspend fun restore(note: Note) {
        dao.upsert(note.toEntity())
        effects.onNotesChanged()
    }

    // ---- drafts (crash / process-death recovery) --------------------------------------
    suspend fun saveDraft(key: String, note: Note) {
        drafts.upsert(
            NoteDraftEntity(key, note.title, note.content, note.color.name, note.categoryId,
                note.tags.joinToString("\n"), clock.now().toEpochMilli()),
        )
    }

    suspend fun getDraft(key: String): NoteDraftEntity? = drafts.get(key)

    suspend fun allDrafts(): List<NoteDraftEntity> = drafts.getAll()

    suspend fun clearDraft(key: String) = drafts.delete(key)
}

class GoalRepository(
    private val db: YadinoDatabase,
    private val clock: AppClock,
    private val effects: DataSideEffects,
) {
    private val dao = db.goalDao()
    private val milestoneDao = db.milestoneDao()

    val goals: Flow<List<Goal>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    val milestones: Flow<List<Milestone>> = milestoneDao.observeAll().map { l -> l.map { it.toDomain() } }

    fun observe(id: String): Flow<Goal?> = dao.observeById(id).map { it?.toDomain() }
    fun observeMilestones(goalId: String): Flow<List<Milestone>> = milestoneDao.observeForGoal(goalId).map { l -> l.map { it.toDomain() } }

    suspend fun get(id: String): Goal? = dao.getById(id)?.toDomain()
    suspend fun getMilestones(goalId: String): List<Milestone> = milestoneDao.getForGoal(goalId).map { it.toDomain() }
    suspend fun getAll(): List<Goal> = dao.getAll().map { it.toDomain() }

    suspend fun save(goal: Goal, milestones: List<Milestone>? = null): Goal {
        val now = clock.now()
        val g = goal.copy(
            title = goal.title.trim(), updatedAt = now,
            completedAt = if (goal.status == GoalStatus.COMPLETED) goal.completedAt ?: now else null,
        )
        val errors = Validators.goal(g)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        db.withTransaction {
            dao.upsert(g.toEntity())
            if (milestones != null) {
                val cleaned = milestones.filter { it.title.isNotBlank() }.mapIndexed { i, m ->
                    m.copy(goalId = g.id, sortOrder = i, updatedAt = now, completedAt = if (m.isDone) m.completedAt ?: now else null)
                }
                milestoneDao.deleteForGoalExcept(g.id, cleaned.map { it.id })
                milestoneDao.upsertAll(cleaned.map { it.toEntity() })
            }
        }
        effects.onGoalsChanged(listOf(g))
        return g
    }

    suspend fun setStatus(goal: Goal, status: GoalStatus) {
        save(goal.copy(status = status))
    }

    suspend fun setArchived(goal: Goal, archived: Boolean) {
        save(goal.copy(isArchived = archived))
    }

    suspend fun toggleMilestone(m: Milestone) {
        val now = clock.now()
        milestoneDao.upsert(m.copy(isDone = !m.isDone, completedAt = if (!m.isDone) now else null, updatedAt = now).toEntity())
        effects.onGoalsChanged(emptyList())
    }

    suspend fun addMilestone(goalId: String, title: String) {
        if (title.isBlank()) return
        val now = clock.now()
        val order = milestoneDao.getForGoal(goalId).size
        milestoneDao.upsert(Milestone(Ids.newId(), goalId, title.trim(), sortOrder = order, createdAt = now, updatedAt = now).toEntity())
        effects.onGoalsChanged(emptyList())
    }

    suspend fun delete(id: String) {
        val now = clock.now().toEpochMilli()
        db.withTransaction {
            dao.delete(id)
            db.logDeletion(EntityType.GOAL, id, now)
        }
        effects.onGoalsChanged(emptyList(), listOf(id))
    }
}

class HabitRepository(
    private val db: YadinoDatabase,
    private val clock: AppClock,
    private val effects: DataSideEffects,
) {
    private val dao = db.habitDao()
    private val logDao = db.habitLogDao()

    val habits: Flow<List<Habit>> = dao.observeAll().map { l -> l.map { it.toDomain() } }

    /** Logs of the last [days] days (enough for streaks and reports). */
    fun logsSince(from: LocalDate): Flow<List<HabitLog>> = logDao.observeSince(from.toEpochDay()).map { l -> l.map { it.toDomain() } }

    fun observe(id: String): Flow<Habit?> = dao.observeById(id).map { it?.toDomain() }
    fun observeLogs(habitId: String): Flow<List<HabitLog>> = logDao.observeForHabit(habitId).map { l -> l.map { it.toDomain() } }

    suspend fun get(id: String): Habit? = dao.getById(id)?.toDomain()
    suspend fun getAll(): List<Habit> = dao.getAll().map { it.toDomain() }
    suspend fun getLogsSince(from: LocalDate): List<HabitLog> = logDao.getSince(from.toEpochDay()).map { it.toDomain() }

    suspend fun save(habit: Habit): Habit {
        val h = habit.copy(title = habit.title.trim(), updatedAt = clock.now())
        val errors = Validators.habit(h)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        dao.upsert(h.toEntity())
        effects.onHabitsChanged(listOf(h))
        return h
    }

    /** Sets the check-in count of [habit] on [date] (0 removes the log). */
    suspend fun setCount(habit: Habit, date: LocalDate, count: Int) {
        val c = count.coerceIn(0, habit.targetPerDay * 10)
        db.withTransaction {
            if (c == 0) logDao.delete(habit.id, date.toDb())
            else {
                val existing = logDao.get(habit.id, date.toDb())
                logDao.upsert(HabitLog(existing?.id ?: Ids.newId(), habit.id, date, c, clock.now()).toEntity())
            }
        }
        effects.onHabitsChanged(listOf(habit))
    }

    /** Toggles completion for single-count habits, increments otherwise (wrapping to 0). */
    suspend fun checkIn(habit: Habit, date: LocalDate) {
        val current = logDao.get(habit.id, date.toDb())?.count ?: 0
        val next = if (habit.targetPerDay <= 1) (if (current >= 1) 0 else 1)
        else if (current >= habit.targetPerDay) 0 else current + 1
        setCount(habit, date, next)
    }

    suspend fun setArchived(habit: Habit, archived: Boolean) {
        save(habit.copy(isArchived = archived))
    }

    suspend fun linkToGoal(habitIds: List<String>, goalId: String?) {
        val changed = ArrayList<Habit>()
        for (id in habitIds) {
            val h = get(id) ?: continue
            changed += save(h.copy(goalId = goalId))
        }
    }

    suspend fun delete(id: String) {
        val now = clock.now().toEpochMilli()
        db.withTransaction {
            dao.delete(id)
            db.logDeletion(EntityType.HABIT, id, now)
        }
        effects.onHabitsChanged(emptyList(), listOf(id))
    }
}

class CategoryRepository(private val db: YadinoDatabase, private val clock: AppClock) {
    private val dao = db.categoryDao()
    val categories: Flow<List<Category>> = dao.observeAll().map { l -> l.map { it.toDomain() } }

    suspend fun getAll(): List<Category> = dao.getAll().map { it.toDomain() }

    /** Inserts the default categories on first launch (ignored if they exist). */
    suspend fun ensureDefaults() {
        if (dao.count() == 0) dao.insertIgnore(DefaultCategories.create(clock.now()).map { it.toEntity() })
    }

    suspend fun save(category: Category): Category {
        val existing = getAll()
        val c = category.copy(name = category.name.trim(), updatedAt = clock.now())
        val errors = Validators.category(c, existing)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        dao.upsert(c.toEntity())
        return c
    }

    suspend fun delete(id: String) {
        val now = clock.now().toEpochMilli()
        db.withTransaction {
            dao.delete(id)
            db.logDeletion(EntityType.CATEGORY, id, now)
        }
    }
}

/** Record counts shown in Settings › Data. */
data class DataStats(
    val tasks: Int, val notes: Int, val goals: Int, val habits: Int, val habitLogs: Int,
    val categories: Int, val completions: Int, val databaseBytes: Long,
)

/** Whole-database operations: backup snapshot, atomic restore, statistics and resets. */
class DataRepository(
    private val db: YadinoDatabase,
    private val databaseFile: () -> java.io.File,
    private val clock: AppClock,
    private val effects: DataSideEffects,
) {
    suspend fun snapshot(settings: Map<String, String>, dashboard: String?): BackupData = db.withTransaction {
        BackupData(
            categories = db.categoryDao().getAll().map { it.toDomain() },
            tasks = db.taskDao().getAll().map { it.toDomain() },
            subtasks = db.subtaskDao().getAll().map { it.toDomain() },
            completions = db.completionDao().getAll().map { it.toDomain() },
            notes = db.noteDao().getAll().map { it.toDomain() },
            goals = db.goalDao().getAll().map { it.toDomain() },
            milestones = db.milestoneDao().getAll().map { it.toDomain() },
            habits = db.habitDao().getAll().map { it.toDomain() },
            habitLogs = db.habitLogDao().getAll().map { it.toDomain() },
            settings = settings,
            dashboard = dashboard,
        )
    }

    /** Replaces the whole database content with [data] in a single transaction. */
    suspend fun replaceAll(data: BackupData) {
        db.withTransaction {
            clearTables()
            db.categoryDao().insertAll(data.categories.map { it.toEntity() })
            db.goalDao().insertAll(data.goals.map { it.toEntity() })
            db.noteDao().insertAll(data.notes.map { it.toEntity() })
            db.taskDao().insertAll(data.tasks.map { it.toEntity() })
            db.subtaskDao().upsertAll(data.subtasks.map { it.toEntity() })
            db.completionDao().insertAll(data.completions.map { it.toEntity() })
            db.milestoneDao().upsertAll(data.milestones.map { it.toEntity() })
            db.habitDao().insertAll(data.habits.map { it.toEntity() })
            db.habitLogDao().insertAll(data.habitLogs.map { it.toEntity() })
        }
        effects.onEverythingChanged()
    }

    private suspend fun clearTables() {
        db.habitLogDao().deleteAll()
        db.habitDao().deleteAll()
        db.milestoneDao().deleteAll()
        db.completionDao().deleteAll()
        db.subtaskDao().deleteAll()
        db.taskDao().deleteAll()
        db.noteDao().deleteAll()
        db.goalDao().deleteAll()
        db.categoryDao().deleteAll()
    }

    /** Deletes all user data (settings are kept); default categories are recreated. */
    suspend fun deleteAllData() {
        db.withTransaction {
            clearTables()
            db.noteDraftDao().deleteAll()
            db.deletionDao().deleteAll()
            db.categoryDao().insertIgnore(DefaultCategories.create(clock.now()).map { it.toEntity() })
        }
        effects.onEverythingChanged()
    }

    suspend fun deleteArchived(): Int {
        var n = 0
        db.withTransaction {
            n += db.taskDao().deleteArchived()
            n += db.noteDao().deleteArchived()
        }
        effects.onEverythingChanged()
        return n
    }

    suspend fun stats(): DataStats = DataStats(
        tasks = db.taskDao().count(), notes = db.noteDao().count(), goals = db.goalDao().count(),
        habits = db.habitDao().count(), habitLogs = db.habitLogDao().count(), categories = db.categoryDao().count(),
        completions = db.completionDao().count(),
        databaseBytes = databaseFile().let { f ->
            listOf(f, java.io.File(f.path + "-wal"), java.io.File(f.path + "-shm")).filter { it.exists() }.sumOf { it.length() }
        },
    )

    /** Removes deletion tombstones older than 180 days. */
    suspend fun pruneTombstones() {
        db.deletionDao().prune(clock.now().minusSeconds(180L * 24 * 3600).toEpochMilli())
    }
}

/** Default start time for a new task on [date], based on settings. */
fun defaultStartTime(settingsTime: LocalTime?): LocalTime? = settingsTime
