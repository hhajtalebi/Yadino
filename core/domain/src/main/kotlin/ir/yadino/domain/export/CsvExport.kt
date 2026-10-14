package ir.yadino.domain.export

import ir.yadino.calendar.CalendarFormatter
import ir.yadino.calendar.JalaliDate
import ir.yadino.calendar.NumeralStyle
import ir.yadino.calendar.PersianNames
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.goals.GoalProgressCalculator
import ir.yadino.domain.habits.HabitStats
import ir.yadino.domain.model.GoalStatus
import ir.yadino.domain.model.GoalTerm
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.notes.Checklist
import ir.yadino.domain.recurrence.RecurrenceEngine
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** RFC 4180 CSV for spreadsheet apps (Excel, LibreOffice Calc, Google Sheets). */
object Csv {
    /** UTF-8 byte order mark: lets Excel recognise UTF-8 (Persian text) when opening the file. */
    const val BOM = "\uFEFF"
    const val EOL = "\r\n"

    /** Quotes a field when needed; quotes inside are doubled. */
    fun field(value: String): String {
        val quote = value.isNotEmpty() &&
            (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value.first().isWhitespace() || value.last().isWhitespace())
        return if (quote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    /**
     * Free text typed by the user. Spreadsheet apps run cells starting with = + - @ (or a tab /
     * carriage return) as formulas, so such text gets a leading apostrophe (CSV injection).
     */
    fun text(value: String): String = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

    fun row(fields: List<String>): String = fields.joinToString(",") { field(it) }

    /** A whole file: BOM, header and rows, CRLF line endings. */
    fun table(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder(BOM).append(row(header)).append(EOL)
        for (r in rows) {
            require(r.size == header.size) { "row has ${r.size} fields, header has ${header.size}" }
            sb.append(row(r)).append(EOL)
        }
        return sb.toString()
    }
}

/**
 * Spreadsheet export of all records (tasks, notes, goals, habits, habit check-ins) for viewing
 * and analysis. Dates are given in both calendars (Jalali `1405/07/22`, Gregorian ISO
 * `2026-10-14`), numbers use Latin digits so spreadsheets can calculate with them, and labels
 * are Persian. This is not a backup format: restoring always uses the JSON backup.
 */
object CsvExporter {
    data class Table(val fileName: String, val content: String, val rows: Int)

    private const val YES = "بله"
    private const val NO = "خیر"

    fun export(data: BackupData, today: LocalDate, zone: ZoneId, firstDayOfWeek: DayOfWeek): List<Table> {
        val categories = data.categories.associate { it.id to it.name }
        val goalTitles = data.goals.associate { it.id to it.title }
        val habitTitles = data.habits.associate { it.id to it.title }
        val summaries = data.habits.associate { it.id to HabitStats.summarize(it, data.habitLogs, today, firstDayOfWeek) }

        val tasks = data.tasks.sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.startTime ?: LocalTime.MAX }, { it.sortOrder }, { it.title }))
        val taskRows = tasks.map { t ->
            val subtasks = data.subtasks.filter { it.taskId == t.id }.sortedBy { it.sortOrder }
            listOf(
                t.id, Csv.text(t.title), Csv.text(t.description), kind(t.kind), status(t.status), priority(t.priority),
                Csv.text(t.categoryId?.let { categories[it] }.orEmpty()), Csv.text(t.tags.joinToString("، ")),
                jalali(t.date), gregorian(t.date), time(t.startTime), time(t.endTime),
                jalali(t.dueDate), gregorian(t.dueDate), time(t.dueTime),
                t.reminderOffsetMinutes?.toString().orEmpty(),
                t.recurrence?.let { RecurrenceEngine.describe(it, t.recurrenceStart ?: t.date, NumeralStyle.LATIN) }.orEmpty(),
                Csv.text(t.goalId?.let { goalTitles[it] }.orEmpty()),
                subtasks.joinToString("\n") { (if (it.isDone) Checklist.CHECKED_SYMBOL else Checklist.UNCHECKED_SYMBOL) + " " + it.title },
                yesNo(t.isArchived), timestamp(t.completedAt, zone), timestamp(t.createdAt, zone), timestamp(t.updatedAt, zone),
            )
        }
        val taskHeader = listOf(
            "شناسه", "عنوان", "توضیحات", "نوع", "وضعیت", "اولویت", "دسته", "برچسب‌ها",
            "تاریخ (شمسی)", "تاریخ (میلادی)", "ساعت شروع", "ساعت پایان",
            "موعد (شمسی)", "موعد (میلادی)", "ساعت موعد", "یادآوری (دقیقه قبل)", "تکرار", "هدف", "زیرکارها",
            "بایگانی", "زمان انجام", "زمان ایجاد", "آخرین ویرایش",
        )

