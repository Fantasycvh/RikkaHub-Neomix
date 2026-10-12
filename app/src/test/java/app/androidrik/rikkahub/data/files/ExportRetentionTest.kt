package app.androidrik.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出留存区 TTL 的纯函数判定（`docs/design/design-export-retention.md` §4.3）。
 *
 * 清理是启动时的破坏性动作且难以从 UI 观察，故边界值在此钉死。
 */
class ExportRetentionTest {

    private val now = 1_700_000_000_000L  // 固定"现在"，避免测试依赖真实时钟
    private val dayMs = 24L * 60 * 60 * 1000

    // ===== exportRetentionCutoff =====

    @Test
    fun `zero means never clean up`() {
        // 0 = 用户在设置里关掉了 TTL（EXPORT_RETENTION_NEVER）
        assertNull(exportRetentionCutoff(EXPORT_RETENTION_NEVER, now))
    }

    @Test
    fun `negative also means never clean up`() {
        assertNull(exportRetentionCutoff(-1, now))
        assertNull(exportRetentionCutoff(-365, now))
    }

    @Test
    fun `cutoff is now minus retention days`() {
        assertEquals(now - 7 * dayMs, exportRetentionCutoff(7, now)!!)
        assertEquals(now - 1 * dayMs, exportRetentionCutoff(1, now)!!)
        assertEquals(now - 30 * dayMs, exportRetentionCutoff(30, now)!!)
    }

    @Test
    fun `days beyond the cap are clamped instead of overflowing`() {
        // 防御性：防手滑填出离谱值导致 Long 溢出（负数 cutoff 会误删全部）
        val huge = exportRetentionCutoff(Int.MAX_VALUE, now)!!
        assertEquals(exportRetentionCutoff(EXPORT_RETENTION_DAYS_MAX, now)!!, huge)
        assertTrue("clamp 后仍应是过去的合法时刻", huge < now)
    }

    // ===== isExportExpired =====

    @Test
    fun `file older than the cutoff is expired`() {
        val cutoff = now - 7 * dayMs
        assertTrue(isExportExpired(now - 8 * dayMs, cutoff))
    }

    @Test
    fun `file newer than the cutoff is kept`() {
        val cutoff = now - 7 * dayMs
        assertFalse(isExportExpired(now - 6 * dayMs, cutoff))
        assertFalse(isExportExpired(now, cutoff))
    }

    @Test
    fun `exactly at the cutoff is expired`() {
        // 「保留 N 天」的直觉：恰好 N 天前的文件应被清掉（用 <= 而非 <）
        val cutoff = now - 7 * dayMs
        assertTrue(isExportExpired(cutoff, cutoff))
    }

    @Test
    fun `future timestamp is never expired`() {
        // 时钟回拨/文件 mtime 异常时不应误删
        val cutoff = now - 7 * dayMs
        assertFalse(isExportExpired(now + dayMs, cutoff))
    }

    // ===== 组合：默认设置下的整体行为 =====

    @Test
    fun `default retention keeps recent and drops old`() {
        val cutoff = exportRetentionCutoff(DEFAULT_EXPORT_RETENTION_DAYS, now)!!
        assertFalse("一天前的应保留", isExportExpired(now - dayMs, cutoff))
        assertTrue("默认 7 天前的应清理", isExportExpired(now - 7 * dayMs, cutoff))
        assertTrue("更早的应清理", isExportExpired(now - 30 * dayMs, cutoff))
    }

    @Test
    fun `temp prefix matches the constant used by the atomic writer`() {
        // 原子写入的临时文件用该前缀；启动清理按它无条件删除
        assertEquals(".tmp-", EXPORT_TEMP_PREFIX)
    }
}
