package ir.yadino.domain.backup

import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.validation.Limits
import java.time.Instant

data class ValidationReport(val errors: List<String>, val warnings: List<String>)

/**
 * Structural validation of backup content: identifiers must be unique and non blank,
 * mandatory fields present, and references consistent. References to missing parents
 * are reported as warnings (they are dropped during restore) unless they make the data
 * unusable.
 */
object BackupValidator {

    fun validate(data: BackupData): ValidationReport {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun <T> uniqueIds(name: String, items: List<T>, id: (T) -> String) {
            val ids = HashSet<String>()
            for (item in items) {
                val v = id(item)
                if (v.isBlank()) errors += "$name: empty id"
                else if (!ids.add(v)) errors += "$name: duplicate id $v"
            }
        }
        uniqueIds("categories", data.categories) { it.id }
        uniqueIds("tasks", data.tasks) { it.id }
        uniqueIds("subtasks", data.subtasks) { it.id }
        uniqueIds("completions", data.completions) { it.id }
        uniqueIds("notes", data.notes) { it.id }
        uniqueIds("goals", data.goals) { it.id }
        uniqueIds("milestones", data.milestones) { it.id }
        uniqueIds("habits", data.habits) { it.id }
        uniqueIds("habitLogs", data.habitLogs) { it.id }
        uniqueIds("templates", data.templates) { it.id }
        uniqueIds("templateItems", data.templateItems) { it.id }

        val logKeys = HashSet<String>()
        for (l in data.habitLogs) {
            if (!logKeys.add(l.habitId + "|" + l.date)) errors += "habitLogs: duplicate day ${l.date} for ${l.habitId}"
            if (l.count < 0) errors += "habitLogs: negative count"
        }

        data.tasks.forEach { if (it.title.isBlank()) errors += "tasks: empty title (${it.id})" }
        data.goals.forEach { if (it.title.isBlank()) errors += "goals: empty title (${it.id})" }
        data.habits.forEach {
            if (it.title.isBlank()) errors += "habits: empty title (${it.id})"
            if (it.targetPerDay !in 1..Limits.MAX_HABIT_TARGET) errors += "habits: invalid target (${it.id})"
        }
        data.categories.forEach { if (it.name.isBlank()) errors += "categories: empty name (${it.id})" }
        data.templates.forEach {
            if (it.name.isBlank()) errors += "templates: empty name (${it.id})"
            if (it.spanDays != PlanTemplate.DAY && it.spanDays != PlanTemplate.WEEK) errors += "templates: invalid span (${it.id})"
        }
        data.templateItems.forEach {
            if (it.title.isBlank()) errors += "templateItems: empty title (${it.id})"
            if (it.dayOffset == null && it.dueOffset == null) errors += "templateItems: no day (${it.id})"
        }

        val taskIds = data.tasks.map { it.id }.toSet()
        val goalIds = data.goals.map { it.id }.toSet()
        val habitIds = data.habits.map { it.id }.toSet()
        val categoryIds = data.categories.map { it.id }.toSet()
        val orphanSubtasks = data.subtasks.count { it.taskId !in taskIds }
        if (orphanSubtasks > 0) warnings += "subtasks without task: $orphanSubtasks"
        val orphanMilestones = data.milestones.count { it.goalId !in goalIds }
        if (orphanMilestones > 0) warnings += "milestones without goal: $orphanMilestones"
        val orphanLogs = data.habitLogs.count { it.habitId !in habitIds }
        if (orphanLogs > 0) warnings += "habit logs without habit: $orphanLogs"
        val badCategory = data.tasks.count { it.categoryId != null && it.categoryId !in categoryIds } +
            data.notes.count { it.categoryId != null && it.categoryId !in categoryIds }
        if (badCategory > 0) warnings += "records with unknown category: $badCategory"
        val templateIds = data.templates.map { it.id }.toSet()
        val orphanItems = data.templateItems.count { it.templateId !in templateIds }
        if (orphanItems > 0) warnings += "template items without template: $orphanItems"
        return ValidationReport(errors, warnings)
    }

    /** Removes dangling references so the data can be inserted with foreign keys enabled. */
    fun sanitize(data: BackupData): BackupData {
        val taskIds = data.tasks.map { it.id }.toSet()
        val goalIds = data.goals.map { it.id }.toSet()
        val habitIds = data.habits.map { it.id }.toSet()
        val categoryIds = data.categories.map { it.id }.toSet()
        val noteIds = data.notes.map { it.id }.toSet()
        val templateIds = data.templates.map { it.id }.toSet()
        return data.copy(
            tasks = data.tasks.map { t ->
                t.copy(
                    categoryId = t.categoryId?.takeIf { it in categoryIds },
                    goalId = t.goalId?.takeIf { it in goalIds },
                    sourceNoteId = t.sourceNoteId?.takeIf { it in noteIds },
                )
            },
            subtasks = data.subtasks.filter { it.taskId in taskIds },
            completions = data.completions.filter { it.taskId in taskIds },
            notes = data.notes.map { n ->
                n.copy(categoryId = n.categoryId?.takeIf { it in categoryIds }, goalId = n.goalId?.takeIf { it in goalIds })
            },
            goals = data.goals.map { g -> g.copy(categoryId = g.categoryId?.takeIf { it in categoryIds }) },
            milestones = data.milestones.filter { it.goalId in goalIds },
            habits = data.habits.map { h -> h.copy(goalId = h.goalId?.takeIf { it in goalIds }) },
            habitLogs = data.habitLogs.filter { it.habitId in habitIds },
            templateItems = data.templateItems.filter { it.templateId in templateIds }.map { x ->
                x.copy(categoryId = x.categoryId?.takeIf { it in categoryIds }, goalId = x.goalId?.takeIf { it in goalIds })
            },
        )
    }
}

