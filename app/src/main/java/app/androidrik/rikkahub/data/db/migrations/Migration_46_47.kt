package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

/** Semantic defaults only affect live rows; historical JSON remains an immutable audit record. */
val Migration_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(46, 47)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'topic'")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `task_status` TEXT NOT NULL DEFAULT 'open'")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `due_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `memoryentity` SET `kind` = 'profile' WHERE `is_anchor` = 1")
            db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `kind` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `due_at` INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
            Log.i("Migration_46_47", "migrate: memory kinds and task lifecycle added")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
