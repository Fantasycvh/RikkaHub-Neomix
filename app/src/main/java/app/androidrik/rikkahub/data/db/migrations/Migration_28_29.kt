package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_28_29"

/**
 * 28 → 29：记忆表新增 `importance`（重要度）与 `updated_at`（最近修改时间）。
 *
 * 背景：注入策略从「取最新 N 条」升级为「打分校准 + 预算截断」
 * （`importance × (0.55 + 0.45 × 时效衰减)`，见 `docs/design/design-memory-scoring.md`）。
 * 打分需要两个新字段：重要度（谁更该被记住）与时间基准（衰减多久了）。
 *
 * 存量行回填策略：
 * - `importance` 用默认 0.6（普通偏好档）——API 是 `ADD COLUMN ... DEFAULT`，由 SQLite 自动回填；
 * - `updated_at` 回填**迁移时刻**而非 0：真实历史时间不可考，回填 0 会让所有老记忆
 *   被当成「极旧」而静默沉底；回填当下让它们在升级时刻统一起算，排序由 importance 决定。
 *
 * 仅 ADD COLUMN（不重建表），纯 SQL 无逐行读写。
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 28 to 29 (memory importance/updated_at)")
        DatabaseMigrationTracker.onMigrationStart(28, 29)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `importance` REAL NOT NULL DEFAULT 0.6")
            db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE `MemoryEntity` SET `updated_at` = CAST(strftime('%s','now') AS INTEGER) * 1000"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 28 to 29 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
