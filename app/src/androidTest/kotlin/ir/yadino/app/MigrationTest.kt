package ir.yadino.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.yadino.app.data.local.YadinoDatabase
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
        helper.runMigrationsAndValidate(TEST_DB, YadinoDatabase.VERSION, true, *YadinoDatabase.MIGRATIONS).close()
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
