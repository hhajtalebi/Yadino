package ir.yadino.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.core.AppClock
import ir.yadino.app.data.local.YadinoDatabase
import ir.yadino.app.data.local.toEntity
import ir.yadino.app.data.repository.DataRepository
import ir.yadino.app.data.repository.DataSideEffects
import ir.yadino.app.data.repository.TemplateRepository
import ir.yadino.app.data.repository.ValidationException
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.PlanTemplate
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import ir.yadino.domain.model.TaskStatus
import ir.yadino.domain.validation.ValidationError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Plan templates on a real Room database (in memory): save from plan, apply, undo, backup. */
@RunWith(AndroidJUnit4::class)
class TemplateRepositoryTest {
    private lateinit var db: YadinoDatabase
    private lateinit var templates: TemplateRepository
    private lateinit var data: DataRepository
    private val now = Instant.parse("2026-10-14T08:00:00Z")
    private val wednesday = LocalDate.of(2026, 10, 14)
    private val changed = ArrayList<Task>()
    private val effects = object : DataSideEffects {
        override fun onTasksChanged(changed: List<Task>, deletedIds: List<String>) {
            this@TemplateRepositoryTest.changed += changed
        }
        override fun onHabitsChanged(changed: List<Habit>, deletedIds: List<String>) = Unit
        override fun onGoalsChanged(changed: List<Goal>, deletedIds: List<String>) = Unit
        override fun onNotesChanged() = Unit
        override fun onEverythingChanged() = Unit
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, YadinoDatabase::class.java).build()
        val clock = AppClock { AppSettings() }
        templates = TemplateRepository(db, clock, { DayOfWeek.SATURDAY }, effects)
        data = DataRepository(db, { File(context.cacheDir, "none.db") }, clock, effects)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun plan() {
        db.categoryDao().upsert(Category("cat", "کار", createdAt = now, updatedAt = now).toEntity())
        db.taskDao().upsert(Task(id = "t1", title = "کار عمیق", date = wednesday, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 0),
            categoryId = "cat", status = TaskStatus.DONE, createdAt = now, updatedAt = now).toEntity())
        db.taskDao().upsert(Task(id = "t2", title = "خرید", date = wednesday, sortOrder = 1, createdAt = now, updatedAt = now).toEntity())
        db.subtaskDao().upsert(Subtask("s1", "t2", "نان", true, 0, now, now).toEntity())
    }

    @Test
    fun savePlanApplyAndUndo() = runTest {
        plan()
        assertEquals(2, templates.planSize(wednesday, PlanTemplate.DAY))
        val template = templates.saveFromPlan(wednesday, PlanTemplate.DAY, "روز کاری")
        assertNotNull(template)
        val items = templates.getItems(template!!.id)
        assertEquals(listOf("کار عمیق", "خرید"), items.map { it.title })
        assertEquals(listOf("نان"), items[1].subtasks)

        // the same name cannot be used twice
        try {
            templates.saveFromPlan(wednesday, PlanTemplate.DAY, "روز كاري")
            fail("duplicate name")
        } catch (e: ValidationException) {
            assertTrue(ValidationError.NAME_DUPLICATE in e.errors)
        }

        val target = wednesday.plusDays(1)
        val applied = templates.apply(template, target, skipDuplicates = true)
        assertEquals(2, applied.tasks.size)
        val created = db.taskDao().getAll().filter { it.date == target.toEpochDay() }
        assertEquals(2, created.size)
        assertTrue(created.all { it.status == "TODO" })
        assertEquals(1, db.subtaskDao().getForTask(applied.tasks.first { it.title == "خرید" }.id).size)
        assertEquals(applied.tasks.map { it.id }.toSet(), changed.map { it.id }.toSet())

        // applying again only finds duplicates
        val again = templates.apply(template, target, skipDuplicates = true)
        assertEquals(0, again.tasks.size)
        assertEquals(2, again.skipped)

        // undo: the created tasks and their subtasks disappear
        applied.tasks.forEach { db.taskDao().delete(it.id) }
        assertTrue(db.taskDao().getAll().none { it.date == target.toEpochDay() })
        assertTrue(db.subtaskDao().getAll().all { it.taskId == "t2" })
    }

    @Test
    fun templatesAreUserDataInBackupsAndDeletes() = runTest {
        plan()
        val template = templates.saveFromPlan(wednesday, PlanTemplate.DAY, "روز کاری")!!
        val snapshot = data.snapshot(emptyMap(), null)
        assertEquals(1, snapshot.templates.size)
        assertEquals(2, snapshot.templateItems.size)

        // deleting the category unlinks the item, the template stays
        db.categoryDao().delete("cat")
        assertNull(templates.getItems(template.id).first { it.title == "کار عمیق" }.categoryId)

        // restore writes templates back atomically
        data.replaceAll(BackupData(templates = snapshot.templates, templateItems = snapshot.templateItems.map { it.copy(categoryId = null) }))
        assertEquals(listOf("روز کاری"), templates.templates.first().map { it.name })
        assertEquals(2, templates.items.first().size)

        // undo of a deleted template brings back its items
        val removed = templates.delete(template.id)!!
        assertTrue(templates.getItems(template.id).isEmpty())
        templates.restore(removed.first, removed.second)
        assertEquals(2, templates.getItems(template.id).size)

        data.deleteAllData()
        assertTrue(templates.getAll().isEmpty())
    }
}
