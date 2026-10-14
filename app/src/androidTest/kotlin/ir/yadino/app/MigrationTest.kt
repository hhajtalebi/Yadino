package ir.yadino.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.yadino.app.data.local.Migrations
import ir.yadino.app.data.local.YadinoDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the database schema against the exported schemas in app/schemas and runs all
 * migrations from version 1 to the current version. Every new schema version must add a
 * migration to [YadinoDatabase.MIGRATIONS]; this test then verifies it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), YadinoDatabase::class.java)

    @Test
    fun migrateAllVersions() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO tasks (id,title,description,kind,priority,status,category_id,tags,date,start_minute,end_minute,due_date,due_minute," +
                    "reminder_offset,recurrence,recurrence_start,goal_id,source_note_id,sort_order,is_archived,completed_at,created_at,updated_at) " +
                    "VALUES ('t1','کار','','TASK',1,'TODO',NULL,'',20375,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,0,NULL,0,0)",
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, YadinoDatabase.VERSION, true, *YadinoDatabase.MIGRATIONS).use { db ->
            // the data written by version 1 is still there and the new tables are usable
            db.query("SELECT title FROM tasks WHERE id = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("کار", c.getString(0))
            }
            db.execSQL("INSERT INTO plan_templates (id,name,description,span_days,created_at,updated_at) VALUES ('p1','روز کاری','',1,0,0)")
            db.query("SELECT COUNT(*) FROM plan_templates").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test
    fun migrationFrom1To2AddsTemplates() {
        helper.createDatabase(TEST_DB_2, 1).close()
        helper.runMigrationsAndValidate(TEST_DB_2, 2, true, Migrations.MIGRATION_1_2).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('plan_templates', 'template_items')").use { c ->
                assertEquals(2, c.count)
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
        private const val TEST_DB_2 = "migration-test-1-2"
    }
}
