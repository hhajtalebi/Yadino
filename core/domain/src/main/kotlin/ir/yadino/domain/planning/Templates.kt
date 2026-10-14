package ir.yadino.domain.planning

import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.validation.Limits
import ir.yadino.domain.validation.Validators
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Plan templates: building a template from an existing day/week plan and applying a
 * template to a date. Pure functions; the data layer stores the results atomically.
 *
 * Day offsets: in a day template every item is on day 0 and a deadline offset counts days
 * after that day. In a week template offsets name weekdays counted from Saturday
 * (0 = Saturday … 6 = Friday) — independent of the "first day of week" setting, so a
 * template keeps its meaning when that setting changes. A deadline offset of an item with a
 * planned day is relative in the same way (planned offset + days until the deadline).
 */
object PlanTemplates {

    /** Weekday of offset 0 in week templates. */
    val WEEK_ANCHOR: DayOfWeek = DayOfWeek.SATURDAY

    /** Weekday of a week-template day offset (0..6). */
    fun weekdayOf(offset: Int): DayOfWeek = WEEK_ANCHOR.plus(Math.floorMod(offset, 7).toLong())

    /** Week-template day offset of [day]. */
    fun offsetOf(day: DayOfWeek): Int = Math.floorMod(day.value - WEEK_ANCHOR.value, 7)

