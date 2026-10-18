package app.androidrik.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Establishes a version baseline without inventing evidence for old proposals.
 * IDs, library ownership, content, telemetry and the complete earlier migration chain are unchanged.
 */
object Migration_40_41 : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `expected_revision` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `operation_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `depends_on` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `applied_target_id` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `applied_revision` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_proposal` ADD COLUMN `contract_version` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `memory_id` INTEGER NOT NULL,
                `public_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `snapshot` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `recorded_at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_history_assistant_id_memory_id` " +
                "ON `memory_history` (`assistant_id`, `memory_id`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_trash` (
                `memory_id` INTEGER NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `public_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `snapshot` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `deleted_at` INTEGER NOT NULL,
                PRIMARY KEY(`memory_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_trash_assistant_id_deleted_at` " +
                "ON `memory_trash` (`assistant_id`, `deleted_at`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_trash_public_id` ON `memory_trash` (`public_id`)")
        // No expected_revision is backfilled. Even old creates have unknown dependency semantics.
        // Keep the old draft and its audit row, but never let it overwrite a post-upgrade memory.
        db.execSQL(
            "UPDATE `memory_proposal` SET `decision` = 'stale', " +
                "`decided_at` = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE `decision` = 'pending'",
        )
    }
}
