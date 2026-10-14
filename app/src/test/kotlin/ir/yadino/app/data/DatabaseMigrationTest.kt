package ir.yadino.app.data

import ir.yadino.app.data.local.MigrationSql
import ir.yadino.domain.json.Json
import ir.yadino.domain.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the real migration SQL ([MigrationSql]) on a SQLite database created from the
 * exported version 1 schema, with user data in it, and compares the result with a database
 * created from the exported version 2 schema: same tables, columns, keys, foreign keys and
 * indices — and the existing rows are untouched. (On a device `MigrationTest` additionally
 * runs the migration through Room's own validation.)
 */
class DatabaseMigrationTest {

    private lateinit var schemaDir: File
    private lateinit var migrated: Connection
    private lateinit var fresh: Connection
    private val now = 1_760_000_000_000L

    private fun schema(version: Int): JsonObject =
        (Json.parse(File(schemaDir, "$version.json").readText()) as JsonObject).obj("database")!!

    private fun entities(db: JsonObject) = db.array("entities")!!.items.map { it as JsonObject }

    /** CREATE statements of a schema, exactly as Room writes them. */
    private fun createStatements(db: JsonObject): List<String> = entities(db).flatMap { e ->
        val table = e.string("tableName")!!
        listOf(e.string("createSql")!!.replace("\${TABLE_NAME}", table)) +
            e.array("indices")?.items.orEmpty().map { (it as JsonObject).string("createSql")!!.replace("\${TABLE_NAME}", table) }
    }

    private fun open(statements: List<String>): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            val out = ArrayList<List<String?>>()
            while (rs.next()) out += (1..n).map { rs.getString(it) }
            out
        }
    }

    private fun Connection.count(sql: String): Int = rows(sql).single().single()!!.toInt()

    private fun Connection.tables(): List<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it[0]!! }

    /** Columns, foreign keys and indices of [table] in a comparable form. */
    private fun Connection.describe(table: String): List<String> {
        val columns = rows("PRAGMA table_info(`$table`)").map { r -> "col ${r[1]} ${r[2]} notnull=${r[3]} pk=${r[5]}" }
        val keys = rows("PRAGMA foreign_key_list(`$table`)").map { r -> "fk ${r[3]} -> ${r[2]}.${r[4]} update=${r[5]} delete=${r[6]}" }.sorted()
        val indices = rows("PRAGMA index_list(`$table`)").filter { !it[1]!!.startsWith("sqlite_autoindex") }.map { r ->
            val cols = rows("PRAGMA index_info(`${r[1]}`)").sortedBy { it[0]!!.toInt() }.joinToString(",") { it[2]!! }
            "index ${r[1]} unique=${r[2]} ($cols)"
        }.sorted()
        return columns + keys + indices
    }

    @Before
    fun setUp() {
        schemaDir = listOf(File("schemas"), File("app/schemas"))
            .map { File(it, "ir.yadino.app.data.local.YadinoDatabase") }
            .first { it.isDirectory }
        migrated = open(createStatements(schema(1)))
        // data written by app 1.2 (database version 1)
        migrated.exec("INSERT INTO categories (id,name,color,kind,is_default,sort_order,created_at,updated_at) VALUES ('cat','کار','SKY','GENERAL',1,0,$now,$now)")
        migrated.exec("INSERT INTO goals (id,title,description,term,priority,status,is_archived,start_date,deadline,color,category_id,manual_progress,completed_at,created_at,updated_at) " +
            "VALUES ('goal','هدف','','SHORT',1,'ACTIVE',0,20370,NULL,'GREEN',NULL,NULL,NULL,$now,$now)")
        migrated.exec("INSERT INTO tasks (id,title,description,kind,priority,status,category_id,tags,date,start_minute,end_minute,due_date,due_minute,reminder_offset," +
            "recurrence,recurrence_start,goal_id,source_note_id,sort_order,is_archived,completed_at,created_at,updated_at) " +
            "VALUES ('task','جلسه','','EVENT',2,'TODO','cat','کار',20375,600,660,NULL,NULL,15,NULL,NULL,'goal',NULL,1,0,NULL,$now,$now)")
        MigrationSql.V1_TO_V2.forEach { migrated.exec(it) }
        fresh = open(createStatements(schema(2)))
    }

    @After
    fun tearDown() {
        migrated.close()
        fresh.close()
    }

    @Test
    fun migratedSchemaEqualsTheExportedVersion2Schema() {
        assertEquals(fresh.tables(), migrated.tables())
        for (table in fresh.tables()) assertEquals(table, fresh.describe(table), migrated.describe(table))
    }

    @Test
    fun migrationCreatesExactlyTheNewTablesOfVersion2() {
        val v1 = createStatements(schema(1)).toSet()
        val added = createStatements(schema(2)).filter { it !in v1 }
        assertEquals(added.sorted(), MigrationSql.V1_TO_V2.sorted())
        assertEquals(2, schema(2).int("version"))
    }

    @Test
    fun existingDataSurvivesAndNewTablesWork() {
        assertEquals(listOf(listOf("جلسه", "cat", "goal", "600")), migrated.rows("SELECT title, category_id, goal_id, start_minute FROM tasks"))
        migrated.exec("INSERT INTO plan_templates (id,name,description,span_days,created_at,updated_at) VALUES ('tpl','روز کاری','',1,$now,$now)")
        migrated.exec("INSERT INTO template_items (id,template_id,title,description,kind,priority,category_id,tags,day_offset,start_minute,end_minute,due_offset," +
            "due_minute,reminder_offset,goal_id,subtasks,sort_order,created_at,updated_at) " +
            "VALUES ('i1','tpl','ورزش','','TASK',1,'cat','',0,420,465,NULL,NULL,5,'goal','گرم کردن\nدویدن',0,$now,$now)")
        // deleting a category or goal keeps the item but unlinks it
        migrated.exec("DELETE FROM tasks")
        migrated.exec("DELETE FROM categories WHERE id='cat'")
        migrated.exec("DELETE FROM goals WHERE id='goal'")
        assertEquals(1, migrated.count("SELECT COUNT(*) FROM template_items WHERE category_id IS NULL AND goal_id IS NULL"))
        // deleting the template removes its items
        migrated.exec("DELETE FROM plan_templates WHERE id='tpl'")
        assertEquals(0, migrated.count("SELECT COUNT(*) FROM template_items"))
        // orphans are rejected
        val rejected = runCatching {
            migrated.exec("INSERT INTO template_items (id,template_id,title,description,kind,priority,category_id,tags,day_offset,start_minute,end_minute,due_offset," +
                "due_minute,reminder_offset,goal_id,subtasks,sort_order,created_at,updated_at) " +
                "VALUES ('i2','missing','x','','TASK',1,NULL,'',0,NULL,NULL,NULL,NULL,NULL,NULL,'',0,$now,$now)")
        }.isFailure
        assertTrue("orphan template item must be rejected", rejected)
    }
}