    /** First day the template is applied to when the user picks [date]: the day itself or the start of its week. */
    fun startFor(template: PlanTemplate, date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
        if (template.isWeek) date.minusDays(Math.floorMod(date.dayOfWeek.value - firstDayOfWeek.value, 7).toLong()) else date

    /**
     * Position (0..span-1) of a day offset inside the target range that starts on a
     * [firstDayOfWeek] (week templates) — used for display order and for applying.
     */
    fun position(template: PlanTemplate, offset: Int, firstDayOfWeek: DayOfWeek): Int =
        if (template.isWeek) Math.floorMod(weekdayOf(offset).value - firstDayOfWeek.value, 7) else offset

    /** Display order of template items: by day as shown to the user, timed items by time, then manual order. */
    fun itemOrder(template: PlanTemplate, firstDayOfWeek: DayOfWeek): Comparator<TemplateItem> =
        compareBy<TemplateItem> { position(template, it.anchorOffset, firstDayOfWeek) }
            .thenBy { it.startTime?.toSecondOfDay() ?: Int.MAX_VALUE }
            .thenBy { it.sortOrder }
            .thenBy { it.title }

    /** Storage order (independent of settings): by offset, time, manual order. */
    val storedOrder: Comparator<TemplateItem> = compareBy<TemplateItem> { it.anchorOffset }
        .thenBy { it.startTime?.toSecondOfDay() ?: Int.MAX_VALUE }
        .thenBy { it.sortOrder }
        .thenBy { it.title }

    /**
     * Creates a template from the stored tasks whose effective day lies in
     * [start] .. [start] + [spanDays] - 1 (the same selection as [PlanCopier]). Items keep
     * kind, priority, category, tags, times, deadline, reminder, goal and subtasks; status
     * and recurrence are dropped. Returns null when the plan is empty.
     */
    fun fromPlan(
        tasks: List<Task>,
        subtasks: List<Subtask>,
        start: LocalDate,
        spanDays: Int,
        name: String,
        now: Instant,
        newId: () -> String,
    ): Pair<PlanTemplate, List<TemplateItem>>? {
        require(spanDays == PlanTemplate.DAY || spanDays == PlanTemplate.WEEK) { "unsupported span $spanDays" }
        val end = start.plusDays(spanDays - 1L)
        val selected = tasks.filter { t ->
            val d = TaskFilters.effectiveDate(t)
            !t.isArchived && t.status != TaskStatus.CANCELLED && d != null && !d.isBefore(start) && !d.isAfter(end)
        }.sortedWith(
            compareBy<Task> { TaskFilters.effectiveDate(it)!!.toEpochDay() }
                .thenBy { it.sortOrder }
                .thenBy { it.startTime?.toSecondOfDay() ?: Int.MAX_VALUE }
                .thenByDescending { it.priority.level }
                .thenBy { it.createdAt },
        ).take(Limits.TEMPLATE_ITEMS)
        if (selected.isEmpty()) return null
        val template = PlanTemplate(newId(), name.trim(), spanDays = spanDays, createdAt = now, updatedAt = now)
        val base: (LocalDate) -> Int = { d -> if (template.isWeek) offsetOf(d.dayOfWeek) else days(start, d) }
        val bySource = subtasks.groupBy { it.taskId }
        val items = selected.mapIndexed { index, t ->
            val date = t.date
            val day = date?.let(base)
            val due = t.dueDate?.let { d ->
                if (date != null) base(date) + days(date, d).coerceIn(0, Limits.TEMPLATE_DUE_OFFSET) else base(d)
            }
            TemplateItem(
                id = newId(), templateId = template.id, title = t.title.trim(), description = t.description,
                kind = t.kind, priority = t.priority, categoryId = t.categoryId, tags = t.tags,
                dayOffset = day,
                startTime = if (day != null) t.startTime else null,
                endTime = if (day != null && t.startTime != null) t.endTime?.takeIf { it.isAfter(t.startTime) } else null,
                dueOffset = due,
                dueTime = if (due != null) t.dueTime else null,
                reminderOffsetMinutes = t.reminderOffsetMinutes?.takeIf { it in 0..Limits.MAX_REMINDER_OFFSET },
                goalId = t.goalId,
                subtasks = cleanSubtasks(bySource[t.id].orEmpty().sortedBy { it.sortOrder }.map { it.title }),
                sortOrder = index, createdAt = now, updatedAt = now,
            )
        }
        return template to items
    }

    /** Trims subtask titles, joins accidental line breaks and drops blanks (within limits). */
    fun cleanSubtasks(titles: List<String>): List<String> = titles
        .map { it.replace('\n', ' ').replace('\r', ' ').trim().take(Limits.TITLE) }
        .filter { it.isNotEmpty() }
        .take(Limits.TEMPLATE_SUBTASKS)

    /** Planned date and deadline an item gets when the template starts on [start]. */
    data class Placement(val date: LocalDate?, val dueDate: LocalDate?) {
        /** The day the created task shows up on. */
        val day: LocalDate get() = date ?: dueDate!!
    }

    fun place(template: PlanTemplate, item: TemplateItem, start: LocalDate, firstDayOfWeek: DayOfWeek): Placement {
        val dayOffset = item.dayOffset
        val date = dayOffset?.let { start.plusDays(position(template, it, firstDayOfWeek).toLong()) }
        val due = item.dueOffset?.let { dueOffset ->
            if (dayOffset != null) {
                start.plusDays(position(template, dayOffset, firstDayOfWeek) + (dueOffset - dayOffset).coerceAtLeast(0).toLong())
            } else {
                start.plusDays(position(template, dueOffset, firstDayOfWeek).toLong())
            }
        }
        return Placement(date, due ?: if (date == null) start else null)
    }

    /** One row of the "apply template" preview. */
    data class PreviewItem(
        val item: TemplateItem,
        val placement: Placement,
        /** An open or finished task with the same title already exists on that day. */
        val isDuplicate: Boolean,
    ) {
        val date: LocalDate get() = placement.day
    }

    /**
     * Where the items of [template] land when the user applies it to [date] (the day, or the
     * week containing it), and which of them already exist there (same title on the same
     * effective day, ignoring archived and cancelled tasks) — so applying the same template
     * twice by accident does not duplicate the plan.
     */
    fun preview(
        template: PlanTemplate,
        items: List<TemplateItem>,
        date: LocalDate,
        firstDayOfWeek: DayOfWeek,
        existing: List<Task>,
    ): List<PreviewItem> {
        val start = startFor(template, date, firstDayOfWeek)
        val taken = HashSet<Pair<LocalDate, String>>()
        existing.forEach { t ->
            if (t.isArchived || t.status == TaskStatus.CANCELLED) return@forEach
            val d = TaskFilters.effectiveDate(t) ?: return@forEach
            taken += d to titleKey(t.title)
        }
        return items.map { item ->
            val p = place(template, item, start, firstDayOfWeek)
            PreviewItem(item, p, (p.day to titleKey(item.title)) in taken)
        }.sortedWith(
            compareBy<PreviewItem> { it.date }
                .thenBy { it.item.startTime?.toSecondOfDay() ?: Int.MAX_VALUE }
                .thenBy { it.item.sortOrder },
        )
    }

    data class Applied(val start: LocalDate, val tasks: List<Task>, val subtasks: List<Subtask>, val skipped: Int)

    /**
     * Creates tasks (with their subtasks) from the items of [template] for [date] (the day,
     * or the week containing it). With [skipDuplicates] items that already exist on their
     * day are not created again. New tasks are appended after the manual order of each day.
     */
    fun apply(
        template: PlanTemplate,
        items: List<TemplateItem>,
        date: LocalDate,
        firstDayOfWeek: DayOfWeek,
        existing: List<Task>,
        skipDuplicates: Boolean,
        now: Instant,
        newId: () -> String,
    ): Applied {
        val nextOrder = HashMap<LocalDate, Int>()
        existing.forEach { t -> t.date?.let { d -> nextOrder[d] = maxOf(nextOrder[d] ?: 0, t.sortOrder) } }
        val tasks = ArrayList<Task>()
        val subtasks = ArrayList<Subtask>()
        var skipped = 0
        for (p in preview(template, items, date, firstDayOfWeek, existing)) {
            if (skipDuplicates && p.isDuplicate) {
                skipped++
                continue
            }
            val item = p.item
            val planned = p.placement.date
            val order = if (planned != null) {
                val next = (nextOrder[planned] ?: 0) + 1
                nextOrder[planned] = next
                next
            } else 0
            val task = Task(
                id = newId(), title = item.title.trim(), description = item.description, kind = item.kind,
                priority = item.priority, status = TaskStatus.TODO, categoryId = item.categoryId, tags = item.tags,
                date = planned,
                startTime = if (planned != null) item.startTime else null,
                endTime = if (planned != null && item.startTime != null) item.endTime else null,
                dueDate = p.placement.dueDate,
                dueTime = if (p.placement.dueDate != null) item.dueTime else null,
                reminderOffsetMinutes = item.reminderOffsetMinutes,
                goalId = item.goalId, sortOrder = order, createdAt = now, updatedAt = now,
            )
            tasks += task
            cleanSubtasks(item.subtasks).forEachIndexed { i, title ->
                subtasks += Subtask(newId(), task.id, title, false, i, now, now)
            }
        }
        return Applied(startFor(template, date, firstDayOfWeek), tasks, subtasks, skipped)
    }

    /**
     * Changes the span of a template being edited. Day → week puts every item on
     * [firstDayOfWeek]; week → day moves every item to the single day. Deadlines keep their
     * distance to the planned day.
     */
    fun changeSpan(items: List<TemplateItem>, toWeek: Boolean, firstDayOfWeek: DayOfWeek): List<TemplateItem> {
        val target = if (toWeek) offsetOf(firstDayOfWeek) else 0
        return items.map { item ->
            val day = item.dayOffset
            if (day != null) item.copy(dayOffset = target, dueOffset = item.dueOffset?.let { target + (it - day).coerceAtLeast(0) })
            else item.copy(dueOffset = target)
        }
    }

    /** Copy of a template (and its items) under a new name, e.g. as a starting point. */
    fun duplicate(
        template: PlanTemplate,
        items: List<TemplateItem>,
        name: String,
        now: Instant,
        newId: () -> String,
    ): Pair<PlanTemplate, List<TemplateItem>> {
        val copy = template.copy(id = newId(), name = name.trim().take(Limits.TEMPLATE_NAME), createdAt = now, updatedAt = now)
        return copy to items.map { it.copy(id = newId(), templateId = copy.id, createdAt = now, updatedAt = now) }
    }

    /** Comparison key of a title (see [Validators.comparisonKey]). */
    fun titleKey(title: String): String = Validators.comparisonKey(title)

    private fun days(from: LocalDate, to: LocalDate): Int = ChronoUnit.DAYS.between(from, to).toInt()
}
