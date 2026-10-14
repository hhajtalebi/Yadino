package ir.yadino.domain.backup

import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonArray
import ir.yadino.domain.json.JsonObject
import ir.yadino.domain.json.JsonString
import ir.yadino.domain.json.JsonValue
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.CategoryKind
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.GoalTerm
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.recurrence.RecurrenceRule
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Everything that is included in a backup. */
data class BackupData(
    val categories: List<Category> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val subtasks: List<Subtask> = emptyList(),
    val completions: List<TaskCompletion> = emptyList(),
    val notes: List<Note> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val habitLogs: List<HabitLog> = emptyList(),
    /** Essential settings ([ir.yadino.domain.settings.SettingsCodec] representation). */
    val settings: Map<String, String> = emptyMap(),
    val dashboard: String? = null,
) {
    val totalRecords: Int
        get() = categories.size + tasks.size + subtasks.size + completions.size + notes.size + goals.size +
            milestones.size + habits.size + habitLogs.size

    fun counts(): BackupCounts = BackupCounts(
        tasks = tasks.size, subtasks = subtasks.size, notes = notes.size, goals = goals.size,
        milestones = milestones.size, habits = habits.size, habitLogs = habitLogs.size,
        categories = categories.size, completions = completions.size,
    )
}

data class BackupCounts(
    val tasks: Int, val subtasks: Int, val notes: Int, val goals: Int, val milestones: Int,
    val habits: Int, val habitLogs: Int, val categories: Int, val completions: Int,
)

/** Metadata stored next to the data. */
data class BackupInfo(
    val formatVersion: Int,
    val appVersion: String,
    val createdAt: Instant,
    val checksum: String,
    val counts: BackupCounts,
)

data class BackupFile(val info: BackupInfo, val data: BackupData)

/** Reasons a backup file can be rejected. */
enum class BackupError {
    EMPTY_FILE,
    NOT_JSON,
    NOT_A_YADINO_BACKUP,
    NEWER_FORMAT,
    CHECKSUM_MISMATCH,
    INVALID_CONTENT,
}

sealed interface BackupReadResult {
    data class Success(val file: BackupFile, val warnings: List<String>) : BackupReadResult
    data class Failure(val error: BackupError, val detail: String? = null) : BackupReadResult
}

/**
 * Versioned JSON backup format.
 *
 * ```
 * { "format": "yadino-backup", "formatVersion": 1, "appVersion": "1.0.0",
 *   "createdAt": "2026-10-14T08:00:00Z", "counts": {…}, "checksum": "<sha256 of data>",
 *   "data": { "categories": […], "tasks": […], … } }
 * ```
 * Dates are ISO-8601 (`2026-10-14`), times `HH:mm`, instants ISO-8601 UTC. The checksum
 * is the SHA-256 of the compact serialisation of `data`, so any modification or
 * truncation of the file is detected before restoring.
 */
object BackupCodec {
    const val FORMAT = "yadino-backup"
    const val FORMAT_VERSION = 1
    const val FILE_EXTENSION = "yadino.json"

    fun encode(data: BackupData, appVersion: String, createdAt: Instant, pretty: Boolean = false): String {
        val dataJson = encodeData(data)
        val checksum = sha256(Json.stringify(dataJson))
        val c = data.counts()
        val root = JsonObject(linkedMapOf(
            "format" to JsonString(FORMAT),
            "formatVersion" to Json.toJsonValue(FORMAT_VERSION),
            "appVersion" to JsonString(appVersion),
            "createdAt" to JsonString(createdAt.toString()),
            "counts" to Json.toJsonValue(linkedMapOf(
                "tasks" to c.tasks, "subtasks" to c.subtasks, "notes" to c.notes, "goals" to c.goals,
                "milestones" to c.milestones, "habits" to c.habits, "habitLogs" to c.habitLogs,
                "categories" to c.categories, "completions" to c.completions,
            )),
            "checksum" to JsonString(checksum),
            "data" to dataJson,
        ))
        return Json.stringify(root, pretty)
    }

