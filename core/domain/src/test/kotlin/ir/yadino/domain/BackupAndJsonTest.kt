package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.j
import ir.yadino.domain.TestData.task
import ir.yadino.domain.backup.BackupCodec
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.BackupValidator
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlanner
import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonArray
import ir.yadino.domain.json.JsonObject
import ir.yadino.domain.json.JsonParseException
import ir.yadino.domain.json.JsonString
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.DefaultCategories
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.HabitLog
import ir.yadino.domain.model.HabitSchedule
import ir.yadino.domain.model.Milestone
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.TaskCompletion
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.recurrence.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class BackupAndJsonTest {

    @Test
    fun jsonRoundTrip() {
        val text = """{"a":1,"b":[true,false,null],"c":"س\"لام\n\u0041","d":{"e":-1.5e3},"f":[]}"""
        val v = Json.parse(text) as JsonObject
        assertEquals("س\"لام\nA", v.string("c"))
        assertEquals(1L, v.long("a"))
        assertEquals(3, v.array("b")!!.items.size)
        assertEquals(v, Json.parse(Json.stringify(v)))
        assertEquals(v, Json.parse(Json.stringify(v, pretty = true)))
        assertEquals("""{"a":1,"b":[true,false,null],"c":"س\"لام\nA","d":{"e":-1.5e3},"f":[]}""", Json.stringify(v))
    }

    @Test
    fun jsonRejectsInvalidInput() {
        val bad = listOf("", "{", "[1,]", "{\"a\" 1}", "01", "\"unterminated", "{\"a\":1} x", "tru", "[1 2]", "\"\\x\"", "-", "1.")
        for (b in bad) {
            try {
                Json.parse(b)
                fail("should reject: $b")
            } catch (expected: JsonParseException) {
            }
        }
        val deep = "[".repeat(100) + "]".repeat(100)
        try {
            Json.parse(deep); fail("depth limit")
        } catch (expected: JsonParseException) {
        }
    }

    private fun sample(): BackupData {
        val cat = DefaultCategories.create(NOW)
        val t1 = task("گزارش", j(1405, 7, 22)) {
            copy(
                description = "متن\nچندخطی", priority = Priority.HIGH, categoryId = cat[0].id, tags = listOf("کار", "فوری"),
                startTime = LocalTime.of(9, 30), endTime = LocalTime.of(11, 0), dueDate = j(1405, 7, 25),
                reminderOffsetMinutes = 15, recurrence = RecurrenceRule.weekly(setOf(DayOfWeek.SATURDAY)),
                recurrenceStart = j(1405, 7, 22), kind = TaskKind.TASK,
            )
        }
        val goal = Goal("g1", "یادگیری", startDate = j(1405, 7, 1), deadline = j(1405, 12, 29), createdAt = NOW, updatedAt = NOW)
        val habit = TestData.habit(HabitSchedule.SpecificDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY)))
        return BackupData(
            categories = cat,
            tasks = listOf(t1.copy(goalId = "g1")),
            subtasks = listOf(Subtask("s1", t1.id, "قدم اول", true, 0, NOW, NOW)),
            completions = listOf(TaskCompletion("c1", t1.id, j(1405, 7, 15), NOW)),
            notes = listOf(Note("n1", "ایده", "محتوای \"مهم\" 😀", NoteColor.GOLD, cat[6].id, listOf("ایده"), isPinned = true, goalId = "g1", createdAt = NOW, updatedAt = NOW)),
            goals = listOf(goal),
            milestones = listOf(Milestone("m1", "g1", "فصل اول", dueDate = j(1405, 8, 1), createdAt = NOW, updatedAt = NOW)),
            habits = listOf(habit.copy(reminderTime = LocalTime.of(20, 0), goalId = "g1")),
            habitLogs = TestData.logs(habit, j(1405, 7, 18), j(1405, 7, 20)),
            settings = mapOf("general.numerals" to "PERSIAN", "general.use24Hour" to "true"),
            dashboard = "TODAY_PLAN:1:6;NEW_TASK",
        )
    }

    @Test
    fun backupRoundTripPreservesEverything() {
        val data = sample()
        val text = BackupCodec.encode(data, "1.0.0", NOW)
        val result = BackupCodec.decode(text)
        assertTrue(result is BackupReadResult.Success)
        val file = (result as BackupReadResult.Success).file
        assertEquals(data, file.data)
        assertEquals(BackupCodec.FORMAT_VERSION, file.info.formatVersion)
        assertEquals(NOW, file.info.createdAt)
        assertEquals(data.counts(), file.info.counts)
        assertTrue(result.warnings.isEmpty())
        // pretty printing produces an equivalent, valid file
        assertTrue(BackupCodec.decode(BackupCodec.encode(data, "1.0.0", NOW, pretty = true)) is BackupReadResult.Success)
    }

    @Test
    fun tamperedOrTruncatedFilesAreRejected() {
        val text = BackupCodec.encode(sample(), "1.0.0", NOW)
        val tampered = text.replace("گزارش", "گزارش!")
        assertEquals(BackupError.CHECKSUM_MISMATCH, (BackupCodec.decode(tampered) as BackupReadResult.Failure).error)
        val truncated = text.substring(0, text.length / 2)
        assertEquals(BackupError.NOT_JSON, (BackupCodec.decode(truncated) as BackupReadResult.Failure).error)
        assertEquals(BackupError.EMPTY_FILE, (BackupCodec.decode("  ") as BackupReadResult.Failure).error)
        assertEquals(BackupError.NOT_A_YADINO_BACKUP, (BackupCodec.decode("""{"hello":"world"}""") as BackupReadResult.Failure).error)
        val newer = text.replace("\"formatVersion\":${BackupCodec.FORMAT_VERSION}", "\"formatVersion\":99")
        assertTrue(newer != text)
        assertEquals(BackupError.NEWER_FORMAT, (BackupCodec.decode(newer) as BackupReadResult.Failure).error)
    }

    @Test
    fun invalidContentIsRejectedEvenWithValidChecksum() {
        val data = sample()
        val dup = data.copy(tasks = data.tasks + data.tasks.first())
        val r = BackupCodec.decode(BackupCodec.encode(dup, "1.0.0", NOW))
        assertEquals(BackupError.INVALID_CONTENT, (r as BackupReadResult.Failure).error)
        val badLogs = data.copy(habitLogs = data.habitLogs + data.habitLogs.first().copy(id = "other"))
        assertEquals(BackupError.INVALID_CONTENT, (BackupCodec.decode(BackupCodec.encode(badLogs, "1.0.0", NOW)) as BackupReadResult.Failure).error)
    }

    @Test
    fun wrongFieldTypesAreRejected() {
        // build a structurally valid file whose task misses its title, with a correct checksum
        val data = Json.parse(BackupCodec.encode(sample(), "1.0.0", NOW)) as JsonObject
        val dataObj = data.obj("data")!!
        val tasks = dataObj.array("tasks")!!.items.map { t ->
            JsonObject((t as JsonObject).members.filterKeys { it != "title" })
        }
        val newData = JsonObject(dataObj.members + ("tasks" to JsonArray(tasks)))
        val root = JsonObject(data.members + ("data" to newData) + ("checksum" to JsonString(BackupCodec.sha256(Json.stringify(newData)))))
        val r = BackupCodec.decode(Json.stringify(root))
        assertEquals(BackupError.INVALID_CONTENT, (r as BackupReadResult.Failure).error)
    }

    @Test
    fun replaceRestorePlan() {
        val local = BackupData(tasks = listOf(task("محلی")))
        val incoming = (BackupCodec.decode(BackupCodec.encode(sample(), "1.0.0", NOW)) as BackupReadResult.Success).file
        val plan = RestorePlanner.plan(local, incoming, RestoreMode.REPLACE, null)
        assertEquals(incoming.data, plan.result)
        assertEquals(1, plan.stats.removed)
        assertFalse(plan.alreadyRestored)
        assertTrue(RestorePlanner.plan(local, incoming, RestoreMode.REPLACE, incoming.info.checksum).alreadyRestored)
    }

    @Test
    fun mergeKeepsNewestVersion() {
        val older = Instant.parse("2026-10-01T00:00:00Z")
        val newer = Instant.parse("2026-10-20T00:00:00Z")
        val shared = task("نسخه محلی", id = "shared") { copy(updatedAt = newer) }
        val localOnly = task("فقط محلی", id = "local")
        val local = BackupData(tasks = listOf(shared, localOnly))
        val incomingData = BackupData(
            tasks = listOf(shared.copy(title = "نسخه قدیمی", updatedAt = older), task("فقط پشتیبان", id = "remote")),
            notes = listOf(Note("n", "", "x", createdAt = NOW, updatedAt = NOW)),
        )
        val incoming = (BackupCodec.decode(BackupCodec.encode(incomingData, "1.0.0", NOW)) as BackupReadResult.Success).file
        val plan = RestorePlanner.plan(local, incoming, RestoreMode.MERGE, null)
        val titles = plan.result.tasks.associate { it.id to it.title }
        assertEquals("نسخه محلی", titles["shared"])
        assertEquals(setOf("shared", "local", "remote"), titles.keys)
        assertEquals(2, plan.stats.added) // remote task + note
        assertEquals(1, plan.stats.kept)
        assertEquals(0, plan.stats.updated)
        // incoming newer wins
        val plan2 = RestorePlanner.plan(BackupData(tasks = listOf(shared.copy(updatedAt = older))),
            (BackupCodec.decode(BackupCodec.encode(BackupData(tasks = listOf(shared.copy(title = "تازه", updatedAt = newer))), "1", NOW)) as BackupReadResult.Success).file,
            RestoreMode.MERGE, null)
        assertEquals("تازه", plan2.result.tasks.single().title)
        assertEquals(1, plan2.stats.updated)
    }

    @Test
    fun mergeHabitLogsByDay() {
        val h = TestData.habit()
        val day = j(1405, 7, 20)
        val local = BackupData(habits = listOf(h), habitLogs = listOf(HabitLog("l1", h.id, day, 1, Instant.parse("2026-10-01T00:00:00Z"))))
        val incomingData = BackupData(habits = listOf(h), habitLogs = listOf(HabitLog("l2", h.id, day, 3, Instant.parse("2026-10-05T00:00:00Z"))))
        val incoming = (BackupCodec.decode(BackupCodec.encode(incomingData, "1", NOW)) as BackupReadResult.Success).file
        val logs = RestorePlanner.plan(local, incoming, RestoreMode.MERGE, null).result.habitLogs
        assertEquals(1, logs.size)
        assertEquals(3, logs.single().count)
        assertEquals("l1", logs.single().id)
    }

    @Test
    fun sanitizeDropsOrphans() {
        val data = BackupData(
            subtasks = listOf(Subtask("s", "missing", "x", createdAt = NOW, updatedAt = NOW)),
            notes = listOf(Note("n", "a", "b", categoryId = "nope", goalId = "nope", createdAt = NOW, updatedAt = NOW)),
            categories = listOf(Category("c", "دسته", createdAt = NOW, updatedAt = NOW)),
        )
        val report = BackupValidator.validate(data)
        assertTrue(report.errors.isEmpty())
        assertEquals(2, report.warnings.size)
        val clean = BackupValidator.sanitize(data)
        assertTrue(clean.subtasks.isEmpty())
        assertEquals(null, clean.notes.single().categoryId)
        assertEquals(null, clean.notes.single().goalId)
    }
}

class BackupLocaleIndependenceTest {
    @org.junit.Test
    fun backupIsIdenticalUnderPersianDefaultLocale() {
        val original = java.util.Locale.getDefault()
        try {
            val data = ir.yadino.domain.backup.BackupData(
                tasks = listOf(TestData.task("زمان‌دار", TestData.j(1405, 7, 22)) { copy(startTime = java.time.LocalTime.of(9, 5)) }),
            )
            java.util.Locale.setDefault(java.util.Locale.US)
            val latin = ir.yadino.domain.backup.BackupCodec.encode(data, "1.0.0", TestData.NOW)
            java.util.Locale.setDefault(java.util.Locale("fa", "IR"))
            val persian = ir.yadino.domain.backup.BackupCodec.encode(data, "1.0.0", TestData.NOW)
            org.junit.Assert.assertEquals(latin, persian)
            org.junit.Assert.assertTrue(persian.contains("\"09:05\""))
            val decoded = ir.yadino.domain.backup.BackupCodec.decode(persian)
            org.junit.Assert.assertTrue(decoded is ir.yadino.domain.backup.BackupReadResult.Success)
            org.junit.Assert.assertEquals("1405/07/22", ir.yadino.calendar.JalaliDate.of(1405, 7, 22).toString())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
