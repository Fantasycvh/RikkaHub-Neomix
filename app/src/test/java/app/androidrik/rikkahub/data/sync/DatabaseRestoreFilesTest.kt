package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据库恢复的文件操作（[DatabaseRestoreFiles]）。
 *
 * 这层刻意与 Android/SQLite 解耦，因此能纯 JVM 单测；对应修复见
 * [BackupArchiveEngine.applyStagedDatabaseFiles]：恢复主库前必须清掉陈旧的
 * `-wal`/`-shm`，否则旧 WAL 会在下次打开时回放、盖掉刚恢复的数据。
 */
class DatabaseRestoreFilesTest {

    private fun tempDir(tag: String): File =
        File("build/tmp/test-restore-$tag").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun `deletes both sidecar files when present`() {
        val dir = tempDir("del-both")
        File(dir, "rikka_hub").writeText("main")
        File(dir, "rikka_hub-wal").writeText("wal")
        File(dir, "rikka_hub-shm").writeText("shm")

        val deleted = DatabaseRestoreFiles.deleteSidecarFiles(dir, "rikka_hub")

        assertEquals(listOf("rikka_hub-shm", "rikka_hub-wal"), deleted.sorted())
        assertFalse(File(dir, "rikka_hub-wal").exists())
        assertFalse(File(dir, "rikka_hub-shm").exists())
        // 主库与无关文件都不该被动
        assertTrue(File(dir, "rikka_hub").exists())
    }

    @Test
    fun `missing sidecars are ignored`() {
        val dir = tempDir("del-none")
        File(dir, "rikka_hub").writeText("main")
        File(dir, "rikka_hub-journal").writeText("journal")

        val deleted = DatabaseRestoreFiles.deleteSidecarFiles(dir, "rikka_hub")

        assertTrue(deleted.isEmpty())
        // -journal 不是 WAL 伴随文件，不应被删
        assertTrue(File(dir, "rikka_hub-journal").exists())
    }

    @Test
    fun `moveIntoPlace replaces existing target`() {
        val dir = tempDir("move-replace")
        val target = File(dir, "rikka_hub")
        target.writeText("old")
        val source = File(dir, "restore_rikka_hub.db")
        source.writeText("new")

        DatabaseRestoreFiles.moveIntoPlace(source, target)

        assertEquals("new", target.readText())
        assertFalse("source must be gone after move", source.exists())
    }

    @Test
    fun `moveIntoPlace creates parent dir`() {
        val dir = tempDir("move-mkdir")
        val source = File(dir, "incoming")
        source.writeText("payload")
        val target = File(File(dir, "nested"), "rikka_hub")

        DatabaseRestoreFiles.moveIntoPlace(source, target)

        assertEquals("payload", target.readText())
        assertFalse(source.exists())
    }

    /**
     * 回归保护：换入主库后，目录里不能残留任何陈旧伴随文件——它们正是
     * 「恢复成功但数据没变」的根因。清理 + 交换的组合应留下干净目录。
     */
    @Test
    fun `sidecar cleanup plus move leaves no stale companion`() {
        val dir = tempDir("combo")
        // 现库现场：旧主库 + 旧 WAL/SHM
        File(dir, "rikka_hub").writeText("old-main")
        File(dir, "rikka_hub-wal").writeText("stale-wal")
        File(dir, "rikka_hub-shm").writeText("stale-shm")
        // 备份只含主库（上游导出包形态）
        val staged = File(dir, "restore_rikka_hub.db")
        staged.writeText("restored-main")

        DatabaseRestoreFiles.deleteSidecarFiles(dir, "rikka_hub")
        DatabaseRestoreFiles.moveIntoPlace(staged, File(dir, "rikka_hub"))

        assertEquals("restored-main", File(dir, "rikka_hub").readText())
        assertFalse(File(dir, "rikka_hub-wal").exists())
        assertFalse(File(dir, "rikka_hub-shm").exists())
    }
}
