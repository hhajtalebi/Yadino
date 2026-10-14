package ir.yadino.domain.model

import java.time.Instant
import java.time.LocalTime

/**
 * A reusable plan such as "work day" or "exam week": a named list of [TemplateItem]s that
 * can be applied to any day (or week) to create real tasks. Templates are user data: they
 * are stored in the database, included in backups (format version 2) and never seeded.
 */
data class PlanTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    /** Number of days covered by the template: [DAY] or [WEEK]. */
    val spanDays: Int = DAY,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isWeek: Boolean get() = spanDays == WEEK

    companion object {
        const val DAY = 1
        const val WEEK = 7
    }
}

/**
 * One item of a [PlanTemplate]. Days are offsets from the first day the template is applied
 * to and mirror [Task.date] / [Task.dueDate]: an item has a planned day ([dayOffset]), a
 * deadline ([dueOffset]) or both. Times are floating wall-clock times like in [Task].
 */
data class TemplateItem(
    val id: String,
    val templateId: String,
    val title: String,
    val description: String = "",
    val kind: TaskKind = TaskKind.TASK,
    val priority: Priority = Priority.MEDIUM,
    val categoryId: String? = null,
    val tags: List<String> = emptyList(),
    /** Planned day inside the template (0 = first day); null for deadline-only items. */
    val dayOffset: Int? = 0,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    /** Deadline day, counted from the first day of the template (may lie after the span). */
    val dueOffset: Int? = null,
    val dueTime: LocalTime? = null,
    val reminderOffsetMinutes: Int? = null,
    val goalId: String? = null,
    /** Titles of the subtasks created together with the task. */
    val subtasks: List<String> = emptyList(),
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** The day the item belongs to: its planned day, otherwise its deadline. */
    val anchorOffset: Int get() = dayOffset ?: dueOffset ?: 0

    /** Days between the planned day and the deadline (null without one of them). */
    val dueAfterDays: Int? get() = if (dayOffset != null && dueOffset != null) dueOffset - dayOffset else null
}
