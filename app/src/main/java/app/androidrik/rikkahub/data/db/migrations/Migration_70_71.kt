package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_70_71"

/**
 * usage_record 加 DeepSeek 内置工具计费次数三列（DeepSeek 适配备料 P2-1，本仓 DB 70→71）。
 *
 * 内置 web tools 的搜索/抓取与 token 分开计费（¥5/千次各自）——不落这三列，
 * 「这轮搜索花了多少」在流水里完全不可见。NOT NULL DEFAULT 0：普通请求行全 0。
 * 手写迁移而非 AutoMigration：与 Migration_69_70 同款取舍（纯 ADD COLUMN）。
 */
val Migration_70_71 = object : Migration(70, 71) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(70, 71)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `tool_search_count` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `tool_fetch_count` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `tool_image_search_count` INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: usage_record tool count columns added")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
