package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 降级隔离的纯 JVM 测试（设计稿 design-db-downgrade-rebuild.md §7）。
 * readVersion 注入假实现，不碰 Android SQLiteDatabase；PendingRestoreStore 同为纯 JVM 可直接联测。
 */
class DowngradeQuarantineStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dbFile: File

    @org.junit.Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        dbFile = File(filesDir, "databases/rikka_hub")
    }

    /** 假 user_version 读取：无论库内容是什么都返回 [version]。 */
    private fun readAs(version: Int) = { _: File -> version }

    private fun writeFakeDb(mainBytes: Long = 100L, withWal: Boolean = true, withShm: Boolean = true) {
        dbFile.parentFile!!.mkdirs()
        dbFile.writeBytes(ByteArray(mainBytes.toInt()))
        if (withWal) File(dbFile.path + "-wal").writeBytes(ByteArray(10))
        if (withShm) File(dbFile.path + "-shm").writeBytes(ByteArray(10))
    }

    // ---------- 检测 ----------

    @Test
    fun `无库文件不动作`() {
        assertNull(DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57)))
        assertFalse(DowngradeQuarantineStore.quarantineDir(filesDir).exists())
    }

    @Test
    fun `版本不高于当前版本不动作且原件未动`() {
        writeFakeDb()
        assertNull(DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(56)))
        assertTrue(dbFile.isFile)
        assertTrue(File(dbFile.path + "-wal").isFile)
    }

    // ---------- 隔离 happy path ----------

    @Test
    fun `降级时三件套隔离-原件删除-marker完整`() {
        writeFakeDb(mainBytes = 128)
        val marker = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(58))

        assertNotNull(marker)
        assertEquals(58, marker!!.schemaVersion)
        assertEquals(128L, marker.databaseSize)
        assertEquals(10L, marker.walSize)
        assertEquals(10L, marker.shmSize)

        // 原件全没了（Room 接下来按当前版本全新建库）
        assertFalse(dbFile.exists())
        assertFalse(File(dbFile.path + "-wal").exists())
        assertFalse(File(dbFile.path + "-shm").exists())

        // committed 目录 + marker 内容一致
        val dir = File(DowngradeQuarantineStore.quarantineDir(filesDir), marker.quarantinedAt.toString())
        assertTrue(dir.isDirectory)
        assertEquals(128L, File(dir, "rikka_hub").length())
        assertEquals(10L, File(dir, "rikka_hub-wal").length())
        val entries = DowngradeQuarantineStore.listEntries(filesDir)
        assertEquals(1, entries.size)
        assertEquals(58, entries[0].marker.schemaVersion)
        assertFalse(entries[0].dismissed)
    }

    @Test
    fun `无伴随文件时 marker 记 null`() {
        writeFakeDb(withWal = false, withShm = false)
        val marker = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))
        assertNotNull(marker)
        assertNull(marker!!.walSize)
        assertNull(marker.shmSize)
    }

    // ---------- 崩溃窗口收敛 ----------

    @Test
    fun `提交后删原件前崩溃-下次启动重新隔离-prune兜底`() {
        writeFakeDb()
        DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))
        // 模拟「commit 后、删原件前」死了：原件回到原位
        writeFakeDb()
        val marker = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))
        assertNotNull(marker)
        assertEquals(2, DowngradeQuarantineStore.listEntries(filesDir).size)
        assertFalse(dbFile.exists())
    }

    @Test
    fun `tmp 孤儿目录每次隔离前清理`() {
        val junk = File(DowngradeQuarantineStore.quarantineDir(filesDir), "tmp-123")
        junk.mkdirs()
        File(junk, "rikka_hub").writeBytes(ByteArray(5))
        writeFakeDb()
        DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))
        assertFalse(junk.exists())
    }

    // ---------- 滚动保留 ----------

    @Test
    fun `滚动保留3份-最旧被删`() {
        repeat(3) { i ->
            writeFakeDb()
            val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))!!
            // 人为拉开时间序，避免同毫秒
            File(DowngradeQuarantineStore.quarantineDir(filesDir), m.quarantinedAt.toString())
                .renameTo(File(DowngradeQuarantineStore.quarantineDir(filesDir), "${1000 + i}"))
        }
        writeFakeDb()
        DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))
        val entries = DowngradeQuarantineStore.listEntries(filesDir)
        assertEquals(3, entries.size)
        // 1000（最旧）已被滚掉
        assertTrue(entries.none { it.dir.name == "1000" })
    }

    // ---------- 恢复扫描 ----------

    @Test
    fun `recoverableEntries 版本闸-过高不提示`() {
        writeFakeDb()
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(58))!!
        // 当前应用仍是 56：隔离件 58 开不了，不提示
        assertTrue(DowngradeQuarantineStore.recoverableEntries(filesDir, 56).isEmpty())
        // 升级回 58：可恢复
        val recoverable = DowngradeQuarantineStore.recoverableEntries(filesDir, 58)
        assertEquals(m.quarantinedAt.toString(), recoverable.single().dir.name)
    }

    @Test
    fun `dismissed 后不再出现在 recoverable`() {
        writeFakeDb()
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))!!
        DowngradeQuarantineStore.markDismissed(filesDir, m.quarantinedAt.toString())
        assertTrue(DowngradeQuarantineStore.recoverableEntries(filesDir, 57).isEmpty())
        assertTrue(DowngradeQuarantineStore.listEntries(filesDir).single().dismissed)
    }

    // ---------- stage 转接 ----------

    @Test
    fun `stageForRestore 转接进 PendingRestore 并可完整应用`() {
        writeFakeDb(mainBytes = 128)
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))!!

        DowngradeQuarantineStore.stageForRestore(filesDir, m.quarantinedAt.toString(), 57)
        assertTrue(PendingRestoreStore.hasPending(filesDir))
        // 隔离件目录已清
        assertFalse(File(DowngradeQuarantineStore.quarantineDir(filesDir), m.quarantinedAt.toString()).exists())

        // 端到端：下次启动的 applyIfPending 直接吃下这份 staged 库
        val target = File(filesDir, "databases/target.db")
        val applied = PendingRestoreStore.applyIfPending(filesDir, target) { /* validate no-op */ }
        assertTrue(applied)
        assertEquals(128L, target.length())
        assertEquals(10L, File(target.path + "-wal").length())
    }

    @Test
    fun `已有待应用换库时 stage 拒绝`() {
        writeFakeDb()
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))!!
        PendingRestoreStore.stage(
            filesDir,
            File(tmp.newFolder(), "a.db").apply { writeBytes(ByteArray(1)) },
            null,
            null,
        )
        try {
            DowngradeQuarantineStore.stageForRestore(filesDir, m.quarantinedAt.toString(), 57)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already pending"))
        }
    }

    @Test
    fun `stage 版本闸不过则拒绝且不产生 pending`() {
        writeFakeDb()
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(58))!!
        try {
            DowngradeQuarantineStore.stageForRestore(filesDir, m.quarantinedAt.toString(), 56)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("incompatible"))
        }
        assertFalse(PendingRestoreStore.hasPending(filesDir))
        // 隔离件还在（fail-closed，不丢数据）
        assertTrue(DowngradeQuarantineStore.listEntries(filesDir).isNotEmpty())
    }

    @Test
    fun `stage 时 payload 与 marker 对不上则拒绝`() {
        writeFakeDb(mainBytes = 128)
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))!!
        // 篡改 payload：截短主库
        File(DowngradeQuarantineStore.quarantineDir(filesDir), m.quarantinedAt.toString())
            .resolve("rikka_hub").writeBytes(ByteArray(10))
        try {
            DowngradeQuarantineStore.stageForRestore(filesDir, m.quarantinedAt.toString(), 57)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
        assertFalse(PendingRestoreStore.hasPending(filesDir))
    }

    // ---------- 删除 ----------

    @Test
    fun `deleteEntry 移除隔离件`() {
        writeFakeDb()
        val m = DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, 56, readAs(57))!!
        DowngradeQuarantineStore.deleteEntry(filesDir, m.quarantinedAt.toString())
        assertTrue(DowngradeQuarantineStore.listEntries(filesDir).isEmpty())
    }
}
