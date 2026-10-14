package ir.yadino.app.data

import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Storage integration test on a real SQLite engine (sqlite-jdbc), executed on the JVM.
 *
 * The database is created exactly from Room's exported schema (app/schemas), so the test
 * checks the schema that the app ships: foreign keys and cascades, uniqueness rules,
 * unambiguous storage types (dates as integers) and that every database version has an
 * exported schema (prerequisite for migration tests).
 */
class RoomSchemaTest {

    private lateinit var schemaDir: File
    private lateinit var latest: JsonObject
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        schemaDir = listOf(File("schemas"), File("app/schemas"))
            .map { File(it, "ir.yadino.app.data.local.YadinoDatabase") }
            .first { it.isDirectory }
        val newest = schemaDir.listFiles { f -> f.name.endsWith(".json") }!!.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        latest = (Json.parse(newest.readText()) as JsonObject).obj("database")!!
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        for (e in latest.array("entities")!!.items) {
            val entity = e as JsonObject
            val table = entity.string("tableName")!!
            exec(entity.string("createSql")!!.replace("\${TABLE_NAME}", table))
            entity.array("indices")?.items?.forEach { idx ->
                exec((idx as JsonObject).string("createSql")!!.replace("\${TABLE_NAME}", table))
            }
        }
    }

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    private fun count(sql: String): Int = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }

    private val now = 1_760_000_000_000L

    private fun insertTask(id: String, goal: String? = null, date: Long? = 20375) = exec(
        "INSERT INTO tasks (id,title,description,kind,priority,status,category_id,tags,date,start_minute,end_minute,due_date," +
            "due_minute,reminder_offset,recurrence,recurrence_start,goal_id,source_note_id,sort_order,is_archived,completed_at,created_at,updated_at) " +
            "VALUES ('$id','عنوان','','TASK',1,'TODO',NULL,'',${date ?: "NULL"},540,600,NULL,NULL,15,NULL,NULL,${goal?.let { "'$it'" } ?: "NULL"},NULL,0,0,NULL,$now,$now)",
    )

    @Test
    fun everyVersionHasAnExportedSchema() {
        val versions = schemaDir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals((1..versions.last()).toList(), versions)
        assertEquals(versions.last(), latest.int("version"))
        val source = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("ir/yadino/app/data/local/YadinoDatabase.kt").readText()
        val declared = Regex("const val VERSION = (\\d+)").find(source)!!.groupValues[1].toInt()
        assertEquals("YadinoDatabase.VERSION must match the newest exported schema", declared, versions.last())
    }

    @Test
    fun allPrimaryKeysAreTextIdsExceptCompositeTombstones() {
        for (e in latest.array("entities")!!.items) {
            val entity = e as JsonObject
            val pk = entity.obj("primaryKey")!!
            assertEquals("no auto-increment keys (sync-friendly ids)", false, pk.bool("autoGenerate"))
        }
    }

    @Test
    fun datesAndTimesAreStoredAsIntegers() {
        val expected = mapOf(
            "tasks" to listOf("date", "due_date", "start_minute", "end_minute", "created_at", "updated_at", "completed_at", "recurrence_start"),
            "habit_logs" to listOf("date", "updated_at"),
            "goals" to listOf("start_date", "deadline"),
            "task_completions" to listOf("occurrence_date", "completed_at"),
        )
        for ((table, cols) in expected) {
            val entity = latest.array("entities")!!.items.map { it as JsonObject }.first { it.string("tableName") == table }
            val fields = entity.array("fields")!!.items.map { it as JsonObject }.associate { it.string("columnName") to it.string("affinity") }
            cols.forEach { assertEquals("$table.$it", "INTEGER", fields[it]) }
        }
    }

    @Test
    fun deletingTaskCascadesToSubtasksAndCompletions() {
        insertTask("t1")
        exec("INSERT INTO subtasks (id,task_id,title,is_done,sort_order,created_at,updated_at) VALUES ('s1','t1','زیرکار',0,0,$now,$now)")
        exec("INSERT INTO task_completions (id,task_id,occurrence_date,completed_at) VALUES ('c1','t1',20375,$now)")
        exec("DELETE FROM tasks WHERE id='t1'")
        assertEquals(0, count("SELECT COUNT(*) FROM subtasks"))
        assertEquals(0, count("SELECT COUNT(*) FROM task_completions"))
    }

    @Test
    fun deletingGoalKeepsTasksButUnlinksThem() {
        exec("INSERT INTO goals (id,title,description,term,priority,status,is_archived,start_date,deadline,color,category_id,manual_progress,completed_at,created_at,updated_at) " +
            "VALUES ('g1','هدف','','SHORT',1,'ACTIVE',0,20370,NULL,'GREEN',NULL,NULL,NULL,$now,$now)")
        exec("INSERT INTO milestones (id,goal_id,title,is_done,due_date,sort_order,completed_at,created_at,updated_at) VALUES ('m1','g1','مرحله',0,NULL,0,NULL,$now,$now)")
        insertTask("t2", goal = "g1")
        exec("DELETE FROM goals WHERE id='g1'")
        assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE id='t2' AND goal_id IS NULL"))
        assertEquals(0, count("SELECT COUNT(*) FROM milestones"))
    }

    @Test
    fun habitLogIsUniquePerDay() {
        exec("INSERT INTO habits (id,title,description,color,icon,schedule_type,schedule_days,schedule_times,target_per_day,reminder_minute,goal_id,start_date,is_archived,sort_order,created_at,updated_at) " +
            "VALUES ('h1','عادت','','GREEN','check','DAILY',0,0,1,NULL,NULL,20370,0,0,$now,$now)")
        exec("INSERT INTO habit_logs (id,habit_id,date,count,updated_at) VALUES ('l1','h1',20375,1,$now)")
        try {
            exec("INSERT INTO habit_logs (id,habit_id,date,count,updated_at) VALUES ('l2','h1',20375,1,$now)")
            fail("duplicate habit log for the same day must be rejected")
        } catch (expected: SQLException) {
        }
    }

    @Test
    fun foreignKeysRejectOrphans() {
        try {
            exec("INSERT INTO subtasks (id,task_id,title,is_done,sort_order,created_at,updated_at) VALUES ('s9','missing','x',0,0,$now,$now)")
            fail("orphan subtask must be rejected")
        } catch (expected: SQLException) {
        }
    }

    @Test
    fun failedBatchInsideTransactionLeavesDataUntouched() {
        // Mirrors DataRepository.replaceAll: everything happens in one transaction.
        insertTask("keep")
        conn.autoCommit = false
        try {
            exec("DELETE FROM tasks")
            insertTask("new1")
            insertTask("new1") // duplicate id -> constraint failure
            conn.commit()
            fail("expected failure")
        } catch (expected: SQLException) {
            conn.rollback()
        } finally {
            conn.autoCommit = true
        }
        assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE id='keep'"))
        assertEquals(0, count("SELECT COUNT(*) FROM tasks WHERE id='new1'"))
    }

    @Test
    fun persianTextRoundTrips() {
        insertTask("fa")
        val title = conn.createStatement().use { st -> st.executeQuery("SELECT title FROM tasks WHERE id='fa'").use { rs -> rs.next(); rs.getString(1) } }
        assertTrue(title == "عنوان")
    }
}
