package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_69_70"

/**
 * usage_record 加按页计费列 page_count（DeepSeek 适配备料 03 §2.5，本仓 DB 69→70）。
 *
 * DeepSeek OCR 按成功页数计费（¥1.5/千页），与 token 无关——没有这一列，页费在
 * 统计流水里完全不可见。NOT NULL DEFAULT 0：既有行全 0，统计侧对 0 页行不展示页数。
 * 手写迁移而非 AutoMigration：本仓 Room 配置下 AutoMigration 需要目标 schema
 * JSON 预先导出（与 Migration_68_69 同款取舍），纯 ADD COLUMN 手写更直接。
 */
val Migration_69_70 = object : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(69, 70)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `page_count` INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: usage_record.page_count added")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
