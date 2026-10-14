package ir.yadino.domain

import ir.yadino.domain.TestData.NOW
import ir.yadino.domain.TestData.task
import ir.yadino.domain.backup.BackupCodec
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.backup.BackupError
import ir.yadino.domain.backup.BackupFile
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.BackupReader
import ir.yadino.domain.backup.BackupValidator
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlanner
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.DefaultCategories
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Priority
import ir.yadino.domain.model.TaskKind
import ir.yadino.domain.model.TemplateItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

/** Plan templates in the backup format (version 2) and compatibility with version 1 files. */
class TemplateBackupTest {

    private val categories = DefaultCategories.create(NOW)
    private val custom = Category("custom-cat", "مطالعه", kind = ir.yadino.domain.model.CategoryKind.TASK, createdAt = NOW, updatedAt = NOW)
    private val goal = Goal("goal-1", "کنکور", startDate = TestData.j(1405, 7, 1), createdAt = NOW, updatedAt = NOW)

    private fun template(id: String = "tpl-1", name: String = "روز مطالعه", updated: Instant = NOW) =
        PlanTemplate(id, name, "برای روزهای امتحان", PlanTemplate.WEEK, NOW, updated)

    private fun items(templateId: String = "tpl-1") = listOf(
        TemplateItem(
            id = "$templateId-a", templateId = templateId, title = "فیزیک", description = "فصل ۳", kind = TaskKind.EVENT,
            priority = Priority.HIGH, categoryId = custom.id, tags = listOf("درس"), dayOffset = 2,
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(10, 0), reminderOffsetMinutes = 15, goalId = goal.id,
            subtasks = listOf("خلاصه", "تست"), sortOrder = 0, createdAt = NOW, updatedAt = NOW,
        ),
        TemplateItem(
            id = "$templateId-b", templateId = templateId, title = "ثبت‌نام", categoryId = categories[0].id, dayOffset = null,
            dueOffset = 9, dueTime = LocalTime.of(23, 0), sortOrder = 1, createdAt = NOW, updatedAt = NOW,
        ),
    )

    private fun localData() = BackupData(
        categories = categories + custom,
        goals = listOf(goal),
        tasks = listOf(task("محلی")),
        templates = listOf(template()),
        templateItems = items(),
    )

    private fun success(text: String): BackupFile {
        val r = BackupCodec.decode(text)
        assertTrue("$r", r is BackupReadResult.Success)
        return (r as BackupReadResult.Success).file
    }

    private fun v1Fixture(): String =
        javaClass.getResourceAsStream("/backup-v1-app-1.2.0.yadino.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun templatesSurviveTheBackupRoundTrip() {
        val data = localData()
        val text = BackupCodec.encode(data, "1.3.0", NOW)
        assertTrue(text.contains("\"formatVersion\":2"))
        val file = success(text)
        assertEquals(data, file.data)
        assertEquals(2, file.info.formatVersion)
        assertEquals(1, file.info.counts.templates)
        assertEquals(2, file.info.counts.templateItems)
        assertEquals(data.totalRecords, file.data.totalRecords)
    }

    @Test
    fun fileWrittenByVersion120IsStillRestorable() {
        val r = BackupReader.read(v1Fixture())
        assertTrue("$r", r is BackupReadResult.Success)
        val file = (r as BackupReadResult.Success).file
        assertEquals(1, file.info.formatVersion)
        assertEquals("1.2.0", file.info.appVersion)
        assertEquals(2, file.data.tasks.size)
        assertEquals("- [ ] نان\n- [x] شیر", file.data.notes.single().content)
        assertEquals(1, file.data.habitLogs.size)
        assertTrue(file.data.templates.isEmpty())
        assertTrue(file.data.templateItems.isEmpty())
        assertTrue(r.warnings.isEmpty())
        assertTrue(RestorePlanner.keepsLocalTemplates(file))
    }

