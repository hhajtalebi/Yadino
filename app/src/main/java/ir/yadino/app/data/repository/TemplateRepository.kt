package ir.yadino.app.data.repository

import androidx.room.withTransaction
import ir.yadino.app.core.AppClock
import ir.yadino.app.data.local.YadinoDatabase
import ir.yadino.app.data.local.toDomain
import ir.yadino.app.data.local.toEntity
import ir.yadino.domain.model.EntityType
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.planning.PlanTemplates
import ir.yadino.domain.validation.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.Collator
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Plan templates: storage, creating them from a plan and applying them to a date.
 * [firstDayOfWeek] supplies the user's setting (week templates are applied to the week that
 * starts on that day).
 */
class TemplateRepository(
    private val db: YadinoDatabase,
    private val clock: AppClock,
    private val firstDayOfWeek: suspend () -> DayOfWeek,
    private val effects: DataSideEffects,
) {
    private val dao = db.templateDao()

    /** All templates in Persian alphabetical order. */
    val templates: Flow<List<PlanTemplate>> = dao.observeTemplates().map { l -> sortByName(l.map { it.toDomain() }) }

    /** Items of all templates (in stored order). */
    val items: Flow<List<TemplateItem>> = dao.observeItems().map { l -> l.map { it.toDomain() }.sortedWith(PlanTemplates.storedOrder) }

    suspend fun get(id: String): PlanTemplate? = dao.getTemplate(id)?.toDomain()

    suspend fun getItems(templateId: String): List<TemplateItem> =
        dao.getItemsOf(templateId).map { it.toDomain() }.sortedWith(PlanTemplates.storedOrder)

    suspend fun getAll(): List<PlanTemplate> = sortByName(dao.getTemplates().map { it.toDomain() })

    /**
     * Validates and stores [template] together with its complete item list (items missing
     * from the list are deleted). Unchanged items keep their modification time.
     */
    suspend fun save(template: PlanTemplate, items: List<TemplateItem>): PlanTemplate {
        val now = clock.now()
        val t = template.copy(name = template.name.trim(), updatedAt = now)
        val cleaned = items.mapIndexed { i, item ->
            item.copy(
                templateId = t.id, title = item.title.trim(), tags = Validators.normalizeTags(item.tags),
                subtasks = PlanTemplates.cleanSubtasks(item.subtasks), sortOrder = i,
            )
        }
        val errors = Validators.template(t, cleaned, dao.getTemplates().map { it.toDomain() })
        if (errors.isNotEmpty()) throw ValidationException(errors)
        db.withTransaction {
            val existing = dao.getItemsOf(t.id).associate { it.id to it.toDomain() }
            val stamped = cleaned.map { item ->
                val old = existing[item.id]
                if (old != null && old.copy(updatedAt = item.updatedAt) == item) item.copy(updatedAt = old.updatedAt) else item.copy(updatedAt = now)
            }
            dao.upsertTemplate(t.toEntity())
            dao.deleteItemsExcept(t.id, stamped.map { it.id })
            dao.upsertItems(stamped.map { it.toEntity() })
            val kept = stamped.map { it.id }.toSet()
            existing.keys.filter { it !in kept }.forEach { db.logDeletion(EntityType.TEMPLATE_ITEM, it, now.toEpochMilli()) }
        }
        return t
    }

    /** Deletes a template with its items and returns them for undo (null if it did not exist). */
    suspend fun delete(id: String): Pair<PlanTemplate, List<TemplateItem>>? {
        val now = clock.now().toEpochMilli()
        return db.withTransaction {
            val template = dao.getTemplate(id)?.toDomain() ?: return@withTransaction null
            val items = dao.getItemsOf(id).map { it.toDomain() }
            dao.deleteTemplate(id)
            db.logDeletion(EntityType.TEMPLATE, id, now)
            items.forEach { db.logDeletion(EntityType.TEMPLATE_ITEM, it.id, now) }
            template to items
        }
    }

    /** Re-inserts a deleted template (undo). */
    suspend fun restore(template: PlanTemplate, items: List<TemplateItem>) {
        db.withTransaction {
            dao.upsertTemplate(template.toEntity())
            dao.upsertItems(items.map { it.toEntity() })
            db.deletionDao().forget(EntityType.TEMPLATE.name, listOf(template.id))
            db.deletionDao().forget(EntityType.TEMPLATE_ITEM.name, items.map { it.id })
        }
    }

    /**
     * Copies a template. [nameFor] gives the candidate names ("… (copy)", "… (copy 2)", …);
     * the first one that is not used yet is taken.
     */
    suspend fun duplicate(id: String, nameFor: (Int) -> String): PlanTemplate? {
        val template = get(id) ?: return null
        val used = dao.getTemplates().map { Validators.comparisonKey(it.name) }.toSet()
        val name = (1..100).asSequence().map(nameFor).firstOrNull { Validators.comparisonKey(it) !in used } ?: nameFor(101)
        val (copy, items) = PlanTemplates.duplicate(template, getItems(id), name, clock.now()) { Ids.newId() }
        return save(copy, items)
    }

    /** Stored tasks (with subtasks) that make up the plan of [start] .. [start]+[spanDays]-1. */
    private suspend fun planOf(start: LocalDate, spanDays: Int): Pair<List<Task>, List<ir.yadino.domain.model.Subtask>> {
        val end = start.plusDays(spanDays - 1L)
        val tasks = db.taskDao().getForRange(start.toEpochDay(), end.toEpochDay()).map { it.toDomain() }
        val subtasks = tasks.flatMap { t -> db.subtaskDao().getForTask(t.id).map { it.toDomain() } }
        return tasks to subtasks
    }

    /** Number of tasks that [saveFromPlan] would take (0 = nothing to save). */
    suspend fun planSize(start: LocalDate, spanDays: Int): Int {
        val (tasks, _) = planOf(start, spanDays)
        return PlanTemplates.fromPlan(tasks, emptyList(), start, spanDays, "-", clock.now()) { "" }?.second?.size ?: 0
    }

    /** Saves the plan of a day ([PlanTemplate.DAY]) or week as a new template; null when it is empty. */
    suspend fun saveFromPlan(start: LocalDate, spanDays: Int, name: String): PlanTemplate? {
        val (tasks, subtasks) = planOf(start, spanDays)
        val (template, items) = PlanTemplates.fromPlan(tasks, subtasks, start, spanDays, name, clock.now()) { Ids.newId() } ?: return null
        return save(template, items)
    }

    /**
     * Where the items of [template] would land if it were applied to [date] (the day, or the
     * week containing it), with already existing items marked.
     */
    suspend fun preview(template: PlanTemplate, date: LocalDate): List<PlanTemplates.PreviewItem> {
        val firstDay = firstDayOfWeek()
        return PlanTemplates.preview(template, getItems(template.id), date, firstDay, db.taskDao().getAll().map { it.toDomain() })
    }

    /**
     * Creates the tasks of [template] for [date] (the day, or the week containing it) in one
     * transaction. The created tasks are returned so the caller can offer undo
     * ([TaskRepository.deleteMany]).
     */
    suspend fun apply(template: PlanTemplate, date: LocalDate, skipDuplicates: Boolean): PlanTemplates.Applied {
        val firstDay = firstDayOfWeek()
        val items = getItems(template.id)
        val applied = db.withTransaction {
            val existing = db.taskDao().getAll().map { it.toDomain() }
            PlanTemplates.apply(template, items, date, firstDay, existing, skipDuplicates, clock.now()) { Ids.newId() }.also { r ->
                db.taskDao().insertAll(r.tasks.map { it.toEntity() })
                db.subtaskDao().upsertAll(r.subtasks.map { it.toEntity() })
            }
        }
        effects.onTasksChanged(applied.tasks)
        return applied
    }

    private fun sortByName(list: List<PlanTemplate>): List<PlanTemplate> {
        val collator = Collator.getInstance(Locale.forLanguageTag("fa"))
        return list.sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.createdAt.compareTo(b.createdAt) }
    }
}