        val notes = data.notes.sortedByDescending { it.updatedAt }
        val noteRows = notes.map { n ->
            val progress = Checklist.progress(n.content)
            listOf(
                n.id, Csv.text(n.title), Csv.text(Checklist.displayText(n.content)),
                Csv.text(n.categoryId?.let { categories[it] }.orEmpty()), Csv.text(n.tags.joinToString("، ")), color(n.color),
                yesNo(n.isPinned), yesNo(n.isArchived), Csv.text(n.goalId?.let { goalTitles[it] }.orEmpty()),
                progress?.let { "${it.first}/${it.second}" }.orEmpty(),
                timestamp(n.createdAt, zone), timestamp(n.updatedAt, zone),
            )
        }
        val noteHeader = listOf(
            "شناسه", "عنوان", "متن", "دسته", "برچسب‌ها", "رنگ", "سنجاق‌شده", "بایگانی", "هدف", "فهرست تیک‌دار (انجام‌شده/کل)",
            "زمان ایجاد", "آخرین ویرایش",
        )

        val goals = data.goals.sortedWith(compareBy({ it.deadline ?: LocalDate.MAX }, { it.title }))
        val goalRows = goals.map { g ->
            val milestones = data.milestones.filter { it.goalId == g.id }.sortedBy { it.sortOrder }
            val linkedTasks = data.tasks.filter { it.goalId == g.id }
            val linkedHabits = data.habits.filter { it.goalId == g.id && !it.isArchived }
            val progress = GoalProgressCalculator.calculate(g, milestones, linkedTasks, linkedHabits.mapNotNull { summaries[it.id]?.rate30 }, today)
            listOf(
                g.id, Csv.text(g.title), Csv.text(g.description), term(g.term), priority(g.priority), goalStatus(g.status),
                Csv.text(g.categoryId?.let { categories[it] }.orEmpty()),
                jalali(g.startDate), gregorian(g.startDate), jalali(g.deadline), gregorian(g.deadline),
                progress.percent.toString(),
                milestones.joinToString("\n") { (if (it.isDone) Checklist.CHECKED_SYMBOL else Checklist.UNCHECKED_SYMBOL) + " " + it.title },
                if (progress.tasksTotal > 0) "${progress.tasksDone}/${progress.tasksTotal}" else "",
                Csv.text(linkedHabits.joinToString("، ") { it.title }),
                yesNo(g.isArchived), timestamp(g.completedAt, zone), timestamp(g.createdAt, zone),
            )
        }
        val goalHeader = listOf(
            "شناسه", "عنوان", "توضیحات", "بازه", "اولویت", "وضعیت", "دسته",
            "شروع (شمسی)", "شروع (میلادی)", "موعد (شمسی)", "موعد (میلادی)", "پیشرفت (درصد)",
            "نقاط عطف", "کارهای مرتبط (انجام‌شده/کل)", "عادت‌های مرتبط", "بایگانی", "زمان تکمیل", "زمان ایجاد",
        )

        val habits = data.habits.sortedWith(compareBy({ it.sortOrder }, { it.title }))
        val habitRows = habits.map { h ->
            val s = summaries.getValue(h.id)
            listOf(
                h.id, Csv.text(h.title), Csv.text(h.description), schedule(h.schedule, firstDayOfWeek), h.targetPerDay.toString(),
                time(h.reminderTime), Csv.text(h.goalId?.let { goalTitles[it] }.orEmpty()),
                jalali(h.startDate), gregorian(h.startDate),
                s.currentStreak.toString(), s.bestStreak.toString(), if (s.streakInWeeks) "هفته" else "روز",
                Math.round(s.rate30 * 100).toString(), yesNo(h.isArchived), timestamp(h.createdAt, zone),
            )
        }
        val habitHeader = listOf(
            "شناسه", "عنوان", "توضیحات", "برنامه", "تعداد در روز", "ساعت یادآوری", "هدف", "شروع (شمسی)", "شروع (میلادی)",
            "زنجیره فعلی", "بهترین زنجیره", "واحد زنجیره", "انجام در ۳۰ روز اخیر (درصد)", "بایگانی", "زمان ایجاد",
        )

