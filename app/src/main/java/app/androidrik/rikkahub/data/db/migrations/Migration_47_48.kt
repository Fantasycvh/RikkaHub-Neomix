package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

/** 工作区类型与 SSH 配置；存量全部是本地工作区，行为不变。 */
val Migration_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(47, 48)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT")
            db.setTransactionSuccessful()
            Log.i("Migration_47_48", "migrate: workspace type and ssh config added")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
