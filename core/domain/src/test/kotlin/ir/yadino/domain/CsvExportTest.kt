package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.export.Csv
import ir.yadino.domain.export.CsvExporter
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.recurrence.Frequency
import ir.yadino.domain.recurrence.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

class CsvExportTest {
    private val zone = ZoneId.of("Asia/Tehran")
    private val today = j(1405, 7, 22)

    /** Minimal RFC 4180 reader, independent of the writer. */
    private fun parse(text: String): List<List<String>> {
        assertTrue("file must start with a BOM", text.startsWith(Csv.BOM))
        val s = text.removePrefix(Csv.BOM)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> { row += field.toString(); field.clear() }
                    '\r' -> {
                        assertEquals('\n', s[i + 1])
                        i++
                        row += field.toString(); field.clear()
                        rows += row; row = mutableListOf()
                    }
                    '\n' -> throw AssertionError("bare line feed outside quotes at $i")
                    else -> field.append(ch)
                }
            }
            i++
        }
        assertTrue("file must end with CRLF", row.isEmpty() && field.isEmpty())
        return rows
    }

    private fun List<List<String>>.cell(row: Int, column: String): String = this[row][this[0].indexOf(column).also { assertTrue(column, it >= 0) }]

    @Test
    fun fieldsAreQuotedOnlyWhenNeeded() {
        assertEquals("ساده", Csv.field("ساده"))
        assertEquals("", Csv.field(""))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"گفت \"\"سلام\"\"\"", Csv.field("گفت \"سلام\""))
        assertEquals("\"خط۱\nخط۲\"", Csv.field("خط۱\nخط۲"))
        assertEquals("\"a\rb\"", Csv.field("a\rb"))
        assertEquals("\" فاصله\"", Csv.field(" فاصله"))
        assertEquals("\"فاصله \"", Csv.field("فاصله "))
    }

    @Test
    fun formulaLikeTextIsNeutralised() {
        for (v in listOf("=SUM(A1:A9)", "+98912", "-5", "@cmd", "\tx", "\rx")) assertEquals("'$v", Csv.text(v))
        for (v in listOf("خرید", "5-3", "a=b", "", "☐ مورد")) assertEquals(v, Csv.text(v))
    }

    @Test
    fun tablesHaveBomCrlfAndConsistentWidth() {
        val text = Csv.table(listOf("الف", "ب"), listOf(listOf("1", "x,y"), listOf("2", "")))
        assertEquals(Csv.BOM + "الف,ب\r\n1,\"x,y\"\r\n2,\r\n", text)
        assertEquals(listOf(listOf("الف", "ب"), listOf("1", "x,y"), listOf("2", "")), parse(text))
        val error = runCatching { Csv.table(listOf("a", "b"), listOf(listOf("only one"))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun valueFormatsUseLatinDigitsAndBothCalendars() {
        assertEquals("1405/07/22", CsvExporter.jalali(today))
        assertEquals("2026-10-14", CsvExporter.gregorian(today))
        assertEquals("", CsvExporter.jalali(null))
        assertEquals("07:05", CsvExporter.time(LocalTime.of(7, 5)))
        // 08:00 UTC is 11:30 in Tehran (no DST since 1401).
        assertEquals("1405/07/22 11:30", CsvExporter.timestamp(NOW, zone))
        assertEquals("روزهای شنبه، دوشنبه، جمعه",
            CsvExporter.schedule(HabitSchedule.SpecificDays(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY)), DayOfWeek.SATURDAY))
        assertEquals("3 بار در هفته", CsvExporter.schedule(HabitSchedule.TimesPerWeek(3), DayOfWeek.SATURDAY))
    }

    @Test
    fun exportWritesEveryKindOfRecord() {
        val category = Category("c1", "کار و پروژه", createdAt = NOW, updatedAt = NOW)
        val goal = Goal(id = "g1", title = "انتشار کتاب", startDate = j(1405, 1, 1), deadline = j(1405, 12, 29), categoryId = "c1",
            createdAt = NOW, updatedAt = NOW)
        val task = Task(
            id = "t1", title = "=HYPERLINK(\"x\")", description = "متن، با ویرگول\nو خط دوم", priority = Priority.URGENT,
            status = TaskStatus.IN_PROGRESS, categoryId = "c1", tags = listOf("مهم", "فوری"), date = today,
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 30), dueDate = j(1405, 7, 30), reminderOffsetMinutes = 15,
            recurrence = RecurrenceRule(Frequency.DAILY), recurrenceStart = today, goalId = "g1", createdAt = NOW, updatedAt = NOW,
        )
        val done = Task(id = "t2", title = "پیش‌نویس", status = TaskStatus.DONE, goalId = "g1", completedAt = NOW, createdAt = NOW, updatedAt = NOW)
        val subtasks = listOf(
            Subtask("s2", "t1", "ویرایش", isDone = false, sortOrder = 1, createdAt = NOW, updatedAt = NOW),
            Subtask("s1", "t1", "نوشتن", isDone = true, sortOrder = 0, createdAt = NOW, updatedAt = NOW),
        )
        val milestones = listOf(
            Milestone("m1", "g1", "فصل اول", isDone = true, createdAt = NOW, updatedAt = NOW),
            Milestone("m2", "g1", "فصل دوم", sortOrder = 1, createdAt = NOW, updatedAt = NOW),
        )
        val note = Note("n1", "", "خرید\n- [x] نان\n- [ ] شیر", NoteColor.GOLD, tags = listOf("خانه"), isPinned = true, createdAt = NOW, updatedAt = NOW)
        val habit = Habit(id = "h1", title = "مطالعه", schedule = HabitSchedule.Daily, targetPerDay = 1, reminderTime = LocalTime.of(21, 0),
            goalId = "g1", startDate = j(1405, 7, 1), createdAt = NOW, updatedAt = NOW)
        val logs = listOf(
            HabitLog("l1", "h1", today, 1, NOW),
            HabitLog("l2", "h1", today.minusDays(1), 1, NOW),
            HabitLog("l0", "h1", today.minusDays(2), 0, NOW),
        )
        val data = BackupData(
            categories = listOf(category), tasks = listOf(task, done), subtasks = subtasks, notes = listOf(note),
            goals = listOf(goal), milestones = milestones, habits = listOf(habit), habitLogs = logs,
        )

        val tables = CsvExporter.export(data, today, zone, DayOfWeek.SATURDAY).associateBy { it.fileName }
        assertEquals(setOf("tasks.csv", "notes.csv", "goals.csv", "habits.csv", "habit_logs.csv"), tables.keys)

        val tasks = parse(tables.getValue("tasks.csv").content)
        assertEquals(3, tasks.size)
        assertEquals(2, tables.getValue("tasks.csv").rows)
        assertTrue(tasks.all { it.size == tasks[0].size })
        assertEquals("t1", tasks.cell(1, "شناسه"))
        assertEquals("'=HYPERLINK(\"x\")", tasks.cell(1, "عنوان"))
        assertEquals("متن، با ویرگول\nو خط دوم", tasks.cell(1, "توضیحات"))
        assertEquals("در حال انجام", tasks.cell(1, "وضعیت"))
        assertEquals("فوری", tasks.cell(1, "اولویت"))
        assertEquals("کار و پروژه", tasks.cell(1, "دسته"))
        assertEquals("مهم، فوری", tasks.cell(1, "برچسب‌ها"))
        assertEquals("1405/07/22", tasks.cell(1, "تاریخ (شمسی)"))
        assertEquals("2026-10-14", tasks.cell(1, "تاریخ (میلادی)"))
        assertEquals("09:00", tasks.cell(1, "ساعت شروع"))
        assertEquals("10:30", tasks.cell(1, "ساعت پایان"))
        assertEquals("1405/07/30", tasks.cell(1, "موعد (شمسی)"))
        assertEquals("15", tasks.cell(1, "یادآوری (دقیقه قبل)"))
        assertEquals("هر روز", tasks.cell(1, "تکرار"))
        assertEquals("انتشار کتاب", tasks.cell(1, "هدف"))
        assertEquals("☑ نوشتن\n☐ ویرایش", tasks.cell(1, "زیرکارها"))
        assertEquals("خیر", tasks.cell(1, "بایگانی"))
        // Undated tasks come last.
        assertEquals("t2", tasks.cell(2, "شناسه"))
        assertEquals("1405/07/22 11:30", tasks.cell(2, "زمان انجام"))

        val notes = parse(tables.getValue("notes.csv").content)
        assertEquals("خرید\n☑ نان\n☐ شیر", notes.cell(1, "متن"))
        assertEquals("1/2", notes.cell(1, "فهرست تیک‌دار (انجام‌شده/کل)"))
        assertEquals("طلایی", notes.cell(1, "رنگ"))
        assertEquals("بله", notes.cell(1, "سنجاق‌شده"))

        val goals = parse(tables.getValue("goals.csv").content)
        assertEquals("1405/12/29", goals.cell(1, "موعد (شمسی)"))
        assertEquals("2027-03-20", goals.cell(1, "موعد (میلادی)"))
        assertEquals("☑ فصل اول\n☐ فصل دوم", goals.cell(1, "نقاط عطف"))
        assertEquals("1/2", goals.cell(1, "کارهای مرتبط (انجام‌شده/کل)"))
        assertEquals("مطالعه", goals.cell(1, "عادت‌های مرتبط"))
        assertEquals("فعال", goals.cell(1, "وضعیت"))
        val percent = goals.cell(1, "پیشرفت (درصد)").toInt()
        assertTrue("progress $percent", percent in 1..99)

        val habits = parse(tables.getValue("habits.csv").content)
        assertEquals("هر روز", habits.cell(1, "برنامه"))
        assertEquals("21:00", habits.cell(1, "ساعت یادآوری"))
        assertEquals("2", habits.cell(1, "زنجیره فعلی"))
        assertEquals("روز", habits.cell(1, "واحد زنجیره"))

        // Zero-count logs are not check-ins.
        val logRows = parse(tables.getValue("habit_logs.csv").content)
        assertEquals(3, logRows.size)
        assertEquals(listOf("مطالعه", "h1", "1405/07/21", "2026-10-13", "سه‌شنبه", "1"), logRows[1])
        assertEquals("چهارشنبه", logRows.cell(2, "روز هفته"))
    }

    @Test
    fun emptyDataGivesHeaderOnlyFiles() {
        val tables = CsvExporter.export(BackupData(), today, zone, DayOfWeek.SATURDAY)
        assertEquals(5, tables.size)
        for (t in tables) {
            assertEquals(0, t.rows)
            assertEquals(1, parse(t.content).size)
        }
    }
}
