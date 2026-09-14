package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_27_28"

/**
 * 27 → 28：记忆表新增对外 opaque id（`public_id`）。
 *
 * 背景：原先对外暴露自增 `id`——删除留下空洞导致编号不连续（观感差），且编号泄漏
 * 记忆条数（上游 #1830 报告者实证「deepseek 把编号 28 理解成有 28 条记忆」）。
 * 改为随机 token 后两个问题一起根治，且**永不需要重排**。
 *
 * 存量行用 SQLite 的 `lower(hex(randomblob(4)))` 就地回填（4 字节 = 8 位十六进制，
 * 与新建行的 PUBLIC_ID_LENGTH 一致）。纯 SQL 无需逐行读写，大表也快。
 *
 * 仅 ADD COLUMN（不重建表）：新列有默认值且允许重复，Room 的 schema 校验
 * 只比对列定义，不会因重复值失败。
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 27 to 28 (memory public_id)")
        DatabaseMigrationTracker.onMigrationStart(27, 28)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `public_id` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `MemoryEntity` SET `public_id` = lower(hex(randomblob(4)))")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 27 to 28 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
