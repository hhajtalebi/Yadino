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
import ir.yadino.domain.backup.BackupCodec
import ir.yadino.domain.backup.BackupData
import ir.yadino.domain.backup.BackupReadResult
import ir.yadino.domain.backup.RestoreMode
import ir.yadino.domain.backup.RestorePlanner
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Habit
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.Subtask
import ir.yadino.domain.model.Task
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Room + repository integration tests on a device/emulator (in-memory database). */
@RunWith(AndroidJUnit4::class)
class DatabaseAndRestoreTest {
    private lateinit var db: YadinoDatabase
    private lateinit var data: DataRepository
    private val now = Instant.parse("2026-10-14T08:00:00Z")
    private val noEffects = object : DataSideEffects {
        override fun onTasksChanged(changed: List<Task>, deletedIds: List<String>) = Unit
        override fun onHabitsChanged(changed: List<Habit>, deletedIds: List<String>) = Unit
        override fun onGoalsChanged(changed: List<Goal>, deletedIds: List<String>) = Unit
        override fun onNotesChanged() = Unit
        override fun onEverythingChanged() = Unit
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, YadinoDatabase::class.java).build()
        data = DataRepository(db, { File(context.cacheDir, "none.db") }, AppClock { AppSettings() }, noEffects)
    }

    @After
    fun tearDown() = db.close()

    private fun task(id: String, title: String = "کار") =
        Task(id = id, title = title, date = LocalDate.of(2026, 10, 14), createdAt = now, updatedAt = now)

    @Test
    fun taskCrudAndCascade() = runTest {
        db.taskDao().upsert(task("t1").toEntity())
        db.subtaskDao().upsert(Subtask("s1", "t1", "زیرکار", createdAt = now, updatedAt = now).toEntity())
        assertEquals(1, db.taskDao().count())
        db.taskDao().delete("t1")
        assertTrue(db.subtaskDao().getForTask("t1").isEmpty())
    }

    @Test
    fun backupRestoreRoundTripThroughDatabase() = runTest {
        val original = BackupData(
            tasks = listOf(task("a", "گزارش"), task("b")),
            notes = listOf(Note("n", "یادداشت", "متن", createdAt = now, updatedAt = now)),
        )
        data.replaceAll(original)
        val json = BackupCodec.encode(data.snapshot(emptyMap(), null), "test", now)
        data.deleteAllData()
        val file = (BackupCodec.decode(json) as BackupReadResult.Success).file
        val plan = RestorePlanner.plan(data.snapshot(emptyMap(), null), file, RestoreMode.REPLACE, null)
        data.replaceAll(plan.result)
        val restored = data.snapshot(emptyMap(), null)
        assertEquals(original.tasks.map { it.id }.toSet(), restored.tasks.map { it.id }.toSet())
        assertEquals("گزارش", restored.tasks.first { it.id == "a" }.title)
        assertEquals(1, restored.notes.size)
    }

    @Test
    fun failedRestoreIsRolledBackCompletely() = runTest {
        data.replaceAll(BackupData(tasks = listOf(task("keep"))))
        val broken = BackupData(tasks = listOf(task("x"), task("x"))) // duplicate primary key
        try {
            data.replaceAll(broken)
            fail("expected constraint failure")
        } catch (expected: Exception) {
        }
        val after = data.snapshot(emptyMap(), null)
        assertEquals(listOf("keep"), after.tasks.map { it.id })
    }
}