enum class RestoreMode {
    /** Local data is replaced by the backup. */
    REPLACE,

    /** Records are merged by id; when both sides have a record the most recently updated wins. */
    MERGE,
}

data class RestoreStats(val added: Int, val updated: Int, val kept: Int, val removed: Int)

data class RestorePlan(
    val mode: RestoreMode,
    /** Final data set to be written atomically. */
    val result: BackupData,
    val stats: RestoreStats,
    /** True when this exact backup (same checksum) was already restored on this device. */
    val alreadyRestored: Boolean,
)

/** Computes the outcome of a restore without touching storage (pure, testable). */
object RestorePlanner {

    fun plan(
        local: BackupData,
        incoming: BackupFile,
        mode: RestoreMode,
        lastRestoredChecksum: String?,
    ): RestorePlan {
        val already = lastRestoredChecksum != null && lastRestoredChecksum.equals(incoming.info.checksum, ignoreCase = true)
        val data = BackupValidator.sanitize(incoming.data)
        return when (mode) {
            RestoreMode.REPLACE -> {
                if (keepsLocalTemplates(incoming)) {
                    // The file predates plan templates, so it cannot say anything about them:
                    // keep the local ones instead of deleting them.
                    val result = BackupValidator.sanitize(data.copy(templates = local.templates, templateItems = local.templateItems))
                    val kept = result.templates.size + result.templateItems.size
                    RestorePlan(
                        mode, result,
                        RestoreStats(added = data.totalRecords, updated = 0, kept = kept, removed = local.totalRecords - kept),
                        already,
                    )
                } else {
                    RestorePlan(
                        mode, data,
                        RestoreStats(added = data.totalRecords, updated = 0, kept = 0, removed = local.totalRecords),
                        already,
                    )
                }
            }
            RestoreMode.MERGE -> merge(local, data, already)
        }
    }

    /** True when [file] was written before templates existed (its restore keeps local templates). */
    fun keepsLocalTemplates(file: BackupFile): Boolean = file.info.formatVersion < BackupCodec.TEMPLATES_SINCE

    private class Counter {
        var added = 0
        var updated = 0
        var kept = 0
    }

    private fun <T> mergeById(local: List<T>, incoming: List<T>, id: (T) -> String, updated: (T) -> Instant, c: Counter): List<T> {
        val map = LinkedHashMap<String, T>()
        local.forEach { map[id(it)] = it }
        for (item in incoming) {
            val existing = map[id(item)]
            when {
                existing == null -> { map[id(item)] = item; c.added++ }
                updated(item).isAfter(updated(existing)) -> { map[id(item)] = item; c.updated++ }
                else -> c.kept++
            }
        }
        return map.values.toList()
    }

    private fun merge(local: BackupData, incoming: BackupData, already: Boolean): RestorePlan {
        val c = Counter()
        // Habit logs are unique per (habit, day): merge on that natural key.
        val logs = LinkedHashMap<String, ir.yadino.domain.model.HabitLog>()
        local.habitLogs.forEach { logs[it.habitId + "|" + it.date] = it }
        for (l in incoming.habitLogs) {
            val key = l.habitId + "|" + l.date
            val existing = logs[key]
            when {
                existing == null -> { logs[key] = l; c.added++ }
                l.updatedAt.isAfter(existing.updatedAt) -> { logs[key] = l.copy(id = existing.id); c.updated++ }
                else -> c.kept++
            }
        }
        val merged = BackupData(
            categories = mergeById(local.categories, incoming.categories, { it.id }, { it.updatedAt }, c),
            tasks = mergeById(local.tasks, incoming.tasks, { it.id }, { it.updatedAt }, c),
            subtasks = mergeById(local.subtasks, incoming.subtasks, { it.id }, { it.updatedAt }, c),
            completions = mergeById(local.completions, incoming.completions, { it.id }, { it.completedAt }, c),
            notes = mergeById(local.notes, incoming.notes, { it.id }, { it.updatedAt }, c),
            goals = mergeById(local.goals, incoming.goals, { it.id }, { it.updatedAt }, c),
            milestones = mergeById(local.milestones, incoming.milestones, { it.id }, { it.updatedAt }, c),
            habits = mergeById(local.habits, incoming.habits, { it.id }, { it.updatedAt }, c),
            habitLogs = logs.values.toList(),
            templates = mergeById(local.templates, incoming.templates, { it.id }, { it.updatedAt }, c),
            templateItems = mergeById(local.templateItems, incoming.templateItems, { it.id }, { it.updatedAt }, c),
            // local settings are kept in merge mode
            settings = local.settings,
            dashboard = local.dashboard,
        )
        val sanitized = BackupValidator.sanitize(merged)
        return RestorePlan(RestoreMode.MERGE, sanitized, RestoreStats(c.added, c.updated, c.kept, 0), already)
    }
}
