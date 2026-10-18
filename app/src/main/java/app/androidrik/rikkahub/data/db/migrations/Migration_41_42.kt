package app.androidrik.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds source-attributed read telemetry without reclassifying legacy reads.
 *
 * All four columns start at 0: existing `read_count` / `last_read_at` stay grandfathered
 * unknown-source credit. Business state, IDs, history and trash snapshots are untouched.
 */
object Migration_41_42 : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `chat_read_count` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `last_chat_read_at` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `consolidation_read_count` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `last_consolidation_read_at` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