    @Test
    fun replacingWithAnOldFileKeepsLocalTemplates() {
        val local = localData()
        val old = success(v1Fixture())
        val plan = RestorePlanner.plan(local, old, RestoreMode.REPLACE, lastRestoredChecksum = null)
        assertEquals(old.data.tasks.map { it.id }, plan.result.tasks.map { it.id })
        assertEquals(listOf("tpl-1"), plan.result.templates.map { it.id })
        assertEquals(2, plan.result.templateItems.size)
        // links to records that are not part of the restored data are cleared
        val physics = plan.result.templateItems.first { it.title == "فیزیک" }
        assertNull(physics.categoryId)
        assertNull(physics.goalId)
        // default categories exist in the old file, so that link is kept
        assertEquals(categories[0].id, plan.result.templateItems.first { it.title == "ثبت‌نام" }.categoryId)
        assertEquals(3, plan.stats.kept)
        assertEquals(local.totalRecords - 3, plan.stats.removed)
    }

    @Test
    fun replacingWithANewFileReplacesTemplates() {
        val incoming = BackupData(categories = categories, templates = listOf(template("tpl-2", "هفته کاری")), templateItems = items("tpl-2"))
        val file = success(BackupCodec.encode(incoming, "1.3.0", NOW))
        assertFalse(RestorePlanner.keepsLocalTemplates(file))
        val plan = RestorePlanner.plan(localData(), file, RestoreMode.REPLACE, null)
        assertEquals(listOf("tpl-2"), plan.result.templates.map { it.id })
        assertEquals(setOf("tpl-2-a", "tpl-2-b"), plan.result.templateItems.map { it.id }.toSet())
        assertEquals(0, plan.stats.kept)
    }

    @Test
    fun mergeKeepsTheNewestVersionOfEachTemplate() {
        val newer = NOW.plusSeconds(3600)
        val incoming = BackupData(
            categories = categories + custom, goals = listOf(goal),
            templates = listOf(template(name = "روز مطالعه (ویرایش‌شده)", updated = newer), template("tpl-3", "جمعه‌ها")),
            templateItems = items() + items("tpl-3"),
        )
        val plan = RestorePlanner.plan(localData(), success(BackupCodec.encode(incoming, "1.3.0", NOW)), RestoreMode.MERGE, null)
        assertEquals(setOf("tpl-1", "tpl-3"), plan.result.templates.map { it.id }.toSet())
        assertEquals("روز مطالعه (ویرایش‌شده)", plan.result.templates.first { it.id == "tpl-1" }.name)
        assertEquals(4, plan.result.templateItems.size)
        assertEquals(custom.id, plan.result.templateItems.first { it.id == "tpl-1-a" }.categoryId)
    }

    @Test
    fun brokenTemplateContentIsRejectedOrCleaned() {
        val orphan = items("missing")
        val data = localData().copy(templateItems = items() + orphan)
        val report = BackupValidator.validate(data)
        assertTrue(report.errors.isEmpty())
        assertTrue(report.warnings.any { it.startsWith("template items without template") })
        assertEquals(2, BackupValidator.sanitize(data).templateItems.size)

        val duplicate = localData().copy(templates = listOf(template(), template()))
        val r = BackupCodec.decode(BackupCodec.encode(duplicate, "1.3.0", NOW))
        assertEquals(BackupError.INVALID_CONTENT, (r as BackupReadResult.Failure).error)
        val noName = localData().copy(templates = listOf(template(name = " ")))
        assertEquals(BackupError.INVALID_CONTENT, (BackupCodec.decode(BackupCodec.encode(noName, "1.3.0", NOW)) as BackupReadResult.Failure).error)
        val noDay = localData().copy(templateItems = listOf(items().first().copy(dayOffset = null, dueOffset = null)))
        assertEquals(BackupError.INVALID_CONTENT, (BackupCodec.decode(BackupCodec.encode(noDay, "1.3.0", NOW)) as BackupReadResult.Failure).error)
    }
}
