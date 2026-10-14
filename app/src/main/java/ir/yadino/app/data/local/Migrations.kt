package ir.yadino.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL of every schema migration. Kept free of Android types so that the JVM tests can run
 * it on a real SQLite engine and compare the result with the exported Room schema of the
 * target version (app/schemas).
 */
object MigrationSql {

    /** Version 1 → 2 (app 1.3): plan templates and their items. */
    val V1_TO_V2: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `plan_templates` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
            "`span_days` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `template_items` (`id` TEXT NOT NULL, `template_id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `kind` TEXT NOT NULL, `priority` INTEGER NOT NULL, `category_id` TEXT, `tags` TEXT NOT NULL, " +
            "`day_offset` INTEGER, `start_minute` INTEGER, `end_minute` INTEGER, `due_offset` INTEGER, `due_minute` INTEGER, " +
            "`reminder_offset` INTEGER, `goal_id` TEXT, `subtasks` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`template_id`) REFERENCES `plan_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
            "FOREIGN KEY(`goal_id`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_template_items_template_id` ON `template_items` (`template_id`)",
        "CREATE INDEX IF NOT EXISTS `index_template_items_category_id` ON `template_items` (`category_id`)",
        "CREATE INDEX IF NOT EXISTS `index_template_items_goal_id` ON `template_items` (`goal_id`)",
    )
}

/** Room migrations; every schema version bump adds one here (no destructive fallback). */
object Migrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MigrationSql.V1_TO_V2.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
