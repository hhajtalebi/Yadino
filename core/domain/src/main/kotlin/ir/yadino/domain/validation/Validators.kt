package ir.yadino.domain.validation

import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TemplateItem
import ir.yadino.domain.recurrence.RecurrenceEnd
import java.text.Normalizer

/** Validation problems; the UI maps each value to a Persian message. */
enum class ValidationError {
    TITLE_REQUIRED,
    TITLE_TOO_LONG,
    DESCRIPTION_TOO_LONG,
    CONTENT_TOO_LONG,
    END_BEFORE_START,
    TIME_WITHOUT_DATE,
    DUE_BEFORE_DATE,
    REMINDER_WITHOUT_DATE,
    REMINDER_OUT_OF_RANGE,
    RECURRENCE_WITHOUT_DATE,
    RECURRENCE_UNTIL_BEFORE_START,
    TOO_MANY_TAGS,
    TAG_TOO_LONG,
    DEADLINE_BEFORE_START,
    PROGRESS_OUT_OF_RANGE,
    TARGET_OUT_OF_RANGE,
    NAME_REQUIRED,
    NAME_TOO_LONG,
    NAME_DUPLICATE,
    /** A plan template needs at least one item. */
    ITEMS_REQUIRED,
    /** More template items (or subtasks of an item) than allowed. */
    TOO_MANY_ITEMS,
    /** A template item lies outside the days of its template. */
    DAY_OUT_OF_RANGE,
}

object Limits {
    const val TITLE = 200
    const val DESCRIPTION = 10_000
    const val NOTE_CONTENT = 100_000
    const val TAGS = 20
    const val TAG = 40
    const val CATEGORY_NAME = 40
    const val MAX_REMINDER_OFFSET = 7 * 24 * 60
    const val MAX_HABIT_TARGET = 100
    const val TEMPLATE_NAME = 60
    const val TEMPLATE_ITEMS = 100
    const val TEMPLATE_SUBTASKS = 50

    /** Latest deadline of a template item, in days after its planned day. */
    const val TEMPLATE_DUE_OFFSET = 365
}

object Validators {

    private fun title(t: String, out: MutableList<ValidationError>) {
        if (t.isBlank()) out += ValidationError.TITLE_REQUIRED
        if (t.length > Limits.TITLE) out += ValidationError.TITLE_TOO_LONG
    }

    private fun tags(tags: List<String>, out: MutableList<ValidationError>) {
        if (tags.size > Limits.TAGS) out += ValidationError.TOO_MANY_TAGS
        if (tags.any { it.length > Limits.TAG }) out += ValidationError.TAG_TOO_LONG
    }