    fun decode(text: String): BackupReadResult {
        if (text.isBlank()) return BackupReadResult.Failure(BackupError.EMPTY_FILE)
        val root = try {
            Json.parse(text)
        } catch (e: Exception) {
            return BackupReadResult.Failure(BackupError.NOT_JSON, e.message)
        }
        if (root !is JsonObject || root.string("format") != FORMAT) {
            return BackupReadResult.Failure(BackupError.NOT_A_YADINO_BACKUP)
        }
        val version = root.int("formatVersion") ?: return BackupReadResult.Failure(BackupError.NOT_A_YADINO_BACKUP)
        if (version > FORMAT_VERSION) return BackupReadResult.Failure(BackupError.NEWER_FORMAT, version.toString())
        if (version < 1) return BackupReadResult.Failure(BackupError.NOT_A_YADINO_BACKUP)
        val dataJson = root.obj("data") ?: return BackupReadResult.Failure(BackupError.INVALID_CONTENT, "data")
        val expected = root.string("checksum")
        if (expected == null || !expected.equals(sha256(Json.stringify(dataJson)), ignoreCase = true)) {
            return BackupReadResult.Failure(BackupError.CHECKSUM_MISMATCH)
        }
        val data = try {
            decodeData(dataJson)
        } catch (e: Exception) {
            return BackupReadResult.Failure(BackupError.INVALID_CONTENT, e.message)
        }
        val problems = BackupValidator.validate(data)
        if (problems.errors.isNotEmpty()) {
            return BackupReadResult.Failure(BackupError.INVALID_CONTENT, problems.errors.joinToString("; "))
        }
        val createdAt = root.string("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        val info = BackupInfo(version, root.string("appVersion") ?: "?", createdAt, expected, data.counts())
        return BackupReadResult.Success(BackupFile(info, data), problems.warnings)
    }

    fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    // ---------------------------------------------------------------- encoding
    private fun d(v: LocalDate?): Any? = v?.toString()
    private fun t(v: LocalTime?): Any? = v?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) }
    private fun i(v: Instant?): Any? = v?.toString()

    private fun encodeData(data: BackupData): JsonObject = Json.toJsonValue(linkedMapOf(
        "categories" to data.categories.map { c ->
            linkedMapOf("id" to c.id, "name" to c.name, "color" to c.color.name, "kind" to c.kind.name,
                "isDefault" to c.isDefault, "sortOrder" to c.sortOrder, "createdAt" to i(c.createdAt), "updatedAt" to i(c.updatedAt))
        },
        "tasks" to data.tasks.map { x ->
            linkedMapOf("id" to x.id, "title" to x.title, "description" to x.description, "kind" to x.kind.name,
                "priority" to x.priority.level, "status" to x.status.name, "categoryId" to x.categoryId, "tags" to x.tags,
                "date" to d(x.date), "startTime" to t(x.startTime), "endTime" to t(x.endTime),
                "dueDate" to d(x.dueDate), "dueTime" to t(x.dueTime), "reminderOffset" to x.reminderOffsetMinutes,
                "recurrence" to x.recurrence?.encode(), "recurrenceStart" to d(x.recurrenceStart),
                "goalId" to x.goalId, "sourceNoteId" to x.sourceNoteId, "sortOrder" to x.sortOrder,
                "isArchived" to x.isArchived, "completedAt" to i(x.completedAt),
                "createdAt" to i(x.createdAt), "updatedAt" to i(x.updatedAt))
        },
        "subtasks" to data.subtasks.map { s ->
            linkedMapOf("id" to s.id, "taskId" to s.taskId, "title" to s.title, "isDone" to s.isDone,
                "sortOrder" to s.sortOrder, "createdAt" to i(s.createdAt), "updatedAt" to i(s.updatedAt))
        },
        "completions" to data.completions.map { c ->
            linkedMapOf("id" to c.id, "taskId" to c.taskId, "occurrenceDate" to d(c.occurrenceDate), "completedAt" to i(c.completedAt))
        },
        "notes" to data.notes.map { n ->
            linkedMapOf("id" to n.id, "title" to n.title, "content" to n.content, "color" to n.color.name,
                "categoryId" to n.categoryId, "tags" to n.tags, "isPinned" to n.isPinned, "isArchived" to n.isArchived,
                "goalId" to n.goalId, "createdAt" to i(n.createdAt), "updatedAt" to i(n.updatedAt))
        },
        "goals" to data.goals.map { g ->
            linkedMapOf("id" to g.id, "title" to g.title, "description" to g.description, "term" to g.term.name,
                "priority" to g.priority.level, "status" to g.status.name, "isArchived" to g.isArchived,
                "startDate" to d(g.startDate), "deadline" to d(g.deadline), "color" to g.color.name,
                "categoryId" to g.categoryId, "manualProgress" to g.manualProgress, "completedAt" to i(g.completedAt),
                "createdAt" to i(g.createdAt), "updatedAt" to i(g.updatedAt))
        },
        "milestones" to data.milestones.map { m ->
            linkedMapOf("id" to m.id, "goalId" to m.goalId, "title" to m.title, "isDone" to m.isDone,
                "dueDate" to d(m.dueDate), "sortOrder" to m.sortOrder, "completedAt" to i(m.completedAt),
                "createdAt" to i(m.createdAt), "updatedAt" to i(m.updatedAt))
        },
        "habits" to data.habits.map { h ->
            val (type, days, times) = HabitScheduleCodec.encode(h.schedule)
            linkedMapOf("id" to h.id, "title" to h.title, "description" to h.description, "color" to h.color.name,
                "icon" to h.icon, "scheduleType" to type, "scheduleDays" to days, "scheduleTimes" to times,
                "targetPerDay" to h.targetPerDay, "reminderTime" to t(h.reminderTime), "goalId" to h.goalId,
                "startDate" to d(h.startDate), "isArchived" to h.isArchived, "sortOrder" to h.sortOrder,
                "createdAt" to i(h.createdAt), "updatedAt" to i(h.updatedAt))
        },
        "habitLogs" to data.habitLogs.map { l ->
            linkedMapOf("id" to l.id, "habitId" to l.habitId, "date" to d(l.date), "count" to l.count, "updatedAt" to i(l.updatedAt))
        },
        "settings" to data.settings.toSortedMap(),
        "dashboard" to data.dashboard,
    )) as JsonObject

    // ---------------------------------------------------------------- decoding
    private fun JsonObject.req(key: String): String = string(key) ?: throw IllegalArgumentException("missing '$key'")
    private fun JsonObject.date(key: String): LocalDate? = string(key)?.let { LocalDate.parse(it) }
    private fun JsonObject.time(key: String): LocalTime? = string(key)?.let { LocalTime.parse(it) }
    private fun JsonObject.instant(key: String): Instant? = string(key)?.let { Instant.parse(it) }
    private fun JsonObject.reqInstant(key: String): Instant = instant(key) ?: throw IllegalArgumentException("missing '$key'")
    private fun JsonObject.strings(key: String): List<String> = (array(key)?.items ?: emptyList()).mapNotNull { (it as? JsonString)?.value }
    private inline fun <reified E : Enum<E>> JsonObject.enum(key: String, default: E): E =
        string(key)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private fun list(data: JsonObject, key: String): List<JsonObject> =
        (data.array(key) ?: JsonArray(emptyList())).items.map { it as? JsonObject ?: throw IllegalArgumentException("$key: object expected") }

    private fun decodeData(o: JsonObject): BackupData = BackupData(
        categories = list(o, "categories").map { c ->
            Category(c.req("id"), c.req("name"), c.enum("color", NoteColor.GREEN), c.enum("kind", CategoryKind.GENERAL),
                c.bool("isDefault") ?: false, c.int("sortOrder") ?: 0, c.reqInstant("createdAt"), c.reqInstant("updatedAt"))
        },
        tasks = list(o, "tasks").map { x ->
            Task(
                id = x.req("id"), title = x.req("title"), description = x.string("description") ?: "",
                kind = x.enum("kind", TaskKind.TASK), priority = Priority.fromLevel(x.int("priority") ?: 1),
                status = x.enum("status", TaskStatus.TODO), categoryId = x.string("categoryId"), tags = x.strings("tags"),
                date = x.date("date"), startTime = x.time("startTime"), endTime = x.time("endTime"),
                dueDate = x.date("dueDate"), dueTime = x.time("dueTime"), reminderOffsetMinutes = x.int("reminderOffset"),
                recurrence = x.string("recurrence")?.let { r -> RecurrenceRule.decode(r) ?: throw IllegalArgumentException("bad recurrence") },
                recurrenceStart = x.date("recurrenceStart"), goalId = x.string("goalId"), sourceNoteId = x.string("sourceNoteId"),
                sortOrder = x.int("sortOrder") ?: 0, isArchived = x.bool("isArchived") ?: false, completedAt = x.instant("completedAt"),
                createdAt = x.reqInstant("createdAt"), updatedAt = x.reqInstant("updatedAt"),
            )
        },
        subtasks = list(o, "subtasks").map { s ->
            Subtask(s.req("id"), s.req("taskId"), s.req("title"), s.bool("isDone") ?: false, s.int("sortOrder") ?: 0,
                s.reqInstant("createdAt"), s.reqInstant("updatedAt"))
        },
        completions = list(o, "completions").map { c ->
            TaskCompletion(c.req("id"), c.req("taskId"), c.date("occurrenceDate") ?: throw IllegalArgumentException("occurrenceDate"),
                c.reqInstant("completedAt"))
        },
        notes = list(o, "notes").map { n ->
            Note(n.req("id"), n.string("title") ?: "", n.string("content") ?: "", n.enum("color", NoteColor.DEFAULT),
                n.string("categoryId"), n.strings("tags"), n.bool("isPinned") ?: false, n.bool("isArchived") ?: false,
                n.string("goalId"), n.reqInstant("createdAt"), n.reqInstant("updatedAt"))
        },
        goals = list(o, "goals").map { g ->
            Goal(
                id = g.req("id"), title = g.req("title"), description = g.string("description") ?: "",
                term = g.enum("term", GoalTerm.SHORT), priority = Priority.fromLevel(g.int("priority") ?: 1),
                status = g.enum("status", GoalStatus.ACTIVE), isArchived = g.bool("isArchived") ?: false,
                startDate = g.date("startDate") ?: throw IllegalArgumentException("startDate"), deadline = g.date("deadline"),
                color = g.enum("color", NoteColor.GREEN), categoryId = g.string("categoryId"),
                manualProgress = g.int("manualProgress"), completedAt = g.instant("completedAt"),
                createdAt = g.reqInstant("createdAt"), updatedAt = g.reqInstant("updatedAt"),
            )
        },
        milestones = list(o, "milestones").map { m ->
            Milestone(m.req("id"), m.req("goalId"), m.req("title"), m.bool("isDone") ?: false, m.date("dueDate"),
                m.int("sortOrder") ?: 0, m.instant("completedAt"), m.reqInstant("createdAt"), m.reqInstant("updatedAt"))
        },
        habits = list(o, "habits").map { h ->
            Habit(
                id = h.req("id"), title = h.req("title"), description = h.string("description") ?: "",
                color = h.enum("color", NoteColor.GREEN), icon = h.string("icon") ?: "check",
                schedule = HabitScheduleCodec.decode(h.string("scheduleType") ?: "DAILY", h.int("scheduleDays") ?: 0, h.int("scheduleTimes") ?: 0),
                targetPerDay = h.int("targetPerDay") ?: 1, reminderTime = h.time("reminderTime"), goalId = h.string("goalId"),
                startDate = h.date("startDate") ?: throw IllegalArgumentException("startDate"),
                isArchived = h.bool("isArchived") ?: false, sortOrder = h.int("sortOrder") ?: 0,
                createdAt = h.reqInstant("createdAt"), updatedAt = h.reqInstant("updatedAt"),
            )
        },
        habitLogs = list(o, "habitLogs").map { l ->
            HabitLog(l.req("id"), l.req("habitId"), l.date("date") ?: throw IllegalArgumentException("date"),
                l.int("count") ?: 0, l.reqInstant("updatedAt"))
        },
        settings = (o.obj("settings")?.members ?: emptyMap<String, JsonValue>())
            .mapNotNull { (k, v) -> (v as? JsonString)?.let { k to it.value } }.toMap(),
        dashboard = o.string("dashboard"),
    )
}

/** Compact representation of [HabitSchedule]: type + weekday bit mask + times per week. */
object HabitScheduleCodec {
    data class Encoded(val type: String, val daysMask: Int, val times: Int)

    fun encode(s: HabitSchedule): Encoded = when (s) {
        HabitSchedule.Daily -> Encoded("DAILY", 0, 0)
        is HabitSchedule.SpecificDays -> Encoded("DAYS", s.days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }, 0)
        is HabitSchedule.TimesPerWeek -> Encoded("WEEKLY", 0, s.times)
    }

    fun decode(type: String, mask: Int, times: Int): HabitSchedule = when (type) {
        "DAYS" -> {
            val days = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.map(DayOfWeek::of).toSet()
            if (days.isEmpty()) HabitSchedule.Daily else HabitSchedule.SpecificDays(days)
        }
        "WEEKLY" -> HabitSchedule.TimesPerWeek(times.coerceIn(1, 7))
        else -> HabitSchedule.Daily
    }
}
