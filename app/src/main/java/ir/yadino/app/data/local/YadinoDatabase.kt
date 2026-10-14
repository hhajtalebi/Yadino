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
import ir.yadino.app.data.local.entity.CategoryEntity
import ir.yadino.app.data.local.entity.DeletionEntity
import ir.yadino.app.data.local.entity.GoalEntity
import ir.yadino.app.data.local.entity.HabitEntity
import ir.yadino.app.data.local.entity.HabitLogEntity
import ir.yadino.app.data.local.entity.MilestoneEntity
import ir.yadino.app.data.local.entity.NoteDraftEntity
import ir.yadino.app.data.local.entity.NoteEntity
import ir.yadino.app.data.local.entity.SubtaskEntity
import ir.yadino.app.data.local.entity.TaskCompletionEntity
import ir.yadino.app.data.local.entity.TaskEntity

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

    companion object {
        const val VERSION = 1
        const val NAME = "yadino.db"

        /**
         * Schema migrations. Every schema change must add a [Migration] here and bump
         * [VERSION]; destructive fallback is intentionally NOT enabled so user data is
         * never dropped silently. Exported schemas live in `app/schemas`.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): YadinoDatabase =
            Room.databaseBuilder(context.applicationContext, YadinoDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