    fun task(task: Task): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        title(task.title, out)
        if (task.description.length > Limits.DESCRIPTION) out += ValidationError.DESCRIPTION_TOO_LONG
        tags(task.tags, out)
        if (task.date == null && (task.startTime != null || task.endTime != null)) out += ValidationError.TIME_WITHOUT_DATE
        if (task.startTime != null && task.endTime != null && !task.endTime.isAfter(task.startTime)) out += ValidationError.END_BEFORE_START
        if (task.date != null && task.dueDate != null && task.dueDate.isBefore(task.date)) out += ValidationError.DUE_BEFORE_DATE
        task.reminderOffsetMinutes?.let {
            if (task.date == null && task.dueDate == null) out += ValidationError.REMINDER_WITHOUT_DATE
            if (it < 0 || it > Limits.MAX_REMINDER_OFFSET) out += ValidationError.REMINDER_OUT_OF_RANGE
        }
        task.recurrence?.let { rule ->
            val anchor = task.recurrenceStart ?: task.date ?: task.dueDate
            if (anchor == null) out += ValidationError.RECURRENCE_WITHOUT_DATE
            val end = rule.end
            if (end is RecurrenceEnd.Until && anchor != null && end.date.isBefore(anchor)) out += ValidationError.RECURRENCE_UNTIL_BEFORE_START
        }
        return out
    }

    fun note(note: Note): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        if (note.title.length > Limits.TITLE) out += ValidationError.TITLE_TOO_LONG
        if (note.content.length > Limits.NOTE_CONTENT) out += ValidationError.CONTENT_TOO_LONG
        tags(note.tags, out)
        return out
    }

    fun goal(goal: Goal): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        title(goal.title, out)
        if (goal.description.length > Limits.DESCRIPTION) out += ValidationError.DESCRIPTION_TOO_LONG
        if (goal.deadline != null && goal.deadline.isBefore(goal.startDate)) out += ValidationError.DEADLINE_BEFORE_START
        goal.manualProgress?.let { if (it !in 0..100) out += ValidationError.PROGRESS_OUT_OF_RANGE }
        return out
    }

    fun habit(habit: Habit): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        title(habit.title, out)
        if (habit.description.length > Limits.DESCRIPTION) out += ValidationError.DESCRIPTION_TOO_LONG
        if (habit.targetPerDay !in 1..Limits.MAX_HABIT_TARGET) out += ValidationError.TARGET_OUT_OF_RANGE
        val s = habit.schedule
        if (s is HabitSchedule.TimesPerWeek && s.times !in 1..7) out += ValidationError.TARGET_OUT_OF_RANGE
        return out
    }

    fun category(category: Category, existing: List<Category>): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        val name = category.name.trim()
        if (name.isEmpty()) out += ValidationError.NAME_REQUIRED
        if (name.length > Limits.CATEGORY_NAME) out += ValidationError.NAME_TOO_LONG
        if (existing.any { it.id != category.id && it.kind == category.kind && it.name.trim().equals(name, ignoreCase = true) }) {
            out += ValidationError.NAME_DUPLICATE
        }
        return out
    }

    /** Validates a plan template with its items; [others] are the other stored templates. */
    fun template(template: PlanTemplate, items: List<TemplateItem>, others: List<PlanTemplate>): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        val name = template.name.trim()
        if (name.isEmpty()) out += ValidationError.NAME_REQUIRED
        if (name.length > Limits.TEMPLATE_NAME) out += ValidationError.NAME_TOO_LONG
        val key = comparisonKey(name)
        if (others.any { it.id != template.id && comparisonKey(it.name) == key }) out += ValidationError.NAME_DUPLICATE
        if (template.description.length > Limits.DESCRIPTION) out += ValidationError.DESCRIPTION_TOO_LONG
        if (template.spanDays != PlanTemplate.DAY && template.spanDays != PlanTemplate.WEEK) out += ValidationError.DAY_OUT_OF_RANGE
        if (items.isEmpty()) out += ValidationError.ITEMS_REQUIRED
        if (items.size > Limits.TEMPLATE_ITEMS) out += ValidationError.TOO_MANY_ITEMS
        items.forEach { out += templateItem(it, template.spanDays) }
        return out.distinct()
    }

    /** Validates one template item of a template covering [spanDays] days. */
    fun templateItem(item: TemplateItem, spanDays: Int): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        title(item.title, out)
        if (item.description.length > Limits.DESCRIPTION) out += ValidationError.DESCRIPTION_TOO_LONG
        tags(item.tags, out)
        val day = item.dayOffset
        val due = item.dueOffset
        when {
            day == null && due == null -> out += ValidationError.DAY_OUT_OF_RANGE
            // a deadline-only item belongs to a day of the template
            day == null && due != null -> if (due !in 0 until spanDays) out += ValidationError.DAY_OUT_OF_RANGE
            day != null -> {
                if (day !in 0 until spanDays) out += ValidationError.DAY_OUT_OF_RANGE
                if (due != null && due < day) out += ValidationError.DUE_BEFORE_DATE
                if (due != null && due - day > Limits.TEMPLATE_DUE_OFFSET) out += ValidationError.DAY_OUT_OF_RANGE
            }
        }
        if (day == null && (item.startTime != null || item.endTime != null)) out += ValidationError.TIME_WITHOUT_DATE
        if (item.startTime != null && item.endTime != null && !item.endTime.isAfter(item.startTime)) out += ValidationError.END_BEFORE_START
        item.reminderOffsetMinutes?.let { if (it < 0 || it > Limits.MAX_REMINDER_OFFSET) out += ValidationError.REMINDER_OUT_OF_RANGE }
        if (item.subtasks.size > Limits.TEMPLATE_SUBTASKS) out += ValidationError.TOO_MANY_ITEMS
        if (item.subtasks.any { it.length > Limits.TITLE }) out += ValidationError.TITLE_TOO_LONG
        return out
    }

    /**
     * Key for comparing short user texts (names, titles) the way a Persian reader does:
     * Unicode NFC, Arabic Yeh/Kaf as their Persian forms, Persian/Arabic digits as Latin,
     * spaces and zero-width (non-)joiners/marks ignored, case-insensitive.
     */
    fun comparisonKey(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        for (ch in nfc) {
            val c = when (ch) {
                '\u064A', '\u0649' -> '\u06CC'
                '\u0643' -> '\u06A9'
                in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                else -> ch
            }
            if (c.isWhitespace() || c in '\u200B'..'\u200F') continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** Normalises user entered tags: trims, removes '#', drops blanks and duplicates. */
    fun normalizeTags(raw: List<String>): List<String> =
        raw.map { it.trim().trimStart('#').trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
}