        val logs = data.habitLogs.filter { it.count > 0 }.sortedWith(compareBy({ it.date }, { habitTitles[it.habitId].orEmpty() }))
        val logRows = logs.map { l ->
            listOf(
                Csv.text(habitTitles[l.habitId].orEmpty()), l.habitId, jalali(l.date), gregorian(l.date),
                PersianNames.weekday(l.date.dayOfWeek), l.count.toString(),
            )
        }
        val logHeader = listOf("عادت", "شناسه عادت", "تاریخ (شمسی)", "تاریخ (میلادی)", "روز هفته", "تعداد")

        return listOf(
            Table("tasks.csv", Csv.table(taskHeader, taskRows), taskRows.size),
            Table("notes.csv", Csv.table(noteHeader, noteRows), noteRows.size),
            Table("goals.csv", Csv.table(goalHeader, goalRows), goalRows.size),
            Table("habits.csv", Csv.table(habitHeader, habitRows), habitRows.size),
            Table("habit_logs.csv", Csv.table(logHeader, logRows), logRows.size),
        )
    }

    // ---- value formatting (Latin digits so spreadsheets can sort and calculate) ---------------
    fun jalali(date: LocalDate?): String =
        date?.let { CalendarFormatter.formatJalali(JalaliDate.from(it), "yyyy/MM/dd", NumeralStyle.LATIN) }.orEmpty()

    fun gregorian(date: LocalDate?): String = date?.toString().orEmpty()

    fun time(time: LocalTime?): String = time?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) }.orEmpty()

    /** Jalali date and 24-hour time in [zone], e.g. `1405/07/22 14:05`. */
    fun timestamp(instant: Instant?, zone: ZoneId): String {
        val t = instant?.atZone(zone) ?: return ""
        return jalali(t.toLocalDate()) + " " + time(t.toLocalTime())
    }

    private fun yesNo(value: Boolean) = if (value) YES else NO

    private fun kind(k: TaskKind) = when (k) {
        TaskKind.TASK -> "کار"
        TaskKind.EVENT -> "رویداد"
        TaskKind.REMINDER -> "یادآور"
    }

    private fun status(s: TaskStatus) = when (s) {
        TaskStatus.TODO -> "انجام‌نشده"
        TaskStatus.IN_PROGRESS -> "در حال انجام"
        TaskStatus.DONE -> "انجام‌شده"
        TaskStatus.CANCELLED -> "لغوشده"
    }

    private fun priority(p: Priority) = when (p) {
        Priority.LOW -> "کم"
        Priority.MEDIUM -> "متوسط"
        Priority.HIGH -> "بالا"
        Priority.URGENT -> "فوری"
    }

    private fun term(t: GoalTerm) = if (t == GoalTerm.SHORT) "کوتاه‌مدت" else "بلندمدت"

    private fun goalStatus(s: GoalStatus) = when (s) {
        GoalStatus.ACTIVE -> "فعال"
        GoalStatus.PAUSED -> "متوقف"
        GoalStatus.COMPLETED -> "تکمیل‌شده"
    }

    private fun color(c: NoteColor) = when (c) {
        NoteColor.DEFAULT -> "پیش‌فرض"
        NoteColor.CREAM -> "کرم"
        NoteColor.GREEN -> "سبز"
        NoteColor.GOLD -> "طلایی"
        NoteColor.ROSE -> "گلبهی"
        NoteColor.SKY -> "آبی"
        NoteColor.LAVENDER -> "یاسی"
        NoteColor.SAND -> "شنی"
    }

    /** Habit schedule in words; specific days are listed from [firstDayOfWeek]. */
    fun schedule(s: HabitSchedule, firstDayOfWeek: DayOfWeek): String = when (s) {
        HabitSchedule.Daily -> "هر روز"
        is HabitSchedule.SpecificDays -> {
            val order = (0..6).map { firstDayOfWeek.plus(it.toLong()) }
            "روزهای " + order.filter { it in s.days }.joinToString("، ") { PersianNames.weekday(it) }
        }
        is HabitSchedule.TimesPerWeek -> "${s.times} بار در هفته"
    }
}
