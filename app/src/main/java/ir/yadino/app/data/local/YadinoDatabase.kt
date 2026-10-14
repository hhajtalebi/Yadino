package ir.yadino.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import ir.yadino.app.data.local.dao.CategoryDao
import ir.yadino.app.data.local.dao.CompletionDao
import ir.yadino.app.data.local.dao.DeletionDao
import ir.yadino.app.data.local.dao.GoalDao
import ir.yadino.app.data.local.dao.HabitDao
import ir.yadino.app.data.local.dao.HabitLogDao
import ir.yadino.app.data.local.dao.MilestoneDao
import ir.yadino.app.data.local.dao.NoteDao
import ir.yadino.app.data.local.dao.NoteDraftDao
import ir.yadino.app.data.local.dao.SubtaskDao
import ir.yadino.app.data.local.dao.TaskDao
import ir.yadino.app.data.local.dao.TemplateDao
import ir.yadino.app.data.local.entity.CategoryEntity
import ir.yadino.app.data.local.entity.DeletionEntity
import ir.yadino.app.data.local.entity.GoalEntity
import ir.yadino.app.data.local.entity.HabitEntity
import ir.yadino.app.data.local.entity.HabitLogEntity
import ir.yadino.app.data.local.entity.MilestoneEntity
import ir.yadino.app.data.local.entity.NoteDraftEntity
import ir.yadino.app.data.local.entity.NoteEntity
import ir.yadino.app.data.local.entity.PlanTemplateEntity
import ir.yadino.app.data.local.entity.SubtaskEntity
import ir.yadino.app.data.local.entity.TaskCompletionEntity
import ir.yadino.app.data.local.entity.TaskEntity
import ir.yadino.app.data.local.entity.TemplateItemEntity

@Database(
    entities = [
        CategoryEntity::class,
        GoalEntity::class,
        NoteEntity::class,
        TaskEntity::class,
        SubtaskEntity::class,
        TaskCompletionEntity::class,
        MilestoneEntity::class,
        HabitEntity::class,
        HabitLogEntity::class,
        NoteDraftEntity::class,
        DeletionEntity::class,
        PlanTemplateEntity::class,
        TemplateItemEntity::class,
    ],
    version = YadinoDatabase.VERSION,
    exportSchema = true,
)
abstract class YadinoDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun completionDao(): CompletionDao
    abstract fun noteDao(): NoteDao
    abstract fun noteDraftDao(): NoteDraftDao
    abstract fun goalDao(): GoalDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun habitDao(): HabitDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun categoryDao(): CategoryDao
    abstract fun deletionDao(): DeletionDao
    abstract fun templateDao(): TemplateDao

    companion object {
        /** 1: app 1.0–1.2; 2: app 1.3 (plan templates). */
        const val VERSION = 2
        const val NAME = "yadino.db"

        /**
         * Schema migrations. Every schema change must add a [Migration] (see [Migrations])
         * and bump [VERSION]; destructive fallback is intentionally NOT enabled so user data
         * is never dropped silently. Exported schemas live in `app/schemas`.
         */
        val MIGRATIONS: Array<Migration> = Migrations.ALL

        fun build(context: Context): YadinoDatabase =
            Room.databaseBuilder(context.applicationContext, YadinoDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
