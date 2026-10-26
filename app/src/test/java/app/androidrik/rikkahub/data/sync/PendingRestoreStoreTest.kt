package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B 档启动期换库的状态机测试（对标 Yuihub 3.0.1 `PendingRestoreStoreTest` 170 行蓝图）。
 *
 * 纯 JVM：不碰 android.*，入库校验用注入 lambda（生产实现是 SQLiteDatabase，JVM 不可用）。
 * 每个场景钉一条不变量，对应设计稿 §6 失败矩阵的一行。
 */
class PendingRestoreStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dbDir: File

    private val dbFile: File get() = File(dbDir, "rikka_hub")
    private val okValidator: (File) -> Unit = {}

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        dbDir = tmp.newFolder("databases")
    }

    // ---- 素材辅助 ----

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun stagedMainFile(content: String): File =
        tmp.newFile("src_staged.db").apply { writeText(content) }

    private fun stagedSidecarFile(content: String): File =
        tmp.newFile("src_staged.db-wal").apply { writeText(content) }

    private val throwingValidator: (File) -> Unit = { throw IllegalStateException("staged db failed integrity check") }

    private fun readTextOrNull(file: File): String? = file.takeIf(File::isFile)?.readText()

    // ---- 1. staging 不触碰现库 ----

    @Test
    fun `staging keeps live database untouched`() {
        write(dbFile, "LIVE-A")
        val staged = stagedMainFile("STAGED-B")

        PendingRestoreStore.stage(filesDir, staged, null, null)

        assertEquals("LIVE-A", dbFile.readText())
        assertTrue(PendingRestoreStore.hasPending(filesDir))
        assertFalse(PendingRestoreStore.hasApplied(filesDir))
    }

    // ---- 2. pending 未消费前二次 stage 拒绝 ----

    @Test
    fun `second stage while pending is rejected`() {
        PendingRestoreStore.stage(filesDir, stagedMainFile("B"), null, null)

        try {
            PendingRestoreStore.stage(filesDir, stagedMainFile("C"), null, null)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("already pending"))
        }
    }

    // ---- 3. 校验失败：现库未动、marker 保留（fail-closed，可重试） ----

    @Test
    fun `validation failure preserves live database and pending marker`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("BAD"), null, null)

        try {
            PendingRestoreStore.applyIfPending(filesDir, dbFile, throwingValidator)
            throw AssertionError("expected validator failure")
        } catch (expected: IllegalStateException) {
            // 预期路径
        }
        assertEquals("LIVE-A", dbFile.readText())
        assertTrue(PendingRestoreStore.hasPending(filesDir))
        assertFalse(PendingRestoreStore.hasApplied(filesDir))
        assertFalse(File(filesDir, "restore_applied").isDirectory)
    }

    // ---- 4. payload 残缺（staged 丢失 / 大小不符）fail-closed ----

    @Test
    fun `missing staged payload fails closed with marker retained`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("B"), null, null)
        File(PendingRestoreStore.pendingDir(filesDir), "staged.db").delete()

        try {
            PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("missing or incomplete"))
        }
        assertEquals("LIVE-A", dbFile.readText())
        assertTrue(PendingRestoreStore.hasPending(filesDir))
    }

    // ---- 5. 正常 apply：现库让位进 rollback、staged 家族到位、marker 消费 ----

    @Test
    fun `apply installs staged family and rolls live aside`() {
        write(dbFile, "LIVE-A")
        write(File(dbDir, "rikka_hub-wal"), "LIVE-WAL")
        val stagedWal = stagedSidecarFile("STAGED-WAL")
        PendingRestoreStore.stage(filesDir, stagedMainFile("STAGED-B"), stagedWal, null)

        assertTrue(PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator))

        assertEquals("STAGED-B", dbFile.readText())
        assertEquals("STAGED-WAL", File(dbDir, "rikka_hub-wal").readText())
        assertEquals("LIVE-A", File(filesDir, "restore_applied/rollback.db").readText())
        assertEquals("LIVE-WAL", File(filesDir, "restore_applied/rollback.db-wal").readText())
        assertFalse(PendingRestoreStore.hasPending(filesDir))
        assertTrue(PendingRestoreStore.hasApplied(filesDir))
    }

    // ---- 6. 全新安装（现库不存在）----

    @Test
    fun `apply without prior database does not create rollback copy`() {
        val staged = stagedMainFile("STAGED-B")
        PendingRestoreStore.stage(filesDir, staged, null, null)

        assertTrue(PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator))

        assertEquals("STAGED-B", dbFile.readText())
        assertFalse(File(filesDir, "restore_applied/rollback.db").isFile)
        assertTrue(PendingRestoreStore.hasApplied(filesDir))
    }

    // ---- 7. 让位后、换入前崩溃 → 下次 apply 先归位再重走（幂等） ----

    @Test
    fun `apply recovers from crash between rename-aside and swap-in`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("STAGED-B"), null, null)
        // 手工构造「让位后崩溃」现场：原库被移进 rollback，pending/applied 原样
        val rollbackDir = File(filesDir, "restore_applied").apply { mkdirs() }
        dbFile.renameTo(File(rollbackDir, "rollback.db"))

        assertTrue(PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator))

        assertEquals("STAGED-B", dbFile.readText())
        // 重走流程重新让位，rollback 里还是原库
        assertEquals("LIVE-A", File(filesDir, "restore_applied/rollback.db").readText())
        assertTrue(PendingRestoreStore.hasApplied(filesDir))
    }

    // ---- 8. 换库后未确认 → 自动回滚原库三件套 ----

    @Test
    fun `rollbackIfUnconfirmed restores previous database family`() {
        write(dbFile, "LIVE-A")
        write(File(dbDir, "rikka_hub-wal"), "LIVE-WAL")
        write(File(dbDir, "rikka_hub-shm"), "LIVE-SHM")
        PendingRestoreStore.stage(filesDir, stagedMainFile("STAGED-B"), null, null)
        PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)
        assertEquals("STAGED-B", dbFile.readText())

        PendingRestoreStore.rollbackIfUnconfirmed(filesDir, dbFile)

        assertEquals("LIVE-A", dbFile.readText())
        assertEquals("LIVE-WAL", File(dbDir, "rikka_hub-wal").readText())
        assertEquals("LIVE-SHM", File(dbDir, "rikka_hub-shm").readText())
        assertFalse(PendingRestoreStore.hasApplied(filesDir))
    }

    // ---- 9. 确认后删 rollback；之后再回滚是无操作 ----

    @Test
    fun `confirmApplied deletes rollback and later rollback is a no-op`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("STAGED-B"), null, null)
        PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)

        PendingRestoreStore.confirmApplied(filesDir)

        assertFalse(File(filesDir, "restore_applied").exists())
        assertFalse(PendingRestoreStore.hasApplied(filesDir))
        PendingRestoreStore.rollbackIfUnconfirmed(filesDir, dbFile)
        assertEquals("STAGED-B", dbFile.readText())
    }

    // ---- 10. 全新安装的回滚方向 = 删除换入库 ----

    @Test
    fun `rollback without prior database deletes swapped-in files`() {
        PendingRestoreStore.stage(filesDir, stagedMainFile("STAGED-B"), null, null)
        PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)

        PendingRestoreStore.rollbackIfUnconfirmed(filesDir, dbFile)

        assertNull(readTextOrNull(dbFile))
        assertNull(readTextOrNull(File(dbDir, "rikka_hub-wal")))
        assertFalse(PendingRestoreStore.hasApplied(filesDir))
    }

    // ---- 11. marker 损坏 fail-closed ----

    @Test
    fun `corrupt marker fails closed without touching live database`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("B"), null, null)
        File(PendingRestoreStore.pendingDir(filesDir), "pending.marker").writeText("not-json{")

        try {
            PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("corrupt"))
        }
        assertEquals("LIVE-A", dbFile.readText())
        assertTrue(PendingRestoreStore.hasPending(filesDir))
    }

    // ---- 12. 声明了 staged sidecar 却缺失 → 拒绝（防「换入半套家族」） ----

    @Test
    fun `declared staged sidecar missing fails closed`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("B"), stagedSidecarFile("WAL"), null)
        File(PendingRestoreStore.pendingDir(filesDir), "staged.db-wal").delete()

        try {
            PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("sidecar"))
        }
        assertEquals("LIVE-A", dbFile.readText())
        assertTrue(PendingRestoreStore.hasPending(filesDir))
    }

    // ---- 13. applied 写完、pending 未清时崩溃：残留 pending 只清不重装 ----

    @Test
    fun `leftover pending after applied is consumed without reinstall`() {
        write(dbFile, "LIVE-A")
        PendingRestoreStore.stage(filesDir, stagedMainFile("STAGED-B"), null, null)
        PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator)
        // 手工残留一份 pending（模拟 applied 写完、clearPending 未执行的崩溃窗口）
        PendingRestoreStore.stage(filesDir, stagedMainFile("SHOULD-NOT-INSTALL"), null, null)
        assertTrue(PendingRestoreStore.hasPending(filesDir))
        assertTrue(PendingRestoreStore.hasApplied(filesDir))

        assertTrue(PendingRestoreStore.applyIfPending(filesDir, dbFile, okValidator))

        assertEquals("STAGED-B", dbFile.readText())
        assertFalse(PendingRestoreStore.hasPending(filesDir))
        assertTrue(PendingRestoreStore.hasApplied(filesDir))
    }
}
