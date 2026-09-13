package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工作区备份的安全边界（zip 路径映射 / root 名校验 / zip-slip 防护）。
 *
 * 备份文件是**外部输入**（可能被篡改或损坏），恢复路径必须严格校验，
 * 否则可逃逸写入 app 私有目录之外。见 docs/design-backup-workspace-files.md §3.3。
 */
class WorkspaceBackupHelperTest {

    /** 用绝对路径：resolveRestoreTarget 内部会 canonical 化，
     * 相对路径与 canonical 后的绝对路径做前缀比较会误判。 */
    private val base = File("build/tmp/test-workspaces").absoluteFile

    // ===== root 名校验（与 WorkspaceManager.ROOT_NAME_REGEX 同规则）=====

    @Test
    fun `accepts ordinary root names`() {
        assertTrue(WorkspaceBackupHelper.isValidRoot("01w5enkg"))
        assertTrue(WorkspaceBackupHelper.isValidRoot("my-workspace_1.0"))
    }

    @Test
    fun `rejects traversal and path separators in root`() {
        assertFalse(WorkspaceBackupHelper.isValidRoot(".."))
        assertFalse(WorkspaceBackupHelper.isValidRoot("."))
        assertFalse(WorkspaceBackupHelper.isValidRoot("a/b"))
        assertFalse(WorkspaceBackupHelper.isValidRoot("a\\b"))
        assertFalse(WorkspaceBackupHelper.isValidRoot(""))
        assertFalse(WorkspaceBackupHelper.isValidRoot("a b"))
    }

    // ===== zip 条目 → 目标文件 =====

    @Test
    fun `resolves ordinary entry into workspace files dir`() {
        val target = WorkspaceBackupHelper.resolveRestoreTarget(base, "workspace_files/root1/a/b.txt")
        assertNotNull(target)
        // 相对于 base 的路径应为 root1/files/a/b.txt（跨平台分隔符统一判断）
        val relative = target!!.relativeTo(base).invariantSeparatorsPath
        assertEquals("root1/files/a/b.txt", relative)
    }

    @Test
    fun `rejects entries outside the workspace prefix`() {
        assertNull(WorkspaceBackupHelper.resolveRestoreTarget(base, "upload/x.txt"))
        assertNull(WorkspaceBackupHelper.resolveRestoreTarget(base, "settings.json"))
        assertNull(WorkspaceBackupHelper.resolveRestoreTarget(base, "workspace_files/"))
    }

    @Test
    fun `rejects path traversal in relative path`() {
        // ../ 逃逸：canonical 化后不在 files 目录内
        assertNull(
            WorkspaceBackupHelper.resolveRestoreTarget(base, "workspace_files/root1/../../../../etc/passwd")
        )
    }

    @Test
    fun `rejects invalid root segment in entry`() {
        assertNull(WorkspaceBackupHelper.resolveRestoreTarget(base, "workspace_files/../evil/x.txt"))
        assertNull(WorkspaceBackupHelper.resolveRestoreTarget(base, "workspace_files/a b/x.txt"))
    }

    // ===== 目录扫描 =====

    @Test
    fun `lists only valid root directories`() {
        val dir = File(base, "scan-test")
        (File(dir, "rootA")).mkdirs()
        (File(dir, "rootB")).mkdirs()
        (File(dir, "bad name")).mkdirs()
        File(dir, "afile.txt").writeText("x")
        try {
            val roots = WorkspaceBackupHelper.listWorkspaceRoots(dir)
            assertEquals(listOf("rootA", "rootB"), roots)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `listing missing base dir yields empty list`() {
        assertEquals(emptyList<String>(), WorkspaceBackupHelper.listWorkspaceRoots(File(base, "not-exist")))
    }

    @Test
    fun `zip prefix does not collide with app file folders`() {
        // 前缀须与 upload/skills/fonts/tool_outputs 区分，恢复时靠前缀分派
        assertTrue(WorkspaceBackupHelper.ZIP_PREFIX.startsWith("workspace"))
        assertTrue(WorkspaceBackupHelper.ZIP_PREFIX.endsWith("/"))
    }
}
